package io.github.deadly447.h9ops;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Developer log: one line per Bluetooth, messaging, position and radio event,
 * kept only while Developer mode is on (Settings). Lines go to an app-private
 * file (devlog.txt, rolled over to devlog.1.txt at about 1 MB, so at most
 * about 2 MB) and can be exported from the menu.
 *
 * Never written here: message text, the radio PIN, or your
 * coordinates (fixes are logged by accuracy and age only). Callsigns, message
 * IDs and timings are, because they are what the log is for.
 */
public final class DevLog {
    static final String FILE = "devlog.txt", OLD = "devlog.1.txt";
    private static final long MAX_BYTES = 1_000_000;
    private static final int KEEP_SCREEN = 400;
    private static DevLog instance;

    private final File dir;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final SimpleDateFormat ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private volatile boolean on;

    static synchronized DevLog get(Context c) {
        if (instance == null) instance = new DevLog(c.getApplicationContext());
        return instance;
    }

    private DevLog(Context c) {
        dir = c.getFilesDir();
        prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        on = prefs.getBoolean("devmode", false);
        io.execute(this::loadRecent);
    }

    public boolean on() { return on; }

    public void setOn(boolean v) {
        if (v == on) return;
        if (!v) log("APP", "developer log off");
        on = v;
        prefs.edit().putBoolean("devmode", v).apply();
        if (v) log("APP", "developer log on · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + ") · " + Build.MANUFACTURER + " " + Build.MODEL);
    }

    /** Category: BLE, KISS, MSG, POS, PIN, APP. Text must not hold secrets or message content. */
    public void log(String category, String text) {
        if (!on) return;
        final String line;
        synchronized (ts) { line = ts.format(new Date()) + "  " + category + "  " + text.replace('\n', ' '); }
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > KEEP_SCREEN) recent.removeFirst();
        }
        io.execute(() -> append(line + "\n"));
    }

    /** The last few hundred lines, oldest first. */
    public List<String> recent() {
        synchronized (recent) { return new ArrayList<>(recent); }
    }

    public void clear() {
        synchronized (recent) { recent.clear(); }
        io.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, FILE).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, OLD).delete();
        });
    }

    /** Writes the whole log (older file first) to {@code out}; runs on the log's own thread, then calls done. */
    public void export(OutputStream out, String header, java.util.function.Consumer<String> done) {
        io.execute(() -> {
            String err = null;
            try (OutputStream o = out) {
                o.write(header.getBytes(StandardCharsets.UTF_8));
                for (String name : new String[]{OLD, FILE}) {
                    File f = new File(dir, name);
                    if (!f.exists()) continue;
                    try (FileInputStream in = new FileInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            done.accept(err);
        });
    }

    private void append(String line) {
        File f = new File(dir, FILE);
        try {
            if (f.length() > MAX_BYTES) {
                File old = new File(dir, OLD);
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
                f = new File(dir, FILE);
            }
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // the log is best-effort
        }
    }

    private void loadRecent() {
        File f = new File(dir, FILE);
        if (!f.exists()) return;
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                tail.addLast(l);
                if (tail.size() > KEEP_SCREEN) tail.removeFirst();
            }
        } catch (IOException ignored) {
            return;
        }
        synchronized (recent) {
            ArrayDeque<String> now = new ArrayDeque<>(recent);
            recent.clear();
            recent.addAll(tail);
            recent.addAll(now);
            while (recent.size() > KEEP_SCREEN) recent.removeFirst();
        }
    }

    /** Readable name for an Android Bluetooth GATT status code. */
    static String gattStatus(int s) {
        switch (s) {
            case 0: return "0 success";
            case 8: return "8 link timeout (out of range, radio off, or its Bluetooth stuck)";
            case 19: return "19 radio closed the connection";
            case 22: return "22 phone closed the connection";
            case 34: return "34 link layer timeout";
            case 62: return "62 connection could not be set up";
            case 133: return "133 Android's generic GATT failure";
            case 147: return "147 connection attempt timed out (radio not advertising: its Bluetooth off or stuck)";
            case 257: return "257 GATT failure";
            default: return Integer.toString(s);
        }
    }
}
