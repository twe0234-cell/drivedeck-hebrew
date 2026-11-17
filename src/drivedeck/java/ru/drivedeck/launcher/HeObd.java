package ru.drivedeck.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hebrew OBD additions on DriveDeck's own adapter connection (no second connection to the adapter).
 *
 * Hook: `c.T5.M(ZString)` calls [onRx] with every command and its raw answer, right after it logs `RX`.
 * DriveDeck sends one command at a time from its handler (main looper); extra PIDs are queued through its
 * own queue `T5.c2`, which it sends before the next regular poll, so answers never get mixed up.
 *
 * * Background (always): coolant and battery voltage every ~15 s, a warning toast on overheating or no
 *   charging, and once a minute a log line that compares OBD speed with GPS speed (for the remote log).
 * * While the check screen is open or a drive check runs: fuel trims, coolant, timing, O2 sensors, voltage
 *   and the check-engine status, one extra PID after each regular one, recorded into samples for [HeObdMath].
 */
public final class HeObd {
    static final String TAG = "DriveDeckHe";
    private static final String[] CHECK_EXTRAS = { "0105", "0106", "0107", "0142", "014C", "010E", "0115", "0114", "0101" };
    private static final String[] BACKGROUND_EXTRAS = { "0105", "0142" };
    private static final int BACKGROUND_EVERY = 100;    // regular answers between two background extras (~7 s)
    private static final long FRESH_MS = 20000L;
    private static final long CHECK_MAX_MS = 45 * 60_000L;
    private static final int MAX_SAMPLES = 6000;

    private static volatile Context sApp;
    private static volatile c.T5 sT5;
    private static final Map<String, double[]> sValues = new HashMap<String, double[]>(); // pid -> {value, time}
    private static volatile int sMil = -1;
    private static final List<String> sCodes = new ArrayList<String>();
    private static volatile boolean sCodesRead = false;
    private static final List<String> sPending = new ArrayList<String>(); // commands we queued, not answered yet
    private static int sRegular = 0;
    private static int sExtraIndex = 0;
    private static int sBgIndex = 0;
    private static volatile int sScreenOpen = 0;
    private static volatile long sCheckStart = -1L;
    private static final List<HeObdMath.Sample> sSamples = new ArrayList<HeObdMath.Sample>();
    private static final List<Double> sSpeedRatio = new ArrayList<Double>(); // OBD / GPS, steady > 40 km/h
    private static long sLastSummary = 0L;
    private static long sLastHotAlert = 0L, sLastChargeAlert = 0L;
    private static int sLowVoltCount = 0;
    private static double sLastObdSpeed = Double.NaN;
    private static volatile HeObdMath.Sample sLastSample;
    private static long sLastExtLog = 0L;

    private HeObd() { }

    /** From process start (RootBootEnforcer.initAndEnforce). */
    public static void init(Context app) {
        if (app != null) sApp = app.getApplicationContext() != null ? app.getApplicationContext() : app;
    }

    // ================================================================== the hook

    /** Every OBD command and its raw answer (DriveDeck's handler thread). Never throws. */
    public static void onRx(String cmd, String raw) {
        try {
            handle(cmd == null ? "" : cmd.trim().toUpperCase(Locale.ROOT).replace(" ", ""), raw);
        } catch (Throwable t) {
            Log.w(TAG, "obd hook", t);
        }
    }

    private static void handle(String cmd, String raw) {
        boolean ours;
        synchronized (sPending) { ours = sPending.remove(cmd); }
        long now = System.currentTimeMillis();
        if (ours && (cmd.startsWith("AT") || cmd.startsWith("22") || cmd.startsWith("19"))) {
            HeObdExt.onAnswer(cmd, raw);
            return;
        }
        if (cmd.equals("03") || cmd.equals("07")) {
            List<String> c = HeObdMath.dtcs(raw, cmd.charAt(1));
            synchronized (sCodes) {
                if (cmd.equals("03")) sCodes.clear();
                for (String s : c) if (!sCodes.contains(s)) sCodes.add(s);
            }
            sCodesRead = true;
            Log.i(TAG, "obd codes (" + cmd + "): " + c);
            return;
        }
        if (cmd.length() != 4 || !cmd.startsWith("01")) return;
        int[] b = HeObdMath.parse(raw, cmd);
        if (b != null) {
            if (cmd.equals("0101")) {
                sMil = b[0];
            } else {
                double v = HeObdMath.decode(cmd, b);
                if (cmd.equals("0114") || cmd.equals("0115")) v = b[0] / 200.0;
                if (!Double.isNaN(v)) synchronized (sValues) { sValues.put(cmd, new double[] { v, now }); }
            }
            if (cmd.equals("010D")) onSpeed(now);
            if (cmd.equals("0105") || cmd.equals("0142")) alerts(now);
        }
        if (!ours) {
            sRegular++;
            queueExtra();
        }
    }

