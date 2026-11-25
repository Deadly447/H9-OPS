package io.github.deadly447.h9ops;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.deadly447.h9ops.aprs.AutoPosition;
import io.github.deadly447.h9ops.aprs.Kiss;

/**
 * Keeps a Bluetooth LE connection to the radio's KISS service and feeds every
 * received packet to {@link Repo}. The only thing it ever sends the radio is
 * APRS messages and their acks, which the radio transmits. It never tries to
 * pair (the TD-H9 cannot bond, and asking it to just drops the link).
 *
 * Before each connection attempt it scans for the radio for a few seconds, so
 * Android knows the radio's address type. Without that, a radio running older
 * firmware can only be reached after another app (such as nRF Connect) has
 * seen it in a scan.
 */
@SuppressLint("MissingPermission")   // MainActivity asks for the permissions before starting us
public class RadioService extends Service implements Repo.Listener {
    private static final String TAG = "H9OPS";
    public static final String ACTION_START = "io.github.deadly447.h9ops.START";
    public static final String ACTION_STOP = "io.github.deadly447.h9ops.STOP";

    static final UUID KISS_SERVICE = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
    static final UUID KISS_TX = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb");
    static final UUID KISS_RX = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb");
    private static final int CHUNK = 20;   // default ATT MTU 23 - 3; the radio joins the pieces
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL = "radio";
    private static final int NOTIFY_ID = 1, NOTIFY_SILENT = 2;
    private static final String ALERTS = "alerts";
    private static final long SCAN_MS = 6000;
    private static final long SETUP_TIMEOUT_MS = 20000;

    private final Handler main = new Handler(Looper.getMainLooper());     // Bluetooth callbacks
    private final Handler timers = new Handler(Looper.getMainLooper());   // retry and timeout timers
    private Repo repo;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean wanted;
    private final ReconnectPolicy policy = new ReconnectPolicy();
    private final Kiss kiss = new Kiss();
    private int attempt;          // ignores callbacks from a connection we already gave up on
    private boolean wdAnswered;   // the radio answered this connection's watchdog question
    private boolean infoAnswered, infoSeen;   // radio info: answered the last question / any this connection
    private static final long INFO_EVERY_MS = 60_000;
    private long lastNotifyUpdate;
    private BluetoothGattCharacteristic tx;
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private boolean writing;

    // developer log: one connection's numbers
    private DevLog dev;
    private long tryStart, linkUpAt, readyAt;
    private int rxFrames, rxBytes, txFrames, writeFails, busyRetries;
    private int rssiLast = 0, rssiMin = 0, rssiMax = -200, rssiLogged = 0;
    private long rssiSum, rssiCount, rssiLoggedAt;
    private static final long RSSI_MS = 30_000, RSSI_LOG_EVERY_MS = 5 * 60_000;
    private static final int WEAK_DBM = -85;
    private final Handler rssiTimer = new Handler(Looper.getMainLooper());
    private boolean rssiOn;

