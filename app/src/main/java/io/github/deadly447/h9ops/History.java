package io.github.deadly447.h9ops;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import java.util.ArrayList;
import java.util.List;
import io.github.deadly447.h9ops.aprs.AprsPacket;
import io.github.deadly447.h9ops.aprs.WxHistory;

/**
 * Packet history: every frame heard, kept for 30 days in a private database inside
 * the app's own storage (Android's built-in SQLite, no extra library). Weather
 * reports get their own table so the graphs don't have to decode every packet.
 *
 * Writes come from Repo's single background thread; reads for the screen also run
 * there. Nothing leaves the phone except what the user exports.
 */
final class History extends SQLiteOpenHelper {
    static final long KEEP_MS = 30L * 24 * 3_600_000L;
    /** A hard cap, whatever the age (a busy digipeater site is ~3000 packets a day). */
    static final int MAX_ROWS = 300_000;
    private static final int VERSION = 1;

    /** One stored frame. */
    static final class Row {
        final long time;
        final byte[] frame;
        final boolean again;

        Row(long time, byte[] frame, boolean again) {
            this.time = time;
            this.frame = frame;
            this.again = again;
        }
    }

    /** A weather station in the history. */
    static final class WxStation {
        final String name;
        final long last;
        final int reports;
        /** Newest reading of each, NaN if the station never sent it. */
        final double temp, hum, wind, gust, dir;
        /** Nearest town from the offline map, or null (filled in by Repo). */
        String place;

        WxStation(String name, long last, int reports, double temp, double hum, double wind, double gust, double dir) {
            this.name = name;
            this.last = last;
            this.reports = reports;
            this.temp = temp;
            this.hum = hum;
            this.wind = wind;
            this.gust = gust;
            this.dir = dir;
        }
    }

    private SQLiteStatement insertPacket, insertWx;
    private int sincePrune;

    History(Context c) {
        super(c, "history.db", null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE packets (id INTEGER PRIMARY KEY, t INTEGER NOT NULL, frame BLOB NOT NULL,"
                + " name TEXT, via TEXT, again INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX packets_t ON packets(t)");
        db.execSQL("CREATE INDEX packets_name ON packets(name, t)");
        db.execSQL("CREATE TABLE wx (t INTEGER NOT NULL, name TEXT NOT NULL, temp REAL, hum REAL, press REAL,"
                + " wind REAL, gust REAL, dir REAL, rain1h REAL, rain24h REAL)");
        db.execSQL("CREATE INDEX wx_name ON wx(name, t)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // only version 1 so far
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    /** Stores one frame (and its weather report, if it has one and isn't a repeat). */
    void add(long t, byte[] frame, AprsPacket p, boolean again) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insert(db, t, frame, p, again);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (++sincePrune >= 500) prune(t);
    }

    /** Many frames in one go (moving the old packets.log in). */
    void addAll(List<Row> rows, List<AprsPacket> packets) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                insert(db, r.time, r.frame, packets.get(i), r.again);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void insert(SQLiteDatabase db, long t, byte[] frame, AprsPacket p, boolean again) {
        if (insertPacket == null) {
            insertPacket = db.compileStatement("INSERT INTO packets (t, frame, name, via, again) VALUES (?,?,?,?,?)");
            insertWx = db.compileStatement("INSERT INTO wx (t, name, temp, hum, press, wind, gust, dir, rain1h, rain24h)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)");
        }
        String name = p == null ? null : key(p);
        insertPacket.clearBindings();
        insertPacket.bindLong(1, t);
        insertPacket.bindBlob(2, frame);
        if (name != null) insertPacket.bindString(3, name);
        if (p != null && p.heardVia != null) insertPacket.bindString(4, p.heardVia);
        insertPacket.bindLong(5, again ? 1 : 0);
        insertPacket.executeInsert();
        if (p == null || p.wx == null || again || name == null) return;
        AprsPacket.Weather w = p.wx;
        double[] v = {w.tempF, w.humidity, w.pressureMb, w.windMph, w.gustMph, w.windDir, w.rain1hIn, w.rain24hIn};
        boolean any = false;
        for (double d : v) any |= !Double.isNaN(d);
        if (!any) return;
        insertWx.clearBindings();
        insertWx.bindLong(1, t);
        insertWx.bindString(2, name);
        for (int i = 0; i < v.length; i++) if (!Double.isNaN(v[i])) insertWx.bindDouble(3 + i, v[i]);
        insertWx.executeInsert();
    }

    /** The station a packet is about, as the station list names it. */
    static String key(AprsPacket p) {
        return p.name == null || p.name.isEmpty() ? p.sender : p.name;
    }

    /** The newest {@code n} frames, oldest first. */
    List<Row> recent(int n) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t, frame, again FROM packets ORDER BY id DESC LIMIT " + n, null)) {
            while (c.moveToNext()) out.add(new Row(c.getLong(0), c.getBlob(1), c.getInt(2) != 0));
        }
        java.util.Collections.reverse(out);
        return out;
    }

