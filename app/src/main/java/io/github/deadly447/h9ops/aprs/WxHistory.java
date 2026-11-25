package io.github.deadly447.h9ops.aprs;

import java.util.List;

/**
 * Weather reports from one station over time, turned into points for small graphs.
 * The app keeps the reports in its packet history (up to 30 days); this class only
 * does the arithmetic, so it can be tested without a phone.
 */
public final class WxHistory {
    private WxHistory() {}

    public enum Field {
        TEMP("Temperature", "°F", false),
        HUMIDITY("Humidity", "%", false),
        PRESSURE("Pressure", " mb", false),
        WIND("Wind", " mph", false),
        GUST("Gust", " mph", true),
        RAIN_1H("Rain, past hour", "\"", true),
        RAIN_24H("Rain, past 24 hours", "\"", true),
        WIND_DIR("Wind from", "°", false);

        public final String label, unit;
        /** Buckets show the highest value in them (gusts, rain) rather than the average. */
        public final boolean peak;

        Field(String label, String unit, boolean peak) {
            this.label = label;
            this.unit = unit;
            this.peak = peak;
        }

        /** "47°F", "29.97\"", "1012.3 mb". */
        public String format(double v) {
            if (Double.isNaN(v)) return "–";
            switch (this) {
                case PRESSURE: return String.format(java.util.Locale.US, "%.1f", v) + unit;
                case RAIN_1H: case RAIN_24H: return String.format(java.util.Locale.US, "%.2f", v) + unit;
                default: return Math.round(v) + unit;
            }
        }
    }

    /** One weather report. Missing values are NaN. */
    public static final class Sample {
        public final long time;
        final double[] v = new double[Field.values().length];

        public Sample(long time) {
            this.time = time;
            java.util.Arrays.fill(v, Double.NaN);
        }

        public Sample set(Field f, double value) {
            v[f.ordinal()] = value;
            return this;
        }

        public double get(Field f) { return v[f.ordinal()]; }

        public static Sample of(long time, AprsPacket.Weather w) {
            return new Sample(time)
                    .set(Field.TEMP, w.tempF).set(Field.HUMIDITY, w.humidity).set(Field.PRESSURE, w.pressureMb)
                    .set(Field.WIND, w.windMph).set(Field.GUST, w.gustMph).set(Field.WIND_DIR, w.windDir)
                    .set(Field.RAIN_1H, w.rain1hIn).set(Field.RAIN_24H, w.rain24hIn);
        }
    }

    /**
     * Splits [from, to) into {@code n} equal buckets and gives one value per bucket: the
     * average of the reports in it (or the highest, for gusts and rain). A bucket with no
     * report is NaN, so the graph shows a gap instead of a made-up line.
     * Samples must be oldest first.
     */
    public static double[] buckets(List<Sample> samples, Field f, long from, long to, int n) {
        double[] out = new double[n];
        double[] sum = new double[n];
        int[] count = new int[n];
        java.util.Arrays.fill(out, Double.NaN);
        double width = (to - from) / (double) n;
        if (width <= 0) return out;
        for (Sample s : samples) {
            double v = s.get(f);
            if (Double.isNaN(v) || s.time < from || s.time >= to) continue;
            int i = (int) Math.min(n - 1, (s.time - from) / width);
            if (f.peak) out[i] = count[i] == 0 ? v : Math.max(out[i], v);
            else sum[i] += v;
            count[i]++;
        }
        if (!f.peak) for (int i = 0; i < n; i++) if (count[i] > 0) out[i] = sum[i] / count[i];
        return out;
    }

    /** True if any report has this value (stations send different sets). */
    public static boolean has(List<Sample> samples, Field f) {
        for (Sample s : samples) if (!Double.isNaN(s.get(f))) return true;
        return false;
    }

    /** The newest report's value, or NaN. Samples oldest first. */
    public static double latest(List<Sample> samples, Field f) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            double v = samples.get(i).get(f);
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    /** Lowest and highest reported value from {@code from} on: {min, max}, NaN if none. */
    public static double[] range(List<Sample> samples, Field f, long from) {
        double lo = Double.NaN, hi = Double.NaN;
        for (Sample s : samples) {
            double v = s.get(f);
            if (Double.isNaN(v) || s.time < from) continue;
            if (Double.isNaN(lo) || v < lo) lo = v;
            if (Double.isNaN(hi) || v > hi) hi = v;
        }
        return new double[]{lo, hi};
    }

    /**
     * How much the value changed over the last {@code windowMs} before the newest report
     * (pressure rising or falling). NaN if there's no report about that long before it.
     */
    public static double change(List<Sample> samples, Field f, long windowMs) {
        int last = -1;
        for (int i = samples.size() - 1; i >= 0; i--) if (!Double.isNaN(samples.get(i).get(f))) { last = i; break; }
        if (last < 0) return Double.NaN;
        long target = samples.get(last).time - windowMs;
        for (int i = last - 1; i >= 0; i--) {
            Sample s = samples.get(i);
            if (Double.isNaN(s.get(f)) || s.time > target) continue;
            // the first report at or before the target; too far before it says nothing
            if (target - s.time > windowMs / 2) return Double.NaN;
            return samples.get(last).get(f) - s.get(f);
        }
        return Double.NaN;
    }

    /**
     * Rain over the time shown, inches, from the "past hour" figures: the sum of one
     * reading per whole hour (readings in between overlap). NaN if there are none.
     */
    public static double rainTotal(List<Sample> samples, long from, long to) {
        double total = 0;
        boolean any = false;
        long hourEnd = Long.MIN_VALUE;
        for (Sample s : samples) {
            double v = s.get(Field.RAIN_1H);
            if (Double.isNaN(v) || s.time < from || s.time >= to) continue;
            any = true;
            if (s.time >= hourEnd) {           // first reading at least an hour after the last one counted
                total += v;
                hourEnd = s.time + 3_600_000L;
            }
        }
        return any ? total : Double.NaN;
    }
}
