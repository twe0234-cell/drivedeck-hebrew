package ru.drivedeck.launcher;

import android.content.Context;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * he-16: the steering-wheel voice key.
 *
 * The key goes from the MCU to com.tw.service, never through Android (no KeyEvent reaches DriveDeck), which opens
 * the app named in /data/tw/speech_name (see SpeechName). It is pointed at the Hebrew voice app's WheelKeyActivity
 * (voice 9+), which opens Gemini or the Hebrew assistant, as chosen in the voice app's settings.
 * Checked at process start and at every car wake: Topway's voice setting screen may write the file again.
 */
final class HeVoiceKey {
    static final String FILE = "/data/tw/speech_name";
    static final int MIN_VOICE = 9; // first voice app with WheelKeyActivity
    private static volatile boolean sSuTried = false;

    static void pointAsync(final Context app, final String why) {
        new Thread(new Runnable() {
            public void run() { point(app, why); }
        }, "dd-he-voicekey").start();
    }

    private static synchronized void point(Context app, String why) {
        try {
            if (app.getPackageManager().getPackageInfo(SpeechName.PKG, 0).versionCode < MIN_VOICE) return;
        } catch (Throwable notInstalled) {
            return;
        }
        String cur = read();
        if (SpeechName.pointsAtWheelKey(cur)) return;
        boolean ok = false;
        File f = new File(FILE);
        if (f.isFile()) {
            // overwrite in place: the file keeps its owner, mode and SELinux label (com.tw.service must read it)
            try {
                FileOutputStream out = new FileOutputStream(f);
                try { out.write(SpeechName.CONTENT.getBytes("UTF-8")); } finally { out.close(); }
                ok = SpeechName.pointsAtWheelKey(read());
            } catch (Throwable e) {
                HeLog.w("voice key: direct write refused, trying su", e);
            }
        }
        if (!ok) {
            // su at most once per process: a su call at every ignition starts Magisk while the car wakes up
            if (sSuTried) {
                HeLog.i("voice key (" + why + "): speech_name " + SpeechName.oneLine(cur) + ", su already tried");
                return;
            }
            sSuTried = true;
            String cmd = "printf '%s\\n%s\\n' " + SpeechName.PKG + " " + SpeechName.CLS + " > " + FILE
                    + "; chmod 666 " + FILE + "; restorecon " + FILE;
            try {
                Runtime.getRuntime().exec(new String[] { "su", "-c", cmd }).waitFor();
            } catch (Throwable e) {
                HeLog.w("voice key: su write failed", e);
            }
            ok = SpeechName.pointsAtWheelKey(read());
        }
        HeLog.i("voice key (" + why + "): speech_name was " + SpeechName.oneLine(cur)
                + (ok ? ", now the voice app's wheel key" : ", could not be changed"));
    }

    static String read() {
        try {
            File f = new File(FILE);
            if (!f.isFile()) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[512];
                int n = in.read(buf);
                return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return "?" + e.getClass().getSimpleName();
        }
    }

    /** For the remote log header: what the key opens, and whether a short or a long press does it. */
    static String state(Context app) {
        String mode = "?";
        try { mode = Settings.System.getString(app.getContentResolver(), "SWC_VOICE_MODE"); } catch (Throwable ignored) { }
        return "speech_name=" + SpeechName.oneLine(read()) + " SWC_VOICE_MODE=" + mode;
    }
}
