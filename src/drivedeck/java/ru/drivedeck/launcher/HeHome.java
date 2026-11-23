package ru.drivedeck.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps DriveDeck in front after power-on, and only then.
 *
 * The Topway firmware starts its own launcher (the Dofun theme app, com.dofun.carsetting) at boot and at every
 * ignition, not through the HOME intent, so being the default home app is not enough. Right after a real power-on
 * (boot, ACC on, screen on after a long sleep) the theme launcher is replaced by DriveDeck once (plus its bounce a
 * few seconds later). Nothing else is ever touched.
 *
 * he-15: he-6..he-14 also counted every "dofun" package as a launcher, opened the window on the voice wake-up
 * broadcast (unisound DO_WAKEUP) and kept it open for two full minutes whatever the driver did, so the phone key,
 * the voice key and an incoming call could be pushed back to DriveDeck. Now the guard ends as soon as:
 *   - it has replaced the theme and the theme stayed away for a few seconds,
 *   - a steering-wheel / hardware key is pressed,
 *   - any app other than a launcher comes to the front (phone, voice, music, settings...),
 *   - a call is ringing or active.
 */
public final class HeHome {
    private static final String SELF = "ru.drivedeck.launcher";
    private static final long WINDOW_MS = 60_000L;   // from the power-on
    private static final long BOUNCE_MS = 8_000L;    // after a replacement, the theme may come back once
    private static final int MAX_SWITCHES = 3;

    private static volatile long sWakeAt = -1L;
    private static volatile long sDisarmAt = -1L;    // > 0: the guard ends at this time (after a replacement)
    private static volatile int sSwitches = 0;
    private static volatile long sLastSwitch = 0L;
    private static volatile String sForeground = "";
    private static volatile Set<String> sHomes = null;

