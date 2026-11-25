package io.github.deadly447.h9ops.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which screens and tools sit in the two rows under the radio card, and in what order
 * (Settings → Tabs and shortcuts). One list of every item: the first four are row 1,
 * the next four row 2, the rest are in the ⋮ menu. Saved as a comma-separated list of
 * names, so a new item added in a later version just joins the end (the ⋮ menu).
 */
public final class Shortcuts {
    public enum Item {
        STATIONS("Stations", "Stations", true),
        MESSAGES("Messages", "Messages", true),
        LOG("Log", "Log", true),
        MAP("Map", "Map", true),
        WEATHER("Weather", "Weather…", false),
        TRACKS("Tracks", "Tracks…", false),
        OBJECTS("Objects", "My objects…", false),
        ALERTS("Alerts", "Alerts…", false),
        BULLETINS("Bulletins", "Bulletins…", false),
        RECORD("Record", "Record track", false),
        MAP_FILE("Map file", "Map file…", false),
        OVERLAYS("Overlays", "Map overlays…", false);

        /** Short label for a row button; the longer one for the ⋮ menu and the settings list. */
        public final String label, menuLabel;
        /** A screen (switches the tab) rather than a tool (opens a dialog). */
        public final boolean tab;

        Item(String label, String menuLabel, boolean tab) {
            this.label = label;
            this.menuLabel = menuLabel;
            this.tab = tab;
        }
    }

    public static final int PER_ROW = 4, IN_ROWS = 2 * PER_ROW;

    private final List<Item> order = new ArrayList<>();

    public Shortcuts() { reset(); }

    /** From the saved text; unknown names are skipped, missing items go at the end. */
    public static Shortcuts parse(String saved) {
        Shortcuts s = new Shortcuts();
        if (saved == null || saved.trim().isEmpty()) return s;
        Set<Item> seen = new LinkedHashSet<>();
        for (String name : saved.split(",")) {
            try {
                seen.add(Item.valueOf(name.trim().toUpperCase(Locale.US)));
            } catch (IllegalArgumentException ignored) {
                // an item a newer or older version had
            }
        }
        seen.addAll(Arrays.asList(Item.values()));
        s.order.clear();
        s.order.addAll(seen);
        return s;
    }

    public String save() {
        StringBuilder sb = new StringBuilder();
        for (Item i : order) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i.name());
        }
        return sb.toString();
    }

    public void reset() {
        order.clear();
        order.addAll(Arrays.asList(Item.values()));
    }

    public List<Item> order() { return new ArrayList<>(order); }

    /** Row 0 or 1: four items. */
    public List<Item> row(int r) {
        return new ArrayList<>(order.subList(r * PER_ROW, (r + 1) * PER_ROW));
    }

    /** What's left for the ⋮ menu, in order. */
    public List<Item> menu() {
        return new ArrayList<>(order.subList(IN_ROWS, order.size()));
    }

    /** Swaps the item at {@code i} with its neighbour above (-1) or below (+1). */
    public boolean move(int i, int delta) {
        int j = i + delta;
        if (i < 0 || i >= order.size() || j < 0 || j >= order.size()) return false;
        Item t = order.get(i);
        order.set(i, order.get(j));
        order.set(j, t);
        return true;
    }

    /** "Row 1", "Row 2" or "⋮ menu" for a position in the list. */
    public static String place(int i) {
        return i < PER_ROW ? "Row 1" : i < IN_ROWS ? "Row 2" : "⋮ menu";
    }
}
