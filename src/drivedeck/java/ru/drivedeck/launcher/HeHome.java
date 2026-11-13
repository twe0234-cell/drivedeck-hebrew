package ru.drivedeck.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps DriveDeck in front after power-on / wake-up.
 *
 * The Topway firmware starts its own launcher (the Dofun "theme" app) directly at boot and at every car wake,
 * not through the HOME intent, so being the default home app is not enough: it stays in front until Home is
 * pressed. For two minutes after a boot / wake, as soon as another launcher comes to the front, DriveDeck takes
 * its place (seen through DriveDeck's accessibility service). Outside that window nothing is touched, so opening
 * the Topway app on purpose later still works.
 */
public final class HeHome {
    private static final String TAG = "DriveDeckHe";
    private static final String SELF = "ru.drivedeck.launcher";
    private static final long WINDOW_MS = 120_000L;
    private static final int MAX_SWITCHES = 6;

    private static volatile long sWakeAt = -1L;
    private static volatile int sSwitches = 0;
    private static volatile long sLastSwitch = 0L;
    private static volatile String sForeground = "";
    private static volatile Set<String> sHomes = null;

    /** Boot (process start), ACC on, wake-up, screen on after a sleep. */
    public static void onWake(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sWakeAt = SystemClock.elapsedRealtime();
        sSwitches = 0;
        sHomes = null; // re-read: the firmware may change
        Log.i(TAG, "wake: keeping DriveDeck in front for 2 min (foreground now: " + sForeground + ")");
        Handler h = new Handler(Looper.getMainLooper());
        // the firmware's launcher can appear a few seconds after the wake broadcast
        for (long d : new long[] { 1500L, 5000L, 12000L, 25000L }) {
            h.postDelayed(new Runnable() {
                public void run() { check(app, sForeground, "timer"); }
            }, d);
        }
    }

    /** From LauncherAccessibilityService.onAccessibilityEvent. */
    public static void onA11yEvent(Context ctx, AccessibilityEvent e) {
        try {
            if (e == null || e.getEventType() != 0x20) return; // TYPE_WINDOW_STATE_CHANGED
            CharSequence p = e.getPackageName();
            if (p == null) return;
            String pkg = p.toString();
            if (pkg.equals("com.android.systemui") || pkg.equals("android")) return; // overlays, dialogs
            if (!pkg.equals(sForeground)) {
                sForeground = pkg;
                if (inWindow()) Log.i(TAG, "foreground after wake: " + pkg + " / " + e.getClassName());
            }
            check(ctx, pkg, "a11y");
        } catch (Throwable t) {
            Log.w(TAG, "a11y home check failed", t);
        }
    }

    private static boolean inWindow() {
        long w = sWakeAt;
        return w > 0 && SystemClock.elapsedRealtime() - w < WINDOW_MS;
    }

    private static void check(Context ctx, String pkg, String why) {
        if (ctx == null || pkg == null || pkg.isEmpty() || !inWindow()) return;
        if (!isForeignLauncher(ctx, pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (sSwitches >= MAX_SWITCHES || now - sLastSwitch < 1200L) return;
        sSwitches++;
        sLastSwitch = now;
        try {
            Intent i = new Intent("android.intent.action.MAIN");
            i.addCategory("android.intent.category.HOME");
            i.setClassName(SELF, "ru.drivedeck.launcher.MainActivity");
            i.addFlags(0x10000000 | 0x00200000); // NEW_TASK | RESET_TASK_IF_NEEDED
            ctx.startActivity(i);
            Log.i(TAG, "replaced launcher " + pkg + " with DriveDeck (" + why + ", #" + sSwitches + ")");
        } catch (Throwable t) {
            Log.w(TAG, "bring DriveDeck to front failed", t);
        }
    }

    /** Another home app (the Topway / Dofun launcher), not the boot-time FallbackHome of Settings. */
    static boolean isForeignLauncher(Context ctx, String pkg) {
        if (pkg.equals(SELF) || pkg.equals("com.android.settings") || pkg.equals("com.android.provision")) return false;
        if (pkg.contains("dofun")) return true;
        Set<String> homes = sHomes;
        if (homes == null) {
            homes = new HashSet<String>();
            try {
                Intent i = new Intent("android.intent.action.MAIN");
                i.addCategory("android.intent.category.HOME");
                List<ResolveInfo> l = ctx.getPackageManager().queryIntentActivities(i, 0);
                if (l != null) for (ResolveInfo r : l) if (r != null && r.activityInfo != null) homes.add(r.activityInfo.packageName);
            } catch (Throwable ignored) { }
            sHomes = homes;
            Log.i(TAG, "home apps: " + homes);
        }
        return homes.contains(pkg);
    }
}
