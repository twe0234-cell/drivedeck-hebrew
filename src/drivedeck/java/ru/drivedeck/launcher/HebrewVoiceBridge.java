package ru.drivedeck.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Routes every DriveDeck voice-assistant entry point (steering-wheel ASSIST / VOICE_ASSIST / SEARCH keys,
 * system ASSIST / VOICE_COMMAND intents, DriveDeck "assistant" action) to the Hebrew voice app
 * "il.carvoice.he" when it is installed. Without it, DriveDeck's original behaviour is untouched.
 */
public final class HebrewVoiceBridge {
    private static final String TAG = "HebrewVoiceBridge";
    static final String PKG = "il.carvoice.he";
    static final String CLS = "il.carvoice.he.VoiceActivity";
    private static volatile long sLast = 0L;

    public static boolean isInstalled(Context c) {
        try {
            return c != null && c.getPackageManager().getLaunchIntentForPackage(PKG) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start the Hebrew assistant. Returns true if it is installed (handled), false to fall back to DriveDeck's own. */
    public static boolean launch(Context c) {
        if (!isInstalled(c)) return false;
        long now = SystemClock.elapsedRealtime();
        if (sLast > 0L && now - sLast < 1500L) return true; // key + intent can arrive together
        sLast = now;
        try {
            Intent i = new Intent("android.intent.action.MAIN");
            i.setClassName(PKG, CLS);
            i.addFlags(0x10000000 | 0x00008000); // NEW_TASK | CLEAR_TASK
            c.startActivity(i);
            Log.i(TAG, "Hebrew voice assistant started");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            return false;
        }
    }

    /** Hardware key hook (MainActivity.dispatchKeyEvent + accessibility key filter). */
    public static boolean onKey(Context c, KeyEvent e) {
        if (e == null) return false;
        int k = e.getKeyCode();
        if (k != 219 && k != 231 && k != 84) return false; // ASSIST, VOICE_ASSIST, SEARCH
        if (!isInstalled(c)) return false;
        if (e.getAction() == 0 && e.getRepeatCount() == 0) launch(c);
        return true;
    }

    /** ASSIST / VOICE_ASSIST / VOICE_COMMAND / SEARCH_LONG_PRESS delivered to DriveDeck. */
    public static boolean onIntent(Context c, Intent i) {
        if (i == null) return false;
        if (HeObdScreen.onIntent(c, i)) return true;
        String a = i.getAction();
        if (a == null) return false;
        if (!a.equals("android.intent.action.ASSIST") && !a.equals("android.intent.action.VOICE_ASSIST")
                && !a.equals("android.intent.action.VOICE_COMMAND") && !a.equals("android.intent.action.SEARCH_LONG_PRESS")) return false;
        if (!launch(c)) return false;
        i.setAction(null);
        return true;
    }
}
