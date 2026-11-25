package io.github.deadly447.h9ops.map;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The overlays added to the map, kept in the app's private storage: one JSON file each
 * (what the map page draws) and an index with the name, whether it's shown, and a summary.
 */
public final class Overlays {
    public static final int MAX = 30;

    public static final class Entry {
        public final String id, name, summary;
        public boolean visible;

        Entry(String id, boolean visible, String name, String summary) {
            this.id = id;
            this.visible = visible;
            this.name = name;
            this.summary = summary;
        }
    }

    private final File dir;
    private final List<Entry> list = new ArrayList<>();

    public Overlays(File dir) {
        this.dir = dir;
        load();
    }

    public List<Entry> list() { return new ArrayList<>(list); }

    public Entry add(Overlay o) throws IOException {
        if (list.size() >= MAX) throw new IOException("Up to " + MAX + " overlays: remove one first");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't make the overlay folder");
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        Files.write(new File(dir, id + ".json").toPath(), o.toJson().getBytes(StandardCharsets.UTF_8));
        Entry e = new Entry(id, true, clean(o.name), o.summary());
        list.add(e);
        save();
        return e;
    }

    public void remove(String id) {
        list.removeIf(e -> e.id.equals(id));
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".json").delete();
        save();
    }

    public void setVisible(String id, boolean on) {
        for (Entry e : list) if (e.id.equals(id)) e.visible = on;
        save();
    }

    /** [{"id":…, …the overlay…}, …] of the visible ones, for the map page. */
    public String json() {
        StringBuilder sb = new StringBuilder("[");
        for (Entry e : list) {
            if (!e.visible) continue;
            String body;
            try {
                body = new String(Files.readAllBytes(new File(dir, e.id + ".json").toPath()), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            if (!body.startsWith("{")) continue;
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(MapJson.q(e.id)).append(',').append(body.substring(1));
        }
        return sb.append(']').toString();
    }

    private static String clean(String s) {
        return s == null ? "Overlay" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private File index() { return new File(dir, "index.txt"); }

    private void load() {
        list.clear();
        try {
            for (String l : Files.readAllLines(index().toPath(), StandardCharsets.UTF_8)) {
                String[] f = l.split("\t", 4);
                if (f.length == 4 && f[0].matches("[a-z0-9]{1,20}")) list.add(new Entry(f[0], f[1].equals("1"), f[2], f[3]));
            }
        } catch (IOException ignored) {
            // none yet
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(e.id).append('\t').append(e.visible ? "1" : "0").append('\t')
                .append(e.name).append('\t').append(clean(e.summary)).append('\n');
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, "index.txt.tmp");
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(index())) Files.write(index().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // shown again next time from what's on disk
        }
    }
}
