package io.github.deadly447.h9ops.map;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A picture map in MBTiles format (an SQLite file of PNG, JPEG or WebP tiles), as
 * QGIS, GDAL and most map tools export, for example a USGS topo map. Opened read-only.
 * MBTiles numbers rows from the bottom (TMS); this converts from the usual top-down rows.
 */
public final class MbTiles implements TileSource {
    /** The first 16 bytes of every SQLite file. */
    public static boolean looksLike(byte[] head) {
        byte[] magic = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (head == null || head.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (head[i] != magic[i]) return false;
        return true;
    }

    private final File path;
    private final SQLiteDatabase db;
    private final Map<String, String> meta = new HashMap<>();
    private final int minZoom, maxZoom;
    private final double[] bounds;
    private final String mime;

    private MbTiles(File path, SQLiteDatabase db) throws IOException {
        this.path = path;
        this.db = db;
        try (Cursor c = db.rawQuery("SELECT name, value FROM metadata", null)) {
            while (c.moveToNext()) {
                if (!c.isNull(0) && !c.isNull(1)) meta.put(c.getString(0).toLowerCase(Locale.US), c.getString(1));
            }
        } catch (SQLiteException e) {
            throw new IOException("Not an MBTiles map (no metadata table)");
        }
        int lo = -1, hi = -1;
        try (Cursor c = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null)) {
            if (c.moveToFirst() && !c.isNull(0)) { lo = c.getInt(0); hi = c.getInt(1); }
        } catch (SQLiteException e) {
            throw new IOException("Not an MBTiles map (no tiles table)");
        }
        if (lo < 0 || hi > 24 || lo > hi) throw new IOException("This MBTiles file has no tiles");
        minZoom = lo;
        maxZoom = hi;
        String f = meta.getOrDefault("format", "").toLowerCase(Locale.US);
        mime = f.equals("png") ? "image/png" : f.equals("jpg") || f.equals("jpeg") ? "image/jpeg"
                : f.equals("webp") ? "image/webp" : f.isEmpty() ? sniff() : null;
        if (mime == null) {
            throw new IOException(f.equals("pbf") || f.equals("mvt")
                    ? "This is a vector map, not terrain or a topo map"
                    : "This MBTiles file holds " + f + " tiles, which can't be shown (PNG, JPEG or WebP only)");
        }
        bounds = parseBounds(meta.get("bounds"));
    }

    /** Opens and checks an MBTiles file. */
    public static MbTiles open(File f) throws IOException {
        SQLiteDatabase db;
        try {
            db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        } catch (SQLiteException e) {
            throw new IOException("Not a readable MBTiles file");
        }
        try {
            return new MbTiles(f, db);
        } catch (IOException | RuntimeException e) {
            db.close();
            throw e instanceof IOException ? (IOException) e : new IOException("Not a readable MBTiles file", e);
        }
    }

    /** No "format" in the metadata: look at the first tile's bytes. */
    private String sniff() {
        try (Cursor c = db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            byte[] b = c.getBlob(0);
            if (b == null || b.length < 12) return null;
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E') return "image/webp";
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "w,s,e,n" → {s, w, n, e}; the whole world when missing or damaged. */
    static double[] parseBounds(String s) {
        double[] world = {-85.0511, -180, 85.0511, 180};
        if (s == null) return world;
        String[] p = s.split(",");
        if (p.length != 4) return world;
        try {
            double w = Double.parseDouble(p[0].trim()), so = Double.parseDouble(p[1].trim());
            double e = Double.parseDouble(p[2].trim()), n = Double.parseDouble(p[3].trim());
            if (!(so < n) || !(w < e) || so < -90 || n > 90 || w < -180 || e > 180) return world;
            return new double[]{so, w, n, e};
        } catch (NumberFormatException ex) {
            return world;
        }
    }

    @Override
    public byte[] tile(int z, int x, int y) {
        if (z < minZoom || z > maxZoom || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) return null;
        int row = (1 << z) - 1 - y;
        try (Cursor c = db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                new String[]{Integer.toString(z), Integer.toString(x), Integer.toString(row)})) {
            return c.moveToFirst() ? c.getBlob(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override public int minZoom() { return minZoom; }
    @Override public int maxZoom() { return maxZoom; }
    @Override public double[] bounds() { return bounds.clone(); }
    @Override public String mime() { return mime; }

    @Override
    public String describe() {
        String kind = mime.substring("image/".length()).toUpperCase(Locale.US);
        String name = meta.get("name");
        String what = (name != null && !name.trim().isEmpty() ? clip(name.trim()) + ", " : "") + kind + " tiles";
        return MapFile.Header.area(what, minZoom, maxZoom, bounds[0], bounds[2], bounds[1], bounds[3]);
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /** The metadata table as a JSON object. */
    @Override
    public String metadata() {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (e.getKey().equals("json")) continue;
            if (sb.length() > 1) sb.append(',');
            sb.append(MapJson.q(e.getKey())).append(':').append(MapJson.q(e.getValue()));
        }
        return sb.append('}').toString();
    }

    @Override
    public String version() {
        return Long.toString(path.lastModified(), 36) + "-" + Long.toString(path.length(), 36);
    }

    @Override public long size() { return path.length(); }

    @Override
    public void close() {
        db.close();
    }
}
