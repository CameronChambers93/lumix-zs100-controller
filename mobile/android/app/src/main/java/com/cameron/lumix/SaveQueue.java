package com.cameron.lumix;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The save queue: downloads camera files into MediaStore from a pool of worker threads. It lives in static state so it
 * outlives the activity; SaveService (a foreground service) keeps the process alive and shows the notification.
 * A file that fails mid-download keeps its pending MediaStore row and is resumed with a Range request next time.
 */
final class SaveQueue {
    static final String TAG = "LumixSave";

    interface Sink {
        void queue(JSObject snapshot);
        void saved(JSObject file);
    }

    static final class Job {
        final String url, name, mime;
        final long size, date;
        Job(String url, String name, String mime, long size, long date) {
            this.url = url; this.name = name; this.mime = mime; this.size = size; this.date = date;
        }
    }

    /** A non-network failure (HTTP error, wrong size): retrying won't need the camera to come back first. */
    static final class HttpFail extends IOException {
        HttpFail(String m) { super(m); }
    }

    static volatile Sink sink;            // the plugin (JS events); null when the activity is gone
    static volatile Runnable onChange;    // the service: refresh the notification
    static volatile Runnable onFinish;    // the service: post the summary and stop

    private static final Object L = new Object();
    private static final Object CAMLOCK = new Object();
    private static Context app;
    private static boolean running, cancelled, lost, waiting, camOk;
    private static int total, done, runId, workersLeft;
    private static long doneBytes, totalBytes;
    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    private static final Map<String, AtomicLong> active = new LinkedHashMap<>();
    private static final List<String[]> failed = new ArrayList<>();
    private static final Set<String> pending = new HashSet<>();   // queued or in flight
    private static final Set<String> runNames = new HashSet<>();
    private static String camIp, folder, appId;
    private static long okAt;
    private static Network boundTo;
    private static PowerManager.WakeLock wake;
    private static WifiManager.WifiLock wlock;

    private static final Object EM = new Object();
    private static long lastEmit;
    private static boolean scheduled;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static boolean isRunning() { synchronized (L) { return running; } }

    static JSObject snapshot() {
        synchronized (L) {
            JSObject o = new JSObject();
            o.put("running", running);
            o.put("total", total);
            o.put("done", done);
            long b = doneBytes;
            JSArray cur = new JSArray();
            for (Map.Entry<String, AtomicLong> e : active.entrySet()) { b += e.getValue().get(); cur.put(e.getKey()); }
            o.put("bytes", b);
            o.put("totalBytes", totalBytes);
            o.put("current", cur);
            JSArray f = new JSArray();
            for (String[] x : failed) {
                JSObject j = new JSObject();
                j.put("name", x[0]);
                j.put("error", x[1]);
                f.put(j);
            }
            o.put("failed", f);
            o.put("waiting", waiting);
            o.put("cancelled", cancelled);
            return o;
        }
    }

    static int failedCount() { synchronized (L) { return failed.size(); } }
    static int doneCount() { synchronized (L) { return done; } }
    static boolean wasCancelled() { synchronized (L) { return cancelled; } }
    static String folder() { synchronized (L) { return folder == null ? "DCIM/Lumix/" : folder; } }

    static void enqueue(Context ctx, List<Job> jobs, String ip, String fol, int par, String id) {
        boolean start = false;
        int workers = 1;
        synchronized (L) {
            app = ctx.getApplicationContext();
            if (!running) {
                cancelled = lost = waiting = false;
                total = done = 0;
                doneBytes = totalBytes = 0;
                failed.clear(); queue.clear(); active.clear(); pending.clear(); runNames.clear();
                camIp = ip; folder = fol; appId = id;
                running = true; start = true;
                workers = Math.max(1, Math.min(3, par));
                workersLeft = workers;
                playChecked = false;
                runId++;
            }
            for (Job j : jobs) {
                if (pending.contains(j.name)) continue;
                for (int i = failed.size() - 1; i >= 0; i--) {
                    if (failed.get(i)[0].equals(j.name)) { failed.remove(i); total--; }
                }
                queue.add(j); pending.add(j.name); runNames.add(j.name);
                total++; totalBytes += j.size;
            }
        }
        if (start) {
            acquireLocks();
            final int myRun = runId;
            Thread k = new Thread(() -> keepalive(myRun), "save-keepalive");
            k.setDaemon(true);
            k.start();
            for (int i = 0; i < workers; i++) {
                Thread t = new Thread(SaveQueue::worker, "save-worker-" + i);
                t.start();
            }
        }
        touch(true);
    }