    /** A real power-on: boot (process start), ACC on, screen on after a long sleep. */
    public static void onWake(Context ctx, String why) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sWakeAt = SystemClock.elapsedRealtime();
        sDisarmAt = -1L;
        sSwitches = 0;
        sHomes = null; // re-read: the firmware may change
        HeLog.i("home guard: armed by " + why + " (foreground now: " + sForeground + ")");
        Handler h = new Handler(Looper.getMainLooper());
        // the firmware's launcher can appear a few seconds after the wake broadcast
        for (long d : new long[] { 1500L, 5000L, 12000L, 25000L }) {
            h.postDelayed(new Runnable() {
                public void run() { check(app, sForeground, "", "timer"); }
            }, d);
        }
    }

    /** Older callers. */
    public static void onWake(Context ctx) { onWake(ctx, "wake"); }

    /** The driver did something: the guard must not undo it. */
    static void disarm(String why) {
        if (!armed()) return;
        sWakeAt = -1L;
        sDisarmAt = -1L;
        HeLog.i("home guard: off (" + why + ")");
    }

    /** Every hardware key that reaches DriveDeck's accessibility service (steering wheel, panel). */
    static void onKey(KeyEvent e) {
        if (e == null || e.getAction() != 0 || e.getRepeatCount() != 0) return;
        int k = e.getKeyCode();
        HeLog.i("key " + k + " scan=" + e.getScanCode() + " fg=" + sForeground);
        disarm("key " + k);
    }

    /** From LauncherAccessibilityService.onAccessibilityEvent. */
    public static void onA11yEvent(Context ctx, AccessibilityEvent e) {
        try {
            if (e == null || e.getEventType() != 0x20) return; // TYPE_WINDOW_STATE_CHANGED
            CharSequence p = e.getPackageName();
            if (p == null) return;
            String pkg = p.toString();
            if (pkg.equals("com.android.systemui") || pkg.equals("android")) return; // overlays, dialogs
            CharSequence c = e.getClassName();
            String cls = c == null ? "" : c.toString();
            if (!pkg.equals(sForeground)) {
                sForeground = pkg;
                HeLog.i("foreground: " + pkg + " / " + cls + (armed() ? " (guard on)" : ""));
            }
            check(ctx, pkg, cls, "a11y");
        } catch (Throwable t) {
            HeLog.w("a11y home check failed", t);
        }
    }

    private static boolean armed() {
        long w = sWakeAt;
        if (w <= 0) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - w >= WINDOW_MS) { sWakeAt = -1L; return false; }
        long d = sDisarmAt;
        if (d > 0 && now >= d) { sWakeAt = -1L; sDisarmAt = -1L; return false; }
        return true;
    }

    private static void check(Context ctx, String pkg, String cls, String why) {
        if (ctx == null || pkg == null || pkg.isEmpty() || !armed()) return;
        if (pkg.equals(SELF)) return;
        if (!isForeignLauncher(ctx, pkg)) {
            // keyboards and DriveDeck's own embedded map/camera are not the driver's choice; anything else is
            if (!isPassive(pkg)) disarm("driver opened " + pkg);
            return;
        }
        if (inCall(ctx)) { disarm("call"); return; }
        long now = SystemClock.elapsedRealtime();
        if (sSwitches >= MAX_SWITCHES || now - sLastSwitch < 1200L) return;
        sSwitches++;
        sLastSwitch = now;
        sDisarmAt = now + BOUNCE_MS;
        try {
            Intent i = new Intent("android.intent.action.MAIN");
            i.addCategory("android.intent.category.HOME");
            i.setClassName(SELF, "ru.drivedeck.launcher.MainActivity");
            i.addFlags(0x10000000 | 0x00200000); // NEW_TASK | RESET_TASK_IF_NEEDED
            ctx.startActivity(i);
            HeLog.i("home guard: replaced " + pkg + " / " + cls + " with DriveDeck (" + why + ", #" + sSwitches + ")");
        } catch (Throwable t) {
            HeLog.w("bring DriveDeck to front failed", t);
        }
    }

    /** Windows that come up by themselves around a power-on and say nothing about what the driver wants. */
    private static boolean isPassive(String pkg) {
        return pkg.startsWith("com.android.inputmethod") || pkg.contains("inputmethod") || pkg.equals("com.waze")
                || pkg.equals("com.tw.reverse") || pkg.equals("com.tw.service") || pkg.equals("com.tw.jar1")
                || pkg.equals("com.android.settings") || pkg.equals("com.android.provision")
                || pkg.startsWith("com.jtech.zemer"); // ZemerDrive resumes by itself at ignition
    }

    /** A Bluetooth (or SIM) call is ringing or active: never fight the phone screen. */
    static boolean inCall(Context ctx) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService("audio");
            if (am != null && am.getMode() != 0) return true; // RINGTONE / IN_CALL / IN_COMMUNICATION
        } catch (Throwable ignored) { }
        try {
            java.lang.ref.WeakReference r = c.Z7.Q;
            c.Z7 z = r == null ? null : (c.Z7) r.get();
            if (z != null && z.a != null && z.a.e != 0) return true; // DriveDeck's own HFP call state
        } catch (Throwable ignored) { }
        return false;
    }

    /** The Topway theme launcher: the other HOME apps (not the boot-time FallbackHome of Settings). */
    static boolean isForeignLauncher(Context ctx, String pkg) {
        if (pkg.equals(SELF) || pkg.equals("com.android.settings") || pkg.equals("com.android.provision")) return false;
        if (pkg.equals("com.dofun.carsetting")) return true; // the theme launcher seen in the logs
        Set<String> homes = sHomes;
        if (homes == null) {
            homes = new HashSet<String>();
            try {
                Intent i = new Intent("android.intent.action.MAIN");
                i.addCategory("android.intent.category.HOME");
                List<ResolveInfo> l = ctx.getPackageManager().queryIntentActivities(i, 0);
                if (l != null) for (ResolveInfo r : l) if (r != null && r.activityInfo != null) homes.add(r.activityInfo.packageName);
            } catch (Throwable ignored) { }
            homes.remove(SELF);
            homes.remove("com.android.settings");
            sHomes = homes;
            HeLog.i("home apps: " + homes);
        }
        return homes.contains(pkg);
    }

    /** For the remote log header. */
    static String state() {
        return (armed() ? "on" : "off") + ", foreground=" + sForeground + ", homes=" + sHomes;
    }
}
