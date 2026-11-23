package io.github.deadly447.h9ops;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.deadly447.h9ops.aprs.AprsPacket;
import io.github.deadly447.h9ops.aprs.AutoPosition;
import io.github.deadly447.h9ops.aprs.Ax25;
import io.github.deadly447.h9ops.aprs.Messenger;
import io.github.deadly447.h9ops.aprs.PositionShare;
import io.github.deadly447.h9ops.aprs.Station;
import io.github.deadly447.h9ops.aprs.StationStore;
import io.github.deadly447.h9ops.aprs.Kiss;
import io.github.deadly447.h9ops.track.Tracks;

/**
 * App-wide state: heard stations, connection status and the saved radio.
 * Everything here runs on the main thread. Packets are kept in a private file
 * inside the app's own storage so the list survives a restart; nothing leaves
 * the phone.
 */
public final class Repo {
    public enum State { STOPPED, SCANNING, CONNECTING, CONNECTED, WAITING }

    public interface Listener {
        void onRepoChanged();
    }

    private static Repo instance;

    public static Repo get(Context c) {
        if (instance == null) instance = new Repo(c.getApplicationContext());
        return instance;
    }

    /** Sends a finished AX.25 frame to the radio; set by RadioService while connected. */
    public interface Transmitter {
        boolean transmit(byte[] ax25);
        /** Sends a raw KISS command frame (radio PIN commands). */
        boolean sendCommand(byte[] kissFrame);
    }

    static final String MSG_CHANNEL = "messages";
    private static final String LOG_FILE = "packets.log";
    private static final String MSG_FILE = "messages.txt";
    private static final long TICK_MS = 1000;
    private static final int KEEP_FILE_LINES = StationStore.KEEP_LOG;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    public final StationStore store = new StationStore();
    private State state = State.STOPPED;
    private String stateText = "Not connected";
    private int linesInFile;
    private Transmitter transmitter;
    public final Messenger messenger;
    /** Developer log (Settings → Developer mode). */
    public final DevLog dev;
    /** Recorded tracks (TrackService adds the phone's GPS, addFrame the stations heard). */
    public final Tracks tracks;
    private final Set<String> unread = new HashSet<>();
    private String openThread;        // conversation on screen right now (no notification for it)
    private String notice;            // one-off message for the screen (radio PIN replies)
    private int noticeSeq;
    private String pendingPin;        // PIN we asked the radio to set, until it answers