    static void cancel() {
        synchronized (L) {
            if (!running) return;
            cancelled = true;
            for (Job j : queue) pending.remove(j.name);
            queue.clear();
        }
        touch(true);
    }

    // ---- workers

    private static boolean playChecked;

    /** Once per run, before the first download: make sure the camera is in playback mode. */
    private static void checkPlayMode() {
        synchronized (CAMLOCK) {
            if (playChecked) return;
            playChecked = true;
            try { ensurePlay(readCam0("http://" + camIp + "/cam.cgi?mode=getstate", 3000)); } catch (Exception e) { Log.w(TAG, "mode check: " + e); }
        }
    }

    private static void worker() {
        checkPlayMode();
        for (;;) {
            Job j;
            AtomicLong prog;
            synchronized (L) {
                if (cancelled) break;
                j = queue.poll();
                if (j == null) break;
                prog = new AtomicLong(0);
                active.put(j.name, prog);
            }
            String err = process(j, prog);
            synchronized (L) {
                active.remove(j.name);
                pending.remove(j.name);
                done++;
                doneBytes += j.size;
                if (err != null && !cancelled) failed.add(new String[]{j.name, err});
            }
            touch(false);
        }
        boolean last;
        synchronized (L) { last = --workersLeft == 0; }
        if (last) finish();
    }

    private static String process(Job j, AtomicLong prog) {
        String err = "Failed";
        for (int attempt = 0; attempt < 3; attempt++) {
            synchronized (L) {
                if (cancelled) return null;
                if (lost) return "Lost the camera";
            }
            try {
                download(j, prog);
                return null;
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(TAG, j.name + " attempt " + (attempt + 1) + " failed: " + err);
                synchronized (L) { if (cancelled) return null; }
                if (attempt == 2) break;
                if (!(e instanceof HttpFail)) {   // the link dropped: wait up to a minute for the camera to answer again
                    if (!waitForCamera()) {
                        synchronized (L) { lost = true; }
                        return err + " (camera not reachable)";
                    }
                }
                if (!sleep(attempt == 0 ? 2000 : 5000)) return null;
            }
        }
        return err;
    }

