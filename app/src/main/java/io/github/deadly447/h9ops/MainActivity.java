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
import io.github.deadly447.h9ops.map.MapFile;
import io.github.deadly447.h9ops.map.MapJson;
import io.github.deadly447.h9ops.aprs.AutoPosition;
import io.github.deadly447.h9ops.aprs.SmartBeacon;
import io.github.deadly447.h9ops.aprs.WxHistory;
import io.github.deadly447.h9ops.ui.Shortcuts;
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

/** One screen: radio card, then the stations list, messages, the raw packet log or the map. */
@SuppressLint("MissingPermission")   // checked in hasPermissions() before any Bluetooth call
public class MainActivity extends Activity implements Repo.Listener, MapPane.Host {
    public static final String EXTRA_THREAD = "thread";
    public static final String EXTRA_STATION = "station";
    private static final int REQ_PERMS = 1, REQ_LOCATION = 2;
    private static final int TAB_STATIONS = 0, TAB_MESSAGES = 1, TAB_LOG = 2, TAB_MAP = 3;

    private Repo repo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView emptyText, footer;
    private RadioCard radioCard;
    private Button newMsgBtn, bulletinsBtn;
    /** The two rows under the radio card (Settings → Tabs and shortcuts). */
    private Shortcuts shortcuts;
    private LinearLayout row1View, row2View;
    private final List<Button> slotButtons = new ArrayList<>();
    private final List<Shortcuts.Item> slotItems = new ArrayList<>();
    private boolean slotsRecording;
    private LinearLayout msgRow;
    private View headerView, tabsView;
    private android.widget.FrameLayout mapHolder;
    private MapPane mapPane;        // created the first time the Map tab opens
    private boolean mapFull;
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
        String st = i == null ? null : i.getStringExtra(EXTRA_STATION);
        if (st != null) {
            i.removeExtra(EXTRA_STATION);
            Station s = st.length() <= 9 ? repo.store.get(st) : null;   // only a station we have
            if (s != null) showStation(s);
            return;
        }
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
        if (mapPane != null) mapPane.show(tab == TAB_MAP);
        // A recording that Android stopped (low memory, update) carries on once the app is open
        if (repo.tracks.recording() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) TrackService.start(this, null);
    }

    @Override
    protected void onPause() {
        repo.removeListener(this);
        ui.removeCallbacks(tick);
        if (mapPane != null) mapPane.show(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mapPane != null) mapPane.destroy();
        mapPane = null;
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mapFull) { setMapFull(false); return; }
        super.onBackPressed();
    }

    @Override
    public void onRepoChanged() {
        Repo.State s = repo.state();
        boolean connected = s == Repo.State.CONNECTED;
        String name = repo.radioName(), mac = repo.radioAddress();
        String radio = mac == null ? "No radio chosen" : (name != null ? name.replace("(BLE)", "").trim() : mac)
                + " · BLE" + (repo.radioPin() != null ? " · PIN LOCKED" : "");
        radioCard.update(s, repo.radioInfo(), radio, repo.stateText(), repo.quietHint());
        labelSlots();
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
        emptyText.setVisibility(empty && tab != TAB_MAP ? View.VISIBLE : View.GONE);
        msgRow.setVisibility(tab == TAB_MESSAGES ? View.VISIBLE : View.GONE);
        if (tab == TAB_MESSAGES) {
            int n = repo.board.entries(System.currentTimeMillis()).size(), unseen = repo.board.unseen();
            bulletinsBtn.setText("📢 Bulletins" + (n > 0 ? " · " + n : "") + (unseen > 0 ? " NEW" : ""));
        }
        if (mapPane != null) mapPane.stationsChanged();
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
        headerView = header;
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
        // On the Map tab the card is one line; a tap opens it fully (another tap: radio details).
        radioCard = new RadioCard(this, v -> {
            if (radioCard.compact()) radioCard.setCompact(false);
            else showRadioInfo();
        }, v -> toggleConnection());
        View card = radioCard.root;
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(dp(16), dp(8), dp(16), 0);
        root.addView(card, cl);

        // two rows: four tabs in a segmented bar, then four tools (Settings → Tabs and shortcuts)
        LinearLayout slots = new LinearLayout(this);
        slots.setOrientation(LinearLayout.VERTICAL);
        tabsView = slots;
        row1View = new LinearLayout(this);
        row1View.setPadding(dp(4), dp(4), dp(4), dp(4));
        row1View.setBackground(Ui.box(this, Ui.SURFACE, Ui.BORDER, 14));
        slots.addView(row1View, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row2View = new LinearLayout(this);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        r2.topMargin = dp(8);
        slots.addView(row2View, r2);
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tbl.setMargins(dp(16), dp(14), dp(16), dp(8));
        root.addView(slots, tbl);
        buildSlots();

        newMsgBtn = button("New message", v -> newMessage(null));
        Ui.outline(newMsgBtn);
        bulletinsBtn = button("📢 Bulletins", v -> bulletinsDialog());
        Ui.outline(bulletinsBtn);
        msgRow = new LinearLayout(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(44), 1);
        msgRow.addView(newMsgBtn, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(44), 1);
        half2.setMargins(dp(8), 0, 0, 0);
        msgRow.addView(bulletinsBtn, half2);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        nl.setMargins(dp(16), dp(2), dp(16), dp(6));
        root.addView(msgRow, nl);

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

        mapHolder = new android.widget.FrameLayout(this);
        mapHolder.setVisibility(View.GONE);
        root.addView(mapHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        footer = new TextView(this);
        footer.setTypeface(Ui.mono());
        footer.setTextSize(11);
        footer.setTextColor(Ui.muted);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.addView(footer);
        return root;
    }

    /**
     * ⋮: the radio, data, whatever isn't in the two rows (Settings → Tabs and shortcuts), and
     * Settings. Clearing stations and messages lives in Settings.
     */
    private void showMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        android.view.Menu mm = m.getMenu();
        if (Build.VERSION.SDK_INT >= 28) mm.setGroupDividerEnabled(true);
        mm.add(1, 1, 0, "Choose radio");
        mm.add(1, 3, 1, Ui.night ? "Night red: on" : "Night red: off");
        mm.add(2, 6, 2, "Export packet log…");
        if (repo.dev.on()) mm.add(2, 7, 3, "Developer log");
        List<Shortcuts.Item> more = shortcuts.menu();
        for (int i = 0; i < more.size(); i++) mm.add(3, 100 + more.get(i).ordinal(), 10 + i, menuLabel(more.get(i)));
        mm.add(4, 2, 50, "Settings");
        m.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100 && id - 100 < Shortcuts.Item.values().length) {
                runItem(Shortcuts.Item.values()[id - 100]);
                return true;
            }
            switch (id) {
                case 1: withPermissions(this::chooseRadio); return true;
                case 2: showSettings(); return true;
                case 3: Ui.setNight(this, !Ui.night); recreate(); return true;
                case 6: exportPackets(); return true;
                case 7: showDevLog(); return true;
                default: return false;
            }
        });
        m.show();
    }

    // ---- the two rows of tabs and tools ----

    private void buildSlots() {
        shortcuts = Shortcuts.parse(getSharedPreferences("settings", MODE_PRIVATE).getString("shortcuts", ""));
        slotButtons.clear();
        slotItems.clear();
        row1View.removeAllViews();
        row2View.removeAllViews();
        for (int r = 0; r < 2; r++) {
            List<Shortcuts.Item> items = shortcuts.row(r);
            for (int i = 0; i < items.size(); i++) {
                Shortcuts.Item it = items.get(i);
                Button b = button(it.label, v -> runItem(it));
                LinearLayout.LayoutParams lp;
                if (r == 0) {
                    lp = new LinearLayout.LayoutParams(0, dp(40), 1);
                    lp.setMargins(dp(2), 0, dp(2), 0);
                    row1View.addView(b, lp);
                } else {
                    lp = new LinearLayout.LayoutParams(0, dp(56), 1);
                    if (i > 0) lp.leftMargin = dp(6);
                    row2View.addView(b, lp);
                }
                slotButtons.add(b);
                slotItems.add(it);
            }
        }
        styleSlots();
    }

    private static int tabOf(Shortcuts.Item it) {
        switch (it) {
            case MESSAGES: return TAB_MESSAGES;
            case LOG: return TAB_LOG;
            case MAP: return TAB_MAP;
            default: return TAB_STATIONS;
        }
    }

    /** Colours for the current tab and a running recording. */
    private void styleSlots() {
        slotsRecording = repo.tracks.recording();
        for (int k = 0; k < slotButtons.size(); k++) {
            Button b = slotButtons.get(k);
            Shortcuts.Item it = slotItems.get(k);
            boolean on = (it.tab && tabOf(it) == tab) || (it == Shortcuts.Item.RECORD && slotsRecording);
            if (k < Shortcuts.PER_ROW) {
                if (on) Ui.filled(b, 10);
                else {
                    Ui.plain(b);
                    b.setBackground(null);
                    b.setTextColor(Ui.text);
                    b.setTypeface(Typeface.DEFAULT);
                }
                b.setCompoundDrawables(null, null, null, null);
                b.setPadding(dp(4), 0, dp(4), 0);
                // Keep each tab on one line whatever the phone's font: shrink the text to fit.
                b.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP);
            } else {
                if (on) Ui.outline(b); else Ui.plain(b);
                b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                android.graphics.drawable.Drawable ic = slotIcon(it);
                b.setCompoundDrawablesRelativeWithIntrinsicBounds(null, ic, null, null);
                b.setCompoundDrawablePadding(dp(2));
                b.setPadding(dp(2), dp(6), dp(2), dp(4));
                b.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            }
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setSingleLine(true);
        }
        labelSlots();
    }

    /** Counts on the buttons (stations heard, unread conversations, new bulletins). */
    private void labelSlots() {
        if (repo.tracks.recording() != slotsRecording) { styleSlots(); return; }
        for (int k = 0; k < slotButtons.size(); k++) {
            String t = slotLabel(slotItems.get(k));
            Button b = slotButtons.get(k);
            if (!t.contentEquals(b.getText())) b.setText(t);
            b.setContentDescription(t);
        }
    }

    private String slotLabel(Shortcuts.Item it) {
        switch (it) {
            case STATIONS: return "Stations " + repo.store.size();
            case MESSAGES: {
                int unread = 0;
                for (String p : repo.messenger.peers()) if (repo.isUnread(p)) unread++;
                return unread > 0 ? "Messages " + unread : "Messages";
            }
            case BULLETINS: {
                int unseen = repo.board.unseen();
                return unseen > 0 ? "Bulletins " + unseen : "Bulletins";
            }
            case RECORD: return repo.tracks.recording() ? "Stop rec" : "Record";
            default: return it.label;
        }
    }

    private String menuLabel(Shortcuts.Item it) {
        if (it == Shortcuts.Item.RECORD) return repo.tracks.recording() ? "Stop recording track" : "Record track";
        if (it.tab) return slotLabel(it);
        return it.menuLabel;
    }

    private android.graphics.drawable.Drawable slotIcon(Shortcuts.Item it) {
        int res;
        switch (it) {
            case STATIONS: res = R.drawable.ic_sc_stations; break;
            case MESSAGES: res = R.drawable.ic_sc_messages; break;
            case LOG: res = R.drawable.ic_sc_log; break;
            case MAP: res = R.drawable.ic_sc_map; break;
            case WEATHER: res = R.drawable.ic_sc_weather; break;
            case TRACKS: res = R.drawable.ic_sc_tracks; break;
            case OBJECTS: res = R.drawable.ic_sc_objects; break;
            case ALERTS: res = R.drawable.ic_sc_alerts; break;
            case BULLETINS: res = R.drawable.ic_sc_bulletins; break;
            case RECORD: res = R.drawable.ic_sc_record; break;
            case OVERLAYS: res = R.drawable.ic_sc_overlays; break;
            default: res = R.drawable.ic_sc_mapfile; break;
        }
        android.graphics.drawable.Drawable d = getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(Ui.accent);
        return d;
    }

    /** A tab switches the screen; a tool opens its dialog. */
    private void runItem(Shortcuts.Item it) {
        switch (it) {
            case STATIONS: case MESSAGES: case LOG: case MAP: selectTab(tabOf(it)); break;
            case WEATHER: weatherListDialog(); break;
            case TRACKS: showTracks(); break;
            case OBJECTS: objectsDialog(); break;
            case ALERTS: alertsDialog(); break;
            case BULLETINS: bulletinsDialog(); break;
            case RECORD: toggleTrack(); break;
            case MAP_FILE: mapFileDialog(); break;
            case OVERLAYS: overlaysDialog(); break;
        }
    }

    /** Settings → Tabs and shortcuts: move items between row 1, row 2 and the ⋮ menu. */
    private void shortcutsDialog() {
        Shortcuts edit = Shortcuts.parse(shortcuts.save());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(12), dp(8));
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            List<Shortcuts.Item> o = edit.order();
            for (int i = 0; i < o.size(); i++) {
                if (i == 0 || i == Shortcuts.PER_ROW || i == Shortcuts.IN_ROWS) {
                    TextView h = label(i == 0 ? "ROW 1 · TABS" : i == Shortcuts.PER_ROW ? "ROW 2" : "IN THE ⋮ MENU");
                    h.setTextSize(11);
                    h.setLetterSpacing(0.15f);
                    h.setPadding(0, dp(14), 0, dp(4));
                    box.addView(h);
                }
                Shortcuts.Item it = o.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = new TextView(this);
                name.setText(it.tab ? it.label : it.menuLabel.replace("…", ""));
                name.setTextSize(15);
                name.setTextColor(i < Shortcuts.IN_ROWS ? Ui.text : Ui.muted);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                final int k = i;
                Button up = button("↑", v -> { edit.move(k, -1); fill[0].run(); });
                Button down = button("↓", v -> { edit.move(k, 1); fill[0].run(); });
                for (Button b : new Button[]{up, down}) {
                    b.setTextColor(Ui.accent);
                    b.setTextSize(18);
                    b.setBackground(null);
                    b.setPadding(0, 0, 0, 0);
                }
                up.setEnabled(i > 0);
                down.setEnabled(i < o.size() - 1);
                up.setAlpha(i > 0 ? 1f : 0.25f);
                down.setAlpha(i < o.size() - 1 ? 1f : 0.25f);
                up.setContentDescription("Move " + it.label + " up");
                down.setContentDescription("Move " + it.label + " down");
                row.addView(up, new LinearLayout.LayoutParams(dp(48), dp(44)));
                row.addView(down, new LinearLayout.LayoutParams(dp(48), dp(44)));
                box.addView(row);
            }
        };
        fill[0].run();
        TextView how = label("Row 1 is the tab bar, row 2 the buttons under it. Everything else is in ⋮. "
                + "Move an item past the end of a row to swap it into the next one.");
        how.setTextSize(13);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        how.setPadding(dp(20), dp(4), dp(20), 0);
        outer.addView(how);
        outer.addView(box);
        ScrollView sv = new ScrollView(this);
        sv.addView(outer);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Tabs and shortcuts")
                .setView(sv)
                .setPositiveButton("Done", (dd, w) -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("shortcuts", edit.save()).apply();
                    buildSlots();
                    selectTab(tab);
                })
                .setNeutralButton("Reset", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            edit.reset();
            fill[0].run();
        }));
        d.show();
    }

    private void confirmClearMessages() {
        new AlertDialog.Builder(this)
                .setMessage("Delete all messages?")
                .setPositiveButton("Delete", (d, w) -> repo.clearMessages())
                .setNegativeButton("Cancel", null)
                .show();
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
        styleSlots();
        list.setAdapter(t == TAB_LOG ? logAdapter : t == TAB_MESSAGES ? peerAdapter : stationAdapter);
        boolean map = t == TAB_MAP;
        if (map && mapPane == null) {
            mapPane = new MapPane(this, this, () -> MapJson.stations(repo.store.stations()),
                    () -> MapJson.areas(repo.alerts.areas()), () -> overlays().json());
            mapHolder.addView(mapPane.web, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        list.setVisibility(map ? View.GONE : View.VISIBLE);
        mapHolder.setVisibility(map ? View.VISIBLE : View.GONE);
        if (mapPane != null) mapPane.show(map);
        if (!map && mapFull) setMapFull(false);
        radioCard.setCompact(map);
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
        Button layoutBtn = button("Tabs and shortcuts…", v -> shortcutsDialog());
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lb.topMargin = dp(8);
        box.addView(layoutBtn, lb);
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
        box.addView(label("Data on this phone"));
        LinearLayout clearRow = new LinearLayout(this);
        clearRow.addView(button("Clear stations…", v -> confirmClear()), weight());
        Button cm = button("Clear messages…", v -> confirmClearMessages());
        LinearLayout.LayoutParams cml = weight();
        cml.leftMargin = dp(8);
        clearRow.addView(cm, cml);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        crl.bottomMargin = dp(12);
        box.addView(clearRow, crl);
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

    private static final int REQ_EXPORT_PACKETS = 41, REQ_EXPORT_DEV = 42, REQ_EXPORT_TRACK = 43, REQ_MAP_FILE = 44,
            REQ_OVERLAY = 45;
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

    /** Which packets the export in progress covers: -1 = the newest 1000, else from this time on. */
    private long exportSince = -1;
    private String exportWhat = "packet log";

    private void exportPackets() {
        String[] opts = {"Newest 1000 (the Packets tab)", "Last 24 hours", "Last 7 days", "Everything kept (30 days)"};
        long[] back = {-1, 86_400_000L, 7 * 86_400_000L, History.KEEP_MS};
        String[] what = {"packet log, newest 1000", "packet log, last 24 hours", "packet log, last 7 days", "packet log, last 30 days"};
        new AlertDialog.Builder(this)
                .setTitle("Export which packets?")
                .setItems(opts, (d, w) -> {
                    exportSince = back[w] < 0 ? -1 : System.currentTimeMillis() - back[w];
                    exportWhat = what[w];
                    String name = "h9ops-packets-" + stamp() + ".txt";
                    saveOrShare("packet log", () -> exportFile(REQ_EXPORT_PACKETS, name),
                            () -> shareFile("packet log", name, "text/plain",
                                    (out, done) -> repo.exportPackets(out, exportHeader(exportWhat), exportSince, done)));
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        if (req == REQ_MAP_FILE) { importMap(data.getData()); return; }
        if (req == REQ_OVERLAY) { importOverlay(data.getData()); return; }
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
        if (req == REQ_EXPORT_PACKETS) repo.exportPackets(out, exportHeader(exportWhat), exportSince, done);
        else repo.dev.export(out, exportHeader("developer log"), done);
    }

    // ---- track recording ----

    private void toggleTrack() {
        if (repo.tracks.recording()) {
            TrackService.stop(this);
            toast("Track saved: see Tracks");
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
                    .setMessage("No tracks yet.")
                    .setPositiveButton("Record track", (d, w) -> toggleTrack())
                    .setNegativeButton("Close", null).show();
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
                .setNeutralButton(cur != null ? "Stop recording" : "Record track", (d, w) -> toggleTrack())
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
                .setNegativeButton("Map", (d, w) -> showTrackOnMap(t));
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
                autoLine.setText("📍 " + plan.label()
                        + (plan.smart != null ? (plan.lastSent > 0 ? " · last sent " + hhmm(plan.lastSent) : "")
                            : next > System.currentTimeMillis() ? " · next " + hhmm(next) : "")
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
                            + " · tap for the map";
                    b.setText("📍 " + where + "\n" + how);
                    b.setTypeface(Ui.mono());
                    b.setTextSize(13);
                    b.setOnClickListener(v -> {
                        dialog.dismiss();
                        showOnMap(m.outgoing ? "Sent to " + peer : m.from(), pos.lat, pos.lon);
                    });
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
        SmartBeacon.Profile[] profiles = SmartBeacon.Profile.values();
        for (SmartBeacon.Profile sp : profiles) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(2900 + sp.ordinal());
            rb.setText(sp == SmartBeacon.Profile.DRIVING ? "🚗 Smart" : "🚶 Smart");
            every.addView(rb);
        }
        for (int m : AutoPosition.MINUTES) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(2000 + m);
            rb.setText(m < 60 ? m + "m" : (m / 60) + "h");
            every.addView(rb);
        }
        every.check(now != null && now.smart != null ? 2900 + now.smart.profile.ordinal()
                : 2000 + (now != null ? now.minutes : 30));
        android.widget.HorizontalScrollView everyScroll = new android.widget.HorizontalScrollView(this);
        everyScroll.addView(every);
        TextView everyHelp = label("");
        everyHelp.setTextSize(13);
        Runnable explain = () -> {
            int id = every.getCheckedRadioButtonId();
            if (id >= 2900) {
                SmartBeacon.Profile sp = profiles[id - 2900];
                everyHelp.setText(sp.label + ": checks the GPS every " + (sp.checkMs / 60_000) + " min and sends every "
                        + (sp.fastMs / 60_000) + " min at " + Math.round(sp.fastMph) + " mph or faster, less often when "
                        + "slower, every " + (sp.slowMs / 60_000) + " min when stopped, and soon after a turn or a stop.");
                everyHelp.setVisibility(View.VISIBLE);
            } else everyHelp.setVisibility(View.GONE);
        };
        every.setOnCheckedChangeListener((g, id) -> explain.run());
        explain.run();
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
        box.addView(label("Every:")); box.addView(everyScroll); box.addView(everyHelp);
        box.addView(label("How precise:")); box.addView(prec);
        box.addView(label("Stop:")); box.addView(stop);
        if (now != null) {
            TextView st = label("Now: " + now.label() + ", " + now.blur.label.toLowerCase(Locale.US)
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
                    int id = every.getCheckedRadioButtonId();
                    SmartBeacon.Profile smart = id >= 2900 ? profiles[id - 2900] : null;
                    int minutes = smart != null ? 0 : id - 2000;
                    int idx = Math.max(0, prec.getCheckedRadioButtonId() - 1000);
                    int hours = stop.getCheckedRadioButtonId() - 3000;
                    withLocation(() -> {
                        String err = repo.startAuto(peer, minutes, smart, blurs[idx], hours);
                        if (err != null) toast(err);
                        else toast("Sending your position to " + peer + " " + repo.auto.get(peer).label());
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
        repo.historySize(n -> {
            if (isFinishing()) return;
            String kept = n[0] == 0 ? "" : " (" + String.format(Locale.US, "%,d", n[0]) + " packets since "
                    + new SimpleDateFormat("d MMM", Locale.US).format(new Date(n[1])) + ")";
            new AlertDialog.Builder(this)
                    .setMessage("Clear all heard stations and the 30-day packet history" + kept
                            + "? The weather graphs start again from empty too.")
                    .setPositiveButton("Clear", (d, w) -> repo.clear())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
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
            b.setPositiveButton("Map", (d, w) -> showOnMap(label, la, lo));   // short: three buttons fit in one row
        }
        if (s.wx != null) b.setNeutralButton("Weather", (d, w) -> weatherDialog(s.name));
        else if (!s.object) b.setNeutralButton("Message", (d, w) -> openConversation(s.name));
        b.show();
    }

    // ---------------------------------------------------------------- weather

    private static final long DAY = 86_400_000L;
    private static final long[] WX_RANGES = {DAY, 3 * DAY, 7 * DAY, 30 * DAY};
    private static final String[] WX_RANGE_NAMES = {"24 h", "3 days", "7 days", "30 days"};
    /** Points per graph: 15 minutes, 1 hour, 2 hours, 6 hours each. */
    private static final int[] WX_BUCKETS = {96, 72, 84, 120};

    /** Weather stations heard in the last 30 days, newest first. */
    private void weatherListDialog() {
        repo.weatherStations(list -> {
            if (isFinishing()) return;
            if (list.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Weather")
                        .setMessage("No weather reports heard in the last 30 days. Weather stations show up here "
                                + "as they're heard, with graphs of everything they send.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            long now = System.currentTimeMillis();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(4), dp(16), dp(8));
            TextView head = label(list.size() + (list.size() == 1 ? " station" : " stations")
                    + " heard in the last 30 days · tap one for graphs");
            head.setTextSize(13);
            head.setPadding(dp(4), 0, dp(4), dp(6));
            box.addView(head);
            AlertDialog[] dlg = new AlertDialog[1];
            for (History.WxStation w : list) box.addView(weatherRow(w, now, () -> {
                if (dlg[0] != null) dlg[0].dismiss();
                weatherDialog(w.name);
            }));
            ScrollView sv = new ScrollView(this);
            sv.addView(box);
            dlg[0] = new AlertDialog.Builder(this)
                    .setTitle("Weather")
                    .setView(sv)
                    .setNegativeButton("Close", null)
                    .show();
        });
    }

    /** One weather station: name, town and age on the left, temperature large on the right. */
    private View weatherRow(History.WxStation w, long now, Runnable open) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Ui.box(this, Ui.SURFACE, Ui.BORDER, 14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> open.run());
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(w.name);
        name.setTypeface(Ui.monoBold());
        name.setTextSize(16);
        name.setTextColor(Ui.accent);
        left.addView(name);
        TextView where = new TextView(this);
        where.setText((w.place != null ? w.place + " · " : "") + Format.ago(now - w.last));
        where.setTextSize(13);
        where.setTextColor(Ui.text);
        left.addView(where);
        List<String> bits = new ArrayList<>();
        if (!Double.isNaN(w.wind)) {
            String dir = Double.isNaN(w.dir) || w.wind < 1 ? "" : Format.compass(w.dir) + " ";
            bits.add(w.wind < 1 ? "Calm" : "Wind " + dir + Math.round(w.wind) + " mph"
                    + (!Double.isNaN(w.gust) && w.gust > w.wind + 2 ? ", gusts " + Math.round(w.gust) : ""));
        }
        if (!Double.isNaN(w.hum)) bits.add(Math.round(w.hum) + "% humidity");
        if (!bits.isEmpty()) {
            TextView more = new TextView(this);
            more.setText(String.join(" · ", bits));
            more.setTextSize(12);
            more.setTextColor(Ui.muted);
            left.addView(more);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView temp = new TextView(this);
        temp.setText(Double.isNaN(w.temp) ? "–" : Math.round(w.temp) + "°");
        temp.setTextSize(30);
        temp.setTypeface(Ui.monoBold());
        temp.setTextColor(Ui.text);
        temp.setPadding(dp(12), 0, 0, 0);
        row.addView(temp);
        row.setContentDescription(w.name + (w.place != null ? ", " + w.place : "") + ", "
                + (Double.isNaN(w.temp) ? "no temperature" : Math.round(w.temp) + " degrees") + ". " + String.join(", ", bits));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** Small graphs of one station's weather: 24 hours to 30 days. */
    private void weatherDialog(String name) {
        repo.weather(name, System.currentTimeMillis() - WX_RANGES[WX_RANGES.length - 1], samples -> {
            if (isFinishing()) return;
            android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
            int[] sel = {Math.max(0, Math.min(WX_RANGES.length - 1, prefs.getInt("wx_range", 0)))};
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(8), dp(20), dp(8));
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            TextView status = label("");
            status.setTextSize(13);
            LinearLayout graphs = new LinearLayout(this);
            graphs.setOrientation(LinearLayout.VERTICAL);
            Button[] btns = new Button[WX_RANGES.length];
            Runnable[] draw = new Runnable[1];
            for (int i = 0; i < btns.length; i++) {
                final int k = i;
                btns[i] = button(WX_RANGE_NAMES[i], v -> {
                    sel[0] = k;
                    prefs.edit().putInt("wx_range", k).apply();
                    draw[0].run();
                });
                btns[i].setTextSize(13);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (i > 0) lp.leftMargin = dp(6);
                chips.addView(btns[i], lp);
            }
            draw[0] = () -> {
                for (int i = 0; i < btns.length; i++) {
                    if (i == sel[0]) Ui.filled(btns[i], 12); else Ui.plain(btns[i]);
                    btns[i].setTextSize(13);
                }
                fillWeather(graphs, status, name, samples, sel[0]);
            };
            box.addView(chips);
            box.addView(status);
            box.addView(graphs);
            draw[0].run();
            ScrollView sv = new ScrollView(this);
            sv.addView(box);
            Station st = repo.store.get(name);
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle((st != null ? Symbols.icon(st.symTable(), st.symCode()) + "  " : "") + name)
                    .setView(sv)
                    .setPositiveButton("Close", null);
            if (st != null) b.setNeutralButton("Station", (d, w) -> showStation(st));
            b.show();
        });
    }

    private void fillWeather(LinearLayout graphs, TextView status, String name, List<WxHistory.Sample> all, int range) {
        graphs.removeAllViews();
        long to = System.currentTimeMillis(), from = to - WX_RANGES[range];
        int n = WX_BUCKETS[range];
        List<WxHistory.Sample> in = new ArrayList<>();
        for (WxHistory.Sample x : all) if (x.time >= from) in.add(x);
        if (in.isEmpty()) {
            status.setText("No weather reports from " + name + " in the last " + WX_RANGE_NAMES[range]
                    + (all.isEmpty() ? "." : ". Try a longer time."));
            return;
        }
        String town = repo.placeName(name);
        status.setText((town != null ? town + " · " : "") + in.size() + (in.size() == 1 ? " report" : " reports") + " · newest "
                + Format.ago(to - in.get(in.size() - 1).time) + " · drag across a graph to read it");
        WxHistory.Field T = WxHistory.Field.TEMP, H = WxHistory.Field.HUMIDITY, P = WxHistory.Field.PRESSURE,
                W = WxHistory.Field.WIND, G = WxHistory.Field.GUST, D = WxHistory.Field.WIND_DIR,
                R1 = WxHistory.Field.RAIN_1H, R24 = WxHistory.Field.RAIN_24H;
        if (WxHistory.has(in, T)) addGraph(graphs, in, T, null, from, to, n, lowHigh(in, T, from), null);
        if (WxHistory.has(in, H)) addGraph(graphs, in, H, null, from, to, n, lowHigh(in, H, from), null);
        if (WxHistory.has(in, P)) {
            double ch = WxHistory.change(in, P, 3 * 3_600_000L);
            String trend = Double.isNaN(ch) ? "" : Math.abs(ch) < 1 ? " · steady" + " over 3 h"
                    : (ch > 0 ? " · rising " : " · falling ") + String.format(Locale.US, "%.1f mb in 3 h", Math.abs(ch));
            addGraph(graphs, in, P, null, from, to, n, lowHigh(in, P, from) + trend, null);
        }
        if (WxHistory.has(in, W) || WxHistory.has(in, G)) {
            double dir = WxHistory.latest(in, D), gust = WxHistory.latest(in, G);
            double[] gr = WxHistory.range(in, G, from);
            String value = W.format(WxHistory.latest(in, W)) + (Double.isNaN(gust) ? "" : ", gust " + Math.round(gust));
            String sub = (Double.isNaN(dir) ? "" : "from " + Format.compass(dir) + " · ")
                    + (Double.isNaN(gr[1]) ? "average speed" : "strongest gust " + G.format(gr[1]) + " · light line: gusts");
            addGraph(graphs, in, W, WxHistory.has(in, G) ? G : null, from, to, n, sub, value);
        }
        if (WxHistory.has(in, R1)) {
            double total = WxHistory.rainTotal(in, from, to);
            String sub = Double.isNaN(total) || total == 0 ? "no rain" : "about " + R1.format(total) + " in total";
            addGraph(graphs, in, R1, null, from, to, n, sub, null);
        } else if (WxHistory.has(in, R24)) {
            addGraph(graphs, in, R24, null, from, to, n, "each point: rain in the 24 hours before it", null);
        }
    }

    private static String lowHigh(List<WxHistory.Sample> in, WxHistory.Field f, long from) {
        double[] r = WxHistory.range(in, f, from);
        return "low " + f.format(r[0]) + " · high " + f.format(r[1]);
    }

    private void addGraph(LinearLayout graphs, List<WxHistory.Sample> in, WxHistory.Field f, WxHistory.Field f2,
                          long from, long to, int n, String sub, String value) {
        WxGraph g = new WxGraph(this);
        g.set(f.label, value != null ? value : f.format(WxHistory.latest(in, f)), sub,
                f, WxHistory.buckets(in, f, from, to, n),
                f2, f2 == null ? null : WxHistory.buckets(in, f2, from, to, n), from, to);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        graphs.addView(g, lp);
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

    // ---------------------------------------------------------------- map tab

    /** Opens the Map tab on a station's position (or a pin, for a position not on a station). */
    private void showOnMap(String name, double lat, double lon) {
        selectTab(TAB_MAP);
        mapPane.focus(name, lat, lon);
    }

    private void showTrackOnMap(io.github.deadly447.h9ops.track.Track t) {
        String json;
        synchronized (repo.tracks) { json = MapJson.track(t); }   // a recording track is still growing
        selectTab(TAB_MAP);
        mapPane.setTrack(json);
    }

    /** Full screen: only the map, until ⛶ or Back. */
    private void setMapFull(boolean on) {
        mapFull = on;
        int v = on ? View.GONE : View.VISIBLE;
        headerView.setVisibility(v);
        radioCard.root.setVisibility(v);
        tabsView.setVisibility(v);
        footer.setVisibility(v);
    }

    @Override public void mapStation(String name) {
        Station s = repo.store.get(name);
        if (s != null) showStation(s);
    }

    @Override public void mapNavigate(double lat, double lon, String label) { openMap(lat, lon, label); }

    @Override public void mapMe() { withLocation(this::mapFix); }

    @Override public void mapFull() { setMapFull(!mapFull); }

    /** The map's renderer was stopped (crash or memory): start a fresh one if the tab is open. */
    @Override public void mapGone() {
        if (mapPane == null) return;
        mapHolder.removeView(mapPane.web);
        mapPane.destroy();
        mapPane = null;
        if (tab == TAB_MAP) selectTab(TAB_MAP);
    }

    /** One GPS fix for the map's ◎ button. Only when tapped: the map never follows you by itself. */
    private void mapFix() {
        oneFix(l -> {
            if (mapPane == null) return;
            if (l == null) mapPane.meFailed();
            else mapPane.setMe(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0);
        });
    }

    /** One GPS fix (a recent one if there is one), or null after a minute without. Call inside withLocation. */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void oneFix(java.util.function.Consumer<android.location.Location> then) {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            toast("Turn on location (GPS) in the phone's settings");
            then.accept(null);
            return;
        }
        final boolean[] done = {false};
        java.util.function.Consumer<android.location.Location> got = l -> {
            if (done[0]) return;
            done[0] = true;
            if (l == null) toast("No GPS fix");
            then.accept(l);
        };
        android.location.Location last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
        if (last != null && System.currentTimeMillis() - last.getTime() < 120_000) {
            got.accept(last);
            return;
        }
        toast("Getting a GPS fix…");
        if (Build.VERSION.SDK_INT >= 30) {
            android.os.CancellationSignal cancel = new android.os.CancellationSignal();
            lm.getCurrentLocation(android.location.LocationManager.GPS_PROVIDER, cancel, getMainExecutor(), got::accept);
            ui.postDelayed(() -> { if (!done[0]) { cancel.cancel(); got.accept(null); } }, 60_000);
        } else {
            android.location.LocationListener listener = new android.location.LocationListener() {
                @Override public void onLocationChanged(android.location.Location l) { lm.removeUpdates(this); got.accept(l); }
                @Override public void onStatusChanged(String p, int st, Bundle b) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            ui.postDelayed(() -> { if (!done[0]) { lm.removeUpdates(listener); got.accept(null); } }, 60_000);
        }
    }

    @Override public void mapLongPress(double lat, double lon) {
        new AlertDialog.Builder(this)
                .setTitle(String.format(Locale.US, "%.5f, %.5f", lat, lon))
                .setItems(new String[]{"Place an object here…", "Alert area here…", "Navigate here"}, (d, w) -> {
                    if (w == 0) objectDialog(lat, lon);
                    else if (w == 1) areaDialog(lat, lon);
                    else openMap(lat, lon, "Map point");
                })
                .show();
    }

    @Override public void mapRemoveObject(String name) {
        new AlertDialog.Builder(this)
                .setMessage("Remove the object " + name + "? It is sent three more times as removed, so other "
                        + "maps drop it too.")
                .setPositiveButton("Remove", (d, w) -> repo.removeObject(name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- alerts

    private void alertsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(label("Alert when one of these is heard (again after 30 minutes without it). Commas or "
                + "spaces between; N0CALL-* means any SSID."));
        EditText watch = edit(String.join(", ", repo.alerts.watch()), "N0CALL-7, N0CALL-*");
        watch.setSingleLine(false);
        watch.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(watch);
        CheckBox em = new CheckBox(this);
        em.setText("Emergency packets: a Mic-E emergency, an EMERG object, or EMERGENCY in a comment or status");
        em.setChecked(repo.alerts.emergency());
        box.addView(em);
        box.addView(label("Areas (alert when a station goes in or out). Long-press the map to add one."));
        AlertDialog[] self = {null};
        List<io.github.deadly447.h9ops.aprs.Alerts.Area> areas = repo.alerts.areas();
        if (areas.isEmpty()) box.addView(label("No areas yet."));
        for (io.github.deadly447.h9ops.aprs.Alerts.Area a : areas) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText("⚑ " + a.name + " · " + String.format(Locale.US, "%.2g mi", a.radiusM / io.github.deadly447.h9ops.aprs.Alerts.M_PER_MILE)
                    + " · " + (a.calls.isEmpty() ? "any station" : String.join(" ", a.calls)));
            t.setTextColor(Ui.text);
            t.setTextSize(14);
            row.addView(t, weight());
            row.addView(button("Map", v -> { if (self[0] != null) self[0].dismiss(); showOnMap("⚑ " + a.name, a.lat, a.lon); }));
            row.addView(button("Remove", v -> {
                repo.removeAlertArea(a.name);
                if (self[0] != null) self[0].dismiss();
                alertsDialog();
            }));
            box.addView(row);
        }
        box.addView(label("Alerts are notifications (Settings → Notifications → H9 OPS to change their sound). "
                + "The lock screen only says there is one, not who or where."));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        self[0] = new AlertDialog.Builder(this)
                .setTitle("Alerts")
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    repo.setAlerts(watch.getText().toString(), em.isChecked());
                    toast("Alerts saved");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void areaDialog(double lat, double lon) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(label("Name"));
        EditText name = edit("Area " + (repo.alerts.areas().size() + 1), "Camp, Search area 2…");
        box.addView(name);
        box.addView(label("Size (radius)"));
        android.widget.RadioGroup g = new android.widget.RadioGroup(this);
        double[] r = io.github.deadly447.h9ops.aprs.Alerts.RADIUS_MILES;
        for (int i = 0; i < r.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(2000 + i);
            rb.setText((r[i] < 1 ? String.format(Locale.US, "%.2g", r[i]) : String.format(Locale.US, "%.0f", r[i])) + " mi");
            g.addView(rb);
        }
        g.check(2002);
        box.addView(g);
        box.addView(label("Only these stations (empty = any station)"));
        EditText calls = edit("", "N0CALL-*, N0CALL-9");
        calls.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(calls);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Alert area")
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().replaceAll("[\\t\\n|]", " ").trim();
                    if (n.length() > 24) n = n.substring(0, 24).trim();
                    if (n.isEmpty()) n = "Area";
                    double miles = r[Math.max(0, g.getCheckedRadioButtonId() - 2000)];
                    repo.putAlertArea(new io.github.deadly447.h9ops.aprs.Alerts.Area(n, lat, lon,
                            miles * io.github.deadly447.h9ops.aprs.Alerts.M_PER_MILE,
                            io.github.deadly447.h9ops.aprs.Alerts.parseCalls(calls.getText().toString())));
                    toast("Alert area " + n + " set");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- objects and bulletins

    private void objectsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(label("Objects you place go out on the APRS channel under your callsign every few minutes, "
                + "until you remove them, and every APRS map that hears them shows them: a command post, a "
                + "staging area, a hazard. To place one, long-press the map, or use your position."));
        AlertDialog[] self = {null};
        List<io.github.deadly447.h9ops.aprs.Broadcasts.Obj> objs = repo.broadcasts.objects();
        if (objs.isEmpty()) box.addView(label("None on the air."));
        for (io.github.deadly447.h9ops.aprs.Broadcasts.Obj o : objs) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(Symbols.icon(o.table, o.code) + " " + o.name + " · every " + o.minutes + " min"
                    + (o.comment.isEmpty() ? "" : "\n" + o.comment));
            t.setTextColor(Ui.text);
            t.setTextSize(14);
            row.addView(t, weight());
            row.addView(button("Map", v -> { if (self[0] != null) self[0].dismiss(); showOnMap(o.name, o.lat, o.lon); }));
            row.addView(button("Remove", v -> {
                repo.removeObject(o.name);
                if (self[0] != null) self[0].dismiss();
                objectsDialog();
            }));
            box.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        self[0] = new AlertDialog.Builder(this)
                .setTitle("My objects")
                .setView(sv)
                .setPositiveButton("At my position…", (d, w) -> withLocation(() -> oneFix(l -> {
                    if (l != null) objectDialog(l.getLatitude(), l.getLongitude());
                })))
                .setNegativeButton("Close", null)
                .show();
    }

    private void objectDialog(double lat, double lon) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(label("Name (up to 9 characters; the same name moves it)"));
        EditText name = edit("", "ICP, STAGING…");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(io.github.deadly447.h9ops.aprs.Broadcasts.MAX_NAME)});
        box.addView(name);
        box.addView(label("Symbol"));
        android.widget.RadioGroup sym = new android.widget.RadioGroup(this);
        String[][] syms = io.github.deadly447.h9ops.aprs.Broadcasts.SYMBOLS;
        for (int i = 0; i < syms.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(3000 + i);
            rb.setText(Symbols.icon(syms[i][0].charAt(0), syms[i][1].charAt(0)) + "  " + syms[i][2]);
            sym.addView(rb);
        }
        sym.check(3000);
        box.addView(sym);
        box.addView(label("Comment (optional, up to 43 characters)"));
        EditText comment = edit("", "Frequency, contact, what's here");
        comment.setFilters(new InputFilter[]{new InputFilter.LengthFilter(io.github.deadly447.h9ops.aprs.Broadcasts.MAX_OBJECT_COMMENT)});
        box.addView(comment);
        box.addView(label("Send it again every"));
        android.widget.RadioGroup every = new android.widget.RadioGroup(this);
        every.setOrientation(LinearLayout.HORIZONTAL);
        int[] mins = io.github.deadly447.h9ops.aprs.Broadcasts.OBJECT_MINUTES;
        for (int i = 0; i < mins.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(4000 + i);
            rb.setText(mins[i] + " min  ");
            every.addView(rb);
        }
        every.check(4001);
        box.addView(every);
        box.addView(label("It's a transmission under your callsign (" + (repo.myCall() == null ? "set it in Settings" : repo.myCall())
                + "), in plain text like all APRS."));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(String.format(Locale.US, "Object at %.5f, %.5f", lat, lon))
                .setView(sv)
                .setPositiveButton("Place", (d, w) -> {
                    String[] sy = syms[Math.max(0, sym.getCheckedRadioButtonId() - 3000)];
                    String err = repo.placeObject(name.getText().toString(), lat, lon, sy[0].charAt(0), sy[1].charAt(0),
                            comment.getText().toString(), mins[Math.max(0, every.getCheckedRadioButtonId() - 4000)]);
                    if (err != null) toast(err);
                    else toast(repo.canTransmit() ? "Object placed" : "Object saved: it goes out when the radio is connected");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void bulletinsDialog() {
        repo.board.seen();
        refreshList();
        long now = System.currentTimeMillis();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        AlertDialog[] self = {null};
        for (io.github.deadly447.h9ops.aprs.Broadcasts.Bln b : repo.broadcasts.bulletins()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText("Sending " + b.addressee() + ": " + b.text);
            t.setTextColor(Ui.accent);
            t.setTextSize(14);
            row.addView(t, weight());
            row.addView(button("Stop", v -> {
                repo.stopBulletin(b.line);
                if (self[0] != null) self[0].dismiss();
                bulletinsDialog();
            }));
            box.addView(row);
        }
        List<io.github.deadly447.h9ops.aprs.BulletinBoard.Entry> l = repo.board.entries(now);
        if (l.isEmpty()) box.addView(label("No bulletins heard in the last two days. Bulletins are messages to "
                + "everyone (BLN0–BLN9, BLNA–BLNZ for announcements), for nets and searches."));
        for (io.github.deadly447.h9ops.aprs.BulletinBoard.Entry e : l) {
            TextView h = new TextView(this);
            h.setText(e.to + " · " + (e.mine ? "you" : e.from) + " · " + Format.ago(now - e.last)
                    + (e.count > 1 ? " · ×" + e.count : ""));
            h.setTypeface(Ui.mono());
            h.setTextColor(Ui.muted);
            h.setTextSize(12);
            h.setPadding(0, dp(10), 0, 0);
            TextView t = new TextView(this);
            t.setText(e.text);
            t.setTextColor(Ui.text);
            t.setTextSize(15);
            t.setTextIsSelectable(true);
            box.addView(h);
            box.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        self[0] = new AlertDialog.Builder(this)
                .setTitle("Bulletins")
                .setView(sv)
                .setPositiveButton("Send bulletin…", (d, w) -> sendBulletinDialog())
                .setNegativeButton("Close", null)
                .show();
    }

    private void sendBulletinDialog() {
        if (!readyToSend()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(label("Line: 0–9 for bulletins, A–Z for announcements. Sending on a line you used replaces it."));
        EditText line = edit("0", "0");
        line.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});
        line.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(line);
        box.addView(label("Text (up to 67 characters)"));
        EditText text = edit("", "Net tonight 1900 on 146.520");
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(io.github.deadly447.h9ops.aprs.Broadcasts.MAX_BULLETIN)});
        box.addView(text);
        android.widget.RadioGroup rep = new android.widget.RadioGroup(this);
        String[] reps = {"Once", "Every 10 min for 1 hour", "Every 10 min for 4 hours"};
        for (int i = 0; i < reps.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(5000 + i);
            rb.setText(reps[i]);
            rep.addView(rb);
        }
        rep.check(5001);
        box.addView(rep);
        box.addView(label("Bulletins go to everyone listening, in plain text, and aren't acknowledged."));
        new AlertDialog.Builder(this)
                .setTitle("Send a bulletin")
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    int hours = io.github.deadly447.h9ops.aprs.Broadcasts.BULLETIN_HOURS[Math.max(0, rep.getCheckedRadioButtonId() - 5000)];
                    String err = repo.sendBulletin(line.getText().toString(), text.getText().toString(), hours);
                    toast(err != null ? err : "Bulletin sent");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Map file…: what's installed, and add, replace or remove it. */
    // ---- overlays: GPX / KML / KMZ trail plans and search areas drawn on the map ----

    private io.github.deadly447.h9ops.map.Overlays overlayStore;

    private io.github.deadly447.h9ops.map.Overlays overlays() {
        if (overlayStore == null) overlayStore = new io.github.deadly447.h9ops.map.Overlays(new java.io.File(getFilesDir(), "overlays"));
        return overlayStore;
    }

    private void overlaysDialog() {
        List<io.github.deadly447.h9ops.map.Overlays.Entry> list = overlays().list();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        TextView how = label("Trail plans, search areas and waypoints from a GPX, KML or KMZ file (CalTopo, Gaia, "
                + "onX, Google Earth, ATAK). They're drawn in green on the Map tab. Tick to show or hide, tap a name "
                + "to zoom to it or remove it.");
        how.setTextSize(13);
        box.addView(how);
        if (list.isEmpty()) box.addView(label("No overlays yet."));
        AlertDialog[] dlg = new AlertDialog[1];
        for (io.github.deadly447.h9ops.map.Overlays.Entry e : list) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            cb.setChecked(e.visible);
            cb.setContentDescription("Show " + e.name);
            cb.setOnCheckedChangeListener((v, on) -> {
                overlays().setVisible(e.id, on);
                if (mapPane != null) mapPane.overlaysChanged(null);
            });
            row.addView(cb);
            TextView t = new TextView(this);
            t.setText(e.name + "\n" + e.summary);
            t.setTextColor(Ui.text);
            t.setTextSize(14);
            t.setPadding(dp(4), dp(10), 0, dp(10));
            t.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setMessage(e.summary)
                    .setPositiveButton("Show on map", (d, w) -> {
                        if (dlg[0] != null) dlg[0].dismiss();
                        showOverlay(e.id);
                    })
                    .setNeutralButton("Remove", (d, w) -> {
                        overlays().remove(e.id);
                        if (mapPane != null) mapPane.overlaysChanged(null);
                        if (dlg[0] != null) dlg[0].dismiss();
                        overlaysDialog();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Map overlays")
                .setView(sv)
                .setPositiveButton("Add file…", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gpx+xml",
                                    "application/vnd.google-earth.kml+xml", "application/vnd.google-earth.kmz",
                                    "application/xml", "text/xml", "application/octet-stream", "*/*"});
                    try { startActivityForResult(i, REQ_OVERLAY); }
                    catch (ActivityNotFoundException ex) { toast("This phone has no file picker"); }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showOverlay(String id) {
        overlays().setVisible(id, true);
        selectTab(TAB_MAP);
        mapPane.overlaysChanged(id);
    }

    /** Reads a chosen GPX/KML/KMZ in the background, keeps what it draws, and shows it on the map. */
    private void importOverlay(Uri uri) {
        String name = "overlay";
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        final String fileName = name;
        toast("Reading " + fileName + "…");
        new Thread(() -> {
            String err = null;
            io.github.deadly447.h9ops.map.Overlays.Entry added = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Couldn't open the file");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > io.github.deadly447.h9ops.map.Overlay.MAX_BYTES)
                        throw new java.io.IOException("The file is too big for a map overlay (over 20 MB)");
                }
                io.github.deadly447.h9ops.map.Overlay o = io.github.deadly447.h9ops.map.Overlay.parse(out.toByteArray(), fileName);
                synchronized (this) { added = overlays().add(o); }
            } catch (java.io.IOException | RuntimeException e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String e2 = err;
            final io.github.deadly447.h9ops.map.Overlays.Entry a2 = added;
            runOnUiThread(() -> {
                if (e2 != null) {
                    new AlertDialog.Builder(this).setTitle("Couldn't add " + fileName).setMessage(e2)
                            .setPositiveButton("OK", null).show();
                    return;
                }
                toast("Added " + a2.name + ": " + a2.summary);
                showOverlay(a2.id);
            });
        }).start();
    }

    private void mapFileDialog() {
        java.io.File path = MapPane.mapFile(this);
        String info;
        if (path.isFile()) {
            try (MapFile f = MapFile.open(path)) {
                info = f.header.describe() + "\n" + mb(f.size) + ", added " + time(path.lastModified());
            } catch (java.io.IOException e) {
                info = "The map file can't be read: " + e.getMessage();
            }
        } else {
            info = "No map yet. Stations still show on the Map tab, on a plain background.";
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(8));
        TextView t = label(info + "\n\nThe map is one file for your area (a Protomaps .pmtiles file), kept "
                + "on this phone. The app has no internet access, so it never downloads maps: you make the file "
                + "once on a computer and copy it here.");
        box.addView(t);
        AlertDialog[] dlg = new AlertDialog[1];
        Button get = button("Get a map for your area…", v -> { if (dlg[0] != null) dlg[0].dismiss(); getMapDialog(); });
        Ui.filled(get, 12);
        Button choose = button(path.isFile() ? "Replace with another file…" : "Choose the map file…", v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            pickMapFile();
        });
        for (Button bt : new Button[]{get, choose}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            lp.topMargin = dp(10);
            box.addView(bt, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Offline map")
                .setView(sv)
                .setNegativeButton("Close", null);
        if (path.isFile()) {
            b.setNeutralButton("Remove", (d, w) -> new AlertDialog.Builder(this)
                    .setMessage("Remove the offline map? (" + mb(path.length()) + ")")
                    .setPositiveButton("Remove", (d2, w2) -> {
                        if (!path.delete()) toast("Couldn't remove it");
                        if (mapPane != null) mapPane.load();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        dlg[0] = b.show();
    }

    /**
     * How to make a map file for this area: the guide (in the browser), the pmtiles tool, and the
     * exact command for a box around where the map was last looking (or this phone's last GPS fix).
     */
    private void getMapDialog() {
        double lat = Double.NaN, lon = Double.NaN;
        android.content.SharedPreferences mp = getSharedPreferences("map", MODE_PRIVATE);
        if (mp.contains("lat")) { lat = mp.getFloat("lat", 0); lon = mp.getFloat("lon", 0); }
        if (Double.isNaN(lat) && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
            android.location.Location last = null;
            try {
                if (lm != null) last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                if (last == null && lm != null) last = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            } catch (RuntimeException ignored) { }
            if (last != null) { lat = last.getLatitude(); lon = last.getLongitude(); }
        }
        final double cLat = lat, cLon = lon;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(8));
        box.addView(label("1. On a computer, get the free pmtiles tool (Windows, Mac or Linux).\n"
                + "2. Run the command below. It cuts your area out of Protomaps' free world map (OpenStreetMap "
                + "data) into one file, h9-map.pmtiles.\n"
                + "3. Copy that file to this phone (USB, a cloud drive, email) and choose it in Map file….\n\n"
                + "The guide has the same steps with pictures and other ways to choose an area."));
        TextView cmd = new TextView(this);
        cmd.setTypeface(Ui.mono());
        cmd.setTextSize(12);
        cmd.setTextColor(Ui.text);
        cmd.setTextIsSelectable(true);
        cmd.setPadding(dp(12), dp(10), dp(12), dp(10));
        cmd.setBackground(Ui.box(this, Ui.SURFACE, Ui.BORDER, 10));
        TextView size = label("");
        size.setTextSize(13);
        String[] current = new String[1];
        android.widget.RadioGroup area = new android.widget.RadioGroup(this);
        area.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < io.github.deadly447.h9ops.map.MapRecipe.MILES.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(4000 + i);
            rb.setText(io.github.deadly447.h9ops.map.MapRecipe.MILES[i] + " mi");
            area.addView(rb);
        }
        Runnable update = () -> {
            int i = Math.max(0, area.getCheckedRadioButtonId() - 4000);
            int miles = io.github.deadly447.h9ops.map.MapRecipe.MILES[i];
            if (Double.isNaN(cLat)) {
                current[0] = null;
                cmd.setText("No position yet: open the Map tab and move to your area, or allow location, then come "
                        + "back. The guide shows how to pick an area by hand.");
                size.setText("");
            } else {
                current[0] = io.github.deadly447.h9ops.map.MapRecipe.command(cLat, cLon, miles, System.currentTimeMillis());
                cmd.setText(current[0]);
                size.setText("A box " + miles + " miles each way from " + String.format(Locale.US, "%.2f, %.2f", cLat, cLon)
                        + (mp.contains("lat") ? " (where the map was last looking)" : " (this phone's last GPS fix)")
                        + ". The file will be " + io.github.deadly447.h9ops.map.MapRecipe.roughSize(miles) + ".");
            }
        };
        area.setOnCheckedChangeListener((g, id) -> update.run());
        area.check(4001);
        box.addView(label("Area around:"));
        box.addView(area);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(6);
        box.addView(cmd, cl);
        box.addView(size);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Copy", v -> {
            if (current[0] == null) { toast("No command yet"); return; }
            android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
            if (cb != null) cb.setPrimaryClip(android.content.ClipData.newPlainText("pmtiles command", current[0]));
            toast("Copied");
        }), weight());
        Button share = button("Send to computer…", v -> {
            if (current[0] == null) { toast("No command yet"); return; }
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Map for H9 OPS")
                    .putExtra(Intent.EXTRA_TEXT, "Get the pmtiles tool: " + io.github.deadly447.h9ops.map.MapRecipe.TOOL_URL
                            + "\n\nThen run:\n" + current[0]
                            + "\n\nCopy h9-map.pmtiles to the phone and choose it in H9 OPS → Map file….\n"
                            + "Guide: " + io.github.deadly447.h9ops.map.MapRecipe.GUIDE_URL);
            try { startActivity(Intent.createChooser(i, "Send the map command")); }
            catch (ActivityNotFoundException e) { toast("Nothing on this phone can share text"); }
        });
        LinearLayout.LayoutParams sl = weight();
        sl.leftMargin = dp(8);
        row.addView(share, sl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(8);
        box.addView(row, rl);
        LinearLayout links = new LinearLayout(this);
        links.addView(button("Open the guide", v -> openLink(io.github.deadly447.h9ops.map.MapRecipe.GUIDE_URL)), weight());
        Button tool = button("Get pmtiles tool", v -> openLink(io.github.deadly447.h9ops.map.MapRecipe.TOOL_URL));
        LinearLayout.LayoutParams tl = weight();
        tl.leftMargin = dp(8);
        links.addView(tool, tl);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(8);
        box.addView(links, ll);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Get a map for your area")
                .setView(sv)
                .setPositiveButton("I have the file…", (d, w) -> pickMapFile())
                .setNegativeButton("Close", null)
                .show();
    }

    @Override public void mapGetMap() { getMapDialog(); }

    /** Opens a web page in the phone's browser (the app itself has no internet access). */
    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { toast("No browser on this phone: " + url); }
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
    }

    private void pickMapFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");     // .pmtiles has no MIME type of its own
        try { startActivityForResult(i, REQ_MAP_FILE); }
        catch (ActivityNotFoundException e) { toast("This phone has no file picker"); }
    }

    /** Copies a chosen map file into the app's storage, checking it is a vector PMTiles map first. */
    private void importMap(Uri uri) {
        long total = -1;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) total = c.getLong(0);
        } catch (RuntimeException ignored) { }
        java.io.File dest = MapPane.mapFile(this);
        java.io.File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) { toast("Couldn't make the map folder"); return; }
        if (total > 0 && dir != null && dir.getUsableSpace() < total + 64L * 1048576) {
            new AlertDialog.Builder(this).setTitle("Not enough space")
                    .setMessage("The map needs " + mb(total) + " and the phone has " + mb(dir.getUsableSpace()) + " free.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final long size = total;
        TextView msg = new TextView(this);
        msg.setTextSize(14);
        msg.setTypeface(Ui.mono());
        msg.setPadding(dp(24), dp(16), dp(24), dp(8));
        msg.setText("Checking the file…");
        final boolean[] cancelled = {false}, finished = {false};
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Adding the map").setView(msg)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true)
                .setCancelable(false).create();
        dlg.show();
        java.io.File tmp = new java.io.File(dir, "area.pmtiles.part");
        new Thread(() -> {
            String err = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new java.io.IOException("Couldn't open the file");
                byte[] head = new byte[MapFile.HEADER_LEN];
                int got = 0;
                while (got < head.length) {
                    int n = in.read(head, got, head.length - got);
                    if (n < 0) break;
                    got += n;
                }
                MapFile.Header.parse(got == head.length ? head : java.util.Arrays.copyOf(head, got));
                out.write(head);
                byte[] buf = new byte[1 << 16];
                long done = head.length, shown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled[0]) throw new java.io.IOException("Cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - shown > 250) {
                        shown = now;
                        final long d = done;
                        runOnUiThread(() -> msg.setText("Copying… " + mb(d) + (size > 0 ? " of " + mb(size) : "")));
                    }
                }
                out.getFD().sync();
            } catch (java.io.IOException | RuntimeException e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String error = err;
            runOnUiThread(() -> {
                finished[0] = true;
                if (dlg.isShowing()) dlg.dismiss();
                if (error != null) {
                    if (!tmp.delete()) tmp.deleteOnExit();
                    if (!cancelled[0]) new AlertDialog.Builder(this).setTitle("Couldn't add the map")
                            .setMessage(error).setPositiveButton("OK", null).show();
                    return;
                }
                if (!tmp.renameTo(dest)) {
                    toast("Couldn't save the map");
                    if (!tmp.delete()) tmp.deleteOnExit();
                    return;
                }
                String what;
                try (MapFile f = MapFile.open(dest)) { what = f.header.describe(); }
                catch (java.io.IOException e) { what = "Map saved"; }
                toast("Map added: " + what);
                if (mapPane != null) mapPane.load();
                selectTab(TAB_MAP);
            });
        }, "map-import").start();
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
