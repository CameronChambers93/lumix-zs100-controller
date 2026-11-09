package com.cameron.lumix;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service (type location) that logs the phone's position for tagging photos: a fix about every 2 minutes
 * and whenever it moves 100 m, into LocationLog. LocationManager only, no Play Services.
 */
public class LocationService extends Service {
    static final String CHANNEL = "location";
    static final String ACTION_STOP = "com.cameron.lumix.STOP_LOCATION";
    static final int ID = 43;
    static volatile boolean running;
    static volatile Runnable onChange;   // the plugin: tell the page the count or state changed
    private final List<LocationListener> listeners = new ArrayList<>();

    static boolean enabled(Context c) { return c.getSharedPreferences("location", MODE_PRIVATE).getBoolean("enabled", false); }

    static void setEnabled(Context c, boolean on) { c.getSharedPreferences("location", MODE_PRIVATE).edit().putBoolean("enabled", on).apply(); }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getForegroundService(this, 2,
            new Intent(this, LocationService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording location for photos")
            .setContentText("Saved photos get the place they were taken")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stop)
            .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Location", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while the phone records its location for tagging photos");
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must be called promptly after startForegroundService(), whatever happens next.
        ServiceCompat.startForeground(this, ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        startUpdates();
        return START_NOT_STICKY;
    }

    private void startUpdates() {
        if (running) return;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            Log.w(LocationLog.TAG, "no location permission");
            setEnabled(this, false);
            stopSelf();
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String[] providers = fine ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER} : new String[]{LocationManager.NETWORK_PROVIDER};
        for (String p : providers) {
            if (!lm.getAllProviders().contains(p)) continue;
            // One request for the 2-minute cadence, one that reports movement of 100 m or more.
            long[] mins = {120000L, 10000L};
            float[] dists = {0f, 100f};
            for (int i = 0; i < 2; i++) {
                LocationListener l = new LocationListener() {
                    @Override public void onLocationChanged(Location loc) { record(loc); }
                    @Override public void onProviderEnabled(String s) { }
                    @Override public void onProviderDisabled(String s) { }
                    @Override @SuppressWarnings("deprecation") public void onStatusChanged(String s, int st, Bundle b) { }
                };
                try {
                    lm.requestLocationUpdates(p, mins[i], dists[i], l);
                    listeners.add(l);
                } catch (Exception e) {
                    Log.w(LocationLog.TAG, "request " + p + ": " + e);
                }
            }
        }
        running = true;
        Log.i(LocationLog.TAG, "recording started (" + listeners.size() + " requests)");
        try {   // seed with the last known position so a photo taken right away has something
            Location best = null;
            for (String p : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && System.currentTimeMillis() - l.getTime() < 120000 && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) record(best);
        } catch (SecurityException ignored) {
        }
        changed();
    }

    private void record(Location loc) {
        long t = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        boolean stored = LocationLog.add(this, t, loc.getLatitude(), loc.getLongitude(), loc.hasAltitude() ? loc.getAltitude() : Double.NaN, loc.getAccuracy());
        Log.i(LocationLog.TAG, (stored ? "fix " : "skipped ") + loc.getProvider() + " " + loc.getLatitude() + "," + loc.getLongitude());
        if (stored) changed();
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    @Override
    public void onDestroy() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        for (LocationListener l : listeners) {
            try { lm.removeUpdates(l); } catch (Exception ignored) { }
        }
        listeners.clear();
        running = false;
        changed();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
