package ru.drivedeck.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/**
 * Hebrew/Topway integration for DriveDeck (build he2).
 *
 * - One-time defaults: Hebrew UI, OBD profile "MQB, PQ26: TSI/TFSI EA211 1.0-1.5L" (Seat Leon 5F 1.2 TSI CYVB),
 *   CAN 11bit/500k, engine 1.2L / 4 cyl. The signed catalog + profile are bundled and seeded into the
 *   app cache so no internet is needed.
 * - Auto-select a paired OBD Bluetooth adapter when none is configured.
 * - Car wake (boot / ACC / screen on after sleep): clear battery-protection park and connect once,
 *   with a single safety retry only if the first attempt failed. Never touches an open connection.
 * - System settings via Settings API (app runs as system uid); su only as a fallback, once per boot.
 *   Accessibility service is APPENDED to the enabled list, other apps' services (e.g. ZemerDrive) are kept.
 */
public class RootBootEnforcer {
    private static final String TAG = "RootBootEnforcer";
    private static final String HE_PREFS = "drivedeck_he";
    private static final String OBD_PREFS = "drivedeck_obd";
    static final String PROFILE_ID = "01e985f62b07bf78fbba66f5";
    static final String PROFILE_FILE = "profiles/8816d93260d0d39cc8048a0b7d408eb3f01b5a22b865e35c1d249341b6cfb539.json";
    private static final String A11Y = "ru.drivedeck.launcher/ru.drivedeck.launcher.LauncherAccessibilityService";

    static volatile boolean sRootExecuted = false;
    private static volatile long sLastWake = -1L;

    // ---------------------------------------------------------------- entry points

    /** Process start / boot: defaults + system settings. */
    public static void initAndEnforce(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        initDefaults(app);
        HeObd.init(app);
        enforce(app);
        HeRemote.start(app);
    }

    /** Legacy no-arg entry (kept for binary compatibility). */
    public static void enforce() { }

