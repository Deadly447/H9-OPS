package io.github.deadly447.h9ops.map;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The one command that makes a map file for an area: Protomaps' free world map, cut down
 * to a box around a point with the pmtiles tool on a computer. The app has no internet
 * access, so it hands the person this command instead of downloading anything.
 */
public final class MapRecipe {
    private MapRecipe() {}

    /** Where the guide lives (opened in the phone's browser). */
    public static final String GUIDE_URL = "https://github.com/Deadly447/H9-OPS/blob/main/MAPS.md";
    /** The pmtiles tool for Windows, Mac and Linux. */
    public static final String TOOL_URL = "https://github.com/protomaps/go-pmtiles/releases";

    /** Box sizes offered, miles from the centre to each edge. */
    public static final int[] MILES = {15, 40, 100};

    /** {west, south, east, north}, degrees, rounded to 4 places. */
    public static double[] bbox(double lat, double lon, int miles) {
        double dLat = miles / 69.0;
        double dLon = miles / (69.0 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        double s = Math.max(-85, lat - dLat), n = Math.min(85, lat + dLat);
        double w = Math.max(-180, lon - dLon), e = Math.min(180, lon + dLon);
        return new double[]{round(w), round(s), round(e), round(n)};
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000.0; }

    /**
     * Protomaps keeps every daily build for a week; two days back is always there.
     * yyyyMMdd, UTC.
     */
    public static String buildDate(long nowMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(nowMs - 2 * 86_400_000L));
    }

    /** "pmtiles extract https://build.protomaps.com/20261122.pmtiles h9-map.pmtiles --bbox=w,s,e,n" */
    public static String command(double lat, double lon, int miles, long nowMs) {
        double[] b = bbox(lat, lon, miles);
        return "pmtiles extract https://build.protomaps.com/" + buildDate(nowMs) + ".pmtiles h9-map.pmtiles --bbox="
                + fmt(b[0]) + "," + fmt(b[1]) + "," + fmt(b[2]) + "," + fmt(b[3]);
    }

    private static String fmt(double v) {
        String s = String.format(Locale.US, "%.4f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** A rough file size, so nobody is surprised: about 40 MB for a 15-mile box in the country. */
    public static String roughSize(int miles) {
        if (miles <= 15) return "about 20–60 MB";
        if (miles <= 40) return "about 100–300 MB";
        return "about 0.5–1.5 GB";
    }
}
