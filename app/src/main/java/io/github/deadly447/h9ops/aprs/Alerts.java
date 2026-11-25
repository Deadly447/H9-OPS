package io.github.deadly447.h9ops.aprs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides when a packet deserves an alert: a watched callsign heard again,
 * a station entering or leaving an area, or an emergency. Only live packets
 * are checked (the app doesn't alert for packets reloaded at start-up), and
 * each alert is limited so a chatty station can't flood the phone.
 * No Android code: the app turns the result into notifications.
 */
public final class Alerts {
    public enum Kind { HEARD, ENTERED, LEFT, EMERGENCY }

    /** Heard alerts again only after this long without hearing the station. */
    public static final long HEARD_QUIET_MS = 30 * 60_000L;
    /** One emergency alert per station per this long (they repeat every beacon). */
    public static final long EMERGENCY_REPEAT_MS = 15 * 60_000L;
    /** Leaving needs this much past the edge, so GPS wander at the edge doesn't flap. */
    static final double LEAVE_MARGIN = 1.10;
    /** Radius choices for areas, miles. */
    public static final double[] RADIUS_MILES = {0.1, 0.25, 0.5, 1, 2, 5, 10};
    public static final double M_PER_MILE = 1609.344;

    public static final class Alert {
        public final Kind kind;
        public final String call;
        public final String area;      // ENTERED and LEFT only
        public final String detail;    // how heard, the emergency text, distance

        Alert(Kind kind, String call, String area, String detail) {
            this.kind = kind;
            this.call = call;
            this.area = area;
            this.detail = detail;
        }

        /** The notification's title. */
        public String title() {
            switch (kind) {
                case EMERGENCY: return "EMERGENCY · " + call;
                case ENTERED: return call + " entered " + area;
                case LEFT: return call + " left " + area;
                default: return call + " heard";
            }
        }
    }

    /** A circle on the map: alert when a station (any, or the listed ones) goes in or out. */
    public static final class Area {
        public final String name;
        public final double lat, lon, radiusM;
        /** Callsign patterns, or empty for every station. */
        public final List<String> calls;

        public Area(String name, double lat, double lon, double radiusM, List<String> calls) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.radiusM = radiusM;
            this.calls = calls == null ? new ArrayList<>() : new ArrayList<>(calls);
        }

        boolean applies(String call) {
            return calls.isEmpty() || matchesAny(calls, call);
        }

        /** One line for storage: name, lat, lon, radius, calls (tab separated). */
        public String save() {
            return clean(name) + "\t" + String.format(Locale.US, "%.6f\t%.6f\t%.0f", lat, lon, radiusM)
                    + "\t" + String.join(" ", calls);
        }

        public static Area load(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 4) return null;
            try {
                double la = Double.parseDouble(f[1]), lo = Double.parseDouble(f[2]), r = Double.parseDouble(f[3]);
                if (Double.isNaN(la) || Double.isNaN(lo) || !(r > 0)) return null;
                return new Area(f[0], la, lo, r, f.length > 4 ? parseCalls(f[4]) : null);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private List<String> watch = new ArrayList<>();
    private final List<Area> areas = new ArrayList<>();
    private boolean emergency = true;
    /** "area|station" -> inside it, as last seen. */
    private final Map<String, Boolean> inside = new HashMap<>();
    private final Map<String, Long> lastEmergency = new HashMap<>();

    public void setWatch(List<String> calls) { watch = new ArrayList<>(calls); }
    public List<String> watch() { return new ArrayList<>(watch); }
    public void setEmergency(boolean on) { emergency = on; }
    public boolean emergency() { return emergency; }
    public List<Area> areas() { return new ArrayList<>(areas); }

    public void setAreas(List<Area> list) {
        areas.clear();
        areas.addAll(list);
        inside.clear();
    }

    /**
     * Records where stations already are, without alerting: at start-up and
     * after the areas change, so only real comings and goings alert.
     */
    public void prime(Collection<Station> stations) {
        for (Station s : stations) {
            AprsPacket p = s.lastPosition;
            if (p == null || !p.hasPosition() || p.killed) continue;
            for (Area a : areas) {
                if (a.applies(s.name)) inside.put(a.name + "|" + s.name, metres(a.lat, a.lon, p.lat, p.lon) <= a.radiusM);
            }
        }
    }

    /**
     * Checks one live packet. {@code prevHeard} is when this station was last
     * heard before it (0 = never).
     */
    public List<Alert> check(AprsPacket p, long prevHeard, long now) {
        List<Alert> out = new ArrayList<>();
        String call = p.name == null || p.name.isEmpty() ? p.sender : p.name;
        if (call == null) return out;

        if (!p.shared && matchesAny(watch, call) && (prevHeard <= 0 || now - prevHeard >= HEARD_QUIET_MS)) {
            out.add(new Alert(Kind.HEARD, call, null, p.heardVia == null ? "heard direct" : "via " + p.heardVia));
        }

        if (emergency && !p.shared) {
            String why = emergencyText(p);
            Long last = lastEmergency.get(call);
            if (why != null && (last == null || now - last >= EMERGENCY_REPEAT_MS)) {
                lastEmergency.put(call, now);
                out.add(new Alert(Kind.EMERGENCY, call, null, why));
            }
        }

        if (p.hasPosition() && !p.killed) {
            for (Area a : areas) {
                if (!a.applies(call)) continue;
                double d = metres(a.lat, a.lon, p.lat, p.lon);
                String key = a.name + "|" + call;
                Boolean was = inside.get(key);
                boolean in = was != null && was ? d <= a.radiusM * LEAVE_MARGIN : d <= a.radiusM;
                inside.put(key, in);
                if (in && (was == null || !was)) out.add(new Alert(Kind.ENTERED, call, a.name, distance(d) + " from its centre"));
                else if (!in && was != null && was) out.add(new Alert(Kind.LEFT, call, a.name, distance(d) + " from its centre"));
            }
        }
        return out;
    }

    /** Why a packet counts as an emergency, or null. */
    static String emergencyText(AprsPacket p) {
        if ("Emergency".equals(p.miceStatus)) {
            return "Mic-E emergency" + (p.comment != null && !p.comment.isEmpty() ? ": " + p.comment.trim() : "");
        }
        String name = p.name == null ? "" : p.name.toUpperCase(Locale.US);
        if ((p.type == AprsPacket.Type.OBJECT || p.type == AprsPacket.Type.ITEM) && name.startsWith("EMERG") && !p.killed) {
            return "emergency object";
        }
        for (String t : new String[]{p.comment, p.status}) {
            if (t != null && EMERGENCY_WORD.matcher(t).find()) return t.trim();
        }
        return null;
    }

    private static final Pattern EMERGENCY_WORD = Pattern.compile("\\bEMERGENCY\\b", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- callsign patterns

    /** "N0CALL-7" exact; "N0CALL-*" any SSID (or none); "N0*" any call starting N0. Case ignored. */
    public static boolean matches(String pattern, String call) {
        if (pattern == null || call == null) return false;
        String p = pattern.trim().toUpperCase(Locale.US), c = call.trim().toUpperCase(Locale.US);
        if (p.isEmpty()) return false;
        if (p.endsWith("-*")) {
            String base = p.substring(0, p.length() - 2);
            return c.equals(base) || c.startsWith(base + "-");
        }
        if (p.endsWith("*")) return c.startsWith(p.substring(0, p.length() - 1));
        return c.equals(p);
    }

    static boolean matchesAny(List<String> patterns, String call) {
        for (String p : patterns) if (matches(p, call)) return true;
        return false;
    }

    /** Callsign patterns from what was typed: commas, spaces or new lines between them. */
    public static List<String> parseCalls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.split("[,;\\s]+")) {
            String c = s.trim().toUpperCase(Locale.US);
            if (c.matches("[A-Z0-9*-]{1,12}") && !out.contains(c)) out.add(c);
        }
        return out;
    }

    static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
    }

    static String distance(double m) {
        double mi = m / M_PER_MILE;
        return mi < 0.1 ? String.format(Locale.US, "%.0f ft", m * 3.28084)
                : String.format(Locale.US, mi < 10 ? "%.1f mi" : "%.0f mi", mi);
    }

    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
