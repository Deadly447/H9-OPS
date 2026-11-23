package io.github.deadly447.h9ops;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.deadly447.h9ops.aprs.AprsPacket;
import io.github.deadly447.h9ops.aprs.AutoPosition;
import io.github.deadly447.h9ops.aprs.Ax25;
import io.github.deadly447.h9ops.aprs.AprsParser;
import io.github.deadly447.h9ops.aprs.Format;
import io.github.deadly447.h9ops.aprs.Kiss;
import io.github.deadly447.h9ops.aprs.Messenger;
import io.github.deadly447.h9ops.aprs.PositionShare;
import io.github.deadly447.h9ops.aprs.Telemetry;
import io.github.deadly447.h9ops.aprs.Station;
import io.github.deadly447.h9ops.aprs.Symbols;
import io.github.deadly447.h9ops.track.Track;

/** One screen: connection bar, then either the heard-stations list or the raw packet log. */
@SuppressLint("MissingPermission")   // checked in hasPermissions() before any Bluetooth call
public class MainActivity extends Activity implements Repo.Listener {
    public static final String EXTRA_THREAD = "thread";
    private static final int REQ_PERMS = 1, REQ_LOCATION = 2;
    private static final int TAB_STATIONS = 0, TAB_MESSAGES = 1, TAB_LOG = 2;

