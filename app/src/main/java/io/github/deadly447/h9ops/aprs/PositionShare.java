package io.github.deadly447.h9ops.aprs;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A position sent to one station inside an APRS text message, instead of as
 * a broadcast beacon. Only this app reads it as a position; maps and iGates
 * see an ordinary message.
 *
 * Text format (fits a 67-character plain message):
 *   "POS 40.84103,-105.46120 @0515z"          exact
 *   "POS 40.8417,-105.4583 1mi @0515z"        blurred
 *
 * APRS message text can't carry "~", so the blur tag has none; "~1mi"
 * (from older versions) is still read.
 */
public final class PositionShare {
    private PositionShare() {}

    /** How much to blur a position: the size of the grid cell it is rounded to. */
    public enum Blur {
        EXACT("Exact", 0, ""),
        TENTH_MILE("About 0.1 mile", 0.1 / 60, "0.1mi"),   // 0.1 minute of latitude
        ONE_MILE("About 1 mile", 1.0 / 60, "1mi"),         // 1 minute
        TEN_MILES("About 10 miles", 10.0 / 60, "10mi"),    // 10 minutes
        SIXTY_MILES("About 60 miles", 1.0, "60mi");        // 1 degree

        public final String label;
        final double cellDeg;
        final String tag;

        Blur(String label, double cellDeg, String tag) {
            this.label = label;
            this.cellDeg = cellDeg;
            this.tag = tag;
        }

        static Blur fromTag(String t) {
            for (Blur b : values()) if (b.tag.equals(t)) return b;
            return null;
        }
    }

    public static final class Pos {
        public final double lat, lon;
        public final Blur blur;
        public final String timeUtc;   // "0515z" or null

        Pos(double lat, double lon, Blur blur, String timeUtc) {
            this.lat = lat;
            this.lon = lon;
            this.blur = blur;
            this.timeUtc = timeUtc;
        }
    }

    /**
     * Rounds to the centre of the blur cell. Any point inside the cell gives
     * the same answer, so the exact spot can't be recovered from one report.
     */
    static double blurOne(double v, double cell) {
        if (cell <= 0) return v;
        return Math.floor(v / cell) * cell + cell / 2;
    }

    /** Builds the message text. timeMs is when the fix was taken. */
    public static String encode(double lat, double lon, Blur blur, long timeMs) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("No valid position");
        }
        double la = blurOne(lat, blur.cellDeg), lo = blurOne(lon, blur.cellDeg);
        int digits = blur == Blur.EXACT || blur == Blur.TENTH_MILE ? 5 : blur == Blur.ONE_MILE ? 4 : 3;
        String fmt = "%." + digits + "f,%." + digits + "f";
        long minutes = (timeMs / 60_000) % (24 * 60);
        String t = String.format(Locale.US, "%02d%02dz", minutes / 60, minutes % 60);
        return "POS " + String.format(Locale.US, fmt, la, lo)
                + (blur == Blur.EXACT ? "" : " " + blur.tag) + " @" + t;
    }

    private static final Pattern P = Pattern.compile(
            "^POS (-?\\d{1,2}\\.\\d{1,6}),(-?\\d{1,3}\\.\\d{1,6})(?: ~?([0-9.]+mi))?(?: @(\\d{4}z))?\\s*$");

    /** Reads a position message, or returns null if the text isn't one. */
    public static Pos decode(String text) {
        if (text == null) return null;
        Matcher m = P.matcher(text.trim());
        if (!m.matches()) return null;
        double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
        if (Math.abs(la) > 90 || Math.abs(lo) > 180) return null;
        Blur b = m.group(3) == null ? Blur.EXACT : Blur.fromTag(m.group(3).replace("mi", "") + "mi");
        if (b == null) return null;
        return new Pos(la, lo, b, m.group(4));
    }

    /** A packet the station list can show, built from a position message. */
    public static AprsPacket toPacket(String from, Pos p, String raw) {
        AprsPacket a = new AprsPacket();
        a.type = AprsPacket.Type.POSITION;
        a.name = from;
        a.sender = from;
        a.lat = p.lat;
        a.lon = p.lon;
        a.symTable = '/';
        a.symCode = '[';
        a.comment = "Shared with you by message"
                + (p.blur == Blur.EXACT ? "" : ", rounded to " + p.blur.label.toLowerCase(Locale.US))
                + (p.timeUtc != null ? ", fix at " + p.timeUtc : "");
        a.raw = raw;
        return a;
    }
}