    interface RowSink { void row(Row r) throws java.io.IOException; }

    /** Every frame from {@code since} on, oldest first, without loading them all at once. */
    void forEach(long since, RowSink sink) throws java.io.IOException {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t, frame, again FROM packets WHERE t >= ? ORDER BY id", new String[]{Long.toString(since)})) {
            while (c.moveToNext()) sink.row(new Row(c.getLong(0), c.getBlob(1), c.getInt(2) != 0));
        }
    }

    /** {count, oldest time} of the stored packets; oldest is 0 when empty. */
    long[] size() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), MIN(t) FROM packets", null)) {
            if (!c.moveToFirst()) return new long[]{0, 0};
            return new long[]{c.getLong(0), c.isNull(1) ? 0 : c.getLong(1)};
        }
    }

    /** Weather stations with reports from {@code since} on, most recently heard first. */
    List<WxStation> weatherStations(long since) {
        List<WxStation> out = new ArrayList<>();
        StringBuilder q = new StringBuilder("SELECT name, MAX(t), COUNT(*)");
        for (String col : new String[]{"temp", "hum", "wind", "gust", "dir"}) {
            q.append(", (SELECT ").append(col).append(" FROM wx w WHERE w.name = wx.name AND w.").append(col)
                    .append(" IS NOT NULL ORDER BY w.t DESC LIMIT 1)");
        }
        q.append(" FROM wx WHERE t >= ? GROUP BY name ORDER BY MAX(t) DESC");
        try (Cursor c = getReadableDatabase().rawQuery(q.toString(), new String[]{Long.toString(since)})) {
            while (c.moveToNext()) {
                double[] v = new double[5];
                for (int i = 0; i < 5; i++) v[i] = c.isNull(3 + i) ? Double.NaN : c.getDouble(3 + i);
                out.add(new WxStation(c.getString(0), c.getLong(1), c.getInt(2), v[0], v[1], v[2], v[3], v[4]));
            }
        }
        return out;
    }

    /** The newest stored frames about one station, newest first (to find its last position). */
    List<byte[]> latestFrames(String name, int n) {
        List<byte[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT frame FROM packets WHERE name = ? ORDER BY id DESC LIMIT " + n, new String[]{name})) {
            while (c.moveToNext()) out.add(c.getBlob(0));
        }
        return out;
    }

    /** One station's weather reports from {@code since} on, oldest first. */
    List<WxHistory.Sample> weather(String name, long since) {
        List<WxHistory.Sample> out = new ArrayList<>();
        WxHistory.Field[] cols = {WxHistory.Field.TEMP, WxHistory.Field.HUMIDITY, WxHistory.Field.PRESSURE,
                WxHistory.Field.WIND, WxHistory.Field.GUST, WxHistory.Field.WIND_DIR,
                WxHistory.Field.RAIN_1H, WxHistory.Field.RAIN_24H};
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t, temp, hum, press, wind, gust, dir, rain1h, rain24h FROM wx WHERE name = ? AND t >= ? ORDER BY t",
                new String[]{name, Long.toString(since)})) {
            while (c.moveToNext()) {
                WxHistory.Sample s = new WxHistory.Sample(c.getLong(0));
                for (int i = 0; i < cols.length; i++) if (!c.isNull(i + 1)) s.set(cols[i], c.getDouble(i + 1));
                out.add(s);
            }
        }
        return out;
    }

    /** Drops what's older than 30 days, and the oldest rows past the hard cap. */
    void prune(long now) {
        sincePrune = 0;
        SQLiteDatabase db = getWritableDatabase();
        String cut = Long.toString(now - KEEP_MS);
        db.delete("packets", "t < ?", new String[]{cut});
        db.delete("wx", "t < ?", new String[]{cut});
        db.execSQL("DELETE FROM packets WHERE id <= (SELECT MAX(id) FROM packets) - " + MAX_ROWS);
    }

    void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("packets", null, null);
        db.delete("wx", null, null);
    }
}
