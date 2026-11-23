package io.github.deadly447.h9ops.track;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The recorded tracks: one file each in the app's private folder. A track being
 * recorded is "<id>.open" and becomes "<id>.trk" when stopped; an .open file
 * found at start-up (the app was killed) is picked up again, so recording carries on.
 * Plain Java (files and an executor) so it can be unit tested.
 */
public final class Tracks {
    private final File dir;
    private final Executor io;
    private Track current;
    private File currentFile;

    public Tracks(File dir, Executor io) {
        this.dir = dir;
        this.io = io;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] open = dir.listFiles((d, n) -> n.endsWith(".open"));
        if (open != null && open.length > 0) {
            Arrays.sort(open);
            for (int i = 0; i < open.length - 1; i++) close(open[i]);   // only one can be current
            currentFile = open[open.length - 1];
            current = Track.parse(idOf(currentFile), read(currentFile));
            endLine(currentFile);   // a crash can leave half a line: start the next one fresh
        }
    }

    public synchronized boolean recording() { return current != null; }

    /** The track being recorded, or null. Don't change it. */
    public synchronized Track current() { return current; }

    /** Starts a new track (stopping any current one). Returns its id. */
    public synchronized String start(long now, String name) {
        if (current != null) stop(now);
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
        current = new Track(id);
        current.start = now;
        current.name = name == null ? "" : name;
        currentFile = new File(dir, id + ".open");
        append(Track.begin(now, current.name));
        return id;
    }

    /** Stops recording; returns the finished track, or null if none was running. */
    public synchronized Track stop(long now) {
        if (current == null) return null;
        Track t = current;
        t.end = now;
        append(Track.end(now));
        final File f = currentFile;
        io.execute(() -> close(f));
        current = null;
        currentFile = null;
        return t;
    }

    /** A GPS fix from the phone. Ignored when not recording or when filtered out. */
    public synchronized boolean me(long t, double lat, double lon, double alt, float accuracy) {
        if (current == null) return false;
        Track.Point p = current.addMe(t, lat, lon, alt, accuracy);
        if (p == null) return false;
        append(Track.line(p));
        return true;
    }

    /** A station position the radio heard (approx = a rounded shared position). */
    public synchronized void seen(long t, String call, double lat, double lon, boolean approx) {
        if (current == null) return;
        Track.Seen s = current.addSeen(t, call, lat, lon, approx);
        if (s != null) append(Track.line(s));
    }

    /** All finished tracks, newest first (the one recording is not listed). */
    public synchronized List<Track> list() {
        List<Track> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".trk"));
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : fs) out.add(Track.parse(idOf(f), read(f)));
        return out;
    }

    public synchronized boolean delete(String id) {
        return new File(dir, id + ".trk").delete();
    }

    /** Writes one track as GPX, on the io thread; done gets null or an error. */
    public void exportGpx(String id, OutputStream out, String myName, Consumer<String> done) {
        io.execute(() -> {
            File f = new File(dir, id + ".trk");
            if (!f.exists()) f = new File(dir, id + ".open");
            Track t = Track.parse(id, read(f));
            try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                t.writeGpx(w, myName);
                done.accept(null);
            } catch (IOException e) {
                done.accept(e.getMessage());
            }
        });
    }

    // ---- files ----

    private void append(String line) {
        final File f = currentFile;
        final String l = line + "\n";
        io.execute(() -> {
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write(l.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // disk full: the track stops growing, nothing else breaks
            }
        });
    }

    private static void endLine(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            long n = r.length();
            if (n == 0) return;
            r.seek(n - 1);
            if (r.read() != '\n') { r.seek(n); r.write('\n'); }
        } catch (IOException ignored) {
            // unreadable: parse() already skipped what it couldn't read
        }
    }

    private static void close(File open) {
        String n = open.getName();
        //noinspection ResultOfMethodCallIgnored
        open.renameTo(new File(open.getParentFile(), n.substring(0, n.length() - ".open".length()) + ".trk"));
    }

    private static String idOf(File f) {
        String n = f.getName();
        return n.substring(0, n.lastIndexOf('.'));
    }

    private static List<String> read(File f) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        } catch (IOException ignored) {
            // missing or unreadable: an empty track
        }
        return lines;
    }
}