    private static boolean sleep(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            synchronized (L) { if (cancelled) return false; }
            try { Thread.sleep(100); } catch (InterruptedException e) { return false; }
        }
        return true;
    }

    private static boolean waitForCamera() {
        long t0 = SystemClock.uptimeMillis();
        synchronized (L) { waiting = true; okAt = 0; camOk = false; }
        touch(true);
        try {
            while (SystemClock.uptimeMillis() - t0 < 60000) {
                synchronized (L) {
                    if (cancelled) return false;
                    if (camOk && okAt > 0) return true;
                }
                try { Thread.sleep(300); } catch (InterruptedException e) { return false; }
            }
            return false;
        } finally {
            synchronized (L) { waiting = false; }
            touch(true);
        }
    }

    // ---- one file, with resume

    private static android.content.SharedPreferences resumePrefs() {
        return app.getSharedPreferences("resume", Context.MODE_PRIVATE);
    }

    private static void rememberRow(String name, Uri row, long bytes) {
        try {
            resumePrefs().edit().putString(name, new JSONObject().put("uri", row.toString()).put("bytes", bytes).toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void dropRow(ContentResolver cr, String name, Uri row) {
        resumePrefs().edit().remove(name).apply();
        if (row != null) {
            try { cr.delete(row, null, null); } catch (Exception ignored) { }
        }
    }

    private static void download(Job j, AtomicLong prog) throws IOException {
        ContentResolver cr = app.getContentResolver();
        Uri row = null;
        long have = 0;
        String saved = resumePrefs().getString(j.name, null);
        if (saved != null) {
            try {
                Uri u = Uri.parse(new JSONObject(saved).getString("uri"));
                try (ParcelFileDescriptor p = cr.openFileDescriptor(u, "r")) {
                    have = p.getStatSize();
                    row = u;
                }
            } catch (Exception e) {
                resumePrefs().edit().remove(j.name).apply();
                row = null; have = 0;
            }
        }
        if (row != null && (have <= 0 || (j.size > 0 && have >= j.size))) have = 0;
        if (row == null) {
            Uri coll = j.mime.startsWith("video/")
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, j.name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, j.mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            if (j.date > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, j.date);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            row = cr.insert(coll, v);
            if (row == null) throw new HttpFail("couldn't create the gallery file");
            have = 0;
        }
        rememberRow(j.name, row, have);
        HttpURLConnection c = null;
        try {
            int code;
            synchronized (CAMLOCK) {   // the camera garbles overlapping requests: never start one while the keepalive is talking
                c = openCam(j.url, 30000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                code = c.getResponseCode();
            }
            boolean append = false;
            if (have > 0 && code == 206) {
                String cr2 = c.getHeaderField("Content-Range");
                if (cr2 == null || !cr2.startsWith("bytes " + have + "-")) throw new HttpFail("bad Content-Range " + cr2);
                append = true;
                Log.i(TAG, "resume " + j.name + " from " + have + " of " + j.size + ": HTTP 206 " + cr2);
            } else if (code == 200) {
                if (have > 0) Log.i(TAG, "resume " + j.name + ": camera answered 200, starting over");
                have = 0;
            } else {
                throw new HttpFail("camera returned HTTP " + code);
            }
            long total = have, lastNote = have;
            prog.set(have);
            byte[] buf = new byte[256 * 1024];
            try (InputStream in = c.getInputStream(); OutputStream out = cr.openOutputStream(row, append ? "wa" : "wt")) {
                if (out == null) throw new HttpFail("couldn't open the gallery file");
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (L) { if (cancelled) throw new InterruptedIOException("cancelled"); }
                    out.write(buf, 0, n);
                    total += n;
                    prog.set(total);
                    if (total - lastNote >= 256 * 1024) { lastNote = total; touch(false); }
                }
            } catch (IOException e) {
                rememberRow(j.name, row, total);   // keep the row: the next attempt resumes
                throw e;
            }
            if (j.size > 0 && total != j.size) {
                dropRow(cr, j.name, row);
                throw new HttpFail("got " + total + " of " + j.size + " bytes");
            }
            // Remember the camera's size: tagging below makes the file a little bigger, and "already saved" compares sizes.
            app.getSharedPreferences("camsize", Context.MODE_PRIVATE).edit().putLong(j.name, total).apply();
            tagLocation(cr, row, j);
            ContentValues fin = new ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (j.date > 0) fin.put(MediaStore.MediaColumns.DATE_TAKEN, j.date);
            cr.update(row, fin, null, null);
            resumePrefs().edit().remove(j.name).apply();
            // MediaProvider ignores DATE_TAKEN set by apps, so keep the camera's date ourselves (listSaved reads it).
            if (j.date > 0) app.getSharedPreferences("taken", Context.MODE_PRIVATE).edit().putLong(j.name, j.date).apply();
            Sink s = sink;
            if (s != null) {
                JSObject f = new JSObject();
                f.put("name", j.name);
                f.put("size", total);
                f.put("uri", row.toString());
                s.saved(f);
            }
        } catch (HttpFail e) {
            // Keep a row that already holds data (a retry can resume it); an empty or bad one is dropped.
            if (!(have > 0 && e.getMessage() != null && e.getMessage().startsWith("camera returned"))) dropRow(cr, j.name, row);
            throw e;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- GPS tags from the location log (the camera has no GPS)

    static final long TAG_WINDOW_MS = 10 * 60 * 1000L;

    /** Writes GPS EXIF into a JPEG whose bytes are all here (row still pending) from the fix nearest to its DateTimeOriginal. Never fails the save. */
    private static void tagLocation(ContentResolver cr, Uri row, Job j) {
        if (!"image/jpeg".equals(j.mime) || LocationLog.count(app) == 0) return;
        try (ParcelFileDescriptor p = cr.openFileDescriptor(row, "rw")) {
            if (p == null) return;
            androidx.exifinterface.media.ExifInterface x = new androidx.exifinterface.media.ExifInterface(p.getFileDescriptor());
            if (x.getLatLong() != null) return;   // already has a position
            long t = 0;
            String d = x.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL);
            if (d != null) {
                try {
                    java.util.Date dt = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(d);   // camera time = phone local time
                    if (dt != null) t = dt.getTime();
                } catch (java.text.ParseException ignored) {
                }
            }
            if (t <= 0) t = j.date;
            if (t <= 0) return;
            LocationLog.Fix f = LocationLog.nearest(app, t, TAG_WINDOW_MS);
            if (f == null) { Log.i(TAG, j.name + ": no location fix within 10 min of " + d); return; }
            x.setLatLong(f.lat, f.lon);
            if (!Double.isNaN(f.alt)) x.setAltitude(f.alt);
            x.saveAttributes();
            Log.i(TAG, j.name + ": tagged " + f.lat + "," + f.lon + " (fix " + (Math.abs(f.t - t) / 1000) + " s from the photo)");
        } catch (Exception e) {
            Log.w(TAG, j.name + ": location tag failed: " + e);
        }
    }

    // ---- finishing

    private static void finish() {
        ContentResolver cr = app.getContentResolver();
        boolean wasCancelled;
        synchronized (L) { wasCancelled = cancelled; }
        if (wasCancelled) {   // no partial rows left behind
            for (String name : runNames) {
                String saved = resumePrefs().getString(name, null);
                if (saved == null) continue;
                try { dropRow(cr, name, Uri.parse(new JSONObject(saved).getString("uri"))); } catch (Exception e) { dropRow(cr, name, null); }
            }
        }
        releaseLocks();
        synchronized (L) { running = false; waiting = false; }
        touch(true);
        Runnable r = onFinish;
        if (r != null) r.run();
    }

    // ---- keepalive: the camera drops us if nobody talks to it, and the page's JS timers are throttled in the background

    private static void keepalive(int myRun) {
        for (;;) {
            boolean w;
            synchronized (L) {
                if (!running || runId != myRun) return;
                w = waiting;
            }
            try {
                String body = readCam("http://" + camIp + "/cam.cgi?mode=getstate", 3000);
                boolean ok = body.contains("<result>ok</result>");
                if (ok) ensurePlay(body);
                if (!ok) {
                    String r = readCam("http://" + camIp + "/cam.cgi?mode=accctrl&type=req_acc&value=" + appId + "&value2=lumix-phone", 8000);
                    ok = r.trim().startsWith("ok");
                    Log.i(TAG, "re-paired: " + r.trim());
                }
                synchronized (L) { camOk = ok; if (ok) okAt = SystemClock.uptimeMillis(); }
            } catch (Exception e) {
                if (w) Log.i(TAG, "keepalive: " + e);
                synchronized (L) { camOk = false; }
            }
            try { Thread.sleep(w ? 1500 : 5000); } catch (InterruptedException e) { return; }
        }
    }

    private static String readCam(String url, int timeout) throws IOException {
        synchronized (CAMLOCK) { return readCam0(url, timeout); }
    }

    /**
     * The camera is sometimes found in rec mode (it returns to its live view by itself after a client leaves) and media
     * access should happen in playback mode. playmode is non-destructive: send it when getstate says anything but play.
     * Returns the getstate body as it was before the switch.
     */
    private static String ensurePlay(String body) {
        if (body == null || !body.contains("<cammode>") || body.contains("<cammode>play</cammode>")) return body;
        try {
            Log.i(TAG, "camera was in rec mode: sending playmode");
            readCam("http://" + camIp + "/cam.cgi?mode=camcmd&value=playmode", 6000);
            Thread.sleep(2000);
        } catch (Exception e) {
            Log.w(TAG, "playmode: " + e);
        }
        return body;
    }

    private static String readCam0(String url, int timeout) throws IOException {
        HttpURLConnection c = openCam(url, timeout);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection openCam(String url, int timeout) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = LumixNativePlugin.wifiNetwork(cm);   // the camera's own Wi-Fi if joined, else the first Wi-Fi
        if (net != null && !net.equals(boundTo)) {   // Wi-Fi came back as a new network: follow it
            boundTo = net;
            try { cm.bindProcessToNetwork(net); } catch (Exception ignored) { }
            Log.i(TAG, "bound to Wi-Fi network " + net);
        }
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
        c.setConnectTimeout(Math.min(timeout, 10000));
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        c.setRequestProperty("Connection", "close");   // the camera's tiny server stalls on reused connections
        return c;
    }

    // ---- wake and Wi-Fi locks while a run is going

    @SuppressWarnings("deprecation")
    private static void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumix:save");
            wake.setReferenceCounted(false);
            wake.acquire(6 * 60 * 60 * 1000L);
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            wlock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumix:save");
            wlock.setReferenceCounted(false);
            wlock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "locks: " + e);
        }
    }

    private static void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wlock != null && wlock.isHeld()) wlock.release(); } catch (Exception ignored) { }
    }

    // ---- change notifications, at most 4 a second

    static void touch(boolean force) {
        long now = SystemClock.uptimeMillis();
        synchronized (EM) {
            if (!force && now - lastEmit < 250) {
                if (!scheduled) {
                    scheduled = true;
                    main.postDelayed(() -> { synchronized (EM) { scheduled = false; } touch(true); }, 250 - (now - lastEmit));
                }
                return;
            }
            lastEmit = now;
        }
        Sink s = sink;
        if (s != null) s.queue(snapshot());
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