    private static boolean active() {
        return sScreenOpen > 0 || sCheckStart > 0;
    }

    /** One extra PID after a regular answer: every time while checking, else now and then. */
    private static void queueExtra() {
        String pid = null;
        if (active()) {
            pid = CHECK_EXTRAS[sExtraIndex++ % CHECK_EXTRAS.length];
        } else if (sRegular % BACKGROUND_EVERY == 0) {
            pid = BACKGROUND_EXTRAS[sBgIndex++ % BACKGROUND_EXTRAS.length];
        }
        if (pid != null) send(pid);
        if (sCheckStart > 0) HeObdExt.next();
    }

    /** Queues a command on DriveDeck's connection (sent before its next regular poll). */
    static boolean send(final String command) {
        Context app = sApp;
        if (app == null) return false;
        c.T5 cached = sT5;
        if (cached == null) cached = sT5 = c.T5.V(app); // a singleton: fetched once
        final c.T5 t = cached;
        if (t == null || t.c == null || t.c2 == null || !RootBootEnforcer.isConnected(t)) return false;
        synchronized (sPending) {
            if (sPending.contains(command)) return true;
            sPending.add(command);
            while (sPending.size() > 100) sPending.remove(0);
        }
        t.c.post(new Runnable() {
            @SuppressWarnings("unchecked")
            public void run() {
                try { t.c2.add(command); } catch (Throwable e) { Log.w(TAG, "obd queue", e); }
            }
        });
        return true;
    }

    /**
     * Queues a whole batch (HeObdExt) in one go, then "ATSH" + DriveDeck's current header, so its next request goes
     * where it expects. DriveDeck's own requests are always complete in its queue (header, extras, command), and it
     * drains the queue before the next request, so a batch never lands between its header and its command.
     */
    static boolean sendBatch(final List<String> commands) {
        Context app = sApp;
        if (app == null) return false;
        c.T5 cached = sT5;
        if (cached == null) cached = sT5 = c.T5.V(app);
        final c.T5 t = cached;
        if (t == null || t.c == null || t.c2 == null || !RootBootEnforcer.isConnected(t)) return false;
        t.c.post(new Runnable() {
            @SuppressWarnings("unchecked")
            public void run() {
                try {
                    String header = t.P;
                    List<String> all = new ArrayList<String>(commands);
                    all.add("ATSH" + (header == null || header.trim().isEmpty() ? "7DF" : header.trim()));
                    synchronized (sPending) {
                        for (String x : all) sPending.add(x);
                        while (sPending.size() > 100) sPending.remove(0);
                    }
                    for (String x : all) t.c2.add(x);
                } catch (Throwable e) {
                    Log.w(TAG, "obd batch", e);
                }
            }
        });
        return true;
    }

    // ================================================================== samples, speed, summary

    static double value(String pid, long now) {
        double[] v;
        synchronized (sValues) { v = sValues.get(pid); }
        return v != null && now - v[1] <= FRESH_MS ? v[0] : Double.NaN;
    }

