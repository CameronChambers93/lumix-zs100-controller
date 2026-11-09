package com.cameron.lumix;

import android.content.ContentUris;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Size;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native side of the Lumix app. The camera's HTTP server sends no CORS headers, and on the camera's own hotspot
 * (no internet) Android would route traffic over mobile data, so all camera traffic goes through here, pinned to
 * the Wi-Fi network. Downloads stream straight into the gallery (DCIM/Lumix) without passing through JS.
 */
@CapacitorPlugin(name = "LumixNative", permissions = {
    @com.getcapacitor.annotation.Permission(strings = {"android.permission.POST_NOTIFICATIONS"}, alias = "notifications"),
    @com.getcapacitor.annotation.Permission(strings = {"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"}, alias = "location")
})
public class LumixNativePlugin extends Plugin {
    static final String DIR = "DCIM/Lumix/";
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ConnectivityManager cm() {
        return (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** The camera's own Wi-Fi when joined by joinCameraWifi (preferred), else the first Wi-Fi network. */
    static volatile Network camNet;

    static Network wifiNetwork(ConnectivityManager cm) {
        Network j = camNet;
        if (j != null && cm.getNetworkCapabilities(j) != null) return j;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private Network wifi() {
        return wifiNetwork(cm());
    }

    private HttpURLConnection open(String url, int timeout) throws IOException {
        Network net = wifi();
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        c.setRequestProperty("Connection", "close");   // the camera's tiny server stalls on reused connections
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Route the whole app (including the WebView's image loads) over Wi-Fi, even when it has no internet. */
    @PluginMethod
    public void bindWifi(PluginCall call) {
        Network n = wifi();
        boolean ok = cm().bindProcessToNetwork(n);
        JSObject r = new JSObject();
        r.put("wifi", n != null);
        r.put("bound", ok && n != null);
        call.resolve(r);
    }

    @PluginMethod
    public void httpGet(PluginCall call) {
        request(call, "GET");
    }

    @PluginMethod
    public void httpPost(PluginCall call) {
        request(call, "POST");
    }

    private void request(PluginCall call, String method) {
        String url = call.getString("url");
        int timeout = call.getInt("timeout", 8000);
        String body = call.getString("body");
        JSObject headers = call.getObject("headers", new JSObject());
        pool.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = open(url, timeout);
                c.setRequestMethod(method);
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    c.setRequestProperty(k, headers.getString(k));
                }
                if (body != null) {
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(b.length);
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(b);
                    }
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                JSObject r = new JSObject();
                r.put("status", status);
                r.put("body", in == null ? "" : readAll(in));
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.toString());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private android.content.SharedPreferences datePrefs() {
        return getContext().getSharedPreferences("taken", Context.MODE_PRIVATE);
    }

    /** When a saved file was taken: our record from saving, else EXIF DateTimeOriginal (cached), else 0. */
    private long takenFor(String name, Uri uri, boolean image) {
        android.content.SharedPreferences sp = datePrefs();
        long t = sp.getLong(name, 0);
        if (t > 0 || !image) return t;
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            String d = new androidx.exifinterface.media.ExifInterface(in).getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL);
            if (d != null) {
                java.util.Date dt = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(d);
                if (dt != null) t = dt.getTime();
            }
        } catch (Exception ignored) {
        }
        sp.edit().putLong(name, Math.max(t, 0)).apply();
        return t;
    }

    /**
     * Files this app has saved (scoped storage shows an app its own files without a permission), from every folder
     * in the optional "folders" array (default DCIM/Lumix/). Pending rows are excluded by MediaStore.
     */
    @PluginMethod
    public void listSaved(PluginCall call) {
        JSArray fa = call.getArray("folders");
        pool.execute(() -> {
            JSArray files = new JSArray();
            List<String> folders = new ArrayList<>();
            try {
                if (fa != null) {
                    for (int i = 0; i < fa.length(); i++) {
                        String f = fa.getString(i);
                        if (f != null && !f.isEmpty()) folders.add(f.endsWith("/") ? f : f + "/");
                    }
                }
            } catch (Exception ignored) {
            }
            if (folders.isEmpty()) folders.add(DIR);
            StringBuilder sel = new StringBuilder();
            for (int i = 0; i < folders.size(); i++) {
                sel.append(i > 0 ? " OR " : "").append(MediaStore.MediaColumns.RELATIVE_PATH).append("=?");
            }
            String[] proj = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION};
            Uri[] collections = {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            };
            final android.content.SharedPreferences camsize = getContext().getSharedPreferences("camsize", Context.MODE_PRIVATE);
            try {
                for (Uri coll : collections) {
                    try (Cursor cur = getContext().getContentResolver().query(coll, proj,
                            sel.toString(), folders.toArray(new String[0]), null)) {
                        while (cur != null && cur.moveToNext()) {
                            JSObject o = new JSObject();
                            o.put("name", cur.getString(0));
                            long fsize = cur.getLong(1);
                            long csize = camsize.getLong(cur.getString(0), 0);
                            // Tagging with GPS makes a saved JPEG a little bigger than on the camera: report the camera's size so "saved" still matches.
                            o.put("size", csize > 0 && fsize >= csize && fsize - csize <= 65536 ? csize : fsize);
                            o.put("fileSize", fsize);
                            o.put("uri", ContentUris.withAppendedId(coll, cur.getLong(2)).toString());
                            o.put("mime", cur.getString(3));
                            long taken = cur.isNull(4) ? 0 : cur.getLong(4);
                            Uri fu = ContentUris.withAppendedId(coll, cur.getLong(2));
                            if (taken <= 0) taken = takenFor(cur.getString(0), fu, coll.equals(collections[0]));
                            o.put("dateTaken", taken > 0 ? taken : cur.getLong(5) * 1000);
                            o.put("width", cur.getInt(6));
                            o.put("height", cur.getInt(7));
                            o.put("duration", cur.isNull(8) ? 0 : cur.getLong(8));
                            files.put(o);
                        }
                    }
                }
            } catch (Exception e) {
                call.reject(e.toString());
                return;
            }
            JSObject r = new JSObject();
            r.put("files", files);
            call.resolve(r);
        });
    }

    // ---- gallery thumbnails (saved files) and camera thumbnails/dates

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Bitmap decodeScaled(ContentResolver cr, Uri uri, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int shorter = Math.min(o.outWidth, o.outHeight), sample = 1;
            while (shorter / (sample * 2) >= size) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = cr.openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            float scale = (float) size / Math.min(b.getWidth(), b.getHeight());
            Matrix m = new Matrix();
            int orient = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = cr.openInputStream(uri)) {
                orient = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {
            }
            if (scale < 1) m.postScale(scale, scale);
            if (orient == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
            else if (orient == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
            else if (orient == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Exception e) {
            return null;
        }
    }

    private String thumbFor(String uri, int size, File dir) {
        try {
            File f = new File(dir, md5(uri) + "-" + size + ".jpg");
            if (f.length() > 0) return f.getAbsolutePath();
            ContentResolver cr = getContext().getContentResolver();
            String type = cr.getType(Uri.parse(uri));
            Bitmap b = null;
            // loadThumbnail returns the camera's tiny embedded EXIF thumbnail (160x120, letterboxed) for these
            // JPEGs, so photos are decoded and scaled here (shorter side = size); videos use loadThumbnail.
            if (type != null && type.startsWith("image/")) b = decodeScaled(cr, Uri.parse(uri), size);
            if (b == null) b = cr.loadThumbnail(Uri.parse(uri), new Size(size, size), null);
            File tmp = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, 85, o);
            }
            if (!tmp.renameTo(f)) tmp.delete();
            return f.length() > 0 ? f.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** thumbs({uris, size}) -> {thumbs: {uri: cached jpeg path}}; uris that have no thumbnail are left out. */
    @PluginMethod
    public void thumbs(PluginCall call) {
        JSArray arr = call.getArray("uris");
        int size = call.getInt("size", 256);
        pool.execute(() -> {
            File dir = new File(getContext().getCacheDir(), "thumbs");
            dir.mkdirs();
            List<String> uris = new ArrayList<>();
            List<Future<String>> fs = new ArrayList<>();
            try {
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    final String u = arr.getString(i);
                    uris.add(u);
                    fs.add(pool.submit(() -> thumbFor(u, size, dir)));
                }
            } catch (Exception ignored) {
            }
            JSObject out = new JSObject();
            for (int i = 0; i < fs.size(); i++) {
                try {
                    String p = fs.get(i).get();
                    if (p != null) out.put(uris.get(i), p);
                } catch (Exception ignored) {
                }
            }
            JSObject r = new JSObject();
            r.put("thumbs", out);
            call.resolve(r);
        });
    }

    // Camera requests for thumbnails and dates share two slots: its web server is tiny.
    private final Semaphore camSem = new Semaphore(2, true);
    private volatile int datesGen = 0;

    /**
     * camThumb({url, id, cacheOnly}) -> {path, date}. Caches the camera's thumbnail at cacheDir/camthumbs/<id>.jpg;
     * "date" is the X-REC_DATE_TIME header (camera local time, no zone) or null when served from the cache.
     */
    @PluginMethod
    public void camThumb(PluginCall call) {
        String url = call.getString("url");
        String id = call.getString("id", "");
        boolean cacheOnly = call.getBoolean("cacheOnly", false);
        pool.execute(() -> {
            File dir = new File(getContext().getCacheDir(), "camthumbs");
            dir.mkdirs();
            File f = new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg");
            JSObject r = new JSObject();
            if (f.length() > 0) {
                r.put("path", f.getAbsolutePath());
                r.put("date", JSONObject.NULL);
                call.resolve(r);
                return;
            }
            if (cacheOnly || url == null) {
                call.reject("not cached");
                return;
            }
            HttpURLConnection c = null;
            boolean held = false;
            try {
                camSem.acquire();
                held = true;
                // The camera sometimes stalls on a request; a fresh one right after usually works.
                for (int attempt = 0; ; attempt++) {
                    try {
                        c = open(url, 4000);
                        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                        break;
                    } catch (IOException e) {
                        if (c != null) c.disconnect();
                        if (attempt >= 2) throw e;
                    }
                }
                String date = c.getHeaderField("X-REC_DATE_TIME");
                File tmp = new File(dir, f.getName() + ".tmp");
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete();
                    throw new IOException("couldn't cache the thumbnail");
                }
                r.put("path", f.getAbsolutePath());
                r.put("date", date == null ? JSONObject.NULL : date);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.toString());
            } finally {
                if (c != null) c.disconnect();
                if (held) camSem.release();
            }
        });
    }

