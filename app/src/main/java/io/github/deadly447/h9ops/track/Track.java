package io.github.deadly447.h9ops.track;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * One recorded trip: this phone's GPS track, and every station position the
 * radio heard while it was recording. Plain Java so it can be unit tested.
 *
 * Stored one line per event, tab-separated (see {@link #line} and {@link #parse}):
 *   B  start  name                      recording began
 *   M  time  lat  lon  alt  accuracy    a point of this phone's track
 *   S  time  call  lat  lon  approx      a station position heard (approx 1 = rounded)
 *   E  end                              recording stopped
 */
public final class Track {
    /** Points worse than this are dropped (indoors, first fix). */
    static final float MAX_ACCURACY_M = 50f;
    /** A new point must be this far from the last one kept... */
    static final double MIN_MOVE_M = 5;
    /** ...unless this long has passed (so a stop still shows). */
    static final long KEEP_EVERY_MS = 5 * 60_000;
    /** Faster than this between two points is a GPS jump, not you. */
    static final double MAX_SPEED_MS = 70;
    /** A gap longer than this starts a new segment (radio off, phone dead...). */
    static final long SEGMENT_GAP_MS = 10 * 60_000;
    /** The same station at the same place within this long is one report (digipeated copies). */
    static final long DUPLICATE_MS = 90_000;

    public static final class Point {
        public final long time;
        public final double lat, lon, alt;
        public final float accuracy;
        Point(long time, double lat, double lon, double alt, float accuracy) {
            this.time = time; this.lat = lat; this.lon = lon; this.alt = alt; this.accuracy = accuracy;
        }
    }

    public static final class Seen {
        public final long time;
        public final String call;
        public final double lat, lon;
        public final boolean approx;
        Seen(long time, String call, double lat, double lon, boolean approx) {
            this.time = time; this.call = call; this.lat = lat; this.lon = lon; this.approx = approx;
        }
    }

    public final String id;
    public String name = "";
    public long start, end;   // end 0 while recording
    public final List<Point> me = new ArrayList<>();
    public final List<Seen> seen = new ArrayList<>();
    private double distanceM;

    public Track(String id) { this.id = id; }

    // ---- recording ----

    /** Adds a GPS fix if it's good and new enough; returns the point kept, or null. */
    Point addMe(long t, double lat, double lon, double alt, float accuracy) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        if (accuracy > MAX_ACCURACY_M) return null;
        if (!me.isEmpty()) {
            Point last = me.get(me.size() - 1);
            if (t <= last.time) return null;
            double d = metres(last.lat, last.lon, lat, lon);
            long dt = t - last.time;
            if (d < MIN_MOVE_M && dt < KEEP_EVERY_MS) return null;
            if (dt < SEGMENT_GAP_MS && d / (dt / 1000.0) > MAX_SPEED_MS) return null;
            if (dt < SEGMENT_GAP_MS) distanceM += d;
        }
        Point p = new Point(t, lat, lon, alt, accuracy);
        me.add(p);
        return p;
    }

    /** Adds a heard station position unless it's a copy of one just heard. Returns it, or null. */
    Seen addSeen(long t, String call, double lat, double lon, boolean approx) {
        if (call == null || call.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)) return null;
        for (int i = seen.size() - 1; i >= 0; i--) {
            Seen s = seen.get(i);
            if (t - s.time > DUPLICATE_MS) break;
            if (s.call.equals(call) && Math.abs(s.lat - lat) < 1e-5 && Math.abs(s.lon - lon) < 1e-5) return null;
        }
        Seen s = new Seen(t, call, lat, lon, approx);
        seen.add(s);
        return s;
    }

    // ---- numbers for the list ----

    public double distanceM() { return distanceM; }

    public long durationMs() {
        long e = end != 0 ? end : (me.isEmpty() ? start : me.get(me.size() - 1).time);
        return Math.max(0, e - start);
    }

    public int stations() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Seen x : seen) s.add(x.call);
        return s.size();
    }

    // ---- file lines ----

    static String begin(long t, String name) { return "B\t" + t + "\t" + clean(name); }
    static String end(long t) { return "E\t" + t; }

    static String line(Point p) {
        return "M\t" + p.time + "\t" + fmt(p.lat) + "\t" + fmt(p.lon) + "\t"
                + (Double.isNaN(p.alt) ? "" : String.format(Locale.US, "%.1f", p.alt)) + "\t"
                + String.format(Locale.US, "%.0f", p.accuracy);
    }

    static String line(Seen s) {
        return "S\t" + s.time + "\t" + clean(s.call) + "\t" + fmt(s.lat) + "\t" + fmt(s.lon) + "\t" + (s.approx ? 1 : 0);
    }

    /** Rebuilds a track from its file lines. Damaged lines are skipped. */
    static Track parse(String id, List<String> lines) {
        Track t = new Track(id);
        for (String l : lines) {
            String[] f = l.split("\t", -1);
            try {
                switch (f[0]) {
                    case "B": t.start = Long.parseLong(f[1]); t.name = f.length > 2 ? f[2] : ""; break;
                    case "E": t.end = Long.parseLong(f[1]); break;
                    case "M":
                        t.addMe(Long.parseLong(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                                f[4].isEmpty() ? Double.NaN : Double.parseDouble(f[4]), Float.parseFloat(f[5]));
                        break;
                    case "S":
                        t.addSeen(Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4]), "1".equals(f[5]));
                        break;
                    default:
                }
            } catch (RuntimeException ignored) {
                // a half-written last line after a crash
            }
        }
        if (t.start == 0 && !t.me.isEmpty()) t.start = t.me.get(0).time;
        return t;
    }

    // ---- GPX ----

    /**
     * Writes GPX 1.1: this phone's track (split where there's a long gap), each
     * station heard at two or more exact places as a track of its own, and the
     * rest (one exact place, or rounded shared positions) as waypoints.
     */
    public void writeGpx(Writer w, String myName) throws IOException {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String me = (myName == null || myName.isEmpty() ? "This phone" : myName) + " (this phone)";

        Map<String, List<Seen>> exact = new LinkedHashMap<>();
        List<Seen> points = new ArrayList<>();
        for (Seen s : seen) {
            if (!s.approx) exact.computeIfAbsent(s.call, k -> new ArrayList<>()).add(s);
        }
        for (Seen s : seen) {
            if (s.approx || exact.get(s.call).size() < 2) points.add(s);
        }

        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<gpx version=\"1.1\" creator=\"H9 OPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        w.write("<metadata><name>" + esc(title()) + "</name><time>" + iso.format(new Date(start)) + "</time></metadata>\n");
        for (Seen s : points) {
            w.write("<wpt lat=\"" + fmt(s.lat) + "\" lon=\"" + fmt(s.lon) + "\"><time>" + iso.format(new Date(s.time))
                    + "</time><name>" + esc(s.call) + "</name><desc>"
                    + (s.approx ? "Shared position, rounded: the station is somewhere near here" : "Heard by radio")
                    + "</desc></wpt>\n");
        }
        writeTrk(w, iso, me, "Phone GPS", me(), true);
        for (Map.Entry<String, List<Seen>> e : exact.entrySet()) {
            if (e.getValue().size() < 2) continue;
            List<Point> pts = new ArrayList<>();
            for (Seen s : e.getValue()) pts.add(new Point(s.time, s.lat, s.lon, Double.NaN, 0));
            writeTrk(w, iso, e.getKey(), "Heard by radio, " + pts.size() + " positions", pts, false);
        }
        w.write("</gpx>\n");
    }

    private List<Point> me() { return me; }

    private static void writeTrk(Writer w, SimpleDateFormat iso, String name, String desc, List<Point> pts, boolean always)
            throws IOException {
        if (pts.isEmpty() && !always) return;
        w.write("<trk><name>" + esc(name) + "</name><desc>" + esc(desc) + "</desc>\n<trkseg>\n");
        long last = 0;
        for (Point p : pts) {
            if (last != 0 && p.time - last > SEGMENT_GAP_MS) w.write("</trkseg>\n<trkseg>\n");
            last = p.time;
            w.write("<trkpt lat=\"" + fmt(p.lat) + "\" lon=\"" + fmt(p.lon) + "\">"
                    + (Double.isNaN(p.alt) ? "" : "<ele>" + String.format(Locale.US, "%.1f", p.alt) + "</ele>")
                    + "<time>" + iso.format(new Date(p.time)) + "</time></trkpt>\n");
        }
        w.write("</trkseg>\n</trk>\n");
    }

    public String title() {
        String when = new SimpleDateFormat("EEE MMM d, HH:mm", Locale.US).format(new Date(start));
        return name == null || name.isEmpty() ? "Track " + when : name + " (" + when + ")";
    }

    // ---- helpers ----

    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static String fmt(double d) { return String.format(Locale.US, "%.6f", d); }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: if (c >= 0x20 || c == '\t') b.append(c);
            }
        }
        return b.toString();
    }
}
