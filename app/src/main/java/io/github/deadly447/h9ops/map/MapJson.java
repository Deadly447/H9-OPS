package io.github.deadly447.h9ops.map;

import io.github.deadly447.h9ops.aprs.AprsPacket;
import io.github.deadly447.h9ops.aprs.Station;
import io.github.deadly447.h9ops.aprs.Symbols;
import io.github.deadly447.h9ops.track.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the map page is given, as JSON. Station names and comments come from
 * the air, so every string goes through {@link #q}; the page itself only
 * ever shows them as text.
 */
public final class MapJson {
    private MapJson() {}

    static final int MAX_COMMENT = 240;
    static final int MAX_TRAIL = 30;

    /** Every station with a position: [{n, la, lo, ic, sy, k, t, v, r, c, tr}]. */
    public static String stations(List<Station> list) {
        StringBuilder sb = new StringBuilder(list.size() * 160 + 2).append('[');
        boolean first = true;
        for (Station s : list) {
            AprsPacket p = s.lastPosition;
            if (p == null || !p.hasPosition() || p.killed) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":").append(q(s.name))
                    .append(",\"la\":").append(num(p.lat))
                    .append(",\"lo\":").append(num(p.lon))
                    .append(",\"ic\":").append(q(Symbols.icon(p.symTable, p.symCode)))
                    .append(",\"sy\":").append(q(Symbols.name(p.symTable, p.symCode)))
                    .append(",\"k\":").append(q(kind(s)))
                    .append(",\"t\":").append(s.lastPositionTime)
                    .append(",\"v\":").append(q(s.lastVia == null ? "" : s.lastVia));
            if (p.mine) sb.append(",\"m\":1");
            double r = radius(p);
            if (r > 0) sb.append(",\"r\":").append(Math.round(r));
            String c = s.summary();
            if (c != null && !c.isEmpty()) sb.append(",\"c\":").append(q(clip(c, MAX_COMMENT)));
            List<double[]> tr = trail(s);
            if (tr.size() >= 2) {
                sb.append(",\"tr\":[");
                for (int i = 0; i < tr.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('[').append(num(tr.get(i)[0])).append(',').append(num(tr.get(i)[1])).append(']');
                }
                sb.append(']');
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /** Alert areas: [{n, la, lo, r}]. */
    public static String areas(List<io.github.deadly447.h9ops.aprs.Alerts.Area> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            io.github.deadly447.h9ops.aprs.Alerts.Area a = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":").append(q(a.name)).append(",\"la\":").append(num(a.lat))
                    .append(",\"lo\":").append(num(a.lon)).append(",\"r\":").append(Math.round(a.radiusM)).append('}');
        }
        return sb.append(']').toString();
    }

    /** b = beacon, s = shared by message, o = object or item. */
    static String kind(Station s) {
        AprsPacket p = s.lastPosition;
        if (p.shared) return "s";
        return s.object ? "o" : "b";
    }

    /** How far off the shown spot can be: a rounded shared position, or position ambiguity. */
    static double radius(AprsPacket p) {
        if (!Double.isNaN(p.radiusM)) return p.radiusM;
        double cellMin;
        switch (p.ambiguity) {
            case 1: cellMin = 0.1; break;
            case 2: cellMin = 1; break;
            case 3: cellMin = 10; break;
            case 4: cellMin = 60; break;
            default: return 0;
        }
        double ns = cellMin / 60 / 2 * 111_320;
        double ew = ns * Math.cos(Math.toRadians(p.lat));
        return Math.sqrt(ns * ns + ew * ew);
    }

    /** Where the station has been, oldest first, ending at its marker. Empty if it hasn't moved. */
    static List<double[]> trail(Station s) {
        List<double[]> pts = new ArrayList<>();
        AprsPacket cur = s.lastPosition;
        pts.add(new double[]{cur.lat, cur.lon});
        for (Station.Heard h : s.history) {          // newest first
            AprsPacket p = h.packet;
            if (p == cur || !p.hasPosition() || p.killed) continue;
            double[] last = pts.get(pts.size() - 1);
            if (Math.abs(last[0] - p.lat) < 1e-5 && Math.abs(last[1] - p.lon) < 1e-5) continue;
            pts.add(new double[]{p.lat, p.lon});
            if (pts.size() >= MAX_TRAIL) break;
        }
        if (pts.size() < 2) return new ArrayList<>();
        java.util.Collections.reverse(pts);
        return pts;
    }

    /** A recorded track: {title, me: [[la, lo]…], seen: [{n, la, lo, a}]}. */
    public static String track(Track t) {
        StringBuilder sb = new StringBuilder("{\"title\":").append(q(t.title())).append(",\"me\":[");
        List<Track.Point> me = new ArrayList<>(t.me);
        for (int i = 0; i < me.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(num(me.get(i).lat)).append(',').append(num(me.get(i).lon)).append(']');
        }
        sb.append("],\"seen\":[");
        List<Track.Seen> seen = new ArrayList<>(t.seen);
        for (int i = 0; i < seen.size(); i++) {
            Track.Seen s = seen.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":").append(q(s.call)).append(",\"la\":").append(num(s.lat))
                    .append(",\"lo\":").append(num(s.lon)).append(",\"a\":").append(s.approx).append('}');
        }
        return sb.append("]}").toString();
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        return String.format(Locale.US, "%.6f", v);
    }

    /** A JSON string: quotes, backslashes, control characters and the two JavaScript line breaks escaped. */
    public static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;    // no "</script>" or "<!--" in any context
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == 0x7F) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
