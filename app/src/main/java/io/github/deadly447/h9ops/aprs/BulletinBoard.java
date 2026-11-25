package io.github.deadly447.h9ops.aprs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bulletins heard on the air (messages to BLN0–BLN9, BLNA–BLNZ, or a group
 * such as BLN1WX), one entry per sender and line. Bulletins repeat, so a
 * repeat only updates the time; they expire after two days.
 */
public final class BulletinBoard {
    public static final long KEEP_MS = 48 * 3_600_000L;
    static final int MAX = 100;

    public static final class Entry {
        public final String from, to;
        public String text;
        public long first, last;
        public int count;
        public boolean mine;

        Entry(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int unseen;

    public static boolean isBulletin(String addressee) {
        if (addressee == null) return false;
        String a = addressee.trim().toUpperCase(Locale.US);
        return a.matches("BLN[0-9A-Z][0-9A-Z-]{0,5}");
    }

    /** Records a message packet if it is a bulletin. True if it's new or its text changed. */
    public boolean add(AprsPacket p, long now) {
        if (p.type != AprsPacket.Type.MESSAGE || !isBulletin(p.msgTo) || p.msgText == null || p.sender == null) return false;
        return put(p.sender, p.msgTo.trim().toUpperCase(Locale.US), p.msgText.trim(), now, false);
    }

    /** One we sent ourselves (the radio doesn't hear its own transmissions). */
    public void addMine(String me, String to, String text, long now) {
        put(me, to, text, now, true);
    }

    private boolean put(String from, String to, String text, long now, boolean mine) {
        String key = from + "|" + to;
        Entry e = entries.remove(key);
        boolean changed = e == null || !e.text.equals(text);
        if (e == null) {
            e = new Entry(from, to);
            e.first = now;
        }
        e.text = text;
        e.last = now;
        e.count++;
        e.mine = mine;
        entries.put(key, e);              // newest last
        while (entries.size() > MAX) entries.remove(entries.keySet().iterator().next());
        if (changed && !mine) unseen++;
        return changed;
    }

    /** Current bulletins, by line (BLN0 first) and then newest first. */
    public List<Entry> entries(long now) {
        entries.values().removeIf(e -> now - e.last > KEEP_MS);
        List<Entry> l = new ArrayList<>(entries.values());
        Collections.sort(l, (a, b) -> a.to.equals(b.to) ? Long.compare(b.last, a.last) : a.to.compareTo(b.to));
        return l;
    }

    public int unseen() { return unseen; }

    public void seen() { unseen = 0; }

    public void clear() {
        entries.clear();
        unseen = 0;
    }
}