    public static void start(Context c) {
        Intent i = new Intent(c, RadioService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, RadioService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repo = Repo.get(this);
        dev = repo.dev;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Radio connection",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            NotificationChannel al = new NotificationChannel(ALERTS, "Radio problems",
                    NotificationManager.IMPORTANCE_DEFAULT);
            al.setDescription("For example, the radio's Bluetooth has stopped advertising");
            nm.createNotificationChannel(al);
        }
        repo.addListener(this);
        IntentFilter pf = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 23) pf.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, pf, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(powerReceiver, pf);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutDown("Stopped");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            // Location type only while a timed position is set up, and only if allowed:
            // it lets the service take a GPS fix with the app closed. Android refuses it
            // when the service is (re)started from the background; then we go without.
            boolean loc = repo.auto.any() && hasLocation();
            try {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        | (loc ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0));
                locationType = loc;
            } catch (SecurityException | IllegalStateException e) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                locationType = false;
            }
        } else {
            startForeground(NOTIFY_ID, n);
            locationType = true;
        }
        scheduleAuto();
        if (!wanted) {
            wanted = true;
            policy.restart();
            startStats();
            begin();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shutDown("Stopped");
        repo.removeListener(this);
        try { unregisterReceiver(powerReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- connection steps: scan -> connect -> discover -> subscribe ----

    private void begin() {
        timers.removeCallbacksAndMessages(null);
        if (!wanted) return;
        String mac = repo.radioAddress();
        if (mac == null || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            repo.setState(Repo.State.STOPPED, "Choose your radio first");
            wanted = false;
            stopForeground(true);
            stopSelf();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            waitAndRetry("Bluetooth is off");
            return;
        }
        attempt++;
        tryStart = SystemClock.elapsedRealtime();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            dev.log("BLE", "no scanner available, connecting straight to " + mac);
            connect(adapter.getRemoteDevice(mac));
            return;
        }
        dev.log("BLE", "scanning for " + label() + " (" + mac + ")");
        repo.setState(Repo.State.SCANNING, "Looking for " + label());
        final int myAttempt = attempt;
        // Match by address or by advertised name (either one is enough). The name
        // filter still works if the phone's address filter assumes the wrong type.
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
        String name = repo.radioName();
        if (name != null) filters.add(new ScanFilter.Builder().setDeviceName(name).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanning = true;
            scanner.startScan(filters, settings, scanCallback);
        } catch (RuntimeException e) {
            scanning = false;
            Log.w(TAG, "scan failed to start", e);
            dev.log("BLE", "scan failed to start: " + e);
        }
        // If the scan never sees it (some phones filter oddly), try connecting anyway.
        timers.postDelayed(() -> {
            if (attempt != myAttempt || gatt != null) return;
            stopScan();
            if (!policy.scanMissed()) {
                // Not advertising: a direct connection would only time out (147).
                if (policy.missed() == ReconnectPolicy.SILENT_AFTER) {
                    dev.log("BLE", "radio isn't advertising: " + policy.missed() + " scans in a row saw nothing."
                            + " Scanning every " + (ReconnectPolicy.SILENT_MS / 60_000) + " min, no direct connections");
                    showSilent();
                } else {
                    dev.log("BLE", "scan didn't see the radio (" + policy.missed() + " in a row)");
                }
                waitAndRetry("Radio isn't advertising (its Bluetooth is off or stuck)");
                return;
            }
            dev.log("BLE", "scan didn't see the radio in " + (SCAN_MS / 1000) + " s, trying a direct connection");
            connect(adapter.getRemoteDevice(mac));
        }, SCAN_MS);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> found(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) main.post(() -> found(r));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
            main.post(() -> dev.log("BLE", "scan failed, error " + errorCode));
        }
    };

    /**
     * A scan result: ours if it has the saved address, or the saved name.
     * Stock firmware gives the radio a new address at every power-on, so the
     * name is what finds it again; the new address is then saved.
     */
    @SuppressWarnings("MissingPermission")
    private void found(ScanResult r) {
        if (!scanning || gatt != null) return;
        BluetoothDevice d = r.getDevice();
        String mac = repo.radioAddress(), name = repo.radioName();
        boolean byAddress = mac != null && mac.equalsIgnoreCase(d.getAddress());
        String seen = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
        if (seen == null) {
            try { seen = d.getName(); } catch (SecurityException e) { seen = null; }
        }
        boolean byName = name != null && name.equals(seen);
        if (!byAddress && !byName) return;
        if (policy.seen()) {
            dev.log("BLE", "radio is advertising again");
            clearSilent();
        }
        if (!byAddress) {
            // Same name, new address. Normal after a restart (the radio's firmware
            // picks a new address at every start), but a name is easy to fake, and the radio PIN is sent to
            // whatever we connect to. So ask first; keep looking meanwhile.
            repo.offerMove(d.getAddress());
            return;
        }
        stopScan();
        dev.log("BLE", "found " + (seen != null ? seen : d.getAddress()) + " at " + r.getRssi() + " dBm after "
                + (SystemClock.elapsedRealtime() - tryStart) + " ms");
        connect(d);   // the scanned device carries the right address type
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null && adapter.isEnabled()) scanner.stopScan(scanCallback);
        } catch (RuntimeException ignored) {
            // Bluetooth turned off mid-scan
        }
    }

    private void connect(BluetoothDevice d) {
        if (!wanted) return;
        repo.setState(Repo.State.CONNECTING, "Connecting to " + label());
        dev.log("BLE", "connecting to " + d.getAddress());
        kiss.reset();
        final int myAttempt = attempt;
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = d.connectGatt(this, false, callback(myAttempt), BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = d.connectGatt(this, false, callback(myAttempt));
        }
        if (gatt == null) {
            waitAndRetry("Could not start a connection");
            return;
        }
        // Give the whole setup a time limit, then start over.
        timers.postDelayed(() -> {
            if (attempt == myAttempt && repo.state() != Repo.State.CONNECTED) {
                dev.log("BLE", "setup timed out after " + (SETUP_TIMEOUT_MS / 1000) + " s (" + repo.stateText() + ")");
                dropGatt();
                waitAndRetry("Radio did not answer");
            }
        }, SETUP_TIMEOUT_MS);
    }

    private BluetoothGattCallback callback(final int myAttempt) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                main.post(() -> {
                    if (attempt != myAttempt) { g.close(); return; }
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        linkUpAt = SystemClock.elapsedRealtime();
                        dev.log("BLE", "link up after " + (linkUpAt - tryStart) + " ms, reading services");
                        repo.setState(Repo.State.CONNECTING, "Connected, reading services");
                        // a short pause helps some phones finish link setup first
                        main.postDelayed(() -> { if (attempt == myAttempt) g.discoverServices(); }, 300);
                    } else {
                        if (readyAt != 0) {
                            dev.log("BLE", "DISCONNECTED: " + DevLog.gattStatus(status) + " · " + sessionSummary());
                        } else {
                            dev.log("BLE", "connection failed: " + DevLog.gattStatus(status)
                                    + (newState == BluetoothProfile.STATE_DISCONNECTED ? "" : ", state " + newState));
                        }
                        dropGatt();
                        waitAndRetry(newState == BluetoothProfile.STATE_DISCONNECTED && status == 19
                                ? "Radio closed the connection" : "Connection lost (" + status + ")");
                    }
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                main.post(() -> {
                    if (attempt != myAttempt) return;
                    BluetoothGattService s = g.getService(KISS_SERVICE);
                    BluetoothGattCharacteristic rx = s == null ? null : s.getCharacteristic(KISS_RX);
                    dev.log("BLE", "services read (" + DevLog.gattStatus(status) + "): " + g.getServices().size()
                            + " services, KISS " + (rx != null ? "found" : "MISSING"));
                    if (rx == null) {
                        dropGatt();
                        repo.setState(Repo.State.STOPPED,
                                "This radio has no KISS service. Is H9 OPS firmware P2 installed?");
                        wanted = false;
                        stopForeground(true);
                        stopSelf();
                        return;
                    }
                    tx = s.getCharacteristic(KISS_TX);
                    g.setCharacteristicNotification(rx, true);
                    BluetoothGattDescriptor cccd = rx.getDescriptor(CCCD);
                    if (cccd == null) { subscribed(); return; }
                    byte[] on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, on);
                    } else {
                        cccd.setValue(on);
                        g.writeDescriptor(cccd);
                    }
                });
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                main.post(() -> {
                    if (attempt != myAttempt) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) subscribed();
                    else {
                        dev.log("BLE", "radio refused notifications: " + DevLog.gattStatus(status));
                        dropGatt();
                        waitAndRetry("Radio refused notifications (" + status + ")");
                    }
                });
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                main.post(() -> {
                    if (attempt != myAttempt) return;
                    writing = false;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "write failed " + status);
                        writeFails++;
                        dev.log("KISS", "write to radio failed: " + DevLog.gattStatus(status) + "; dropped "
                                + txQueue.size() + " queued pieces (the message will be retried)");
                        txQueue.clear();   // a partial frame is useless; the message will be retried
                    }
                    writeNext();
                });
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
                main.post(() -> { if (attempt == myAttempt && status == BluetoothGatt.GATT_SUCCESS) rssi(rssi); });
            }

            // Android 13 and later
            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                if (KISS_RX.equals(c.getUuid())) received(myAttempt, value.clone());
            }

            // Android 12 and earlier
            @Override
            @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT >= 33) return;   // handled above
                byte[] v = c.getValue();
                if (v != null && KISS_RX.equals(c.getUuid())) received(myAttempt, v.clone());
            }
        };
    }

    private void subscribed() {
        policy.connected();
        clearSilent();
        readyAt = SystemClock.elapsedRealtime();
        rxFrames = rxBytes = txFrames = writeFails = busyRetries = 0;
        rssiMin = 0; rssiMax = -200; rssiSum = rssiCount = 0; rssiLogged = 0; rssiLoggedAt = 0;
        dev.log("BLE", "CONNECTED to " + label() + ": ready " + (readyAt - tryStart) + " ms after the scan started"
                + (repo.radioPin() != null ? ", radio PIN in use" : ""));
        startRssi(attempt);
        repo.setState(Repo.State.CONNECTED, "Listening on " + label());
        updateNotification(true);
        if (tx != null) {
            repo.setTransmitter(new Repo.Transmitter() {
                @Override public boolean transmit(byte[] ax25) { return RadioService.this.transmit(ax25); }
                @Override public boolean sendCommand(byte[] kiss) { return RadioService.this.sendRaw(kiss); }
            });
            askWatchdog(attempt);
            infoSeen = false;
            askInfo(attempt);
        }
    }

    /**
     * Asks the radio for its battery and channel settings (P2 firmware), now and every
     * minute. If the first question goes unanswered the firmware is older: stop asking.
     */
    private void askInfo(int myAttempt) {
        if (attempt != myAttempt || readyAt == 0) return;
        infoAnswered = false;
        if (!sendRaw(Kiss.command(RadioInfo.QUERY))) return;
        timers.postDelayed(() -> {
            if (attempt != myAttempt || readyAt == 0 || infoAnswered) return;
            if (!infoSeen) {
                dev.log("INFO", "no answer: the radio's firmware is older than P2");
                repo.onRadioInfoMissing();
            }
        }, 6000);
        timers.postDelayed(() -> { if (infoSeen) askInfo(myAttempt); }, INFO_EVERY_MS);
    }

    /** With the developer log on, asks the radio for its Bluetooth watchdog counters (P2 firmware). */
    private void askWatchdog(int myAttempt) {
        if (!dev.on()) return;
        wdAnswered = false;
        if (!sendRaw(Kiss.command(WatchdogReport.QUERY))) return;
        timers.postDelayed(() -> {
            if (attempt == myAttempt && readyAt != 0 && !wdAnswered)
                dev.log("WATCHDOG", "no answer: the radio's firmware is older than P2");
        }, 5000);
    }

    /**
     * Queues one AX.25 frame for the radio to transmit (KISS-wrapped, in
     * 20-byte writes). With a radio PIN set, the unlock command goes first:
     * the P2 firmware sends a frame only right after a valid unlock.
     */
    private boolean transmit(byte[] ax25) {
        if (gatt == null || tx == null || repo.state() != Repo.State.CONNECTED) {
            dev.log("KISS", "frame not sent: radio not connected");
            return false;
        }
        byte[] unlock = repo.unlockFrame();
        if (unlock != null) queue(unlock);
        byte[] k = Kiss.encode(ax25);
        queue(k);
        txFrames++;
        dev.log("KISS", "frame to radio: " + ax25.length + " bytes, " + ((k.length + CHUNK - 1) / CHUNK) + " writes"
                + (unlock != null ? " after the PIN unlock" : ""));
        writeNext();
        return true;
    }

    /** Queues a raw KISS frame (radio PIN commands). */
    private boolean sendRaw(byte[] kiss) {
        if (gatt == null || tx == null || repo.state() != Repo.State.CONNECTED) return false;
        queue(kiss);
        writeNext();
        return true;
    }

    private void queue(byte[] k) {
        for (int i = 0; i < k.length; i += CHUNK) {
            txQueue.add(java.util.Arrays.copyOfRange(k, i, Math.min(k.length, i + CHUNK)));
        }
    }

    @SuppressWarnings("deprecation")
    private void writeNext() {
        if (writing || gatt == null || tx == null) return;
        byte[] piece = txQueue.poll();
        if (piece == null) return;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = gatt.writeCharacteristic(tx, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        } else {
            tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            tx.setValue(piece);
            ok = gatt.writeCharacteristic(tx);
        }
        if (ok) {
            writing = true;
        } else {
            // the stack is busy; try this piece again shortly
            busyRetries++;
            if (busyRetries % 20 == 1) dev.log("KISS", "Bluetooth busy, waiting to write (" + busyRetries + " waits this connection)");
            txQueue.addFirst(piece);
            timers.postDelayed(this::writeNext, 50);
        }
    }

    private void received(int myAttempt, byte[] value) {
        main.post(() -> {
            if (attempt != myAttempt) return;
            long now = System.currentTimeMillis();
            rxBytes += value.length;
            for (Kiss.Frame f : kiss.feedFrames(value)) {
                if (f.command == 0) {
                    rxFrames++;
                    hourPackets++;
                    repo.addFrame(now, f.payload);
                } else if (f.command == 6 && f.payload.length == 5 && f.payload[0] == 'H'
                        && f.payload[1] == '9' && f.payload[2] == 'P' && f.payload[3] == 'R') {
                    repo.onRadioReply((char) f.payload[4]);   // reply from the radio's PIN code (P2)
                } else if (f.command == 6) {
                    WatchdogReport w = WatchdogReport.parse(f.payload);   // P2
                    if (w != null) {
                        wdAnswered = true;
                        dev.log("WATCHDOG", w.describe());
                    }
                    RadioInfo info = RadioInfo.parse(f.payload);          // P2
                    if (info != null) {
                        infoAnswered = infoSeen = true;
                        repo.onRadioInfo(info);
                    }
                }
            }
        });
    }

    private void dropGatt() {
        rssiTimer.removeCallbacksAndMessages(null);
        rssiOn = false;
        if (readyAt != 0) {
            countConnected(SystemClock.elapsedRealtime());
            hourDrops++;
        }
        readyAt = 0;
        repo.setTransmitter(null);
        txQueue.clear();
        writing = false;
        tx = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
            gatt = null;
        }
    }

    private void waitAndRetry(String why) {
        stopScan();
        if (!wanted) return;
        attempt++;
        long wait = policy.nextDelay();
        dev.log("BLE", "retrying in " + (wait / 1000) + " s: " + why);
        repo.setState(Repo.State.WAITING, why + ". Trying again in "
                + (wait >= 60_000 ? (wait / 60_000) + " min" : (wait / 1000) + " s"));
        timers.removeCallbacksAndMessages(null);
        timers.postDelayed(this::begin, wait);
    }

    private void shutDown(String why) {
        if (dev != null && (wanted || gatt != null))
            dev.log("BLE", "stopped: " + why + (readyAt != 0 ? " · " + sessionSummary() : ""));
        wanted = false;
        cancelAutoAlarm();
        attempt++;
        timers.removeCallbacksAndMessages(null);
        stopScan();
        dropGatt();
        clearSilent();
        stopStats();
        if (repo != null) repo.setState(Repo.State.STOPPED, why);
    }

    // ---- developer log: signal strength and connection numbers ----

    private void startRssi(int myAttempt) {
        rssiTimer.removeCallbacksAndMessages(null);
        rssiOn = false;
        if (!dev.on()) return;
        rssiOn = true;
        rssiDue = SystemClock.elapsedRealtime() + 2000;
        rssiTimer.postDelayed(new Runnable() {
            @Override public void run() {
                if (attempt != myAttempt || gatt == null || !dev.on()) { rssiOn = false; return; }
                long now = SystemClock.elapsedRealtime();
                noteLate("signal check", now - rssiDue);
                try { gatt.readRemoteRssi(); } catch (RuntimeException ignored) { }
                rssiDue = now + RSSI_MS;
                rssiTimer.postDelayed(this, RSSI_MS);
            }
        }, 2000);
    }

    /** Logs the signal when it is first read, every 5 minutes, on a 6 dB change, and on going weak or recovering. */
    private void rssi(int dbm) {
        boolean weakNow = dbm < WEAK_DBM, weakBefore = rssiCount > 0 && rssiLast < WEAK_DBM;
        rssiLast = dbm;
        rssiMin = rssiCount == 0 ? dbm : Math.min(rssiMin, dbm);
        rssiMax = Math.max(rssiMax, dbm);
        rssiSum += dbm;
        rssiCount++;
        long now = SystemClock.elapsedRealtime();
        String why = null;
        if (rssiCount == 1) why = "first reading";
        else if (weakNow && !weakBefore) why = "WEAK (below " + WEAK_DBM + " dBm)";
        else if (!weakNow && weakBefore) why = "recovered";
        else if (Math.abs(dbm - rssiLogged) >= 6) why = "changed";
        else if (now - rssiLoggedAt >= RSSI_LOG_EVERY_MS) why = "periodic";
        if (why == null) return;
        rssiLogged = dbm;
        rssiLoggedAt = now;
        dev.log("BLE", "signal " + dbm + " dBm (" + why + ")");
    }

    private String sessionSummary() {
        long up = readyAt == 0 ? 0 : SystemClock.elapsedRealtime() - readyAt;
        return "connected " + (up / 60_000) + " min " + (up / 1000 % 60) + " s, " + rxFrames + " packets in ("
                + rxBytes + " bytes), " + txFrames + " frames out, " + writeFails + " failed writes"
                + (rssiCount > 0 ? ", signal last " + rssiLast + " / min " + rssiMin + " / max " + rssiMax
                + " / avg " + (rssiSum / rssiCount) + " dBm" : "");
    }

    private String label() {
        String n = repo.radioName();
        return n != null ? n : repo.radioAddress();
    }

    // ---- developer log: an hourly report, late timers, Doze ----
    // Packets, connected time and battery each hour, so a day out shows where the
    // battery goes and whether packets are lost. Android delays timers while the
    // phone is idle (Doze); how late they run is logged too.

    private static final long STATS_MS = 60 * 60_000, LATE_MS = 60_000, LATE_LOG_MS = 120_000;
    private final Handler statsTimer = new Handler(Looper.getMainLooper());
    private boolean statsOn;
    private long hourStart, statsDue, connMark, hourConnMs, rssiDue, worstLate, lastLateLog;
    private int hourPackets, hourDrops, lateCount, battStart = -1;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (dev == null || !dev.on() || !wanted) return;
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(i.getAction())) {
                dev.log("PWR", "battery saver " + (powerSave() ? "on" : "off"));
            } else {
                dev.log("PWR", idle() ? "phone went idle (Doze): timers and scans may run late"
                        : "phone left Doze");
            }
        }
    };

    private void startStats() {
        statsTimer.removeCallbacksAndMessages(null);
        statsOn = false;
        if (dev == null || !dev.on()) return;
        statsOn = true;
        long now = SystemClock.elapsedRealtime();
        hourStart = connMark = now;
        hourConnMs = 0;
        hourPackets = hourDrops = lateCount = 0;
        worstLate = 0;
        battStart = battery();
        dev.log("STAT", "hourly reports on · " + powerText());
        scheduleStats(now);
    }

    private void scheduleStats(long now) {
        statsDue = now + STATS_MS;
        statsTimer.postDelayed(() -> {
            long t = SystemClock.elapsedRealtime();
            noteLate("hourly report", t - statsDue);
            report(t);
            if (statsOn && dev.on()) scheduleStats(t); else statsOn = false;
        }, STATS_MS);
    }

    private void stopStats() {
        if (statsOn && dev != null && SystemClock.elapsedRealtime() - hourStart >= 5 * 60_000)
            report(SystemClock.elapsedRealtime());
        statsTimer.removeCallbacksAndMessages(null);
        statsOn = false;
    }

    private void countConnected(long now) {
        if (readyAt != 0) hourConnMs += Math.max(0, now - Math.max(readyAt, connMark));
        connMark = now;
    }

    private void report(long now) {
        countConnected(now);
        long span = now - hourStart;
        int batt = battery();
        String b = batt < 0 ? "battery ?" : "battery " + batt + "%"
                + (battStart >= 0 ? " (" + signed(batt - battStart) + "%)" : "");
        dev.log("STAT", "last " + (span / 60_000) + " min: " + hourPackets + " packets in, connected "
                + (hourConnMs / 60_000) + " min, " + hourDrops + " disconnects · " + b + " · " + powerText()
                + (lateCount > 0 ? " · timers late " + lateCount + "x, worst " + mmss(worstLate) : ""));
        hourStart = connMark = now;
        hourConnMs = 0;
        hourPackets = hourDrops = lateCount = 0;
        worstLate = 0;
        battStart = batt;
    }

    private void noteLate(String what, long late) {
        if (late < LATE_MS) return;
        lateCount++;
        worstLate = Math.max(worstLate, late);
        long now = SystemClock.elapsedRealtime();
        if (late >= LATE_LOG_MS && now - lastLateLog >= 10 * 60_000) {
            lastLateLog = now;
            dev.log("PWR", what + " ran " + mmss(late) + " late (" + (idle() ? "phone in Doze" : "phone not in Doze") + ")");
        }
    }

    private int battery() {
        Intent b;
        try { b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }
        catch (RuntimeException e) { return -1; }
        if (b == null) return -1;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : Math.round(100f * level / scale);
    }

    private boolean charging() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (RuntimeException e) { return false; }
    }

    private boolean idle() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && Build.VERSION.SDK_INT >= 23 && pm.isDeviceIdleMode();
    }

    private boolean powerSave() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    private String powerText() {
        return (charging() ? "charging" : "on battery") + ", Doze " + (idle() ? "yes" : "no")
                + ", battery saver " + (powerSave() ? "on" : "off");
    }

    private static String signed(int n) { return n > 0 ? "+" + n : Integer.toString(n); }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + " min " + (s % 60) + " s" : s + " s";
    }

    // ---- "radio isn't advertising" alert ----

    private void showSilent() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE);
        String text = label() + " isn't advertising. Turn the radio's Bluetooth off and on (or restart the radio)."
                + " H9 OPS keeps looking every " + (ReconnectPolicy.SILENT_MS / 60_000) + " min.";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ALERTS) : new Notification.Builder(this);
        try {
            nm.notify(NOTIFY_SILENT, b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Radio not found")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    private void clearSilent() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFY_SILENT);
    }

    // ---- notification ----

    @Override
    public void onRepoChanged() {
        updateNotification(false);
        if (dev.on() && readyAt != 0 && !rssiOn) startRssi(attempt);   // developer mode just turned on
        if (dev.on() && wanted && !statsOn) startStats();
        if (wanted) scheduleAuto();
    }

    // ---- timed position sharing ----
    // A Handler timer stops while the phone sleeps, so a system alarm (allowed in
    // Doze) wakes us; a wake lock keeps the phone awake only while getting a fix.

    private static final String ACTION_AUTO = "io.github.deadly447.h9ops.AUTO_POSITION";
    private static final long FIX_TIMEOUT_MS = 90_000;
    private boolean locationType, fixing, receiverOn;
    private long alarmAt;

    private final BroadcastReceiver autoReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            alarmAt = 0;
            runAuto();
        }
    };

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private PendingIntent autoIntent() {
        Intent i = new Intent(ACTION_AUTO).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, 9, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void scheduleAuto() {
        if (!repo.auto.any()) { cancelAutoAlarm(); return; }
        if (!receiverOn) {
            IntentFilter f = new IntentFilter(ACTION_AUTO);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(autoReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(autoReceiver, f);
            receiverOn = true;
        }
        long wait = repo.auto.msUntilNext(System.currentTimeMillis());
        if (wait == 0) { main.post(this::runAuto); return; }
        long at = SystemClock.elapsedRealtime() + wait;
        if (alarmAt != 0 && Math.abs(alarmAt - at) < 30_000) return;   // already set for about then
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am == null) return;
        alarmAt = at;
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, autoIntent());
        else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, autoIntent());
    }

    private void cancelAutoAlarm() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) am.cancel(autoIntent());
        alarmAt = 0;
        if (receiverOn) {
            try { unregisterReceiver(autoReceiver); } catch (IllegalArgumentException ignored) { }
            receiverOn = false;
        }
    }

    private void runAuto() {
        if (fixing || !wanted) return;
        long now = System.currentTimeMillis();
        List<AutoPosition.Plan> ended = new ArrayList<>();
        List<AutoPosition.Plan> due = repo.auto.due(now, ended);
        for (AutoPosition.Plan p : ended) repo.autoEnded(p);
        if (due.isEmpty()) { scheduleAuto(); return; }
        if (!hasLocation()) {
            for (AutoPosition.Plan p : due) repo.autoFailed(p, "Location permission is off");
            scheduleAuto();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !locationType) {
            for (AutoPosition.Plan p : due) repo.autoFailed(p, "Paused: open H9 OPS once to allow it in the background");
            scheduleAuto();
            return;
        }
        fixing = true;
        // smart plans compare one check with the next, so they need a fresh fix, not a cached one
        long maxAge = 120_000;
        for (AutoPosition.Plan p : due) if (p.smart != null) maxAge = 15_000;
        dev.log("POS", "timed position due for " + due.size() + " station(s), getting a GPS fix");
        final long fixStart = SystemClock.elapsedRealtime();
        PowerManager pm = getSystemService(PowerManager.class);
        PowerManager.WakeLock wl = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "h9ops:fix");
        if (wl != null) wl.acquire(FIX_TIMEOUT_MS + 5_000);
        getFix(maxAge, l -> {
            fixing = false;
            if (wl != null && wl.isHeld()) wl.release();
            long took = SystemClock.elapsedRealtime() - fixStart;
            if (l == null) dev.log("POS", "no GPS fix after " + (took / 1000) + " s");
            else dev.log("POS", "GPS fix in " + (took / 1000) + " s: " + l.getProvider()
                    + (l.hasAccuracy() ? ", accuracy ±" + Math.round(l.getAccuracy()) + " m" : "")
                    + ", fix age " + Math.max(0, (System.currentTimeMillis() - l.getTime()) / 1000) + " s");
            for (AutoPosition.Plan p : due) {
                if (repo.auto.get(p.peer) != p) continue;   // stopped meanwhile
                if (l == null) repo.autoFailed(p, "No GPS fix");
                else if (p.smart != null) repo.smartFix(p, l.getLatitude(), l.getLongitude(), l.getTime(),
                        l.hasSpeed() ? l.getSpeed() : Double.NaN, l.hasBearing() ? l.getBearing() : Double.NaN);
                else repo.sendAuto(p, l.getLatitude(), l.getLongitude(), l.getTime());
            }
            scheduleAuto();
        });
    }

    private interface FixDone { void done(Location l); }

    /** One GPS fix (one up to {@code maxAgeMs} old if there is), or null after 90 s. */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void getFix(long maxAgeMs, FixDone cb) {
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { cb.done(null); return; }
        try {
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < maxAgeMs) { cb.done(last); return; }
            final boolean[] done = {false};
            final CancellationSignal cancel = new CancellationSignal();
            final LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location l) { finish(l); }
                @Override public void onStatusChanged(String p, int st, Bundle b) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
                void finish(Location l) {
                    if (done[0]) return;
                    done[0] = true;
                    lm.removeUpdates(this);
                    cb.done(l);
                }
            };
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, cancel, getMainExecutor(), l -> {
                    if (done[0]) return;
                    done[0] = true;
                    cb.done(l);
                });
            } else {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            }
            main.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                cancel.cancel();
                lm.removeUpdates(listener);
                cb.done(null);
            }, FIX_TIMEOUT_MS);
        } catch (SecurityException e) {
            cb.done(null);
        }
    }

    private void updateNotification(boolean now) {
        if (!wanted) return;
        long t = System.currentTimeMillis();
        if (!now && t - lastNotifyUpdate < 5000) return;
        lastNotifyUpdate = t;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID, notification());
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RadioService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int n = repo.store.size();
        String text = n + (n == 1 ? " station" : " stations") + " heard";
        List<AutoPosition.Plan> plans = repo.auto.plans();
        if (!plans.isEmpty()) {
            // always visible while your position is being sent on a timer
            StringBuilder sb = new StringBuilder("📍 Sending your position to ");
            for (int i = 0; i < plans.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(plans.get(i).peer).append(' ').append(plans.get(i).label());
            }
            text = sb + " · " + text;
        }
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(repo.stateText())
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
}
