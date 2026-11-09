package com.cameron.lumix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.getcapacitor.JSObject;

/** Foreground service (type dataSync) that keeps the process alive while SaveQueue downloads, with a progress notification. */
public class SaveService extends Service {
    static final String CHANNEL = "saving";
    static final String ACTION_CANCEL = "com.cameron.lumix.CANCEL";
    static final int ID_PROGRESS = 41, ID_DONE = 42;
    private long lastNotify;

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Saving", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress while saving photos and videos from the camera");
        ch.setShowBadge(false);
        nm().createNotificationChannel(ch);
        SaveQueue.onChange = this::update;
        SaveQueue.onFinish = this::finished;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) SaveQueue.cancel();
        // Must be called promptly after startForegroundService(), whatever the queue is doing.
        ServiceCompat.startForeground(this, ID_PROGRESS, progress(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (!SaveQueue.isRunning()) finished();
        return START_NOT_STICKY;
    }

    /** Android 15+: a dataSync service is cut off after ~6 hours. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        SaveQueue.cancel();
    }

    @Override
    public void onDestroy() {
        if (SaveQueue.onChange != null) { SaveQueue.onChange = null; SaveQueue.onFinish = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String mb(long b) {
        return b >= 1000000000L ? String.format("%.1f GB", b / 1e9) : b >= 1000000L ? (b / 1000000L) + " MB" : (b / 1000L) + " KB";
    }

    private Notification progress() {
        JSObject s = SaveQueue.snapshot();
        int total = s.optInt("total"), done = s.optInt("done");
        long bytes = s.optLong("bytes"), totalBytes = s.optLong("totalBytes");
        String cur = "";
        try {
            org.json.JSONArray a = s.getJSONArray("current");
            if (a.length() > 0) cur = a.getString(0) + (a.length() > 1 ? " +" + (a.length() - 1) : "");
        } catch (Exception ignored) {
        }
        String title = total == 0 ? "Saving" : "Saving " + Math.min(done + 1, total) + " of " + total + " · " + mb(bytes) + " of " + mb(totalBytes);
        PendingIntent cancel = PendingIntent.getForegroundService(this, 1,
            new Intent(this, SaveService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(s.optBoolean("waiting") ? "Waiting for the camera…" : cur)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel);
        if (totalBytes > 0) b.setProgress(1000, (int) (1000L * Math.min(bytes, totalBytes) / totalBytes), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void update() {
        long now = SystemClock.uptimeMillis();
        if (now - lastNotify < 700 || !SaveQueue.isRunning()) return;
        lastNotify = now;
        try { nm().notify(ID_PROGRESS, progress()); } catch (Exception ignored) { }
    }

    private void finished() {
        if (SaveQueue.isRunning()) return;
        int failed = SaveQueue.failedCount(), done = SaveQueue.doneCount();
        boolean cancelled = SaveQueue.wasCancelled();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (cancelled || done == 0) {
            nm().cancel(ID_DONE);
        } else {
            int ok = done - failed;
            String text = failed == 0 ? "Saved " + ok + (ok == 1 ? " file" : " files") : "Saved " + ok + ", " + failed + " failed";
            Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(text)
                .setContentText(failed == 0 ? "In your gallery, " + SaveQueue.folder().replaceAll("/$", "") : "Open Lumix to retry")
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
            try { nm().notify(ID_DONE, n); } catch (Exception ignored) { }
        }
        stopSelf();
    }
}