    private Repo repo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView emptyText, footer;
    private RadioCard radioCard;
    private Button stationsTab, messagesTab, logTab, newMsgBtn;
    private ListView list;
    private int tab = TAB_STATIONS;
    private final PeerAdapter peerAdapter = new PeerAdapter();
    private Conversation conversation;   // open conversation dialog, if any
    private final StationAdapter stationAdapter = new StationAdapter();
    private final LogAdapter logAdapter = new LogAdapter();
    private Runnable afterPermissions;
    private int noticeShown, moveShown;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            onRepoChanged();   // also updates the "nothing heard" hint
            ui.postDelayed(this, 10000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        Ui.load(this);
        int theme = getResources().getIdentifier(Ui.night ? "AppTheme.Red" : "AppTheme", "style", getPackageName());
        if (theme != 0) setTheme(theme);
        super.onCreate(state);
        repo = Repo.get(this);
        noticeShown = repo.noticeSeq();
        moveShown = 0;
        setContentView(buildLayout());
        if (state != null) tab = state.getInt("tab");
        selectTab(tab);
        openThreadFrom(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openThreadFrom(intent);
    }

    private void openThreadFrom(Intent i) {
        String peer = i == null ? null : i.getStringExtra(EXTRA_THREAD);
        if (peer == null) return;
        i.removeExtra(EXTRA_THREAD);
        // This activity can be started by other apps: only open real callsigns.
        peer = peer.trim().toUpperCase(Locale.US);
        if (!Ax25.validCall(peer)) return;
        selectTab(TAB_MESSAGES);
        openConversation(peer);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        repo.addListener(this);
        onRepoChanged();
        ui.postDelayed(tick, 10000);
        // A recording that Android stopped (low memory, update) carries on once the app is open
        if (repo.tracks.recording() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) TrackService.start(this, null);
    }

    @Override
    protected void onPause() {
        repo.removeListener(this);
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onRepoChanged() {
        Repo.State s = repo.state();
        boolean connected = s == Repo.State.CONNECTED;
        String name = repo.radioName(), mac = repo.radioAddress();
        String radio = mac == null ? "No radio chosen" : (name != null ? name.replace("(BLE)", "").trim() : mac)
                + " · BLE" + (repo.radioPin() != null ? " · PIN LOCKED" : "");
        radioCard.update(s, repo.radioInfo(), radio, repo.stateText(), repo.quietHint());
        stationsTab.setText("Stations " + repo.store.size());
        int unread = 0;
        for (String p : repo.messenger.peers()) if (repo.isUnread(p)) unread++;
        messagesTab.setText(unread > 0 ? "Messages " + unread : "Messages");
        footer.setText("NO INTERNET  ·  " + (repo.rfOnly() ? "NOGATE ON" : "NOGATE OFF")
                + (repo.quiet() ? "  ·  QUIET" : "") + (repo.dev.on() ? "  ·  DEV LOG" : "")
                + (repo.tracks.recording() ? "  ·  ● REC" : "") + "  ·  " + repo.store.size() + " HEARD");
        refreshList();
        if (conversation != null) conversation.refresh();
        if (repo.movedTo() != null && repo.moveSeq() != moveShown) {
            moveShown = repo.moveSeq();
            String was = repo.radioAddress(), now = repo.movedTo();
            new AlertDialog.Builder(this)
                    .setTitle("Radio at a new address")
                    .setMessage(repo.radioName() + " is showing up at a different Bluetooth address:\n\n"
                            + was + "  →  " + now + "\n\n"
                            + "That's normal if you just turned the radio off and on (the radio's firmware "
                            + "changes its address at every start). If you didn't, something nearby may be "
                            + "imitating your radio to collect its PIN and your messages: say Not now.")
                    .setPositiveButton("It's my radio", (dd, w) -> repo.acceptMove())
                    .setNegativeButton("Not now", (dd, w) -> repo.declineMove())
                    .show();
        }
        String check = connected ? repo.takeChannelCheck() : null;
        if (check != null) {
            boolean list = check.equals(ChannelCheck.CHECKLIST);
            new AlertDialog.Builder(this).setTitle("Check channel B")
                    .setMessage(check)
                    .setPositiveButton(list ? "It's set" : "OK", null)
                    .setNeutralButton(list ? "Stop reminding" : "Don't check", (dd, w) -> repo.setChannelReminder(false))
                    .show();
        }
        if (repo.noticeSeq() != noticeShown) {
            noticeShown = repo.noticeSeq();
            new AlertDialog.Builder(this).setTitle("Radio").setMessage(repo.notice())
                    .setPositiveButton("OK", null).show();
        }
    }

    /** Tap on the connection card: what the radio reports (P2 firmware). */
    private void showRadioInfo() {
        RadioInfo info = repo.radioInfo();
        if (info == null) {
            if (repo.state() == Repo.State.CONNECTED)
                toast("The radio doesn't report its settings: that needs firmware P2 or later");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Radio")
                .setMessage(info.details() + "\n\nRead from the radio over Bluetooth, every minute.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void refreshList() {
        stationAdapter.setItems(repo.store.stations());
        logAdapter.setItems(repo.store.log());
        List<String> peers = new ArrayList<>(repo.messenger.peers());
        peerAdapter.setItems(peers);
        boolean empty = tab == TAB_LOG ? logAdapter.getCount() == 0
                : tab == TAB_MESSAGES ? peerAdapter.getCount() == 0 : stationAdapter.getCount() == 0;
        emptyText.setText(tab == TAB_MESSAGES
                ? (repo.myCall() == null
                    ? "No messages yet.\n\nSet your callsign under Settings (top right) to send and receive messages."
                    : "No messages yet.\n\nMessages to " + repo.myCall() + " show up here. Tap New message to send one.")
                : "Nothing heard yet.\n\nChoose your radio, press Connect, and packets the radio "
                    + "decodes will show up here.");
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        newMsgBtn.setVisibility(tab == TAB_MESSAGES ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Ui.BG);

        // header: the app name, menu
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(16), dp(6));
        TextView app = new TextView(this);
        android.text.SpannableString mark = new android.text.SpannableString("H9 OPS");
        mark.setSpan(new android.text.style.ForegroundColorSpan(Ui.accent), 3, 6, 0);
        app.setText(mark);
        app.setTypeface(Ui.monoBold());
        app.setTextSize(30);
        app.setLetterSpacing(0.1f);
        app.setTextColor(Ui.text);
        app.setSingleLine(true);
        app.setContentDescription("H9 OPS");
        header.addView(app, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button menuBtn = button("⋮", null);
        menuBtn.setTextColor(Ui.accent);
        menuBtn.setTextSize(20);
        menuBtn.setContentDescription("Menu");
        menuBtn.setOnClickListener(this::showMenu);
        header.addView(menuBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        // radio card (tactical readout)
        radioCard = new RadioCard(this, v -> showRadioInfo(), v -> toggleConnection());
        View card = radioCard.root;
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(dp(16), dp(8), dp(16), 0);
        root.addView(card, cl);

        // segmented tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(Ui.box(this, Ui.SURFACE, Ui.BORDER, 14));
        stationsTab = button("Stations", v -> selectTab(TAB_STATIONS));
        messagesTab = button("Messages", v -> selectTab(TAB_MESSAGES));
        logTab = button("Log", v -> selectTab(TAB_LOG));
        for (Button b : new Button[]{stationsTab, messagesTab, logTab}) {
            b.setSingleLine(true);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(40), 1);
            tl.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(b, tl);
        }
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tbl.setMargins(dp(16), dp(14), dp(16), dp(8));
        root.addView(tabs, tbl);

        newMsgBtn = button("New message", v -> newMessage(null));
        Ui.outline(newMsgBtn);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        nl.setMargins(dp(16), dp(2), dp(16), dp(6));
        root.addView(newMsgBtn, nl);

        emptyText = new TextView(this);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextColor(Ui.muted);
        emptyText.setTextSize(14);
        emptyText.setPadding(dp(32), dp(48), dp(32), dp(16));
        root.addView(emptyText);

        list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.DIVIDER));
        list.setDividerHeight(Math.max(1, dp(1)));
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x22FFFFFF));
        list.setPadding(dp(16), 0, dp(16), 0);
        list.setClipToPadding(false);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (tab == TAB_LOG) showPacket(logAdapter.getItem(pos));
            else if (tab == TAB_MESSAGES) openConversation(peerAdapter.getItem(pos));
            else showStation(stationAdapter.getItem(pos));
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (tab != TAB_MESSAGES) return false;
            String peer = peerAdapter.getItem(pos);
            new AlertDialog.Builder(this)
                    .setMessage("Delete the conversation with " + peer + "?")
                    .setPositiveButton("Delete", (d, w) -> repo.deleteThread(peer))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        footer = new TextView(this);
        footer.setTypeface(Ui.mono());
        footer.setTextSize(11);
        footer.setTextColor(Ui.muted);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.addView(footer);
        return root;
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Choose radio");
        m.getMenu().add(0, 2, 1, "Settings");
        m.getMenu().add(0, 3, 2, Ui.night ? "Night red: on" : "Night red: off");
        m.getMenu().add(0, 4, 3, "Clear stations and log");
        m.getMenu().add(0, 5, 4, "Clear messages");
        m.getMenu().add(0, 6, 5, "Export packet log…");
        m.getMenu().add(0, 8, 7, repo.tracks.recording() ? "Stop recording track" : "Record track");
        m.getMenu().add(0, 9, 8, "Tracks…");
        if (repo.dev.on()) m.getMenu().add(0, 7, 6, "Developer log");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: withPermissions(this::chooseRadio); return true;
                case 2: showSettings(); return true;
                case 3: Ui.setNight(this, !Ui.night); recreate(); return true;
                case 4: confirmClear(); return true;
                case 6: exportPackets(); return true;
                case 7: showDevLog(); return true;
                case 8: toggleTrack(); return true;
                case 9: showTracks(); return true;
                case 5:
                    new AlertDialog.Builder(this)
                            .setMessage("Delete all messages?")
                            .setPositiveButton("Delete", (d, w) -> repo.clearMessages())
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                default: return false;
            }
        });
        m.show();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        Ui.plain(b);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void selectTab(int t) {
        tab = t;
        Button[] b = {stationsTab, messagesTab, logTab};
        for (int i = 0; i < b.length; i++) {
            if (i == t) {
                Ui.filled(b[i], 10);
            } else {
                Ui.plain(b[i]);
                b[i].setBackground(null);
                b[i].setTextColor(Ui.text);
                b[i].setTypeface(Typeface.DEFAULT);
            }
            // Keep each tab on one line whatever the phone's font: shrink the text to fit.
            b[i].setPadding(dp(4), 0, dp(4), 0);
            b[i].setMinWidth(0);
            b[i].setMinimumWidth(0);
            b[i].setSingleLine(true);
            b[i].setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        list.setAdapter(t == TAB_LOG ? logAdapter : t == TAB_MESSAGES ? peerAdapter : stationAdapter);
        refreshList();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView l1 = label("Your callsign and SSID (used when you send messages)");
        EditText call = edit(repo.myCall() == null ? "" : repo.myCall(), "N0CALL-7");
        call.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView l2 = label("Path");
        EditText path = edit(repo.pathText(), "WIDE1-1");
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        CheckBox rf = new CheckBox(this);
        rf.setText("Keep my messages off the internet (adds NOGATE so iGates do not forward them)");
        rf.setChecked(repo.rfOnly());
        CheckBox quiet = new CheckBox(this);
        quiet.setText("Quiet mode: only acknowledge stations I've messaged");
        quiet.setChecked(repo.quiet());
        TextView qnote = label("An acknowledgement is a transmission. Without quiet mode, anyone can send you a "
                + "message to check you're on the air, and direction-finding can locate the reply. Messages "
                + "from strangers still show; they just aren't acknowledged, so the sender keeps retrying.");
        TextView note = label("Messages go out over the air in plain text, like all APRS. Anyone listening "
                + "can read them, and anyone can send a message under someone else's callsign. "
                + "NOGATE is a request most iGates honour, not a guarantee.");
        box.addView(l1); box.addView(call); box.addView(l2); box.addView(path); box.addView(rf);
        box.addView(quiet); box.addView(qnote); box.addView(note);

        // Radio PIN (firmware P2): stops other phones from transmitting through the radio.
        TextView l3 = label("Radio PIN (firmware P2 or later). With a PIN set on the radio, only phones "
                + "that know it can transmit through it. Leave empty if the radio has none.");
        EditText pin = edit(repo.radioPin() == null ? "" : repo.radioPin(), "4 to 8 characters");
        pin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout pinRow = new LinearLayout(this);
        pinRow.addView(button("Generate", v -> pin.setText(randomPin())), weight());
        pinRow.addView(button("Set on radio…", v -> {
            String p = pin.getText().toString().trim();
            String err = Repo.checkPin(p);
            if (err != null) { toast(err); return; }
            new AlertDialog.Builder(this)
                    .setTitle("Set the radio's PIN")
                    .setMessage("1. On the radio, open the menu → APRS → APRS Mute and press confirm "
                            + "(keep the same setting). That tells the radio you have it in hand.\n\n"
                            + "2. Within a minute, tap Set (the radio closes the window after 60 s). The radio saves the PIN and from then on refuses "
                            + "transmissions from phones without it.\n\nPIN: " + p
                            + "\n\nWrite it down: other phones need it too.")
                    .setPositiveButton("Set", (dd, w) -> {
                        String e2 = repo.setRadioPin(p);
                        if (e2 != null) toast(e2);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }), weight());
        box.addView(l3); box.addView(pin); box.addView(pinRow);

        // TX delay (KISS command 0x01): how long the radio sends flags before the packet.
        TextView l4 = label("");
        SeekBar delay = new SeekBar(this);
        delay.setMax(100);   // 0 = radio's own, 1..100 = 10..1000 ms
        int savedDelay = repo.txDelayMs();
        delay.setProgress(savedDelay < 0 ? 0 : Math.max(1, Math.min(100, savedDelay / 10)));
        Runnable showDelay = () -> {
            int p = delay.getProgress();
            l4.setText("TX delay: " + (p == 0 ? "radio's own (nothing sent)" : (p * 10) + " ms"));
        };
        showDelay.run();
        delay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { showDelay.run(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        TextView delayNote = label("The radio's APRS chip always sends about 500 ms of preamble before each "
                + "packet, so 500 ms or less changes nothing. With firmware P2 or later, a longer setting keys "
                + "the transmitter earlier by the difference, which can help a distant digipeater that misses the "
                + "start of your packets; other firmware ignores it. Longer means more airtime. Sent on Save and "
                + "every time the radio connects. All the way left sends nothing, so the radio keeps the last "
                + "value until it restarts.");
        box.addView(l4); box.addView(delay); box.addView(delayNote);
        CheckBox devBox = new CheckBox(this);
        devBox.setText("Developer mode: keep a log of Bluetooth, messaging, position and radio PIN events");
        devBox.setChecked(repo.dev.on());
        TextView devNote = label("For testing firmware and the app. Kept on the phone (up to about 2 MB) until you "
                + "export or clear it: menu → Developer log. It holds callsigns, message numbers and timings, "
                + "never message text, the PIN or your coordinates.");
        box.addView(devBox); box.addView(devNote);
        CheckBox chb = new CheckBox(this);
        chb.setText("Check channel B (" + ChannelCheck.WANT + ") when the radio connects");
        chb.setChecked(repo.channelReminder());
        TextView chbNote = label("With firmware P2 or later the radio reports its settings, and the app warns "
                + "only when channel B is wrong (frequency, Narrow, APRS off). With other firmware it shows a "
                + "checklist when you press Connect (and at most every 8 hours on automatic reconnects). Either "
                + "way the status line turns to a hint after 15 minutes with nothing heard.");
        box.addView(chb); box.addView(chbNote);
        ScrollView settingsScroll = new ScrollView(this);   // taller than some screens
        settingsScroll.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(settingsScroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String err = repo.saveSettings(call.getText().toString(), path.getText().toString(), rf.isChecked());
            if (err == null) repo.setQuiet(quiet.isChecked());
            if (err == null) repo.setDevMode(devBox.isChecked());
            if (err == null) repo.setChannelReminder(chb.isChecked());
            if (err == null) err = repo.useRadioPin(pin.getText().toString().trim());
            if (err == null) repo.setTxDelay(delay.getProgress() == 0 ? -1 : delay.getProgress() * 10);
            if (err != null) toast(err);
            else d.dismiss();
        }));
        d.show();
    }

    // ---- exports and the developer log ----

    private static final int REQ_EXPORT_PACKETS = 41, REQ_EXPORT_DEV = 42, REQ_EXPORT_TRACK = 43;
    private String exportTrackId;

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
    }

    /** Asks where to save (Android's own file picker: no storage permission needed). */
    private void exportFile(int req, String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, req);
        } catch (android.content.ActivityNotFoundException e) {
            toast("This phone has no file picker to save with");
        }
    }

    /** Save to a file, or share it straight to another app (a map app, email…). */
    private void saveOrShare(String what, Runnable save, Runnable share) {
        new AlertDialog.Builder(this)
                .setTitle("Export " + what)
                .setItems(new String[]{"Save to a file…", "Share…"}, (d, w) -> { if (w == 0) save.run(); else share.run(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportPackets() {
        String name = "h9ops-packets-" + stamp() + ".txt";
        saveOrShare("packet log", () -> exportFile(REQ_EXPORT_PACKETS, name),
                () -> shareFile("packet log", name, "text/plain",
                        (out, done) -> repo.exportPackets(out, exportHeader("packet log"), done)));
    }

    /** Writes an export to the app's cache and opens Android's share sheet for it. */
    private void shareFile(String what, String name, String mime,
                           java.util.function.BiConsumer<java.io.OutputStream, java.util.function.Consumer<String>> write) {
        java.io.File f = new java.io.File(ShareProvider.freshDir(this), name);
        java.io.OutputStream out;
        try {
            out = new java.io.FileOutputStream(f);
        } catch (java.io.IOException e) {
            toast("Couldn't export: " + e.getMessage());
            return;
        }
        write.accept(out, err -> runOnUiThread(() -> {
            if (err != null) { toast("Couldn't export: " + err); return; }
            android.net.Uri u = ShareProvider.uriFor(f);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, u)
                    .putExtra(Intent.EXTRA_TITLE, name)
                    .putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(name, u));
            try {
                startActivity(Intent.createChooser(send, "Share " + what));
            } catch (android.content.ActivityNotFoundException e) {
                toast("No app to share with");
            }
        }));
    }

    private String exportHeader(String what) {
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        return "# H9 OPS " + ver + " " + what + ", exported " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())
                + "\n# Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
                + "\n# radio " + (repo.radioName() != null ? repo.radioName() : "?") + ", callsign "
                + (repo.myCall() != null ? repo.myCall() : "not set") + ", " + repo.stateText() + "\n";
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (req != REQ_EXPORT_PACKETS && req != REQ_EXPORT_DEV && req != REQ_EXPORT_TRACK) return;
        java.io.OutputStream out;
        try {
            out = getContentResolver().openOutputStream(data.getData(), "wt");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
            return;
        }
        if (out == null) { toast("Couldn't save the file"); return; }
        java.util.function.Consumer<String> done = err -> runOnUiThread(() ->
                toast(err == null ? "Saved" : "Couldn't save: " + err));
        if (req == REQ_EXPORT_TRACK) {
            if (exportTrackId != null) repo.tracks.exportGpx(exportTrackId, out, repo.myCall(), done);
            return;
        }
        if (req == REQ_EXPORT_PACKETS) repo.exportPackets(out, exportHeader("packet log"), done);
        else repo.dev.export(out, exportHeader("developer log"), done);
    }

    // ---- track recording ----

    private void toggleTrack() {
        if (repo.tracks.recording()) {
            TrackService.stop(this);
            toast("Track saved: ⋮ → Tracks…");
            return;
        }
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Name (optional), e.g. Lakeside loop");
        name.setSingleLine();
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("Record track")
                .setMessage("Records this phone's GPS track, and every station position the radio hears, until you stop it"
                        + " (here or from the notification). Works without signal. The GPS stays on, so it uses more battery.")
                .setView(box)
                .setPositiveButton("Start", (d, w) -> withLocation(() -> {
                    TrackService.start(this, name.getText().toString().trim());
                    toast("Recording");
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTracks() {
        List<Track> list = repo.tracks.list();
        Track cur = repo.tracks.current();
        if (list.isEmpty() && cur == null) {
            new AlertDialog.Builder(this).setTitle("Tracks")
                    .setMessage("No tracks yet. ⋮ → Record track starts one.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<Track> all = new ArrayList<>();
        if (cur != null) all.add(cur);
        all.addAll(list);
        String[] rows = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Track t = all.get(i);
            rows[i] = (t == cur ? "● " : "") + t.title() + "\n" + TrackService.summary(t);
        }
        new AlertDialog.Builder(this)
                .setTitle("Tracks")
                .setItems(rows, (d, which) -> trackActions(all.get(which), all.get(which) == cur))
                .setNegativeButton("Close", null)
                .show();
    }

    private void trackActions(Track t, boolean recording) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(t.title())
                .setMessage(TrackService.summary(t) + "\n" + t.me.size() + " GPS points, " + t.seen.size()
                        + " station positions.\n\nGPX opens in ATAK, onX, CalTopo, OsmAnd and most map apps.")
                .setPositiveButton("Export GPX…", (d, w) -> {
                    String file = "h9ops-track-" + t.id + ".gpx";
                    saveOrShare("GPX track", () -> {
                        exportTrackId = t.id;
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/gpx+xml")
                                .putExtra(Intent.EXTRA_TITLE, file);
                        try { startActivityForResult(i, REQ_EXPORT_TRACK); }
                        catch (android.content.ActivityNotFoundException e) { toast("This phone has no file picker to save with"); }
                    }, () -> shareFile("GPX track", file, "application/gpx+xml",
                            (out, done) -> repo.tracks.exportGpx(t.id, out, repo.myCall(), done)));
                })
                .setNegativeButton("Close", null);
        if (!recording) {
            b.setNeutralButton("Delete", (d, w) -> new AlertDialog.Builder(this)
                    .setMessage("Delete " + t.title() + "?")
                    .setPositiveButton("Delete", (d2, w2) -> { repo.tracks.delete(t.id); toast("Deleted"); })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        b.show();
    }

    private void showDevLog() {
        List<String> lines = repo.dev.recent();
        TextView t = new TextView(this);
        t.setText(lines.isEmpty() ? "Nothing logged yet." : TextUtils.join("\n", lines));
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextColor(Ui.text);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this)
                .setTitle("Developer log (last " + lines.size() + " lines)")
                .setView(sv)
                .setPositiveButton("Export…", (d, w) -> {
                    String name = "h9ops-devlog-" + stamp() + ".txt";
                    saveOrShare("developer log", () -> exportFile(REQ_EXPORT_DEV, name),
                            () -> shareFile("developer log", name, "text/plain",
                                    (out, done) -> repo.dev.export(out, exportHeader("developer log"), done)));
                })
                .setNeutralButton("Clear", (d, w) -> new AlertDialog.Builder(this)
                        .setMessage("Delete the developer log?")
                        .setPositiveButton("Delete", (d2, w2) -> { repo.dev.clear(); toast("Developer log cleared"); })
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Close", null)
                .show();
    }

    private static String randomPin() {
        final String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.muted);
        t.setTextSize(14);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private EditText edit(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Ui.text);
        e.setHintTextColor(0xFF7A7A7A);
        e.setTextSize(15);
        e.setMinHeight(dp(44));
        e.setPadding(dp(14), dp(8), dp(14), dp(8));
        e.setBackground(Ui.box(this, Ui.SURFACE, Ui.BORDER, 12));
        return e;
    }

    // ---------------------------------------------------------------- messages

    private boolean readyToSend() {
        if (repo.myCall() == null) {
            toast("Set your callsign in Settings first");
            showSettings();
            return false;
        }
        return true;
    }

    /**
     * Asks only who to write to, then opens the conversation, so 📍 Send my
     * position and the text box are both there before the first message.
     */
    private void newMessage(String to) {
        if (!readyToSend()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText who = edit(to == null ? "" : to, "Callsign, e.g. N0CALL-7");
        who.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(who);
        box.addView(label("Next you can send your position or type a message."));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("New message to…")
                .setView(box)
                .setPositiveButton("Open", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String peer = who.getText().toString().trim().toUpperCase(Locale.US);
            if (!Ax25.validCall(peer)) { toast("Callsign should look like N0CALL or N0CALL-7"); return; }
            if (peer.equals(repo.myCall())) { toast("That's your own callsign"); return; }
            d.dismiss();
            openConversation(peer);
        }));
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void openConversation(String peer) {
        if (conversation != null) conversation.dialog.dismiss();
        conversation = new Conversation(peer);
    }

    /** A conversation with one station, full screen: header, messages, then the reply bar. */
    private final class Conversation {
        final String peer;
        final android.app.Dialog dialog;
        final LinearLayout lines = new LinearLayout(MainActivity.this);
        final ScrollView scroll = new ScrollView(MainActivity.this);
        TextView heard, autoLine;
        EditText text;
        int shown = -1;

        Conversation(String peer) {
            this.peer = peer;
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Ui.BG);
            box.setFitsSystemWindows(true);

            // header: back, callsign
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(16), dp(14), dp(16), dp(8));
            Button back = button("‹", v -> close());
            back.setTextColor(Ui.accent);
            back.setTextSize(24);
            back.setPadding(0, 0, 0, dp(3));
            back.setContentDescription("Back");
            head.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout names = new LinearLayout(MainActivity.this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(12), 0, dp(8), 0);
            TextView call = new TextView(MainActivity.this);
            call.setText(peer);
            call.setTypeface(Ui.monoBold());
            call.setTextSize(20);
            call.setTextColor(Ui.accent);
            heard = new TextView(MainActivity.this);
            heard.setTextSize(12);
            heard.setTextColor(Ui.muted);
            autoLine = new TextView(MainActivity.this);
            autoLine.setTextSize(12);
            autoLine.setTypeface(Ui.mono());
            autoLine.setTextColor(Ui.accent);
            autoLine.setOnClickListener(v -> autoPositionDialog(peer));
            names.addView(call);
            names.addView(heard);
            names.addView(autoLine);
            head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(head);

            lines.setOrientation(LinearLayout.VERTICAL);
            lines.setPadding(dp(16), dp(12), dp(16), dp(12));
            scroll.addView(lines);
            box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            // reply bar: position, text, send
            LinearLayout bar = new LinearLayout(MainActivity.this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(16), dp(10), dp(16), dp(16));
            Button pin = button("📍", v -> sendPositionDialog(peer));
            pin.setContentDescription("Send my position");
            pin.setPadding(0, 0, 0, 0);
            pin.setBackground(Ui.box(MainActivity.this, Ui.SURFACE, Ui.BORDER, 22));
            bar.addView(pin, new LinearLayout.LayoutParams(dp(44), dp(44)));
            text = edit("", "Message");
            text.setBackground(Ui.box(MainActivity.this, Ui.SURFACE, Ui.BORDER, 22));
            text.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(44), 1);
            tp.setMargins(dp(8), 0, dp(8), 0);
            bar.addView(text, tp);
            Button send = button("→", v -> {
                if (!readyToSend()) return;
                String err = repo.sendMessage(peer, text.getText().toString());
                if (err != null) { toast(err); return; }
                if (!repo.canTransmit()) toast("Not connected: it will be sent when the radio connects");
                text.setText("");
            });
            Ui.filled(send, 22);
            send.setTextSize(20);
            send.setPadding(0, 0, 0, dp(2));
            send.setContentDescription("Send");
            bar.addView(send, new LinearLayout.LayoutParams(dp(44), dp(44)));
            box.addView(bar);

            dialog = new android.app.Dialog(MainActivity.this, android.R.style.Theme_Material_NoActionBar);
            dialog.setContentView(box);
            dialog.setOnDismissListener(d -> {
                repo.setOpenThread(null);
                if (conversation == this) conversation = null;
            });
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                dialog.getWindow().setStatusBarColor(Ui.BG);
                dialog.getWindow().setNavigationBarColor(Ui.BG);
            }
            repo.setOpenThread(peer);
            dialog.show();
            refresh();
        }

        void close() {
            dialog.dismiss();
        }

        private TextView small(String s, boolean right) {
            TextView t = new TextView(MainActivity.this);
            t.setText(s);
            t.setTypeface(Ui.mono());
            t.setTextSize(11);
            t.setTextColor(Ui.muted);
            t.setLetterSpacing(0.05f);
            t.setPadding(0, dp(2), 0, dp(6));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.gravity = right ? Gravity.END : Gravity.START;
            t.setLayoutParams(p);
            return t;
        }

        void refresh() {
            AutoPosition.Plan plan = repo.auto.get(peer);
            if (plan == null) autoLine.setVisibility(View.GONE);
            else {
                autoLine.setVisibility(View.VISIBLE);
                long next = plan.next();
                autoLine.setText("📍 every " + plan.minutes + " min"
                        + (next > System.currentTimeMillis() ? " · next " + hhmm(next) : "")
                        + (plan.until != 0 ? " · until " + hhmm(plan.until) : "")
                        + (repo.state() == Repo.State.STOPPED ? " · paused: radio connection is off"
                            : plan.problem != null ? " · " + plan.problem : ""));
            }
            Station st = repo.store.get(peer);
            heard.setText(st == null ? "not heard yet"
                    : Format.ago(System.currentTimeMillis() - st.lastHeard)
                    + (st.lastVia == null ? " · direct" : " · via " + st.lastVia));
            text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Messenger.MAX_TEXT)});
            List<Messenger.Msg> msgs = repo.messenger.messages(peer);
            lines.removeAllViews();
            if (msgs.isEmpty()) {
                TextView t = label("No messages with " + peer + " yet.\n\n"
                        + "Tap 📍 to send your position, or type a message below.");
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(16), dp(48), dp(16), 0);
                lines.addView(t);
            }
            for (Messenger.Msg m : msgs) {
                PositionShare.Pos pos = PositionShare.decode(m.text);
                TextView b = new TextView(MainActivity.this);
                b.setTextSize(15);
                b.setLineSpacing(0, 1.15f);
                b.setPadding(dp(14), dp(10), dp(14), dp(10));
                b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
                if (pos != null) {
                    String where = Format.dm(pos.lat, pos.lon);
                    String how = (pos.blur == PositionShare.Blur.EXACT ? "exact" : pos.blur.label.toLowerCase(Locale.US))
                            + " · tap to open map";
                    b.setText("📍 " + where + "\n" + how);
                    b.setTypeface(Ui.mono());
                    b.setTextSize(13);
                    b.setOnClickListener(v -> openMap(pos.lat, pos.lon, m.outgoing ? "Sent to " + peer : m.from()));
                    if (m.outgoing) b.setOnLongClickListener(v -> { messageOptions(m); return true; });
                } else {
                    b.setText(m.text);
                    if (m.outgoing) b.setOnClickListener(v -> messageOptions(m));
                }
                if (m.outgoing) {
                    b.setTextColor(Ui.ON_ACCENT);
                    b.setBackground(Ui.bubble(MainActivity.this, Ui.accent, 16, 16, 4, 16));
                } else {
                    b.setTextColor(Ui.text);
                    b.setBackground(Ui.bubble(MainActivity.this, Ui.BUBBLE, 16, 16, 16, 4));
                }
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.gravity = m.outgoing ? Gravity.END : Gravity.START;
                bp.setMargins(0, dp(6), 0, 0);
                lines.addView(b, bp);
                String hhmm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.time));
                String meta = m.outgoing ? stateText(m).toUpperCase(Locale.US) + " · " + hhmm
                        : hhmm + (m.viaInternet ? " · VIA INTERNET" : "")
                        + (m.notAcked ? " · NOT ACKED (QUIET MODE)" : "");
                lines.addView(small(meta, m.outgoing));
            }
            if (msgs.size() != shown) {
                shown = msgs.size();
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    // ---------------------------------------------------------------- send position

    private Runnable afterLocation;

    /** Sends this phone's position to one station, as a message (exact or blurred). */
    private void sendPositionDialog(String peer) {
        if (!readyToSend()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView how = label("Sent as a message to " + peer + " only. It is NOT a beacon, so maps don't plot it, "
                + "but it is plain text: anyone listening on the frequency can read it.");
        android.widget.RadioGroup g = new android.widget.RadioGroup(this);
        PositionShare.Blur[] blurs = PositionShare.Blur.values();
        for (int i = 0; i < blurs.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(blurs[i].label + (i == 0 ? " (exact GPS position)" : " (rounded)"));
            g.addView(rb);
        }
        g.check(1000 + prefsBlur());
        TextView tip = label("Tip: for privacy, turn off the radio's own automatic APRS beacon, or it keeps "
                + "broadcasting your exact position to everyone.");
        box.addView(how); box.addView(label("How precise:")); box.addView(g); box.addView(tip);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Send my position to " + peer)
                .setView(sv)
                .setPositiveButton("Send", (d, w) -> {
                    int idx = Math.max(0, g.getCheckedRadioButtonId() - 1000);
                    savePrefsBlur(idx);
                    withLocation(() -> getFixAndSend(peer, blurs[idx]));
                })
                .setNeutralButton("Every…", (d, w) -> autoPositionDialog(peer))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Timed position sharing for one conversation: interval, precision, and when to stop. */
    private void autoPositionDialog(String peer) {
        if (!readyToSend()) return;
        AutoPosition.Plan now = repo.auto.get(peer);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView how = label("Each position goes to " + peer + " as a plain message: anyone listening can read it."
                + "\n\nThe phone takes a GPS fix and transmits at each interval, also with the app closed, "
                + "while the radio connection is on. A newer position replaces one still waiting for an ack.");
        android.widget.RadioGroup every = new android.widget.RadioGroup(this);
        every.setOrientation(LinearLayout.HORIZONTAL);
        int pickMin = now != null ? now.minutes : 30;
        for (int m : AutoPosition.MINUTES) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(2000 + m);
            rb.setText(m < 60 ? m + "m" : (m / 60) + "h");
            every.addView(rb);
        }
        every.check(2000 + pickMin);
        android.widget.HorizontalScrollView everyScroll = new android.widget.HorizontalScrollView(this);
        everyScroll.addView(every);
        android.widget.RadioGroup prec = new android.widget.RadioGroup(this);
        PositionShare.Blur[] blurs = PositionShare.Blur.values();
        for (int i = 0; i < blurs.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(blurs[i].label);
            prec.addView(rb);
        }
        prec.check(1000 + (now != null ? now.blur.ordinal() : prefsBlur()));
        android.widget.RadioGroup stop = new android.widget.RadioGroup(this);
        for (int h : AutoPosition.STOP_HOURS) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(3000 + h);
            rb.setText(h == 0 ? "Until I turn it off" : h == 1 ? "After 1 hour" : "After " + h + " hours");
            stop.addView(rb);
        }
        stop.check(3000 + 12);
        box.addView(how);
        box.addView(label("Every:")); box.addView(everyScroll);
        box.addView(label("How precise:")); box.addView(prec);
        box.addView(label("Stop:")); box.addView(stop);
        if (now != null) {
            TextView st = label("Now: every " + now.minutes + " min, " + now.blur.label.toLowerCase(Locale.US)
                    + (now.until == 0 ? ", until turned off" : ", until " + hhmm(now.until))
                    + (now.lastSent > 0 ? ". Last sent " + hhmm(now.lastSent) : "")
                    + (now.problem != null ? ". " + now.problem : "") + ".");
            box.addView(st);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Send my position to " + peer + " regularly")
                .setView(sv)
                .setPositiveButton(now == null ? "Start" : "Update", (d, w) -> {
                    int minutes = every.getCheckedRadioButtonId() - 2000;
                    int idx = Math.max(0, prec.getCheckedRadioButtonId() - 1000);
                    int hours = stop.getCheckedRadioButtonId() - 3000;
                    withLocation(() -> {
                        String err = repo.startAuto(peer, minutes, blurs[idx], hours);
                        if (err != null) toast(err);
                        else toast("Sending your position to " + peer + " every " + minutes + " min");
                    });
                })
                .setNegativeButton("Cancel", null);
        if (now != null) b.setNeutralButton("Stop", (d, w) -> { repo.stopAuto(peer); toast("Stopped"); });
        b.show();
    }

    private static String hhmm(long t) {
        return new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(t));
    }

    private int prefsBlur() {
        return getSharedPreferences("settings", MODE_PRIVATE).getInt("blur", 2);
    }

    private void savePrefsBlur(int i) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("blur", i).apply();
    }

    private void withLocation(Runnable r) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            r.run();
            return;
        }
        afterLocation = r;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    /** Takes one GPS fix (up to 90 s), then sends it. Uses a recent fix (under 2 minutes) if there is one. */
    @SuppressWarnings("deprecation")
    private void getFixAndSend(String peer, PositionShare.Blur blur) {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            toast("Turn on location (GPS) in the phone's settings");
            return;
        }
        android.location.Location last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
        if (last != null && System.currentTimeMillis() - last.getTime() < 120_000) {
            sendFix(peer, blur, last);
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("Getting a GPS fix… (outdoors with a view of the sky works best)")
                .setNegativeButton("Cancel", null)
                .create();
        final boolean[] done = {false};
        final android.os.CancellationSignal cancel = new android.os.CancellationSignal();
        final android.location.LocationListener listener = new android.location.LocationListener() {
            @Override public void onLocationChanged(android.location.Location l) { got(l); }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
            void got(android.location.Location l) {
                if (done[0]) return;
                done[0] = true;
                lm.removeUpdates(this);
                wait.dismiss();
                if (l == null) { toast("No GPS fix"); repo.dev.log("POS", "position to " + peer + ": no GPS fix"); }
                else sendFix(peer, blur, l);
            }
        };
        wait.setOnDismissListener(d -> {
            if (!done[0]) {
                done[0] = true;
                cancel.cancel();
                lm.removeUpdates(listener);
            }
        });
        wait.show();
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(android.location.LocationManager.GPS_PROVIDER, cancel, getMainExecutor(), l -> {
                if (done[0]) return;
                done[0] = true;
                wait.dismiss();
                if (l == null) { toast("No GPS fix"); repo.dev.log("POS", "position to " + peer + ": no GPS fix"); }
                else sendFix(peer, blur, l);
            });
        } else {
            lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
        }
        ui.postDelayed(() -> {
            if (!done[0]) {
                wait.dismiss();
                toast("No GPS fix after 90 seconds");
            }
        }, 90_000);
    }

    private void sendFix(String peer, PositionShare.Blur blur, android.location.Location l) {
        String text = PositionShare.encode(l.getLatitude(), l.getLongitude(), blur, l.getTime());
        repo.dev.log("POS", "position to " + peer + " (" + blur.label + "): fix from " + l.getProvider()
                + (l.hasAccuracy() ? ", accuracy ±" + Math.round(l.getAccuracy()) + " m" : "")
                + ", age " + Math.max(0, (System.currentTimeMillis() - l.getTime()) / 1000) + " s");
        String err = repo.sendMessage(peer, text);
        if (err != null) toast(err);
        else if (!repo.canTransmit()) toast("Not connected: it will be sent when the radio connects");
        else toast("Position sent to " + peer);
    }

    private static String stateText(Messenger.Msg m) {
        switch (m.state) {
            case DELIVERED: return "✓ Delivered";
            case REJECTED: return "✗ Refused by the other station";
            case FAILED: return "✗ No reply, tap to send again";
            default: return m.sends == 0 ? "Waiting for the radio…" : "Sent " + m.sends + "×, waiting for a reply";
        }
    }

    private void messageOptions(Messenger.Msg m) {
        if (m.state == Messenger.State.DELIVERED) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setMessage(m.text);
        if (m.state == Messenger.State.SENDING) {
            b.setPositiveButton("Stop trying", (d, w) -> {
                repo.messenger.cancel(m, System.currentTimeMillis());
                repo.messagesChanged();
            });
        } else {
            b.setPositiveButton("Send again", (d, w) -> {
                repo.messenger.retry(m, System.currentTimeMillis());
                repo.messagesChanged();
            });
        }
        b.setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- actions

    private void toggleConnection() {
        if (repo.state() != Repo.State.STOPPED) {
            RadioService.stop(this);
            return;
        }
        if (repo.radioAddress() == null) {
            withPermissions(this::chooseRadio);
            return;
        }
        withPermissions(() -> {
            if (!bluetoothOn()) return;
            repo.markManualConnect();
            RadioService.start(this);
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Clear all heard stations and the packet log?")
                .setPositiveButton("Clear", (d, w) -> repo.clear())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean bluetoothOn() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) {
            toast("This phone has no Bluetooth");
            return false;
        }
        if (!a.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (RuntimeException e) {
                toast("Turn Bluetooth on first");
            }
            return false;
        }
        return true;
    }

    /** Scans for nearby Bluetooth LE devices and lets the user pick the radio. */
    private void chooseRadio() {
        if (!bluetoothOn()) return;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothLeScanner sc = bm.getAdapter().getBluetoothLeScanner();
        if (sc == null) { toast("Bluetooth scanning is not available"); return; }

        final Map<String, String> found = new LinkedHashMap<>();   // address -> name
        final List<String> rows = new ArrayList<>();
        final List<String> addrs = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (name == null) name = d.getName();
                if (name == null) return;             // the radio always advertises its name
                final String n = name;
                ui.post(() -> {
                    if (found.containsKey(d.getAddress())) return;
                    found.put(d.getAddress(), n);
                    // radios first
                    int at = n.toUpperCase(Locale.US).contains("TD-H9") ? 0 : rows.size();
                    rows.add(at, n + "\n" + d.getAddress());
                    addrs.add(at, d.getAddress());
                    ad.notifyDataSetChanged();
                });
            }
        };
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Choose your radio (the one ending in \"(BLE)\")")
                .setAdapter(ad, (d, which) -> {
                    String a = addrs.get(which);
                    repo.setRadio(a, found.get(a));
                    toast("Saved. Press Connect.");
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnDismissListener(d -> {
            try { sc.stopScan(cb); } catch (RuntimeException ignored) { }
        });
        if (repo.state() != Repo.State.STOPPED) RadioService.stop(this);
        sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        dlg.show();
        ui.postDelayed(() -> {
            try { sc.stopScan(cb); } catch (RuntimeException ignored) { }
            if (dlg.isShowing() && rows.isEmpty()) dlg.setTitle("Nothing found. Is the radio on?");
        }, 15000);
    }

    // ---------------------------------------------------------------- permissions

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);   // Android 11 and older need it to scan
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        return missing.toArray(new String[0]);
    }

    private void withPermissions(Runnable r) {
        String[] missing = neededPermissions();
        if (missing.length == 0) { r.run(); return; }
        afterPermissions = r;
        requestPermissions(missing, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_LOCATION) {
            Runnable r = afterLocation;
            afterLocation = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && r != null) r.run();
            else toast("Location permission is needed to send your position");
            return;
        }
        if (code != REQ_PERMS) return;
        boolean bt = true;
        for (int i = 0; i < perms.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED && !Manifest.permission.POST_NOTIFICATIONS.equals(perms[i])) {
                bt = false;
            }
        }
        Runnable r = afterPermissions;
        afterPermissions = null;
        if (bt && r != null) r.run();
        else if (!bt) toast("Bluetooth permission is needed to talk to the radio");
    }

    // ---------------------------------------------------------------- details

    private void showStation(Station s) {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        AprsPacket pos = s.lastPosition;
        if (pos != null) sb.append(Symbols.name(pos.symTable, pos.symCode)).append("\n");
        if (s.object && s.last != null) sb.append("Object sent by ").append(s.last.sender).append("\n");
        sb.append("Last heard ").append(Format.ago(now - s.lastHeard))
                .append(" (").append(time(s.lastHeard)).append(")\n");
        sb.append(s.lastVia == null ? "Heard direct" : "Heard via " + s.lastVia).append("\n");
        sb.append(s.count).append(s.count == 1 ? " packet" : " packets").append(" since ")
                .append(time(s.firstHeard)).append("\n\n");
        if (pos != null) {
            sb.append(Format.dm(pos.lat, pos.lon)).append("\n").append(Format.latLon(pos.lat, pos.lon));
            if (pos.ambiguity > 0) sb.append("  (approximate)");
            sb.append("\nPosition from ").append(Format.ago(now - s.lastPositionTime)).append("\n");
            String sc = Format.speedCourse(pos.speedKnots, pos.courseDeg);
            if (!sc.isEmpty()) sb.append(sc).append("\n");
            if (!Double.isNaN(pos.altitudeM)) sb.append("Altitude ").append(Format.altitude(pos.altitudeM)).append("\n");
            if (pos.miceStatus != null) sb.append("Status: ").append(pos.miceStatus).append("\n");
            if (pos.comment != null) sb.append(pos.comment).append("\n");
            sb.append("\n");
        }
        if (s.wx != null && !s.wx.summary().isEmpty()) sb.append("Weather: ").append(s.wx.summary()).append("\n\n");
        if (s.status != null) sb.append("Status text: ").append(s.status).append("\n\n");
        if (s.telemetry != null) {
            Telemetry.Defs td = s.telemetryDefs;
            boolean named = td != null && td.any();
            sb.append("Telemetry").append(named && td.title != null ? " (" + td.title + ")" : "")
                    .append(s.telemetry.seq.isEmpty() ? "" : ", report " + s.telemetry.seq)
                    .append(", ").append(Format.ago(now - s.telemetryTime)).append(":\n");
            for (String line : Telemetry.describe(s.telemetry, td)) sb.append("  ").append(line).append("\n");
            if (s.telemetry.comment != null) sb.append("  ").append(s.telemetry.comment).append("\n");
            if (!named) sb.append("  (raw numbers: this station hasn't sent its channel names yet)\n");
            sb.append("\n");
        }
        sb.append("Recent packets:\n");
        for (Station.Heard h : s.history) sb.append(time(h.time)).append("  ").append(h.packet.raw).append("\n");

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(Symbols.icon(s.symTable(), s.symCode()) + "  " + s.name)
                .setView(textBlock(sb.toString()))
                .setNegativeButton("Close", null);
        if (pos != null) {
            final double la = pos.lat, lo = pos.lon;
            final String label = s.name;
            b.setPositiveButton("Map", (d, w) -> openMap(la, lo, label));   // short: three buttons fit in one row
        }
        if (!s.object) b.setNeutralButton("Message", (d, w) -> openConversation(s.name));
        b.show();
    }

    private void showPacket(Station.Heard h) {
        AprsPacket p = h.packet;
        String body = time(h.time) + "\n\n" + p.raw + "\n\n" + p.type.name().replace('_', '-')
                + ", " + h.describe();
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setView(textBlock(body))
                .setPositiveButton("Station", (d, w) -> {
                    Station s = repo.store.get(p.name);
                    if (s != null) showStation(s);
                })
                .setNeutralButton("Copy", (d, w) -> copy(p.raw))
                .setNegativeButton("Close", null)
                .show();
    }

    private View textBlock(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextIsSelectable(true);
        t.setTextSize(14);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        // At most 60% of the screen, so the dialog's buttons always fit below it in one row
        // instead of being pushed into a second scrolling area.
        final int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        ScrollView sv = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                int limit = View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED ? max
                        : Math.min(max, View.MeasureSpec.getSize(h));
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        sv.addView(t);
        return sv;
    }

    /** Hands the position to whatever map app is installed (for example OsmAnd with offline maps). */
    private void openMap(double lat, double lon, String label) {
        String q = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        Uri u = Uri.parse("geo:" + q + "?q=" + q + "(" + Uri.encode(label) + ")");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            toast("No map app installed");
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("APRS", s));
        if (Build.VERSION.SDK_INT < 33) toast("Copied");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String time(long t) {
        return new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date(t));
    }

    // ---------------------------------------------------------------- list adapters

    /** One list row: a short time on the left, then callsign + tag, a line of text, and a mono detail line. */
    private Row row(View convert) {
        if (convert != null) return (Row) convert.getTag();
        return new Row();
    }

    private final class Row {
        final LinearLayout root = new LinearLayout(MainActivity.this);
        final TextView age = new TextView(MainActivity.this);
        final TextView call = new TextView(MainActivity.this);
        final TextView chip = new TextView(MainActivity.this);
        final TextView line = new TextView(MainActivity.this);
        final TextView detail = new TextView(MainActivity.this);

        Row() {
            root.setPadding(dp(4), dp(14), dp(4), dp(14));
            age.setTypeface(Ui.mono());
            age.setTextSize(12);
            age.setTextColor(Ui.muted);
            age.setPadding(0, dp(3), 0, 0);
            root.addView(age, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            call.setTypeface(Ui.monoBold());
            call.setTextSize(16);
            call.setTextColor(Ui.accent);
            call.setSingleLine(true);
            top.addView(call);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(dp(8), 0, 0, 0);
            top.addView(chip, cp);
            col.addView(top);
            line.setTextSize(14);
            line.setTextColor(Ui.text);
            line.setMaxLines(2);
            line.setEllipsize(TextUtils.TruncateAt.END);
            line.setPadding(0, dp(4), 0, 0);
            col.addView(line);
            detail.setTypeface(Ui.mono());
            detail.setTextSize(12);
            detail.setTextColor(Ui.muted);
            detail.setMaxLines(3);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setPadding(0, dp(3), 0, 0);
            col.addView(detail);
            root.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            root.setTag(this);
        }

        void set(String a, String c, String tag, boolean hot, String l, String d) {
            age.setText(a);
            call.setText(c);
            if (tag == null) chip.setVisibility(View.GONE);
            else { chip.setVisibility(View.VISIBLE); Ui.chip(chip, tag, hot); }
            line.setText(l);
            line.setVisibility(l == null || l.isEmpty() ? View.GONE : View.VISIBLE);
            detail.setText(d);
            detail.setVisibility(d == null || d.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private final class StationAdapter extends BaseAdapter {
        private List<Station> items = new ArrayList<>();

        void setItems(List<Station> l) { items = l; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public Station getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row v = row(convert);
            Station s = items.get(i);
            long now = System.currentTimeMillis();
            AprsPacket pos = s.lastPosition;
            boolean shared = pos != null && pos.raw != null && pos.raw.endsWith("(position by message)") && pos == s.last;
            String tag = shared ? "SHARED" : s.lastVia == null ? "DIRECT"
                    : "VIA " + s.lastVia.toUpperCase(Locale.US);
            String what = pos != null ? Symbols.name(pos.symTable, pos.symCode) : null;
            String sum = s.summary();
            String l = what == null ? (sum.isEmpty() ? s.count + " packets" : sum)
                    : sum.isEmpty() ? what : what + " · " + sum;
            String d = pos != null ? Format.dm(pos.lat, pos.lon) : null;
            v.set(Ui.age(now - s.lastHeard), s.name, tag, false, l, d);
            return v.root;
        }
    }

    private final class PeerAdapter extends BaseAdapter {
        private List<String> items = new ArrayList<>();

        void setItems(List<String> l) { items = l; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public String getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row v = row(convert);
            String peer = items.get(i);
            List<Messenger.Msg> msgs = repo.messenger.messages(peer);
            if (msgs.isEmpty()) {
                v.set("", peer, null, false, "No messages yet", null);
                return v.root;
            }
            Messenger.Msg last = msgs.get(msgs.size() - 1);
            boolean unread = repo.isUnread(peer);
            boolean isPos = PositionShare.decode(last.text) != null;
            String tag = unread ? "NEW" : null;
            String who = last.outgoing ? "You: " : "";
            v.set(Ui.age(System.currentTimeMillis() - last.time), peer, tag, unread,
                    who + (isPos ? "Position" : last.text), null);
            return v.root;
        }
    }

    private final class LogAdapter extends BaseAdapter {
        private List<Station.Heard> items = new ArrayList<>();

        void setItems(List<Station.Heard> l) { items = l; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public Station.Heard getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row v = row(convert);
            Station.Heard h = items.get(i);
            String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(h.time));
            String tag = h.tag();
            v.set(t, h.packet.name, tag, false, null, h.packet.raw);
            return v.root;
        }
    }
}