    /**
     * camDates({items:[{id,url}]}): one at a time, HEAD each url (Range GET if HEAD gives no date) and emit a
     * "date" event {id, date}. A newer call or stopDates() ends the run; so do 3 failures in a row (camera gone).
     */
    @PluginMethod
    public void camDates(PluginCall call) {
        JSArray arr = call.getArray("items");
        final int gen = ++datesGen;
        pool.execute(() -> {
            int found = 0, fails = 0;
            try {
                for (int i = 0; arr != null && i < arr.length() && gen == datesGen && fails < 5; i++) {
                    JSONObject it = arr.getJSONObject(i);
                    String date = null;
                    boolean held = false;
                    try {
                        camSem.acquire();
                        held = true;
                        if (gen != datesGen) break;
                        date = dateHeader(it.getString("url"), "HEAD");
                        if (date == null) date = dateHeader(it.getString("url"), "GET");
                        fails = 0;
                    } catch (Exception e) {
                        fails++;
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    } finally {
                        if (held) camSem.release();
                    }
                    if (date != null && gen == datesGen) {
                        JSObject p = new JSObject();
                        p.put("id", it.getString("id"));
                        p.put("date", date);
                        notifyListeners("date", p);
                        found++;
                    }
                }
            } catch (Exception ignored) {
            }
            JSObject r = new JSObject();
            r.put("found", found);
            call.resolve(r);
        });
    }

