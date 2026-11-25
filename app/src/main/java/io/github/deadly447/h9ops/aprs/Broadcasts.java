package io.github.deadly447.h9ops.aprs;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What this phone keeps on the air by itself: objects it placed (a command
 * post, a search area, a hazard) and bulletins. Objects are sent again every
 * few minutes until removed, then sent three times as "killed" so other maps
 * drop them. Bulletins repeat for a set time. This class only decides what is
 * due and builds the APRS text; the app transmits it.
 */
public final class Broadcasts {
    /** Object resend choices, minutes. */
    public static final int[] OBJECT_MINUTES = {5, 10, 30};
    /** Bulletin repeat choices, hours; 0 = send once. */
    public static final int[] BULLETIN_HOURS = {0, 1, 4};
    public static final int BULLETIN_EVERY_MIN = 10;
    static final int KILL_SENDS = 3;
    static final long KILL_EVERY_MS = 60_000;
    public static final int MAX_NAME = 9, MAX_OBJECT_COMMENT = 43, MAX_BULLETIN = 67;

    /** Symbols offered for objects: table, code, name. */
    public static final String[][] SYMBOLS = {
            {"/", "c", "Command post"}, {"/", "A", "Aid station"}, {"/", "z", "Shelter"},
            {"/", "h", "Hospital"}, {"/", "[", "Team"}, {"/", ";", "Camp"},
            {"\\", "P", "Staging / parking"}, {"/", ":", "Fire"}, {"\\", "'", "Crash site"},
            {"/", ".", "Marker"},
    };

    public static final class Obj {
        public final String name;
        public final double lat, lon;
        public final char table, code;
        public final String comment;
        public final int minutes;
        long next;
        /** 0 while it is on the map; counts down the "killed" sends after it is removed. */
        int killsLeft;

        Obj(String name, double lat, double lon, char table, char code, String comment, int minutes) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.table = table;
            this.code = code;
            this.comment = comment;
            this.minutes = minutes;
        }

