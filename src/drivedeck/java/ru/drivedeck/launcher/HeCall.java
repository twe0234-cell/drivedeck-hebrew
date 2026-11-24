package ru.drivedeck.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * he-16: the unit's phone app (Topway com.tw.bt) is the only call handler.
 *
 * DriveDeck's own phone module (c.Z7: Bluetooth HFP polling, its call overlay, answer/hang-up on the call keys) is
 * switched off in the smali (build/patches/he16-phone.py); DriveDeck's phone icon, tile and PHONE action open
 * Topway's phone screen instead (MainActivity.P -> openPhone).
 *
 * Here: the Bluetooth calls are followed from the HFP client broadcast (the same one com.tw.bt listens to) so that
 *   - Topway's call screen comes to the front when a call starts while DriveDeck is in front,
 *   - the home guard and the ignition launch never cover a call,
 *   - a call-state property left stuck after a call (which silences the steering-wheel phone and voice keys in
 *     com.tw.service) is put back to 0 once no call is on.
 */
public final class HeCall {
    static final String TW_BT = "com.tw.bt";
    static final String TW_BT_ACTIVITY = "com.tw.bt.ATBluetoothActivity";
    static final String AG_CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED";
    static final String EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL";
    static final String[] CALL_PROPS = { "persist.dialer.call.state", "persist.hicar.call.state" };

    private static final CallBook BOOK = new CallBook();
    private static volatile boolean sRegistered = false;

    /** Process start: follow the calls, then check the call-state properties once. */
    static void init(final Context app) {
        if (app == null) return;
        if (!sRegistered) {
            sRegistered = true;
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(AG_CALL_CHANGED);
                app.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context c, Intent i) { onCall(app, i); }
                }, f);
            } catch (Throwable e) {
                HeLog.w("call receiver failed", e);
            }
        }
        fixStuckStateAsync(app, "start");
    }

    /** A call is ringing, being dialled or on (Bluetooth, or a SIM call through the audio mode). */
    static boolean inCall(Context ctx) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService("audio");
            if (am != null && am.getMode() != 0) return true; // RINGTONE / IN_CALL / IN_COMMUNICATION
        } catch (Throwable ignored) { }
        return !BOOK.idle();
    }

    private static void onCall(Context app, Intent i) {
        try {
            Bundle b = i == null ? null : i.getExtras();
            Object call = b == null ? null : b.get(EXTRA_CALL);
            if (call == null) return;
            int id = intCall(call, "getId", -1);
            int state = intCall(call, "getState", -1);
            if (state < 0) return;
            boolean started = BOOK.update(id, state);
            HeLog.i("call " + id + " state " + state + (started ? " (new)" : "") + ", fg=" + HeHome.foreground());
            if (started) {
                HeHome.disarm("call");
                if (HeHome.isSelf(HeHome.foreground())) openPhone(app, "call while DriveDeck in front");
            }
            if (BOOK.idle()) {
                // after the last call: Topway clears the properties itself; check a little later that it did
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() { fixStuckStateAsync(app, "call ended"); }
                }, 5000L);
            }
        } catch (Throwable e) {
            HeLog.w("call broadcast failed", e);
        }
    }

    private static int intCall(Object o, String method, int dflt) {
        try {
            Object v = o.getClass().getMethod(method).invoke(o);
            return v instanceof Integer ? (Integer) v : dflt;
        } catch (Throwable e) {
            return dflt;
        }
    }

    /** DriveDeck's phone icon / tile / PHONE action, and a call starting over DriveDeck: Topway's phone screen. */
    public static boolean openPhone(Context ctx) { return openPhone(ctx, "DriveDeck phone entry"); }

    static boolean openPhone(Context ctx, String why) {
        if (ctx == null) return false;
        try {
            Intent i = new Intent();
            i.setClassName(TW_BT, TW_BT_ACTIVITY);
            i.addFlags(0x10000000); // NEW_TASK (the activity is singleTask and shows the call view by itself)
            ctx.startActivity(i);
            HeLog.i("phone: Topway phone screen opened (" + why + ")");
            return true;
        } catch (Throwable e) {
            HeLog.w("phone: Topway phone screen failed (" + why + ")", e);
            return false;
        }
    }

    static void fixStuckStateAsync(final Context app, final String why) {
        new Thread(new Runnable() {
            public void run() { fixStuckState(app, why); }
        }, "dd-he-callstate").start();
    }

    private static synchronized void fixStuckState(Context app, String why) {
        for (String prop : CALL_PROPS) {
            try {
                String v = getprop(prop);
                if (!CallBook.isStuck(v, inCall(app))) continue;
                String cmd = "setprop " + prop + " 0";
                exec(new String[] { "sh", "-c", cmd });
                String after = getprop(prop);
                if (CallBook.isStuck(after, false)) {
                    exec(new String[] { "su", "-c", cmd });
                    after = getprop(prop);
                }
                HeLog.i("call state " + prop + " was " + v.trim() + " with no call (" + why + "), now " + after.trim());
            } catch (Throwable e) {
                HeLog.w("call state check failed: " + prop, e);
            }
        }
    }

    static String getprop(String name) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "getprop", name });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            try {
                String l = r.readLine();
                p.waitFor();
                return l == null ? "" : l;
            } finally {
                r.close();
            }
        } catch (Throwable e) {
            return "?";
        }
    }

    private static void exec(String[] cmd) throws Exception {
        Runtime.getRuntime().exec(cmd).waitFor();
    }

    /** For the remote log header. */
    static String state(Context app) {
        int mode = -1;
        try {
            android.media.AudioManager am = (android.media.AudioManager) app.getSystemService("audio");
            if (am != null) mode = am.getMode();
        } catch (Throwable ignored) { }
        return "dialer=" + getprop(CALL_PROPS[0]) + " hicar=" + getprop(CALL_PROPS[1]) + " bt_calls=" + BOOK.describe()
                + " audio_mode=" + mode;
    }
}
