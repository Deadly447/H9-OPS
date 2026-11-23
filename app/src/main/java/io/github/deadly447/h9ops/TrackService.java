package io.github.deadly447.h9ops;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.Locale;
import io.github.deadly447.h9ops.track.Track;

/**
 * Records this phone's GPS track while you're out, with or without the radio
 * connected. Positions the radio hears meanwhile go into the same track (Repo).
 * A foreground service of type location: it keeps getting fixes with the screen
 * off, and its notification shows it's recording and stops it.
 */
@SuppressLint("MissingPermission")   // MainActivity gets location permission before starting it
public class TrackService extends Service {
    public static final String ACTION_START = "io.github.deadly447.h9ops.TRACK_START";
    public static final String ACTION_STOP = "io.github.deadly447.h9ops.TRACK_STOP";
    private static final String CHANNEL = "track";
    private static final int NOTIFY_ID = 3;
    private static final long FIX_MS = 5_000;        // ask for a fix every 5 s
    private static final long NOTIFY_EVERY_MS = 30_000;

    private Repo repo;
    private LocationManager lm;
    private boolean listening;
    private long lastNotify;
    private int fixes, kept;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static void start(Context c, String name) {
        Intent i = new Intent(c, TrackService.class).setAction(ACTION_START).putExtra("name", name);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, TrackService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repo = Repo.get(this);
        lm = getSystemService(LocationManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Track t = repo.tracks.stop(System.currentTimeMillis());
            if (t != null) repo.dev.log("TRK", "stopped: " + summary(t) + ", " + fixes + " fixes, " + kept + " kept");
            finish();
            repo.trackChanged();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !repo.tracks.recording()) {
            String name = intent.getStringExtra("name");
            repo.tracks.start(System.currentTimeMillis(), name);
            repo.dev.log("TRK", "recording started");
        } else if (!repo.tracks.recording()) {
            // restarted by Android with nothing to record
            finish();
            return START_NOT_STICKY;
        } else if (intent == null) {
            repo.dev.log("TRK", "recording resumed after the app was stopped");
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFY_ID, notification());
        } catch (RuntimeException e) {
            // Android refuses a location service started from the background (restart after a kill)
            repo.dev.log("TRK", "Android wouldn't let recording resume in the background: open H9 OPS to continue");
            stopSelf();
            return START_NOT_STICKY;
        }
        listen();
        repo.trackChanged();
        return START_STICKY;
    }

    private void listen() {
        if (listening || lm == null) return;
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            repo.dev.log("TRK", "GPS is off in the phone's settings: no points until it's on");
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_MS, 0, listener, Looper.getMainLooper());
            listening = true;
        } catch (SecurityException e) {
            repo.dev.log("TRK", "no location permission: recording stopped");
            repo.tracks.stop(System.currentTimeMillis());
            finish();
        }
    }

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            fixes++;
            if (repo.tracks.me(l.getTime(), l.getLatitude(), l.getLongitude(),
                    l.hasAltitude() ? l.getAltitude() : Double.NaN, l.hasAccuracy() ? l.getAccuracy() : 0f)) kept++;
            long now = System.currentTimeMillis();
            if (now - lastNotify >= NOTIFY_EVERY_MS) {
                lastNotify = now;
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFY_ID, notification());
                repo.trackChanged();
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { repo.dev.log("TRK", "GPS turned on"); }
        @Override public void onProviderDisabled(String p) { repo.dev.log("TRK", "GPS turned off: no points until it's on again"); }
    };

    private void finish() {
        if (listening && lm != null) lm.removeUpdates(listener);
        listening = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (listening && lm != null) lm.removeUpdates(listener);
        listening = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static String summary(Track t) {
        long m = t.durationMs() / 60_000;
        return String.format(Locale.US, "%.1f mi, %d h %02d min, %d station%s heard", t.distanceM() / 1609.344,
                m / 60, m % 60, t.stations(), t.stations() == 1 ? "" : "s");
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, TrackService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Track t = repo.tracks.current();
        String text = t == null ? "Starting…" : summary(t);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("● Recording track")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
}