    /** Called from MainActivity.onResume: light work only, never restarts an OBD session. */
    public static void startObdWakeupRetry(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() { autoPickAdapter(app); }
        });
    }

    /** Car woke up (BOOT / ACC / MCU / screen on after sleep). */
    public static void onCarWake(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        long now = SystemClock.elapsedRealtime();
        if (sLastWake > 0 && now - sLastWake < 20000L) return; // debounce: several wake broadcasts arrive together
        sLastWake = now;
        if (HeRemote.hasPendingSelf(app)) {
            // a downloaded DriveDeck update installs now, before OBD connects (the new process reconnects)
            new Thread(new Runnable() {
                public void run() {
                    HeRemote.installPendingSelf(app);
                    // still running: the install failed and was cleared, connect as usual
                    sLastWake = -1L;
                    onCarWake(app);
                }
            }, "dd-he-wake-update").start();
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            public void run() {
                try {
                    autoPickAdapter(app);
                    c.T5 t = c.T5.V(app);
                    if (t == null || !isIdle(t)) return;
                    t.r();   // clear battery-protection park state (new trip)
                    t.u0();  // start connection
                    Log.i(TAG, "car wake: OBD connect started");
                } catch (Throwable e) {
                    Log.w(TAG, "car wake failed", e);
                }
            }
        });
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    c.T5 t = c.T5.V(app);
                    if (t == null || !isIdle(t)) return;
                    String err = app.getSharedPreferences(OBD_PREFS, 0).getString("adapter_error", "");
                    if (err == null || err.isEmpty()) return; // still connecting or nothing failed
                    t.u0();
                    Log.i(TAG, "car wake: OBD safety retry");
                } catch (Throwable e) {
                    Log.w(TAG, "car wake retry failed", e);
                }
            }
        }, 20000L);
    }

    public static void enforceQuiet(Context ctx) {
        if (ctx == null) return;
        try { Settings.System.putInt(ctx.getContentResolver(), "FLOATING_SWITCH", 0); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- OBD helpers

    static boolean isIdle(c.T5 t) {
        return t.K == null && t.J1 == null;
    }

    /** An adapter connection is open (classic Bluetooth stream or BLE). */
    static boolean isConnected(c.T5 t) {
        return t != null && !isIdle(t);
    }

    private static void autoPickAdapter(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(OBD_PREFS, 0);
            String addr = p.getString("address", "");
            if (addr != null && !addr.isEmpty()) return;
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null || !ba.isEnabled()) return;
            Set<BluetoothDevice> bonded = ba.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice d : bonded) {
                String n = d.getName();
                if (n == null) continue;
                String l = n.toLowerCase(Locale.ROOT);
                if (l.contains("obd") || l.contains("elm") || l.contains("vlink") || l.contains("v-link")
                        || l.contains("vlinker") || l.contains("veepeak") || l.contains("konnwei")
                        || l.contains("viecar") || l.contains("vgate") || l.contains("icar")
                        || l.contains("obdlink") || l.contains("kiwi") || l.contains("carista")
                        || l.contains("scan") || l.contains("eobd")) {
                    c.T5 t = c.T5.V(app);
                    if (t == null) return;
                    t.w(d.getAddress(), n); // saves address+name, enables OBD, connects
                    Log.i(TAG, "OBD adapter auto-selected: " + n);
                    return;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "auto adapter pick failed", e);
        }
    }

    // ---------------------------------------------------------------- defaults

    public static void initDefaults(Context app) {
        SharedPreferences he = app.getSharedPreferences(HE_PREFS, 0);
        if (!he.getBoolean("defaults_v2", false)) {
            try {
                SharedPreferences dd = app.getSharedPreferences("drive_deck", 0);
                String lang = dd.getString("app_language", "");
                if (lang == null || lang.isEmpty() || "ru".equals(lang)) {
                    dd.edit().putString("app_language", "lt").apply(); // "lt" slot carries the Hebrew pack
                }
            } catch (Throwable e) {
                Log.w(TAG, "language defaults failed", e);
            }
            try {
                SharedPreferences o = app.getSharedPreferences(OBD_PREFS, 0);
                SharedPreferences.Editor e = o.edit();
                String pid = o.getString("profile_id", "");
                if (pid == null || pid.isEmpty()) e.putString("profile_id", PROFILE_ID);
                if (o.getInt("protocol", 0) == 0) e.putInt("protocol", 6); // ISO 15765-4 CAN 11bit 500k
                if (!o.contains("engine_litres")) e.putFloat("engine_litres", 1.2f);
                if (!o.contains("engine_cylinders")) e.putInt("engine_cylinders", 4);
                e.apply();
            } catch (Throwable e) {
                Log.w(TAG, "OBD defaults failed", e);
            }
            he.edit().putBoolean("defaults_v2", true).apply();
        }
        seedObdCache(app);
    }

    private static void seedObdCache(Context app) {
        String[] files = { "catalog.json", PROFILE_FILE };
        for (String f : files) {
            try {
                File dst = new File(new File(app.getFilesDir(), "obd/v1"), f);
                if (dst.isFile() && dst.length() > 0) continue;
                File dir = dst.getParentFile();
                if (dir != null && !dir.isDirectory()) dir.mkdirs();
                InputStream in = app.getAssets().open("he_obd/" + f);
                File tmp = new File(dst.getPath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                    out.close();
                }
                if (!tmp.renameTo(dst)) tmp.delete();
            } catch (Throwable e) {
                Log.w(TAG, "OBD cache seed failed: " + f, e);
            }
        }
    }

    /** Kept for compatibility with older callers; no forced custom PIDs any more. */
    public static String getDefaultPids() { return "[]"; }

    // ---------------------------------------------------------------- system settings

    private static void enforce(final Context app) {
        if (sRootExecuted) return;
        sRootExecuted = true;
        new Thread(new Runnable() {
            public void run() {
                disableAndroidAuto(app);
                if (applySettings(app)) {
                    Log.i(TAG, "system settings applied via Settings API");
                    return;
                }
                runSuOncePerBoot(app);
            }
        }, "dd-he-settings").start();
    }

    /**
     * The owner does not use Android Auto: it woke on every Bluetooth connection (ACL_CONNECTED / UUID broadcasts,
     * :shared and :car processes) next to Topway's own phone stack. Disabled for user 0 (same as
     * `pm disable-user`), checked on every process start so an OTA re-enable is undone; reversible with
     * `pm enable com.google.android.projection.gearhead`. DriveDeck runs as the system uid, so no root is needed;
     * su is the fallback.
     */
    static final String ANDROID_AUTO = "com.google.android.projection.gearhead";

    private static void disableAndroidAuto(Context app) {
        disablePackage(app, ANDROID_AUTO, "Android Auto");
        replaceEqShortcut(app);
    }

    /**
     * The old equalizer shortcut app (ru.drivedeck.eqshortcut, plain icon) is hidden once the voice app has its own
     * "אקולייזר" launcher icon (voice versionCode 6+). Reversible: `pm enable ru.drivedeck.eqshortcut`.
     */
    static final String OLD_EQ_SHORTCUT = "ru.drivedeck.eqshortcut";

    private static void replaceEqShortcut(Context app) {
        try {
            if (app.getPackageManager().getPackageInfo("il.carvoice.he", 0).versionCode < 6) return;
        } catch (Throwable notInstalled) {
            return;
        }
        disablePackage(app, OLD_EQ_SHORTCUT, "old EQ shortcut");
    }

    private static void disablePackage(Context app, String pkg, String name) {
        try {
            PackageManager pm = app.getPackageManager();
            int st;
            try {
                st = pm.getApplicationEnabledSetting(pkg);
            } catch (IllegalArgumentException notInstalled) {
                return;
            }
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) return;
            try {
                pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
                Log.i(TAG, name + " disabled");
                return;
            } catch (Throwable e) {
                Log.w(TAG, name + ": PackageManager refused, trying su", e);
            }
            Runtime.getRuntime().exec(new String[] { "su", "-c", "pm disable-user --user 0 " + pkg }).waitFor();
            Log.i(TAG, name + " disabled via su, state " + pm.getApplicationEnabledSetting(pkg));
        } catch (Throwable e) {
            Log.w(TAG, name + " disable failed", e);
        }
    }

    private static String mergedA11y(String cur) {
        if (cur == null || cur.trim().isEmpty() || "null".equals(cur.trim())) return A11Y;
        for (String s : cur.split(":")) if (A11Y.equalsIgnoreCase(s.trim())) return cur;
        return cur + ":" + A11Y;
    }

    private static boolean applySettings(Context app) {
        try {
            ContentResolver cr = app.getContentResolver();
            try { Settings.System.putInt(cr, "FLOATING_SWITCH", 0); } catch (Throwable ignored) { }
            try { Settings.Secure.putInt(cr, "assistant_on", 0); } catch (Throwable ignored) { }
            String cur = Settings.Secure.getString(cr, "enabled_accessibility_services");
            String merged = mergedA11y(cur);
            if (!merged.equals(cur)) Settings.Secure.putString(cr, "enabled_accessibility_services", merged);
            Settings.Secure.putInt(cr, "accessibility_enabled", 1);
            try { app.sendBroadcast(new Intent("com.android.systemui.FLOATKEY_ACTION_STOP")); } catch (Throwable ignored) { }
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Settings API not permitted, falling back to su", e);
            return false;
        }
    }

    private static void runSuOncePerBoot(Context app) {
        try {
            int boot = Settings.Global.getInt(app.getContentResolver(), "boot_count", -1);
            SharedPreferences he = app.getSharedPreferences(HE_PREFS, 0);
            if (boot >= 0 && he.getInt("su_boot", -2) == boot) return;
            he.edit().putInt("su_boot", boot).apply();
            String cmd = "pm grant ru.drivedeck.launcher android.permission.WRITE_SECURE_SETTINGS; "
                    + "cmd appops set ru.drivedeck.launcher WRITE_SETTINGS allow; "
                    + "settings put system FLOATING_SWITCH 0; settings put secure assistant_on 0; "
                    + "am broadcast -a com.android.systemui.FLOATKEY_ACTION_STOP; "
                    + "C=" + A11Y + "; S=$(settings get secure enabled_accessibility_services); "
                    + "case \"$S\" in \"\"|null) S=$C;; *\"$C\"*) ;; *) S=\"$S:$C\";; esac; "
                    + "settings put secure enabled_accessibility_services \"$S\"; "
                    + "settings put secure accessibility_enabled 1;";
            Runtime.getRuntime().exec(new String[] { "su", "-c", cmd }).waitFor();
            Log.i(TAG, "system settings applied via su");
        } catch (Throwable e) {
            Log.w(TAG, "su fallback failed", e);
        }
    }
}