    private String dateHeader(String url, String method) throws IOException {
        HttpURLConnection c = open(url, 4000);
        try {
            c.setRequestMethod(method);
            if (method.equals("GET")) c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return null;
            return c.getHeaderField("X-REC_DATE_TIME");
        } finally {
            c.disconnect();
        }
    }

    @PluginMethod
    public void stopDates(PluginCall call) {
        datesGen++;
        call.resolve();
    }

    // ---- viewer: EXIF, share, open in another app, back button

    private static void put(JSObject o, String k, Object v) {
        if (v != null) o.put(k, v);
    }

    private static JSObject exifToJs(androidx.exifinterface.media.ExifInterface x) {
        JSObject o = new JSObject();
        String model = x.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL);
        put(o, "model", model == null || model.trim().isEmpty() ? null : model.trim());
        String lens = x.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL);
        put(o, "lens", lens == null || lens.trim().isEmpty() ? null : lens.trim());
        double d = x.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, 0);
        if (d > 0) o.put("focal", d);
        int f35 = x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0);
        if (f35 > 0) o.put("focal35", f35);
        d = x.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0);
        if (d > 0) o.put("aperture", d);
        d = x.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, 0);
        if (d > 0) o.put("shutter", d);
        int iso = x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0);
        if (iso > 0) o.put("iso", iso);
        if (x.hasAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_BIAS_VALUE)) {
            o.put("expComp", x.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0));
        }
        if (x.hasAttribute(androidx.exifinterface.media.ExifInterface.TAG_FLASH)) {
            o.put("flash", (x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_FLASH, 0) & 1) == 1);
        }
        int w = x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PIXEL_X_DIMENSION, 0);
        int h = x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PIXEL_Y_DIMENSION, 0);
        if (w <= 0 || h <= 0) {
            w = x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0);
            h = x.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0);
        }
        if (w > 0 && h > 0) {
            o.put("width", w);
            o.put("height", h);
        }
        put(o, "taken", x.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL));
        double[] ll = x.getLatLong();
        if (ll != null) {
            o.put("lat", ll[0]);
            o.put("lon", ll[1]);
        }
        return o;
    }

    /** exif({uri}) for a saved photo -> {model, lens, focal, focal35, aperture, shutter, iso, expComp, flash, width, height, taken, lat, lon}. */
    @PluginMethod
    public void exif(PluginCall call) {
        String uri = call.getString("uri");
        pool.execute(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                call.resolve(exifToJs(new androidx.exifinterface.media.ExifInterface(in)));
            } catch (Exception e) {
                call.reject(e.toString());
            }
        });
    }

    /** exifRemote({url}): streams the camera's original only as far as the EXIF header, then drops the connection. */
    @PluginMethod
    public void exifRemote(PluginCall call) {
        String url = call.getString("url");
        pool.execute(() -> {
            HttpURLConnection c = null;
            boolean held = false;
            try {
                camSem.acquire();
                held = true;
                for (int attempt = 0; ; attempt++) {   // the camera sometimes stalls on a request
                    try {
                        c = open(url, 5000);
                        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                        break;
                    } catch (IOException e) {
                        if (c != null) c.disconnect();
                        if (attempt >= 2) throw e;
                    }
                }
                try (InputStream in = c.getInputStream()) {
                    JSObject r = exifToJs(new androidx.exifinterface.media.ExifInterface(in));
                    String rec = c.getHeaderField("X-REC_DATE_TIME");
                    if (rec != null && !r.has("taken")) r.put("taken", rec);
                    call.resolve(r);
                }
            } catch (Exception e) {
                call.reject(e.toString());
            } finally {
                if (c != null) c.disconnect();
                if (held) camSem.release();
            }
        });
    }

    /** share({uris}): system chooser (ACTION_SEND / ACTION_SEND_MULTIPLE) for saved files. */
    @PluginMethod
    public void share(PluginCall call) {
        JSArray arr = call.getArray("uris");
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            String mime = null;
            ContentResolver cr = getContext().getContentResolver();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                Uri u = Uri.parse(arr.getString(i));
                uris.add(u);
                String t = cr.getType(u);
                if (t == null) t = "*/*";
                if (mime == null) mime = t;
                else if (!mime.equals(t)) mime = mime.split("/")[0].equals(t.split("/")[0]) ? mime.split("/")[0] + "/*" : "*/*";
            }
            if (uris.isEmpty()) {
                call.reject("nothing to share");
                return;
            }
            android.content.Intent i = new android.content.Intent(uris.size() == 1
                ? android.content.Intent.ACTION_SEND : android.content.Intent.ACTION_SEND_MULTIPLE);
            i.setType(mime);
            if (uris.size() == 1) i.putExtra(android.content.Intent.EXTRA_STREAM, uris.get(0));
            else i.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris);
            android.content.ClipData cd = android.content.ClipData.newRawUri(null, uris.get(0));
            for (int k = 1; k < uris.size(); k++) cd.addItem(new android.content.ClipData.Item(uris.get(k)));
            i.setClipData(cd);
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.Intent ch = android.content.Intent.createChooser(i, "Share");
            getActivity().runOnUiThread(() -> getActivity().startActivity(ch));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** openExternal({uri, mime}): ACTION_VIEW chooser (Photos, Gallery, editors). */
    @PluginMethod
    public void openExternal(PluginCall call) {
        try {
            Uri u = Uri.parse(call.getString("uri"));
            String mime = call.getString("mime");
            if (mime == null) mime = getContext().getContentResolver().getType(u);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(u, mime == null ? "*/*" : mime);
            i.setClipData(android.content.ClipData.newRawUri(null, u));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.Intent ch = android.content.Intent.createChooser(i, "Open with");
            getActivity().runOnUiThread(() -> {
                try {
                    getActivity().startActivity(ch);
                    call.resolve();
                } catch (Exception e) {
                    call.reject("No app can open this");
                }
            });
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** The system Back gesture/button is sent to the page as a "back" event; with no page listener the app just backgrounds. */
    @Override
    public void load() {
        SaveQueue.sink = new SaveQueue.Sink() {
            @Override public void queue(JSObject snapshot) { notifyListeners("queue", snapshot); }
            @Override public void saved(JSObject file) { notifyListeners("saved", file); }
        };
        LocationService.onChange = () -> notifyListeners("location", locationStatus());
        getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (hasListeners("back")) notifyListeners("back", new JSObject());
                else getActivity().moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        SaveQueue.sink = null;
        LocationService.onChange = null;
        datesGen++;
        stopLive();
    }

    @PluginMethod
    public void exitApp(PluginCall call) {
        getActivity().runOnUiThread(() -> getActivity().moveTaskToBack(true));
        call.resolve();
    }

    @PluginMethod
    public void cancelSave(PluginCall call) {
        SaveQueue.cancel();
        call.resolve();
    }

    // ---- background save queue (SaveQueue + SaveService)

    /** enqueue({items:[{url,name,size,mime,date}], camIp, appId, folder, parallel}) -> queue snapshot. */
    @PluginMethod
    public void enqueue(PluginCall call) {
        JSArray arr = call.getArray("items");
        try {
            List<SaveQueue.Job> jobs = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                jobs.add(new SaveQueue.Job(o.getString("url"), o.getString("name"), o.optString("mime", "image/jpeg"),
                    o.optLong("size", -1), o.optLong("date", 0)));
            }
            String folder = call.getString("folder", DIR);
            if (!folder.endsWith("/")) folder += "/";
            SaveQueue.enqueue(getContext(), jobs, call.getString("camIp"), folder, call.getInt("parallel", 1), call.getString("appId", ""));
            try {
                androidx.core.content.ContextCompat.startForegroundService(getContext(), new android.content.Intent(getContext(), SaveService.class));
            } catch (Exception e) {   // e.g. not allowed to start from the background
                SaveQueue.cancel();
                call.reject("Couldn't start the save service: " + e);
                return;
            }
            call.resolve(SaveQueue.snapshot());
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** queueState() -> {running,total,done,failed:[{name,error}],bytes,totalBytes,current:[names],waiting,cancelled}. */
    @PluginMethod
    public void queueState(PluginCall call) {
        call.resolve(SaveQueue.snapshot());
    }

    /** freeSpace() -> {free, total} bytes on the primary external volume. */
    @PluginMethod
    public void freeSpace(PluginCall call) {
        android.os.StatFs st = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
        JSObject r = new JSObject();
        r.put("free", st.getAvailableBytes());
        r.put("total", st.getTotalBytes());
        call.resolve(r);
    }

    /** Asks for the notification permission (Android 13+); resolves {granted} either way, saving works without it. */
    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (android.os.Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == com.getcapacitor.PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationsResult");
    }

    @com.getcapacitor.annotation.PermissionCallback
    private void notificationsResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notifications") == com.getcapacitor.PermissionState.GRANTED);
        call.resolve(r);
    }

    // ---- settings support: app info, system bars, camera discovery, joining the camera's hotspot

    /** appInfo() -> {versionName, versionCode, sdk, device, night (the system is in dark mode)}. */
    @PluginMethod
    public void appInfo(PluginCall call) {
        JSObject r = new JSObject();
        try {
            android.content.pm.PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            r.put("versionName", pi.versionName);
            r.put("versionCode", android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            r.put("versionName", "?");
        }
        r.put("sdk", android.os.Build.VERSION.SDK_INT);
        r.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        int ui = getContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        r.put("night", ui == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        call.resolve(r);
    }

    /** setSystemBars({color:"#111214", dark:true}): status and navigation bar colour; dark = dark page, so light icons. */
    @PluginMethod
    public void setSystemBars(PluginCall call) {
        String color = call.getString("color", "#f6f5f2");
        boolean dark = call.getBoolean("dark", false);
        getActivity().runOnUiThread(() -> {
            try {
                android.view.Window w = getActivity().getWindow();
                int c = android.graphics.Color.parseColor(color);
                w.setStatusBarColor(c);
                w.setNavigationBarColor(c);
                // The app draws edge to edge (Capacitor 8): the bars are transparent and show the window background,
                // which follows the system theme, so paint it with the page colour as well.
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(c));
                w.getDecorView().setBackgroundColor(c);
                androidx.core.view.WindowInsetsControllerCompat ic = androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
                ic.setAppearanceLightStatusBars(!dark);
                ic.setAppearanceLightNavigationBars(!dark);
            } catch (Exception ignored) {
            }
        });
        call.resolve();
    }

    private static final String SSDP_SEARCH = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n"
        + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n";

    /** "ok" for a camera answering getstate, "busy" for a 503 from a host that also accepts on :60606, else null. */
    private static String probeCamera(Network net, String host, int timeout) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + host + "/cam.cgi?mode=getstate");
            c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            if (code == 200) {
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[2048];
                    int n = in.read(b);
                    return n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).contains("<camrply") ? "ok" : null;
                }
            }
            if (code != 503) return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
        try (java.net.Socket s = net != null ? net.getSocketFactory().createSocket() : new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, 60606), timeout);
            return "busy";
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern SSDP_LOC = java.util.regex.Pattern.compile("^LOCATION:\\s*http://([\\d.]+):(\\d+)(\\S*)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE);

    /** Reads SSDP replies until `until`; adds the IPs of Panasonic-looking ones (LOCATION port 60606 or SERVER Panasonic). */
    private static void collectSsdp(java.net.DatagramSocket sock, long until, java.util.Set<String> out) {
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < until) {
            try {
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(p);
                String t = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = SSDP_LOC.matcher(t);
                boolean cam = t.toLowerCase(java.util.Locale.ROOT).contains("panasonic") || (m.find() && "60606".equals(m.group(2)));
                if (cam) out.add(p.getAddress().getHostAddress());
            } catch (java.net.SocketTimeoutException e) {
                // keep waiting until the deadline
            } catch (Exception e) {
                return;
            }
        }
    }

    private static JSObject camEntry(String ip, String state) {
        JSObject o = new JSObject();
        o.put("ip", ip);
        o.put("name", "Lumix camera");
        o.put("state", state);
        return o;
    }

    /**
     * discover({timeout, subnets}) -> {cameras:[{ip,name,state}], method, hotspot, ms}. Multicast SSDP first (up to
     * `timeout` ms); with no usable reply a sweep of the Wi-Fi /24 (plus any extra "subnets", e.g. "10.0.0.") with
     * getstate on port 80 (1.5 s, 32 at a time, stops at the first camera), with a unicast M-SEARCH sent to each
     * host as well. On the camera's hotspot (192.168.54.x) there is nothing to search.
     */
    @PluginMethod
    public void discover(PluginCall call) {
        int timeout = Math.max(500, Math.min(call.getInt("timeout", 3000), 10000));
        JSArray extra = call.getArray("subnets");
        pool.execute(() -> {
            long t0 = System.currentTimeMillis();
            JSObject r = new JSObject();
            JSArray cams = new JSArray();
            android.net.wifi.WifiManager.MulticastLock ml = null;
            java.net.DatagramSocket sock = null;
            try {
                ConnectivityManager cm = cm();
                Network net = wifi();
                r.put("hotspot", false);
                if (net == null) {
                    r.put("method", "no-wifi");
                    r.put("cameras", cams);
                    r.put("ms", 0);
                    call.resolve(r);
                    return;
                }
                String own = null;
                android.net.LinkProperties lp = cm.getLinkProperties(net);
                if (lp != null) {
                    for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof java.net.Inet4Address) own = la.getAddress().getHostAddress();
                    }
                }
                if (own != null && own.startsWith("192.168.54.")) {
                    r.put("hotspot", true);
                    r.put("method", "hotspot");
                    r.put("cameras", cams);
                    r.put("ms", System.currentTimeMillis() - t0);
                    call.resolve(r);
                    return;
                }
                java.util.Set<String> bases = new java.util.LinkedHashSet<>();
                for (int i = 0; extra != null && i < extra.length(); i++) {
                    String b = extra.getString(i);
                    if (b != null && b.matches("\\d+\\.\\d+\\.\\d+\\.(0)?")) bases.add(b.substring(0, b.lastIndexOf('.') + 1));
                }
                // The /24 the camera was last seen in (usually the Wi-Fi's own) is swept first, then the Wi-Fi's.
                if (own != null) bases.add(own.substring(0, own.lastIndexOf('.') + 1));
                // 1. multicast SSDP
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    try {
                        ml = wm.createMulticastLock("lumix-ssdp");
                        ml.setReferenceCounted(false);
                        ml.acquire();
                    } catch (Exception ignored) {
                        ml = null;
                    }
                }
                sock = new java.net.DatagramSocket();
                sock.setSoTimeout(250);
                try { net.bindSocket(sock); } catch (Exception ignored) { }
                byte[] msg = SSDP_SEARCH.getBytes(StandardCharsets.UTF_8);
                for (int k = 0; k < 2; k++) {
                    try {
                        sock.send(new java.net.DatagramPacket(msg, msg.length, java.net.InetAddress.getByName("239.255.255.250"), 1900));
                    } catch (Exception ignored) { }
                }
                java.util.Set<String> ssdp = new java.util.LinkedHashSet<>();
                collectSsdp(sock, System.currentTimeMillis() + timeout, ssdp);
                String method = "none";
                for (String ip : ssdp) {
                    String st = probeCamera(net, ip, 1500);
                    if (st != null) {
                        cams.put(camEntry(ip, st));
                        method = "ssdp";
                    }
                }
                // 2. sweep (only when multicast found nothing)
                if (cams.length() == 0) {
                    java.util.List<String> hosts = new ArrayList<>();
                    for (String b : bases) {
                        for (int n = 1; n < 255; n++) {
                            String h = b + n;
                            if (!h.equals(own)) hosts.add(h);
                        }
                    }
                    for (String h : hosts) {   // unicast M-SEARCH to every host: replies are collected after the sweep
                        try {
                            sock.send(new java.net.DatagramPacket(msg, msg.length, java.net.InetAddress.getByName(h), 1900));
                        } catch (Exception ignored) { }
                    }
                    ExecutorService ex = Executors.newFixedThreadPool(32);
                    java.util.concurrent.CompletionService<String[]> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
                    try {
                        for (String h : hosts) cs.submit(() -> new String[]{h, probeCamera(net, h, 1500)});
                        long deadline = System.currentTimeMillis() + 45000;
                        for (int i = 0; i < hosts.size() && System.currentTimeMillis() < deadline; i++) {
                            Future<String[]> f = cs.poll(Math.max(1, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (f == null) break;
                            String[] res = f.get();
                            if (res[1] != null) {
                                cams.put(camEntry(res[0], res[1]));
                                method = "sweep";
                                break;   // the first camera is enough
                            }
                        }
                    } finally {
                        ex.shutdownNow();
                    }
                    if (cams.length() == 0) {   // M-SEARCH replies from hosts whose web server isn't on port 80
                        java.util.Set<String> late = new java.util.LinkedHashSet<>();
                        collectSsdp(sock, System.currentTimeMillis() + 500, late);
                        for (String ip : late) {
                            String st = probeCamera(net, ip, 1500);
                            if (st != null) {
                                cams.put(camEntry(ip, st));
                                method = "ssdp-unicast";
                            }
                        }
                    }
                }
                r.put("method", method);
                r.put("cameras", cams);
                r.put("ms", System.currentTimeMillis() - t0);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.toString());
            } finally {
                if (sock != null) sock.close();
                if (ml != null && ml.isHeld()) ml.release();
            }
        });
    }

    /** cameraInfo({ip}) -> {name, model, firmware}: unicast SSDP to the camera, then its device description. Empty when it can't be read. */
    @PluginMethod
    public void cameraInfo(PluginCall call) {
        String ip = call.getString("ip");
        pool.execute(() -> {
            JSObject r = new JSObject();
            java.net.DatagramSocket sock = null;
            try {
                Network net = wifi();
                sock = new java.net.DatagramSocket();
                sock.setSoTimeout(400);
                if (net != null) net.bindSocket(sock);
                byte[] msg = SSDP_SEARCH.getBytes(StandardCharsets.UTF_8);
                sock.send(new java.net.DatagramPacket(msg, msg.length, java.net.InetAddress.getByName(ip), 1900));
                String loc = null;
                byte[] buf = new byte[2048];
                long until = System.currentTimeMillis() + 2000;
                while (loc == null && System.currentTimeMillis() < until) {
                    try {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        java.util.regex.Matcher m = SSDP_LOC.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (m.find()) loc = "http://" + ip + ":" + m.group(2) + m.group(3);
                    } catch (java.net.SocketTimeoutException ignored) { }
                }
                if (loc != null) {
                    HttpURLConnection c = open(loc, 4000);
                    try (InputStream in = c.getInputStream()) {
                        String x = readAll(in);
                        put(r, "name", xmlTag(x, "friendlyName"));
                        put(r, "model", xmlTag(x, "modelNumber"));
                        put(r, "firmware", xmlTag(x, "X_FirmVersion"));
                    } finally {
                        c.disconnect();
                    }
                }
            } catch (Exception e) {
                r.put("error", e.toString());
            } finally {
                if (sock != null) sock.close();
            }
            call.resolve(r);
        });
    }

    private static String xmlTag(String xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:\\w+:)?" + name + ">([^<]*)<").matcher(xml);
        return m.find() && !m.group(1).trim().isEmpty() ? m.group(1).trim() : null;
    }

    // Joining the camera's own Wi-Fi (WifiNetworkSpecifier). The name and password live in SharedPreferences.
    private android.content.SharedPreferences wifiPrefs() {
        return getContext().getSharedPreferences("camwifi", Context.MODE_PRIVATE);
    }

    // static: a recreated activity gets a new plugin instance but must still be able to release the request
    private static ConnectivityManager.NetworkCallback joinCb;
    private static volatile boolean joining = false;

    /** getCameraWifi() -> {ssid, hasPassword, joined}. */
    @PluginMethod
    public void getCameraWifi(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ssid", wifiPrefs().getString("ssid", ""));
        r.put("hasPassword", !wifiPrefs().getString("password", "").isEmpty());
        r.put("joined", camNet != null);
        call.resolve(r);
    }

    /** setCameraWifi({ssid, password}): a missing password keeps the stored one; "" clears it. */
    @PluginMethod
    public void setCameraWifi(PluginCall call) {
        android.content.SharedPreferences.Editor e = wifiPrefs().edit();
        if (call.hasOption("ssid")) e.putString("ssid", call.getString("ssid", ""));
        if (call.hasOption("password")) e.putString("password", call.getString("password", ""));
        e.apply();
        call.resolve();
    }

    /**
     * joinCameraWifi({timeout}) -> {joined:true}. Asks Android to connect to the network whose name starts with the saved
     * name (WPA2, saved password). The system shows its own approval the first time. Rejects on timeout, refusal or when
     * no such network is around (onUnavailable). The joined Network is then preferred for all camera traffic.
     */
    @PluginMethod
    public void joinCameraWifi(PluginCall call) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            call.reject("Joining a Wi-Fi network from the app needs Android 10 or newer");
            return;
        }
        String ssid = wifiPrefs().getString("ssid", "").trim();
        String pw = wifiPrefs().getString("password", "");
        if (ssid.isEmpty() || pw.length() < 8) {
            call.reject("Set the camera's Wi-Fi name and password in Settings first");
            return;
        }
        ConnectivityManager cm = cm();
        if (camNet != null && cm.getNetworkCapabilities(camNet) != null) {
            JSObject r = new JSObject();
            r.put("joined", true);
            r.put("already", true);
            call.resolve(r);
            return;
        }
        if (joining) {
            call.reject("Already joining");
            return;
        }
        int timeout = Math.max(5000, call.getInt("timeout", 30000));
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            android.net.wifi.WifiNetworkSpecifier spec = new android.net.wifi.WifiNetworkSpecifier.Builder()
                .setSsidPattern(new android.os.PatternMatcher(ssid, android.os.PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase(pw).build();
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build();
            leaveCamera(false);
            joining = true;
            joinCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    camNet = n;
                    joining = false;
                    try { cm.bindProcessToNetwork(n); } catch (Exception ignored) { }
                    if (done.compareAndSet(false, true)) {
                        JSObject r = new JSObject();
                        r.put("joined", true);
                        call.resolve(r);
                    }
                }
                @Override public void onUnavailable() {
                    joining = false;
                    if (done.compareAndSet(false, true)) call.reject("Couldn't join the camera's Wi-Fi (not found, or not approved)");
                    leaveCamera(false);
                }
                @Override public void onLost(Network n) {
                    if (n.equals(camNet)) {
                        camNet = null;
                        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) { }
                    }
                }
            };
            cm.requestNetwork(req, joinCb, timeout);
        } catch (Exception e) {   // SecurityException (missing permission) and friends
            joining = false;
            joinCb = null;
            if (done.compareAndSet(false, true)) call.reject("Couldn't ask for the camera's Wi-Fi: " + e);
        }
    }

    private void leaveCamera(boolean rebind) {
        ConnectivityManager.NetworkCallback cb = joinCb;
        joinCb = null;
        if (cb != null) {
            try { cm().unregisterNetworkCallback(cb); } catch (Exception ignored) { }
        }
        if (camNet != null) {
            camNet = null;
            if (rebind) {
                try { cm().bindProcessToNetwork(null); } catch (Exception ignored) { }
            }
        }
        joining = false;
    }

    /** leaveCameraWifi(): give the joined camera network back (toggle turned off, or the camera is gone for good). */
    @PluginMethod
    public void leaveCameraWifi(PluginCall call) {
        leaveCamera(true);
        call.resolve();
    }

    /** joinState() -> {joining, joined}. */
    @PluginMethod
    public void joinState(PluginCall call) {
        JSObject r = new JSObject();
        r.put("joining", joining);
        r.put("joined", camNet != null);
        call.resolve(r);
    }

    // ---- location log (LocationService) for tagging saved photos

    private JSObject locationStatus() {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("enabled", LocationService.enabled(c));
        r.put("running", LocationService.running);
        r.put("count", LocationLog.count(c));
        r.put("last", LocationLog.lastTime(c));
        r.put("permission", hasLocationPermission());
        return r;
    }

    private boolean hasLocationPermission() {
        Context c = getContext();
        return c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            || c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** locationStatus() -> {enabled, running, count, last (ms), permission}. */
    @PluginMethod
    public void locationStatus(PluginCall call) {
        pool.execute(() -> call.resolve(locationStatus()));
    }

    /** setLocationRecording({on}) -> status. Turning on asks for the location permission first; rejects "denied" if refused. */
    @PluginMethod
    public void setLocationRecording(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        Context c = getContext();
        if (!on) {
            LocationService.setEnabled(c, false);
            c.stopService(new android.content.Intent(c, LocationService.class));
            call.resolve(locationStatus());
            return;
        }
        if (!hasLocationPermission()) {
            requestPermissionForAlias("location", call, "locationPermResult");
            return;
        }
        startLocation(call);
    }

    @com.getcapacitor.annotation.PermissionCallback
    private void locationPermResult(PluginCall call) {
        if (!hasLocationPermission()) {
            call.reject("denied");
            return;
        }
        startLocation(call);
    }

    private void startLocation(PluginCall call) {
        Context c = getContext();
        LocationService.setEnabled(c, true);
        try {
            androidx.core.content.ContextCompat.startForegroundService(c, new android.content.Intent(c, LocationService.class));
        } catch (Exception e) {
            LocationService.setEnabled(c, false);
            call.reject("Couldn't start location recording: " + e);
            return;
        }
        call.resolve(locationStatus());
    }

    @PluginMethod
    public void clearLocationLog(PluginCall call) {
        pool.execute(() -> {
            LocationLog.clear(getContext());
            call.resolve(locationStatus());
            notifyListeners("location", locationStatus());
        });
    }

    // ---- live view: the camera sends JPEG frames by UDP to the port given in startstream

    private final Object liveLock = new Object();
    private volatile Thread liveThread;
    private volatile java.net.DatagramSocket liveSock;

    private void stopLive() {
        Thread t;
        java.net.DatagramSocket s;
        synchronized (liveLock) { t = liveThread; s = liveSock; liveThread = null; liveSock = null; }
        if (s != null) s.close();
        if (t != null) t.interrupt();
    }

    /** Offset of the JPEG (FF D8 FF) inside a datagram, or -1. The camera puts a short header in front of it. */
    static int jpegStart(byte[] b, int len) {
        int max = Math.min(len - 3, 512);
        for (int i = 0; i < max; i++) {
            if (b[i] == (byte) 0xFF && b[i + 1] == (byte) 0xD8 && b[i + 2] == (byte) 0xFF) return i;
        }
        return -1;
    }

    private void sendStartStream(String ip, int port) {
        try {
            HttpURLConnection c = open("http://" + ip + "/cam.cgi?mode=startstream&value=" + port, 3000);
            try { c.getResponseCode(); readAll(c.getInputStream()); } finally { c.disconnect(); }
        } catch (Exception e) {
            android.util.Log.w("LumixLive", "startstream: " + e);
        }
    }

    /**
     * startLiveView({camIp, port=49199}): opens a UDP socket on the Wi-Fi network, sends startstream, and emits the newest
     * frame as a base64 JPEG in "frame" events ({data, n}) at most 15 times a second. Re-sends startstream when frames stop.
     * Emits "liveview" {state: "frames"|"stalled"} on changes. Resolves once the socket is open.
     */
    @PluginMethod
    public void startLiveView(PluginCall call) {
        final String ip = call.getString("camIp");
        final int port = call.getInt("port", 49199);
        if (ip == null) { call.reject("camIp is required"); return; }
        stopLive();
        final java.net.DatagramSocket sock;
        try {
            sock = new java.net.DatagramSocket(null);
            sock.setReuseAddress(true);
            Network net = wifi();
            if (net != null) net.bindSocket(sock);
            sock.bind(new java.net.InetSocketAddress(port));
            sock.setSoTimeout(300);
        } catch (Exception e) {
            call.reject("Couldn't open the live view port: " + e);
            return;
        }
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65535];
            java.net.DatagramPacket pk = new java.net.DatagramPacket(buf, buf.length);
            long lastFrame = 0, lastEmit = 0, lastStart = 0, frames = 0;
            boolean stalled = true;
            byte[] pending = null;
            while (liveSock == sock && !Thread.currentThread().isInterrupted()) {
                long now = android.os.SystemClock.uptimeMillis();
                if (lastStart == 0 || now - Math.max(lastFrame, lastStart) > 5000) {   // never started, or frames stopped
                    sendStartStream(ip, port);
                    lastStart = android.os.SystemClock.uptimeMillis();
                }
                try {
                    pk.setLength(buf.length);
                    sock.receive(pk);
                    int off = jpegStart(buf, pk.getLength());
                    if (off >= 0) {
                        pending = java.util.Arrays.copyOfRange(buf, off, pk.getLength());
                        lastFrame = android.os.SystemClock.uptimeMillis();
                        frames++;
                        if (stalled) { stalled = false; JSObject o = new JSObject(); o.put("state", "frames"); notifyListeners("liveview", o); }
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    break;
                }
                now = android.os.SystemClock.uptimeMillis();
                if (pending != null && now - lastEmit >= 66) {   // newest frame only, at most 15 a second
                    JSObject o = new JSObject();
                    o.put("data", android.util.Base64.encodeToString(pending, android.util.Base64.NO_WRAP));
                    o.put("n", frames);
                    pending = null;
                    lastEmit = now;
                    notifyListeners("frame", o);
                }
                if (!stalled && now - lastFrame > 3000) { stalled = true; JSObject o = new JSObject(); o.put("state", "stalled"); notifyListeners("liveview", o); }
            }
            android.util.Log.i("LumixLive", "live view loop ended after " + frames + " frames");
        }, "liveview");
        t.setDaemon(true);
        synchronized (liveLock) { liveSock = sock; liveThread = t; }
        t.start();
        JSObject r = new JSObject();
        r.put("port", port);
        call.resolve(r);
    }

    @PluginMethod
    public void stopLiveView(PluginCall call) {
        stopLive();
        call.resolve();
    }
}
