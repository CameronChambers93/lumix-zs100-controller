package com.cameron.lumix;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** The phone's location fixes, one line each ("ms,lat,lon,alt,accuracy") in app storage; used to tag saved photos. */
final class LocationLog {
    static final String TAG = "LumixLoc";
    static final long KEEP_MS = 60L * 24 * 3600 * 1000;   // fixes older than 60 days are dropped when the log loads

    static final class Fix {
        final long t;
        final double lat, lon, alt;
        final float acc;
        Fix(long t, double lat, double lon, double alt, float acc) { this.t = t; this.lat = lat; this.lon = lon; this.alt = alt; this.acc = acc; }
    }

    private static final Object L = new Object();
    private static List<Fix> fixes;   // sorted by time
    private static File file;

    private static void load(Context ctx) {
        if (fixes != null) return;
        file = new File(ctx.getApplicationContext().getFilesDir(), "locations.csv");
        fixes = new ArrayList<>();
        if (!file.exists()) return;
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        boolean dropped = false;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",", -1);
                if (p.length < 3) continue;
                try {
                    long t = Long.parseLong(p[0]);
                    if (t < cutoff) { dropped = true; continue; }
                    fixes.add(new Fix(t, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        p.length > 3 && !p[3].isEmpty() ? Double.parseDouble(p[3]) : Double.NaN, p.length > 4 && !p[4].isEmpty() ? Float.parseFloat(p[4]) : 0));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load: " + e);
        }
        fixes.sort((a, b) -> Long.compare(a.t, b.t));
        if (dropped) rewrite();
    }

    private static void rewrite() {
        try (FileWriter w = new FileWriter(file, false)) {
            for (Fix f : fixes) w.write(line(f));
        } catch (Exception e) {
            Log.w(TAG, "rewrite: " + e);
        }
    }

    private static String line(Fix f) {
        return f.t + "," + f.lat + "," + f.lon + "," + (Double.isNaN(f.alt) ? "" : String.valueOf(f.alt)) + "," + f.acc + "\n";
    }

    /** Stores a fix unless the last one is under 60 s old and under 100 m away. Returns true if stored. */
    static boolean add(Context ctx, long t, double lat, double lon, double alt, float acc) {
        synchronized (L) {
            load(ctx);
            if (!fixes.isEmpty()) {
                Fix last = fixes.get(fixes.size() - 1);
                float[] d = new float[1];
                Location.distanceBetween(last.lat, last.lon, lat, lon, d);
                if (t >= last.t && t - last.t < 60000 && d[0] < 100) return false;
            }
            Fix f = new Fix(t, lat, lon, alt, acc);
            int i = fixes.size();
            while (i > 0 && fixes.get(i - 1).t > t) i--;
            fixes.add(i, f);
            try (FileWriter w = new FileWriter(file, true)) { w.write(line(f)); } catch (Exception e) { Log.w(TAG, "append: " + e); }
            return true;
        }
    }

    static int count(Context ctx) { synchronized (L) { load(ctx); return fixes.size(); } }

    static long lastTime(Context ctx) { synchronized (L) { load(ctx); return fixes.isEmpty() ? 0 : fixes.get(fixes.size() - 1).t; } }

    static void clear(Context ctx) {
        synchronized (L) {
            load(ctx);
            fixes.clear();
            if (file.exists()) file.delete();
        }
    }

    /** The fix closest in time to t within +-windowMs, or null. */
    static Fix nearest(Context ctx, long t, long windowMs) {
        synchronized (L) {
            load(ctx);
            Fix best = null;
            for (Fix f : fixes) {
                long d = Math.abs(f.t - t);
                if (d <= windowMs && (best == null || d < Math.abs(best.t - t))) best = f;
            }
            return best;
        }
    }
}
