package io.github.deadly447.h9ops.aprs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * APRS telemetry (APRS 1.0.1 chapter 13, with the 1.2 relaxations): value
 * reports "T#seq,a1,a2,a3,a4,a5,bbbbbbbb" and the four definition messages a
 * station sends to itself: PARM. (names), UNIT. (units, or the label for an
 * "on" bit), EQNS. (value = a*x^2 + b*x + c for each of the five analog
 * channels) and BITS. (which bit value counts as "on", then a project title).
 */
public final class Telemetry {
    private Telemetry() { }

    /** One "T#" report. Missing analog channels are NaN. */
    public static final class Values {
        public String seq = "";
        public final double[] analog = new double[5];
        public String bits;          // 8 characters of 0/1, or null
        public String comment;

        public static Values parse(String body) {
            if (body == null) return null;
            Values v = new Values();
            Arrays.fill(v.analog, Double.NaN);
            String[] f = body.split(",", 7);
            v.seq = f[0].trim();
            for (int i = 1; i < f.length; i++) {
                String t = f[i].trim();
                // the bits field: always the 7th, or the last one if a station sends fewer channels
                if (i == 6 || (i == f.length - 1 && t.length() >= 8 && t.substring(0, 8).matches("[01]{8}"))) {
                    if (t.length() >= 8 && t.substring(0, 8).matches("[01]{8}")) {
                        v.bits = t.substring(0, 8);
                        String c = t.substring(8).trim();
                        if (!c.isEmpty()) v.comment = c;
                    }
                    break;
                }
                try {
                    v.analog[i - 1] = Double.parseDouble(t);
                } catch (NumberFormatException e) {
                    v.analog[i - 1] = Double.NaN;
                }
            }
            boolean any = v.bits != null;
            for (double a : v.analog) any |= !Double.isNaN(a);
            return any ? v : null;
        }
    }

    /** What a station's channels mean. Each definition message fills in its part. */
    public static final class Defs {
        public final String[] names = new String[13];
        public final String[] units = new String[13];
        public final double[][] eqns = new double[5][];
        public String sense = "11111111";
        public String title;

        public Defs() {
            for (int i = 0; i < 5; i++) eqns[i] = new double[]{0, 1, 0};
        }

        public boolean any() {
            for (String s : names) if (s != null && !s.isEmpty()) return true;
            return false;
        }

        /** Applies "PARM.…", "UNIT.…", "EQNS.…" or "BITS.…". Returns false if it isn't one. */
        public boolean apply(String body) {
            if (body == null || body.length() < 5) return false;
            String kind = body.substring(0, 5), rest = body.substring(5);
            int brace = rest.lastIndexOf('{');       // some stations send them with a message id
            if (brace >= 0 && rest.length() - brace <= 6) rest = rest.substring(0, brace);
            switch (kind) {
                case "PARM.": fill(names, rest); return true;
                case "UNIT.": fill(units, rest); return true;
                case "EQNS.": {
                    String[] f = rest.split(",", -1);
                    for (int ch = 0; ch < 5; ch++) {
                        double[] e = {0, 1, 0};
                        boolean ok = true;
                        for (int k = 0; k < 3; k++) {
                            int i = ch * 3 + k;
                            if (i >= f.length || f[i].trim().isEmpty()) { ok = false; break; }
                            try { e[k] = Double.parseDouble(f[i].trim()); } catch (NumberFormatException x) { ok = false; break; }
                        }
                        if (ok) eqns[ch] = e;
                    }
                    return true;
                }
                case "BITS.": {
                    String s = rest.trim();
                    if (s.length() >= 8 && s.substring(0, 8).matches("[01]{8}")) {
                        sense = s.substring(0, 8);
                        String t = s.substring(8);
                        if (t.startsWith(",")) t = t.substring(1);
                        t = t.trim();
                        title = t.isEmpty() ? null : t;
                    }
                    return true;
                }
                default: return false;
            }
        }

        private static void fill(String[] into, String list) {
            String[] f = list.split(",", -1);
            for (int i = 0; i < into.length && i < f.length; i++) into[i] = f[i].trim();
        }
    }

    /** Value of analog channel {@code ch} (0..4) with the station's equation. */
    public static double scaled(Defs d, int ch, double raw) {
        if (d == null) return raw;
        double[] e = d.eqns[ch];
        return e[0] * raw * raw + e[1] * raw + e[2];
    }

    /** "13.7", "330", "0.25": about three significant figures, no trailing zeros. */
    public static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "?";
        double a = Math.abs(v);
        int dec = a >= 100 ? 0 : a >= 10 ? 1 : a >= 1 ? 2 : 3;
        String s = String.format(Locale.US, "%." + dec + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    /** One line per channel: "Vin 13.7 Volt", "O1 On". Without definitions, the raw numbers. */
    public static List<String> describe(Values v, Defs d) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        boolean named = d != null && d.any();
        for (int ch = 0; ch < 5; ch++) {
            if (Double.isNaN(v.analog[ch])) continue;
            String name = named ? d.names[ch] : null;
            if (named && (name == null || name.isEmpty())) continue;   // channel the station doesn't use
            String unit = named && d.units[ch] != null ? d.units[ch] : "";
            out.add((name != null ? name : "A" + (ch + 1)) + "  " + number(scaled(d, ch, v.analog[ch]))
                    + (unit.isEmpty() ? "" : " " + unit));
        }
        if (v.bits != null) {
            if (!named) {
                out.add("Bits  " + v.bits);
            } else {
                for (int b = 0; b < 8; b++) {
                    String name = d.names[5 + b];
                    if (name == null || name.isEmpty()) continue;
                    boolean on = v.bits.charAt(b) == d.sense.charAt(b);
                    String label = d.units[5 + b];
                    out.add(name + "  " + (on ? (label == null || label.isEmpty() ? "on" : label) : "off"));
                }
            }
        }
        return out;
    }

    /** Short form for the station list: the first two channels. */
    public static String brief(Values v, Defs d) {
        List<String> l = describe(v, d);
        if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 2; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(l.get(i).replace("  ", " "));
        }
        return sb.toString();
    }
}