        public boolean removing() { return killsLeft > 0; }
    }

    public static final class Bln {
        public final String line;      // "0".."9", or a letter for announcements
        public final String text;
        public final long until;       // last send by this time; 0 = once
        long next;

        Bln(String line, String text, long until) {
            this.line = line;
            this.text = text;
            this.until = until;
        }

        public String addressee() { return "BLN" + line; }
    }

    private final List<Obj> objects = new ArrayList<>();
    private final List<Bln> bulletins = new ArrayList<>();

    public List<Obj> objects() {
        List<Obj> l = new ArrayList<>();
        for (Obj o : objects) if (!o.removing()) l.add(o);
        return l;
    }

    public List<Bln> bulletins() { return new ArrayList<>(bulletins); }

    public Obj object(String name) {
        for (Obj o : objects) if (o.name.equalsIgnoreCase(name.trim()) && !o.removing()) return o;
        return null;
    }

    /** Places (or moves) an object; it goes out straight away. Throws with a reason a person can fix. */
    public Obj putObject(String name, double lat, double lon, char table, char code, String comment, int minutes, long now) {
        String n = cleanName(name);
        if (n.isEmpty()) throw new IllegalArgumentException("Give the object a name");
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("That position isn't valid");
        }
        String c = cleanText(comment, MAX_OBJECT_COMMENT);
        objects.removeIf(o -> o.name.equalsIgnoreCase(n));
        Obj o = new Obj(n, lat, lon, table, code, c, minutes > 0 ? minutes : 10);
        o.next = now;
        objects.add(o);
        return o;
    }

    /** Takes an object off the map: three "killed" sends, a minute apart, then it's gone. */
    public boolean removeObject(String name, long now) {
        Obj o = object(name);
        if (o == null) return false;
        o.killsLeft = KILL_SENDS;
        o.next = now;
        return true;
    }

    /** Sends a bulletin now, and every 10 minutes for {@code hours} (0 = once). Replaces one on the same line. */
    public Bln sendBulletin(String line, String text, int hours, long now) {
        String l = line == null ? "" : line.trim().toUpperCase(Locale.US);
        if (!l.matches("[0-9A-Z]")) throw new IllegalArgumentException("Bulletin line must be 0–9 or A–Z");
        String t = cleanText(text, MAX_BULLETIN);
        if (t.isEmpty()) throw new IllegalArgumentException("The bulletin is empty");
        bulletins.removeIf(b -> b.line.equals(l));
        Bln b = new Bln(l, t, hours > 0 ? now + hours * 3_600_000L : 0);
        b.next = now;
        bulletins.add(b);
        return b;
    }

    public void stopBulletin(String line) {
        bulletins.removeIf(b -> b.line.equalsIgnoreCase(line));
    }

    /** APRS info fields due now, in order. Updates the schedule and drops what's finished. */
    public List<String> due(long now) {
        List<String> out = new ArrayList<>();
        for (Obj o : new ArrayList<>(objects)) {
            if (now < o.next) continue;
            if (o.removing()) {
                out.add(objectInfo(o, false, now));
                if (--o.killsLeft == 0) objects.remove(o);
                else o.next = now + KILL_EVERY_MS;
            } else {
                out.add(objectInfo(o, true, now));
                o.next = now + o.minutes * 60_000L;
            }
        }
        for (Bln b : new ArrayList<>(bulletins)) {
            if (now < b.next) continue;
            out.add(bulletinInfo(b));
            long next = now + BULLETIN_EVERY_MIN * 60_000L;
            if (b.until == 0 || next > b.until) bulletins.remove(b);
            else b.next = next;
        }
        return out;
    }

    /** Milliseconds until something is due (Long.MAX_VALUE if nothing is scheduled). */
    public long msUntilNext(long now) {
        long t = Long.MAX_VALUE;
        for (Obj o : objects) t = Math.min(t, o.next - now);
        for (Bln b : bulletins) t = Math.min(t, b.next - now);
        return t == Long.MAX_VALUE ? t : Math.max(0, t);
    }

    // ---------------------------------------------------------------- APRS text

    /** ";NAME_____*DDHHMMzDDMM.mmN/DDDMM.mmW$comment" ("_" instead of "*" when killed). */
    static String objectInfo(Obj o, boolean alive, long now) {
        StringBuilder sb = new StringBuilder(";");
        sb.append(String.format(Locale.US, "%-9s", o.name)).append(alive ? '*' : '_');
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(now);
        sb.append(String.format(Locale.US, "%02d%02d%02dz", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE)));
        sb.append(lat(o.lat)).append(o.table).append(lon(o.lon)).append(o.code).append(o.comment);
        return sb.toString();
    }

    /** ":BLN0     :text". */
    static String bulletinInfo(Bln b) {
        return ":" + String.format(Locale.US, "%-9s", b.addressee()) + ":" + b.text;
    }

    static String lat(double v) {
        long h = Math.round(Math.abs(v) * 6000);          // hundredths of a minute
        return String.format(Locale.US, "%02d%02d.%02d%c", h / 6000, (h % 6000) / 100, h % 100, v >= 0 ? 'N' : 'S');
    }

    static String lon(double v) {
        long h = Math.round(Math.abs(v) * 6000);
        return String.format(Locale.US, "%03d%02d.%02d%c", h / 6000, (h % 6000) / 100, h % 100, v >= 0 ? 'E' : 'W');
    }

    /** Object names: up to 9 printable characters; '|', '~' and the separators APRS uses are dropped. */
    public static String cleanName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.trim().toCharArray()) {
            if (c >= 0x20 && c < 0x7F && c != '|' && c != '~' && c != '*' && c != '_' && c != ':' && c != ';') sb.append(c);
            if (sb.length() == MAX_NAME) break;
        }
        return sb.toString().trim();
    }

    /** Printable ASCII only (what every APRS receiver can show), no '|', '~' or '{'. */
    static String cleanText(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c >= 0x20 && c < 0x7F && c != '|' && c != '~' && c != '{') sb.append(c);
            if (sb.length() == max) break;
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- storage

    /** One line per item (tab separated); schedules restart from now when loaded. */
    public List<String> save() {
        List<String> l = new ArrayList<>();
        for (Obj o : objects) {
            if (o.removing()) continue;
            l.add("O\t" + o.name + "\t" + String.format(Locale.US, "%.6f\t%.6f", o.lat, o.lon) + "\t" + o.table + o.code
                    + "\t" + o.minutes + "\t" + o.comment);
        }
        for (Bln b : bulletins) l.add("B\t" + b.line + "\t" + b.until + "\t" + b.text);
        return l;
    }

    public void load(List<String> lines, long now) {
        objects.clear();
        bulletins.clear();
        for (String s : lines) {
            String[] f = s.split("\t", -1);
            try {
                if (f[0].equals("O") && f.length >= 7 && f[4].length() == 2) {
                    putObject(f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]), f[4].charAt(0), f[4].charAt(1),
                            f[6], Integer.parseInt(f[5]), now);
                } else if (f[0].equals("B") && f.length >= 4) {
                    long until = Long.parseLong(f[2]);
                    if (until != 0 && until <= now) continue;     // finished while the app was closed
                    Bln b = new Bln(f[1], f[3], until);
                    b.next = now;
                    bulletins.add(b);
                }
            } catch (RuntimeException ignored) {
                // skip a damaged line
            }
        }
    }
}