    private Repo(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        dev = DevLog.get(ctx);
        tracks = new Tracks(new File(ctx.getFilesDir(), "tracks"), io);
        messenger = new Messenger(new Messenger.Config() {
            @Override public String myCall() { return Repo.this.myCall(); }
            @Override public List<String> path() { return Repo.this.txPath(); }
            @Override public boolean quiet() { return Repo.this.quiet(); }
        }, ax25 -> {
            if (transmitter == null) return false;
            if (pinHold) { dev.log("PIN", "not sending: paused after the radio reported a wrong PIN"); return false; }
            return transmitter.transmit(ax25);
        });
        messenger.setEvents(w -> dev.log("MSG", w));
        load();
        loadMessages();
        loadAuto();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(MSG_CHANNEL, "Messages",
                        NotificationManager.IMPORTANCE_HIGH));
            }
        }
        main.postDelayed(ticker, TICK_MS);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (messenger.tick(System.currentTimeMillis())) messagesChanged();
            main.postDelayed(this, TICK_MS);
        }
    };

    // ---- messaging settings ----
    /** Your callsign with SSID, e.g. N0CALL-7; null until set. */
    public String myCall() { return prefs.getString("mycall", null); }
    public String pathText() { return prefs.getString("path", "WIDE1-1"); }
    public boolean rfOnly() { return prefs.getBoolean("rfonly", true); }
    /** Quiet mode: don't acknowledge (transmit to) stations we don't know. */
    public boolean quiet() { return prefs.getBoolean("quiet", false); }
    public void setDevMode(boolean on) { dev.setOn(on); changed(); }
    public void setQuiet(boolean on) { prefs.edit().putBoolean("quiet", on).apply(); changed(); }

    /** Saves the settings; returns an error to show, or null if they were saved. */
    public String saveSettings(String call, String path, boolean rfOnly) {
        String c = call.trim().toUpperCase(Locale.US);
        if (!c.isEmpty() && !Ax25.validCall(c)) return "Callsign should look like N0CALL-7";
        String p = path.trim().toUpperCase(Locale.US).replace(" ", "");
        for (String hop : p.isEmpty() ? new String[0] : p.split(",")) {
            if (!Ax25.validCall(hop)) return "Path should look like WIDE1-1 or WIDE1-1,WIDE2-1";
        }
        if (p.split(",").length > 7) return "Path is too long";
        prefs.edit().putString("mycall", c.isEmpty() ? null : c).putString("path", p)
                .putBoolean("rfonly", rfOnly).apply();
        changed();
        return null;
    }

    List<String> txPath() {
        List<String> l = new ArrayList<>();
        String p = pathText();
        if (!p.isEmpty()) l.addAll(Arrays.asList(p.split(",")));
        // iGates (Direwolf, APRSIS32 and others) do not pass packets with NOGATE to the internet
        if (rfOnly() && !l.contains("NOGATE") && !l.contains("RFONLY")) l.add("NOGATE");
        return l;
    }

    // ---- radio PIN (custom firmware) ----
    // Kept per radio name (TD-H9-1234(BLE)), not per address: on stock
    // firmware the address changes at every power-on.
    private String pinPref() {
        String n = radioName();
        if (n != null) return "pinn_" + n;
        String mac = radioAddress();
        return "pin_" + (mac == null ? "" : mac.replace(":", ""));
    }

    /** The PIN this phone sends before each transmission, or null. */
    public String radioPin() {
        String sealed = prefs.getString(pinPref(), null);
        byte[] b = KeyVault.open(sealed);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Set when the radio says the PIN is wrong (or it has had too many): no
     * more sends until the PIN is fixed, so retries and acks can't use up the
     * radio's 5 tries.
     */
    private boolean pinHold;

    public boolean pinHold() { return pinHold; }

    /** Checks a PIN: 4 to 8 printable characters, no spaces. Returns an error, or null. */
    public static String checkPin(String pin) {
        if (pin == null || !pin.matches("[\\x21-\\x7e]{4,8}")) return "PIN: 4 to 8 characters, no spaces";
        return null;
    }

    /** Uses this PIN from now on, without changing the radio (it already has it). */
    public String useRadioPin(String pin) {
        pinHold = false;
        if (pin == null || pin.isEmpty()) {
            prefs.edit().remove(pinPref()).apply();
            changed();
            return null;
        }
        String err = checkPin(pin);
        if (err != null) return err;
        String sealed = KeyVault.seal(pin.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (sealed == null) return "The phone's keystore refused to store the PIN";
        prefs.edit().putString(pinPref(), sealed).apply();
        changed();
        if (messenger.tick(System.currentTimeMillis())) messagesChanged();
        return null;
    }

    /** Asks the radio to store a new PIN. The radio answers through {@link #onRadioReply}. */
    public String setRadioPin(String pin) {
        String err = checkPin(pin);
        if (err != null) return err;
        if (transmitter == null) return "Connect to the radio first";
        pendingPin = pin;
        if (!transmitter.sendCommand(Kiss.command("H9PS" + pin))) {
            pendingPin = null;
            return "Could not send to the radio";
        }
        return null;
    }

    /** KISS command frame for the unlock that goes before each transmission, or null without a PIN. */
    byte[] unlockFrame() {
        String pin = radioPin();
        return pin == null ? null : Kiss.command("H9PU" + pin);
    }

    /** A reply from the radio's PIN code ("H9PR" + one letter). */
    void onRadioReply(char code) {
        dev.log("PIN", "radio replied '" + code + "': " + (code == 'K' ? "new PIN stored" : code == 'W' ? "set refused (set window not open)"
                : code == 'L' ? "transmit refused: radio has a PIN" : code == 'B' ? "wrong PIN" : code == 'X' ? "too many wrong PINs, locked"
                : "unknown reply"));
        switch (code) {
            case 'K':
                if (pendingPin != null) {
                    String err = useRadioPin(pendingPin);
                    showNotice(err != null ? err : "Radio PIN set. Only phones with this PIN can transmit through the radio.");
                }
                pendingPin = null;
                break;
            case 'W':
                pendingPin = null;
                showNotice("The radio didn't accept the PIN: on the radio, open APRS → APRS Mute and press confirm (keep the same setting), then try again.");
                break;
            case 'L':
                showNotice("The radio refused to transmit: it has a PIN. Enter it under Settings → Radio PIN.");
                break;
            case 'B':
                pinHold = true;
                showNotice("The radio says the PIN is wrong. Sending is paused so the radio doesn't lock you out: fix it under Settings → Radio PIN.");
                break;
            case 'X':
                pinHold = true;
                showNotice("Too many wrong PINs: the radio stopped accepting them. On the radio, open APRS → APRS Mute and press confirm, then check Settings → Radio PIN.");
                break;
            default:
                break;
        }
    }

    private void showNotice(String s) {
        notice = s;
        noticeSeq++;
        changed();
    }

    public int noticeSeq() { return noticeSeq; }
    public String notice() { return notice; }

    // ---- TX delay (KISS command 0x01) ----

    /** TX delay to send the radio, in ms, or -1 to send nothing and keep the radio's own setting. */
    public int txDelayMs() { return prefs.getInt("txdelay_ms", -1); }

    /** Saves the TX delay and, if the radio is connected, sends it now. */
    public void setTxDelay(int ms) {
        int v = ms < 0 ? -1 : Math.min(ms, Kiss.TXDELAY_MAX_MS);
        if (v == txDelayMs()) return;
        prefs.edit().putInt("txdelay_ms", v).apply();
        if (v >= 0) sendTxDelay();
        else dev.log("KISS", "TX delay set back to the radio's own (the radio keeps the last value sent until it restarts)");
    }

    /** Sends the saved TX delay; done on every connect, since the radio may forget it when switched off. */
    private void sendTxDelay() {
        int ms = txDelayMs();
        if (ms < 0 || transmitter == null) return;
        boolean ok = transmitter.sendCommand(Kiss.txDelay(ms));
        dev.log("KISS", "TX delay " + ms + " ms " + (ok ? "sent to the radio" : "not sent: radio not connected"));
    }

    public void setTransmitter(Transmitter t) {
        transmitter = t;
        if (t == null) pendingPin = null;
        if (t != null) sendTxDelay();
        if (t != null && messenger.tick(System.currentTimeMillis())) messagesChanged();
    }

    public boolean canTransmit() { return transmitter != null; }

    // ---- timed position sharing ----

    /** Which stations get my position every N minutes. Run by RadioService. */
    public final AutoPosition auto = new AutoPosition();

    private void loadAuto() {
        String s = prefs.getString("auto", "");
        auto.load(s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split("\n")), System.currentTimeMillis());
    }

    void saveAuto() {
        prefs.edit().putString("auto", String.join("\n", auto.save())).apply();
    }

    /** Starts sending my position to {@code peer}; the first one goes out straight away. */
    public String startAuto(String peer, int minutes, PositionShare.Blur blur, int stopHours) {
        if (myCall() == null) return "Set your callsign first";
        auto.start(peer, minutes, blur, stopHours, System.currentTimeMillis());
        dev.log("POS", "timed position to " + peer + " started: every " + minutes + " min, " + blur.label
                + (stopHours > 0 ? ", stops after " + stopHours + " h" : ", no stop time"));
        saveAuto();
        changed();
        RadioService.start(ctx);   // (re)starts the service with location access
        return null;
    }

    public void stopAuto(String peer) {
        auto.stop(peer);
        dev.log("POS", "timed position to " + peer + " stopped");
        saveAuto();
        changed();
    }

    /** A fix for a timed position: send it, replacing an earlier one still waiting for an ack. */
    void sendAuto(AutoPosition.Plan plan, double lat, double lon, long fixTime) {
        long now = System.currentTimeMillis();
        for (Messenger.Msg m : messenger.messages(plan.peer)) {
            if (m.outgoing && m.state == Messenger.State.SENDING && PositionShare.decode(m.text) != null) {
                messenger.cancel(m, now);   // a newer position makes it pointless
            }
        }
        String err = sendMessage(plan.peer, PositionShare.encode(lat, lon, plan.blur, fixTime));
        if (err != null) auto.failed(plan, now, err);
        else auto.sent(plan, now);
        dev.log("POS", "timed position to " + plan.peer + (err != null ? " not sent: " + err : " handed to messaging (" + plan.blur.label + ")"));
        saveAuto();
        changed();
    }

    void autoFailed(AutoPosition.Plan plan, String why) {
        auto.failed(plan, System.currentTimeMillis(), why);
        dev.log("POS", "timed position to " + plan.peer + " skipped: " + why);
        saveAuto();
        changed();
    }

    void autoEnded(AutoPosition.Plan plan) {
        dev.log("POS", "timed position to " + plan.peer + " ended (stop time reached)");
        saveAuto();
        showNotice("Stopped sending your position to " + plan.peer + " (the time you chose is up).");
    }

    /** Sends a message; returns an error to show, or null. */
    public String sendMessage(String to, String text) {
        try {
            messenger.send(to, text, System.currentTimeMillis());
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        messagesChanged();
        return null;
    }

    public boolean isUnread(String peer) { return unread.contains(peer); }

    public void setOpenThread(String peer) {
        openThread = peer;
        if (peer != null && unread.remove(peer)) changed();
        if (peer != null) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(peer, 2);
        }
    }

    public void messagesChanged() {
        saveMessages();
        changed();
    }

    private void notifyMessage(Messenger.Msg m) {
        if (m.peer.equals(openThread)) return;
        unread.add(m.peer);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class)
                .putExtra(MainActivity.EXTRA_THREAD, m.peer)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, m.peer.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // No message text shows on the lock screen (only "New APRS message").
        String body = m.text;
        Notification pub = (Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, MSG_CHANNEL) : new Notification.Builder(ctx))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("New APRS message")
                .build();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, MSG_CHANNEL) : new Notification.Builder(ctx);
        Notification n = b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(m.peer)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(pub)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(m.peer, 2, n);
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    private void saveMessages() {
        final List<String> lines = messenger.save();
        io.execute(() -> {
            File tmp = new File(ctx.getFilesDir(), MSG_FILE + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (String l : lines) w.write(l + "\n");
            } catch (IOException e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(ctx.getFilesDir(), MSG_FILE));
        });
    }

    private void loadMessages() {
        File f = new File(ctx.getFilesDir(), MSG_FILE);
        if (!f.exists()) return;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        } catch (IOException e) {
            return;
        }
        messenger.load(lines, System.currentTimeMillis());
        for (String peer : messenger.peers()) applySharedPositions(peer);
    }

    /** If a received message is a position (PositionShare), show it on that station. */
    private void applySharedPosition(Messenger.Msg m, boolean live) {
        if (m.outgoing) return;
        PositionShare.Pos pos = PositionShare.decode(m.text);
        if (pos == null) return;
        String who = m.from();
        store.addShared(m.time, PositionShare.toPacket(who, pos,
                who + " (position by message)"));
        if (live) tracks.seen(m.time, who, pos.lat, pos.lon, pos.blur != PositionShare.Blur.EXACT);
    }

    private void applySharedPositions(String peer) {
        for (Messenger.Msg m : messenger.messages(peer)) applySharedPosition(m, false);
    }

    public void clearMessages() {
        messenger.clear();
        unread.clear();
        messagesChanged();
    }

    public void deleteThread(String peer) {
        messenger.deleteThread(peer);
        unread.remove(peer);
        messagesChanged();
    }

    // ---- saved radio ----
    public String radioAddress() { return prefs.getString("mac", null); }
    public String radioName() { return prefs.getString("name", null); }

    public void setRadio(String address, String name) {
        pinHold = false;
        movedTo = null;
        prefs.edit().putString("mac", address).putString("name", name).apply();
        changed();
    }

    /** A device with our radio's name at another address, waiting for the user to confirm. */
    private String movedTo;
    private int moveSeq;

    public String movedTo() { return movedTo; }
    public int moveSeq() { return moveSeq; }

    void offerMove(String address) {
        if (address.equalsIgnoreCase(movedTo)) return;   // already asked about this one
        dev.log("BLE", "radio's name seen at a new address " + address + ": asking you to confirm");
        movedTo = address;
        moveSeq++;
        changed();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, MSG_CHANNEL) : new Notification.Builder(ctx))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Radio at a new address")
                .setContentText("Open H9 OPS to confirm it's your radio")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try { nm.notify("move", 3, n); } catch (SecurityException ignored) { }
    }

    /** The user confirmed the radio at its new address. */
    public void acceptMove() {
        if (movedTo == null) return;
        dev.log("BLE", "new radio address " + movedTo + " confirmed");
        radioMoved(movedTo);
        movedTo = null;
    }

    public void declineMove() { changed(); }

    /** The saved radio was found under a new address (same name): remember it. */
    void radioMoved(String address) {
        prefs.edit().putString("mac", address).apply();
        changed();
    }


    // ---- channel B reminder ----
    private long connectedAt, lastFrameAt;
    private boolean manualConnect;
    private boolean checkWanted, checklistDue;
    /** Remind me to check channel B (default on). */
    public boolean channelReminder() { return prefs.getBoolean("chb_remind", true); }
    public void setChannelReminder(boolean on) { prefs.edit().putBoolean("chb_remind", on).apply(); changed(); }
    /** Called when the user presses Connect: the next connection shows the checklist. */
    public void markManualConnect() { manualConnect = true; }
    /**
     * What to show about channel B now, or null; taking it clears it. With firmware P2 the
     * radio's own report decides (a warning only when something is wrong); without one,
     * once the radio hasn't answered, the checklist (ChannelCheck.CHECKLIST) as before.
     */
    public String takeChannelCheck() {
        if (!checkWanted || state != State.CONNECTED) return null;
        if (radioInfo != null) {
            checkWanted = false;
            return radioInfo.channelBProblem();
        }
        if (!infoMissing) return null;           // still waiting for the radio's answer
        checkWanted = false;
        if (!checklistDue) return null;
        prefs.edit().putLong("chb_shown", System.currentTimeMillis()).apply();
        return ChannelCheck.CHECKLIST;
    }
    /** Hint for the status line, or null. */
    public String quietHint() {
        if (state != State.CONNECTED || !channelReminder()) return null;
        return ChannelCheck.quietHint(System.currentTimeMillis(), connectedAt, lastFrameAt,
                radioInfo != null && radioInfo.channelBProblem() == null);
    }

    // ---- radio info (firmware P2) ----
    private RadioInfo radioInfo;
    private boolean infoMissing;
    /** The radio's last report on this connection, or null (not connected, or firmware older than P2). */
    public RadioInfo radioInfo() { return state == State.CONNECTED ? radioInfo : null; }

    /** A report from the radio (RadioService asks on connect and every minute). */
    void onRadioInfo(RadioInfo i) {
        RadioInfo old = radioInfo;
        if (i.settingsDiffer(old)) dev.log("INFO", i.devLine());
        radioInfo = i;
        // channel B went wrong during the connection: warn again
        if (old != null && channelReminder() && old.channelBProblem() == null && i.channelBProblem() != null)
            checkWanted = true;
        changed();
    }

    /** The radio didn't answer the info question: firmware older than P2. */
    void onRadioInfoMissing() {
        infoMissing = true;
        changed();
    }

    // ---- connection state ----
    public State state() { return state; }
    public String stateText() { return stateText; }

    public void setState(State s, String text) {
        if (s == State.CONNECTED && state != State.CONNECTED) {
            connectedAt = System.currentTimeMillis();
            long now = connectedAt;
            radioInfo = null;
            infoMissing = false;
            checkWanted = channelReminder();
            checklistDue = ChannelCheck.remind(channelReminder(), now, prefs.getLong("chb_shown", 0), manualConnect);
            if (checklistDue) manualConnect = false;
        } else if (s != State.CONNECTED) {
            connectedAt = 0;
        }
        state = s;
        stateText = text;
        changed();
    }

    // ---- packets ----
    /** Records one AX.25 frame received from the radio. */
    public void addFrame(long now, byte[] ax25) {
        lastFrameAt = System.currentTimeMillis();
        AprsPacket p = store.add(now, ax25);
        if (p == null) return;
        if (p.hasPosition() && !p.killed) tracks.seen(now, p.name, p.lat, p.lon, p.ambiguity > 0);
        final String line = now + "\t" + hex(ax25) + "\n";
        io.execute(() -> append(line));
        Messenger.Msg in = messenger.onPacket(p, now);
        if (in != null) {
            applySharedPosition(in, true);
            notifyMessage(in);
        }
        if (p.type == AprsPacket.Type.MESSAGE) saveMessages();
        changed();
    }

    /**
     * Writes the packet log (oldest first) as text: local time, how it was heard, the
     * packet in TNC2 form.
     */
    public void exportPackets(java.io.OutputStream out, String header, java.util.function.Consumer<String> done) {
        List<Station.Heard> l = store.log();
        io.execute(() -> {
            String err = null;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            try (java.io.Writer w = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(header);
                w.write("# time\theard\tpacket\n");
                for (int i = l.size() - 1; i >= 0; i--) {
                    Station.Heard h = l.get(i);
                    w.write(f.format(new java.util.Date(h.time)) + "\t"
                            + h.packet.route() + (h.again ? " (again)" : "") + "\t"
                            + h.packet.raw.replace('\n', ' ') + "\n");
                }
            } catch (java.io.IOException | RuntimeException e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String e2 = err;
            main.post(() -> done.accept(e2));
        });
    }

    public void clear() {
        store.clear();
        io.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(ctx.getFilesDir(), LOG_FILE).delete();
            linesInFile = 0;
        });
        changed();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** The recording started, stopped or grew: redraw. */
    public void trackChanged() { changed(); }

    private void changed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::changed);
            return;
        }
        for (Listener l : new ArrayList<>(listeners)) l.onRepoChanged();
    }

    // ---- private file ----
    private void load() {
        File f = new File(ctx.getFilesDir(), LOG_FILE);
        if (!f.exists()) return;
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                lines.addLast(l);
                if (lines.size() > KEEP_FILE_LINES) lines.removeFirst();
            }
        } catch (IOException e) {
            return;
        }
        for (String l : lines) {
            int tab = l.indexOf('\t');
            if (tab <= 0) continue;
            try {
                store.add(Long.parseLong(l.substring(0, tab)), unhex(l.substring(tab + 1)));
            } catch (RuntimeException ignored) {
                // skip a damaged line
            }
        }
        linesInFile = lines.size();
    }

    private void append(String line) {
        File f = new File(ctx.getFilesDir(), LOG_FILE);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.US_ASCII)) {
            w.write(line);
        } catch (IOException ignored) {
            return;
        }
        if (++linesInFile > KEEP_FILE_LINES * 2) trim(f);
    }

    /** Keeps the file from growing without limit: rewrite it with the newest lines. */
    private void trim(File f) {
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                lines.addLast(l);
                if (lines.size() > KEEP_FILE_LINES) lines.removeFirst();
            }
        } catch (IOException e) {
            return;
        }
        File tmp = new File(ctx.getFilesDir(), LOG_FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.US_ASCII)) {
            for (String l : lines) w.write(l + "\n");
        } catch (IOException e) {
            return;
        }
        if (tmp.renameTo(f)) linesInFile = lines.size();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
