package ru.drivedeck.launcher;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DriveDeck's own diagnostic lines, kept in a private file as well as logcat. DriveDeck runs as the system uid, and
 * logcat's spam filter ("chatty") drops most of that uid's lines; the ring buffer also holds only minutes. The remote
 * log upload takes this file (HeRemote), so a drive check is never lost between ignitions.
 */
final class HeLog {
    private static final long MAX_BYTES = 1_500_000L;
    private static final Object LOCK = new Object();

    private HeLog() { }

    static void i(String msg) { Log.i(HeObd.TAG, msg); append("I", msg); }

    static void w(String msg) { Log.w(HeObd.TAG, msg); append("W", msg); }

    static void w(String msg, Throwable t) { Log.w(HeObd.TAG, msg, t); append("W", msg + ": " + t); }

    private static File file(Context app) { return new File(app.getFilesDir(), "he-obd.log"); }

    private static File sending(Context app) { return new File(app.getFilesDir(), "he-obd.sending"); }

    private static void append(String level, String msg) {
        Context app = HeObd.app();
        if (app == null) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + level + " " + msg + "\n";
        synchronized (LOCK) {
            try {
                File f = file(app);
                if (f.length() > MAX_BYTES) {
                    String keep = read(f);
                    keep = keep.substring(keep.length() / 2);
                    write(f, "…(older lines dropped)\n" + keep, false);
                }
                write(f, line, true);
            } catch (Throwable ignored) { }
        }
    }

    /** The lines not sent yet (an earlier failed send first). Call {@link #sent} after a good upload. */
    static String take(Context app) {
        synchronized (LOCK) {
            try {
                File f = file(app), s = sending(app);
                if (f.exists()) {
                    if (s.exists()) {
                        write(s, read(f), true);
                        f.delete();
                    } else if (!f.renameTo(s)) {
                        return "";
                    }
                }
                return s.exists() ? read(s) : "";
            } catch (Throwable e) {
                return "";
            }
        }
    }

    static void sent(Context app) {
        synchronized (LOCK) { sending(app).delete(); }
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static void write(File f, String s, boolean append) throws Exception {
        FileOutputStream out = new FileOutputStream(f, append);
        try { out.write(s.getBytes("UTF-8")); } finally { out.close(); }
    }
}