    /** GPS speed (km/h) and accuracy from the fix DriveDeck already keeps, when it is under 3 s old. */
    static double[] gps(long now) {
        try {
            Context app = sApp;
            LocationManager lm = app == null ? null : (LocationManager) app.getSystemService("location");
            Location l = lm == null ? null : lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null || !l.hasSpeed() || Math.abs(now - l.getTime()) > 3000) return null;
            return new double[] { l.getSpeed() * 3.6, l.hasAccuracy() ? l.getAccuracy() : Double.NaN };
        } catch (Throwable e) {
            return null;
        }
    }

    /** Once per poll cycle (the speed answer): one sample, the OBD/GPS speed ratio, the minute summary. */
    private static void onSpeed(long now) {
        HeObdMath.Sample s = new HeObdMath.Sample();
        s.t = now;
        s.speed = value("010D", now);
        s.rpm = value("010C", now);
        s.coolant = value("0105", now);
        s.iat = value("010F", now);
        s.ambient = value("0146", now);
        s.stft = value("0106", now);
        s.ltft = value("0107", now);
        s.map = value("010B", now);
        s.baro = value("0133", now);
        s.load = value("0104", now);
        s.throttle = value("014C", now);
        s.voltage = value("0142", now);
        s.o2up = value("0114", now);
        s.o2down = value("0115", now);
        double[] g = gps(now);
        if (g != null) { s.gpsSpeed = g[0]; s.gpsAccuracy = g[1]; }

        if (HeObdMath.ok(s.speed) && HeObdMath.ok(s.gpsSpeed) && s.speed > 40 && s.gpsSpeed > 40
                && HeObdMath.ok(sLastObdSpeed) && Math.abs(s.speed - sLastObdSpeed) <= 2
                && (!HeObdMath.ok(s.gpsAccuracy) || s.gpsAccuracy <= 15)) {
            synchronized (sSpeedRatio) {
                sSpeedRatio.add(s.speed / s.gpsSpeed);
                if (sSpeedRatio.size() > 2000) sSpeedRatio.remove(0);
            }
        }
        sLastObdSpeed = s.speed;

        if (sCheckStart > 0) {
            HeObdExt.fill(s, now);
            sLastSample = s;
            if (now - sLastExtLog >= 10_000L) {
                sLastExtLog = now;
                Log.i(TAG, "obd ext: " + HeObdUds.line(s));
            }
            synchronized (sSamples) {
                if (sSamples.size() < MAX_SAMPLES) sSamples.add(s);
            }
            if (now - sCheckStart > CHECK_MAX_MS) stopCheck();
        }
        if (now - sLastSummary >= 60_000L) {
            sLastSummary = now;
            Log.i(TAG, "obd minute: " + summary(s));
        }
    }

    private static String summary(HeObdMath.Sample s) {
        StringBuilder b = new StringBuilder();
        b.append("speed obd=").append(n(s.speed)).append(" gps=").append(n(s.gpsSpeed));
        double r = speedRatio();
        if (HeObdMath.ok(r)) b.append(" obd/gps=").append(String.format(Locale.US, "%.3f", r)).append(" (n=").append(ratioCount()).append(')');
        b.append(" rpm=").append(n(s.rpm)).append(" coolant=").append(n(s.coolant)).append(" map=").append(n(s.map))
                .append(" baro=").append(n(s.baro)).append(" volt=").append(n(s.voltage)).append(" stft=").append(n(s.stft))
                .append(" ltft=").append(n(s.ltft)).append(" source=").append(homeSpeedFromObd() ? "obd" : "gps")
                .append(" factor=").append(speedFactorSetting());
        return b.toString();
    }

    /** For the remote log header: where the home-screen speed comes from and how OBD compares with GPS. */
    static String headerLine() {
        double r = speedRatio();
        return "home=" + (homeSpeedFromObd() ? "obd" : "gps") + " factor=" + speedFactorSetting()
                + " obd/gps=" + (HeObdMath.ok(r) ? String.format(Locale.US, "%.3f (n=%d)", r, ratioCount()) : "-")
                + " mil=" + sMil;
    }

    private static String n(double v) {
        return HeObdMath.ok(v) ? String.format(Locale.US, "%.1f", v) : "-";
    }

    /** Median OBD / GPS speed ratio of the steady stretches seen since start (NaN under 20). */
    static double speedRatio() {
        List<Double> c;
        synchronized (sSpeedRatio) { c = new ArrayList<Double>(sSpeedRatio); }
        return c.size() >= 20 ? HeObdMath.median(c) : Double.NaN;
    }

    static int ratioCount() {
        synchronized (sSpeedRatio) { return sSpeedRatio.size(); }
    }

    // ================================================================== alerts

    private static void alerts(long now) {
        double coolant = value("0105", now), volt = value("0142", now), rpm = value("010C", now);
        if (HeObdMath.ok(coolant) && coolant >= 112 && now - sLastHotAlert > 10 * 60_000L) {
            sLastHotAlert = now;
            toast("אזהרה: המנוע חם מדי (" + Math.round(coolant) + "°). לעצור בצד ולבדוק נוזל קירור.");
            Log.w(TAG, "obd alert: coolant " + coolant);
        }
        if (HeObdMath.ok(volt) && HeObdMath.ok(rpm) && rpm > 600) {
            sLowVoltCount = volt < 12.4 ? sLowVoltCount + 1 : 0;
            if (sLowVoltCount >= 3 && now - sLastChargeAlert > 30 * 60_000L) {
                sLastChargeAlert = now;
                toast("אזהרה: המצבר לא נטען (" + String.format(Locale.US, "%.1f", volt) + "V במנוע פועל).");
                Log.w(TAG, "obd alert: voltage " + volt);
            }
        }
    }

    private static void toast(final String text) {
        final Context app = sApp;
        if (app == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try { Toast.makeText(app, text, 1).show(); } catch (Throwable ignored) { }
            }
        });
    }

    // ================================================================== the check (used by HeObdScreen)

    static void screenOpened() { sScreenOpen++; }

    static void screenClosed() { if (sScreenOpen > 0) sScreenOpen--; }

    static boolean checking() { return sCheckStart > 0; }

    static long checkStartedAt() { return sCheckStart; }

    static void startCheck() {
        synchronized (sSamples) { sSamples.clear(); }
        sMil = -1;
        sLastSample = null;
        HeObdExt.start();
        sCheckStart = System.currentTimeMillis();
        readCodes();
        Log.i(TAG, "obd check started");
    }

    /** Ends the drive check, saves and logs the report; returns it. */
    static String stopCheck() {
        if (sCheckStart <= 0) return lastReport();
        sCheckStart = -1L;
        HeObdExt.stop();
        String report = report();
        Context app = sApp;
        if (app != null) app.getSharedPreferences("drivedeck_he", 0).edit().putString("obd_report", report).apply();
        Log.i(TAG, "obd check report:\n" + report);
        return report;
    }

    static String lastReport() {
        Context app = sApp;
        return app == null ? "" : app.getSharedPreferences("drivedeck_he", 0).getString("obd_report", "");
    }

    static int sampleCount() {
        synchronized (sSamples) { return sSamples.size(); }
    }

    static void readCodes() {
        sCodesRead = false;
        send("0101");
        send("03");
        send("07");
    }

    static boolean codesRead() { return sCodesRead; }

    static List<String> codes() {
        synchronized (sCodes) { return new ArrayList<String>(sCodes); }
    }

    static int mil() { return sMil; }

    static String report() {
        List<HeObdMath.Sample> copy;
        synchronized (sSamples) { copy = new ArrayList<HeObdMath.Sample>(sSamples); }
        Context app = sApp;
        SharedPreferences he = app == null ? null : app.getSharedPreferences("drivedeck_he", 0);
        double base = Double.NaN;
        if (he != null && he.contains("obd_idle_throttle")) {
            try { base = Double.parseDouble(he.getString("obd_idle_throttle", "")); } catch (Throwable ignored) { }
        }
        List<HeObdMath.Finding> f = HeObdMath.evaluate(copy, sMil, sCodesRead ? codes() : null, base);
        f.addAll(HeObdUds.evaluate(copy, HeObdExt.stat(), HeObdExt.codes(), HeObdExt.failed()));
        double idle = HeObdMath.idleThrottle(copy);
        if (he != null && !HeObdMath.ok(base) && HeObdMath.ok(idle)) {
            he.edit().putString("obd_idle_throttle", String.format(Locale.US, "%.2f", idle)).apply();
        }
        Collections.sort(f, new java.util.Comparator<HeObdMath.Finding>() {
            public int compare(HeObdMath.Finding a, HeObdMath.Finding b) { return b.level - a.level; }
        });
        StringBuilder b = new StringBuilder();
        b.append(new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new java.util.Date()))
                .append(" · ").append(copy.size()).append(" דגימות\n");
        for (HeObdMath.Finding x : f) {
            String mark = x.level == HeObdMath.BAD ? "⛔ " : x.level == HeObdMath.WARN ? "⚠️ " : x.level == HeObdMath.OK ? "✓ " : "ℹ️ ";
            b.append(mark).append(x.title).append(": ").append(x.detail).append('\n');
        }
        double factor = HeObdMath.speedFactor(copy);
        if (HeObdMath.ok(factor)) b.append("מקדם מהירות מוצע: ").append(String.format(Locale.US, "%.3f", factor)).append('\n');
        return b.toString().trim();
    }

    // ================================================================== home-screen speed settings

    /** DriveDeck's "speed from OBD" switch (prefs drive_deck / speed_obd, read live by the home screen). */
    static boolean homeSpeedFromObd() {
        Context app = sApp;
        return app != null && app.getSharedPreferences("drive_deck", 0).getBoolean("speed_obd", false);
    }

    static void setHomeSpeedFromObd(boolean on) {
        Context app = sApp;
        if (app != null) app.getSharedPreferences("drive_deck", 0).edit().putBoolean("speed_obd", on).apply();
    }

    /** DriveDeck's OBD speed factor (prefs drivedeck_obd / speed_factor, a string, default "1"). */
    static String speedFactorSetting() {
        Context app = sApp;
        return app == null ? "1" : app.getSharedPreferences("drivedeck_obd", 0).getString("speed_factor", "1");
    }

    /** The factor that makes OBD speed match GPS (from all steady stretches since start), or NaN. */
    static double suggestedSpeedFactor() {
        double r = speedRatio();
        if (!HeObdMath.ok(r) || r <= 0) return Double.NaN;
        double current = 1;
        try { current = Double.parseDouble(speedFactorSetting()); } catch (Throwable ignored) { }
        // the ratio is measured on the raw PID (DriveDeck applies the factor after us), so the new factor is 1/r
        double f = 1.0 / r;
        return f > 0.8 && f < 1.2 ? f : Double.NaN;
    }

    static void applySpeedFactor(double f) {
        Context app = sApp;
        if (app == null || !HeObdMath.ok(f)) return;
        SharedPreferences.Editor e = app.getSharedPreferences("drivedeck_obd", 0).edit();
        e.putString("speed_factor", String.format(Locale.US, "%.3f", f)).apply();
        Log.i(TAG, "obd speed factor set to " + f);
    }

    /** Live values for the screen: label -> text. */
    static List<String[]> live() {
        long now = System.currentTimeMillis();
        List<String[]> l = new ArrayList<String[]>();
        double[] g = gps(now);
        l.add(new String[] { "מהירות OBD / GPS", n0(value("010D", now)) + " / " + (g == null ? "-" : n0(g[0])) + " קמ\"ש" });
        l.add(new String[] { "סל\"ד", n0(value("010C", now)) });
        l.add(new String[] { "טמפ' מנוע", n0(value("0105", now)) + "°" });
        l.add(new String[] { "טמפ' אוויר יניקה / חוץ", n0(value("010F", now)) + "° / " + n0(value("0146", now)) + "°" });
        l.add(new String[] { "תיקון דלק קצר / ארוך", n1(value("0106", now)) + "% / " + n1(value("0107", now)) + "%" });
        l.add(new String[] { "לחץ סעפת / אוויר", n0(value("010B", now)) + " / " + n0(value("0133", now)) + " kPa" });
        l.add(new String[] { "עומס / מצערת (פקודה)", n0(value("0104", now)) + "% / " + n1(value("014C", now)) + "%" });
        l.add(new String[] { "מתח", n1(value("0142", now)) + "V" });
        l.add(new String[] { "תזמון הצתה", n1(value("010E", now)) + "°" });
        l.add(new String[] { "חיישן חמצן לפני / אחרי ממיר", n2(value("0114", now)) + " / " + n2(value("0115", now)) + "V" });
        l.add(new String[] { "דלק במיכל", n0(value("012F", now)) + "%" });
        if (sCheckStart > 0) l.addAll(HeObdExt.live(sLastSample));
        return l;
    }

    private static String n0(double v) { return HeObdMath.ok(v) ? String.valueOf(Math.round(v)) : "-"; }
    private static String n1(double v) { return HeObdMath.ok(v) ? String.format(Locale.US, "%.1f", v) : "-"; }
    private static String n2(double v) { return HeObdMath.ok(v) ? String.format(Locale.US, "%.2f", v) : "-"; }
}
