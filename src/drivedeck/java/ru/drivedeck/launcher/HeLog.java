package ru.drivedeck.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
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
    static final String TAG = "DriveDeckHe";
    private static final long MAX_BYTES = 750_000L;      // per file; the full one becomes he-obd.prev (two at most)
    private static final int MAX_SEND_CHARS = 2_000_000; // an unsent backlog keeps its newest part
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US); // under LOCK
    private static volatile File sDir;

    private HeLog() { }

    /** From process start (HeObd.init); lines before it go to logcat only. */
    static void init(Context app) {
        if (app != null && sDir == null) sDir = app.getFilesDir();
    }

    static void i(String msg) { Log.i(TAG, msg); append("I", msg); }

    static void w(String msg) { Log.w(TAG, msg); append("W", msg); }

    static void w(String msg, Throwable t) { Log.w(TAG, msg, t); append("W", msg + ": " + t); }

    private static void append(String level, String msg) {
        File dir = sDir;
        if (dir == null) return;
        synchronized (LOCK) {
            String line = TIME.format(new Date()) + " " + level + " " + msg + "\n";
            File f = new File(dir, "he-obd.log");
            if (f.length() > MAX_BYTES) {
                File prev = new File(dir, "he-obd.prev");
                prev.delete();
                f.renameTo(prev);
            }
            writeText(f, line, true);
        }
    }

    /** The lines not sent yet (an earlier failed send first). Call {@link #sent} after a good upload. */
    static String take() {
        File dir = sDir;
        if (dir == null) return "";
        synchronized (LOCK) {
            File sending = new File(dir, "he-obd.sending"), prev = new File(dir, "he-obd.prev"), cur = new File(dir, "he-obd.log");
            String text = HeRemote.readFile(sending);
            String fresh = HeRemote.readFile(prev) + HeRemote.readFile(cur);
            if (fresh.isEmpty()) return text;
            text += fresh;
            if (text.length() > MAX_SEND_CHARS) text = "…(older lines dropped)\n" + text.substring(text.length() - MAX_SEND_CHARS);
            if (writeText(sending, text, false)) { prev.delete(); cur.delete(); }
            return text;
        }
    }

    static void sent() {
        File dir = sDir;
        if (dir != null) synchronized (LOCK) { new File(dir, "he-obd.sending").delete(); }
    }

    /** Writes (or appends) UTF-8 text; false on failure. */
    static boolean writeText(File f, String s, boolean append) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, append);
            out.write(s.getBytes("UTF-8"));
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }
}
