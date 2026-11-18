package ru.drivedeck.launcher;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extended drive check, the live part: while a drive check runs, batches of manufacturer requests (engine, DSG,
 * ABS) go through DriveDeck's own queue (HeObd.sendBatch), one batch at a time, each after a regular answer.
 * Every batch sets its unit's header (and the ABS receive filter / flow control the profile asks for), and
 * HeObd.sendBatch puts DriveDeck's header back at the end, so DriveDeck's own polling is never confused.
 */
final class HeObdExt {
    private static final long FRESH_MS = 8000L, STUCK_MS = 10000L;

    private static volatile boolean sOn = false;
    private static final List<List<String>> sStart = new ArrayList<List<String>>();
    private static List<List<String>> sLive = HeObdUds.liveBatches();
    private static int sLiveIndex = 0;
    private static volatile int sInFlight = 0;
    private static volatile long sSentAt = 0L;

    private static final Map<String, double[]> sLatest = new HashMap<String, double[]>(); // key -> {value, time}
    private static final Map<String, Double> sStat = new LinkedHashMap<String, Double>();
    private static final Map<String, List<String>> sCodes = new LinkedHashMap<String, List<String>>();
    private static final Map<String, int[]> sTries = new HashMap<String, int[]>(); // unit -> {asked, answered}
    private static final Set<String> sBadAt = new LinkedHashSet<String>();
    private static int sScaleA = 0, sScaleB = 0;

    private HeObdExt() { }

    static boolean on() { return sOn; }

    static void start() {
        synchronized (sLatest) {
            sLatest.clear(); sStat.clear(); sCodes.clear(); sTries.clear(); sBadAt.clear();
            sStart.clear(); sStart.addAll(HeObdUds.startBatches());
        }
        sScaleA = sScaleB = 0;
        sLiveIndex = 0;
        sInFlight = 0;
        sOn = true;
    }

    static void stop() {
        sOn = false;
        HeLog.i("obd ext stat: " + stat() + " codes=" + codes() + " failed=" + failed() + " at?=" + sBadAt);
    }

    /** After each regular answer: the next batch, when the previous one is through. */
    static void next() {
        if (!sOn) return;
        long now = System.currentTimeMillis();
        if (sInFlight > 0 && now - sSentAt < STUCK_MS) return;
        List<String> b;
        synchronized (sLatest) {
            b = !sStart.isEmpty() ? sStart.remove(0) : sLive.get(sLiveIndex++ % sLive.size());
        }
        sInFlight = b.size() + 1; // + the header restore
        sSentAt = now;
        if (!HeObd.sendBatch(b)) sInFlight = 0;
    }

    /** One of our commands answered (DriveDeck's handler thread). */
    static void onAnswer(String cmd, String raw) {
        if (sInFlight > 0) sInFlight--;
        String text = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (cmd.startsWith("AT")) {
            if (text.contains("?")) synchronized (sLatest) { if (sBadAt.add(cmd)) HeLog.w("obd ext: adapter refused " + cmd); }
            return;
        }
        String id = HeObdUds.replyId(raw);
        String unit = HeObdUds.unitOfReply(id);
        String asked = unitOfCommand(cmd);
        count(asked, false);
        if (unit == null) return;
        int[] p = HeObdUds.payload(raw, id);
        if (p == null) return;
        long now = System.currentTimeMillis();
        if (cmd.startsWith("19")) {
            List<String> c = HeObdUds.dtcs(p);
            if (p.length >= 2 && p[0] == 0x59) {
                synchronized (sLatest) { sCodes.put(unit, c); }
                count(unit, true);
                HeLog.i("obd ext codes " + unit + ": " + c);
            }
            return;
        }
        Map<String, Double> v = HeObdUds.values(unit, cmd, p);
        if (v.isEmpty()) return;
        count(unit, true);
        synchronized (sLatest) {
            for (Map.Entry<String, Double> e : v.entrySet()) {
                String k = e.getKey();
                if (isStat(k)) sStat.put(k, e.getValue());
                else sLatest.put(k, new double[] { e.getValue(), now });
            }
        }
    }

    /** Which unit a command went to: the batches always start with the unit's ATSH, so the reply id decides; this
     *  is only for counting units that never answer. */
    private static String unitOfCommand(String cmd) {
        for (HeObdUds.Did d : HeObdUds.DIDS) if (d.cmd.equals(cmd)) return d.unit;
        return null;
    }

    private static void count(String unit, boolean answered) {
        if (unit == null) return;
        synchronized (sLatest) {
            int[] t = sTries.get(unit);
            if (t == null) sTries.put(unit, t = new int[2]);
            if (answered) t[1]++; else t[0]++;
        }
    }

    private static boolean isStat(String k) {
        return k.startsWith("tpms") || k.equals("odo") || k.endsWith("Ok") || k.endsWith("Fail") || k.endsWith("Prot")
                || k.endsWith("AdKm") || k.endsWith("Fric");
    }

    private static double latest(String k, long now) {
        double[] v = sLatest.get(k);
        return v != null && now - v[1] <= FRESH_MS ? v[0] : Double.NaN;
    }

    /** Copies the fresh values into a drive sample (once per poll cycle, from HeObd.onSpeed). */
    static void fill(HeObdMath.Sample s, long now) {
        if (!sOn) return;
        synchronized (sLatest) {
            double[] raw = new double[4];
            double sum = 0;
            boolean all = true;
            for (int i = 0; i < 4; i++) {
                raw[i] = latest("wRaw" + i, now);
                if (!HeObdMath.ok(raw[i])) all = false; else sum += raw[i];
            }
            if (all) {
                double sc = HeObdUds.wheelScale(sum / 4, s.speed);
                if (HeObdMath.ok(sc)) { if (sc == 0.1) sScaleA++; else sScaleB++; }
                double scale = sScaleA + sScaleB == 0 ? Double.NaN : (sScaleA >= sScaleB ? 0.1 : 921.59 / 65535.0);
                if (HeObdMath.ok(scale)) for (int i = 0; i < 4; i++) s.wheel[i] = raw[i] * scale;
            }
            s.steer = latest("steer", now);
            s.gear = HeObdUds.gear(latest("gearRaw", now));
            s.clutch1T = latest("c1T", now);
            s.clutch2T = latest("c2T", now);
            s.dsgPressure = latest("dsgP", now);
            for (int i = 0; i < 4; i++) {
                s.misfire[i] = latest("mis" + i, now);
                s.knock[i] = latest("kr" + i, now);
                s.corr[i] = latest("corr" + i, now);
            }
            s.oilT = latest("oilT", now);
            s.torque = latest("torque", now);
        }
    }

    static Map<String, Double> stat() {
        synchronized (sLatest) { return new LinkedHashMap<String, Double>(sStat); }
    }

    static Map<String, List<String>> codes() {
        synchronized (sLatest) { return new LinkedHashMap<String, List<String>>(sCodes); }
    }

    /** Units asked at least 3 times without one good answer. */
    static List<String> failed() {
        List<String> l = new ArrayList<String>();
        synchronized (sLatest) {
            for (Map.Entry<String, int[]> e : sTries.entrySet()) {
                if (e.getValue()[0] >= 3 && e.getValue()[1] == 0) l.add(HeObdUds.unitName(e.getKey()));
            }
        }
        return l;
    }

    /** Live rows for the check screen while recording. */
    static List<String[]> live(HeObdMath.Sample s) {
        List<String[]> l = new ArrayList<String[]>();
        if (s == null) return l;
        l.add(new String[] { "גלגלים (ABS) ק.ש / ק.י / א.ש / א.י", HeObdUds.n1(s.wheel[0]) + " / " + HeObdUds.n1(s.wheel[1]) + " / "
                + HeObdUds.n1(s.wheel[2]) + " / " + HeObdUds.n1(s.wheel[3]) + " קמ\"ש" });
        l.add(new String[] { "הילוך / חום מצמד 1, 2", HeObdUds.n0(s.gear) + " / " + HeObdUds.n0(s.clutch1T) + "°, " + HeObdUds.n0(s.clutch2T) + "°" });
        l.add(new String[] { "מוני החמצות 1-4", HeObdUds.n0(s.misfire[0]) + " / " + HeObdUds.n0(s.misfire[1]) + " / "
                + HeObdUds.n0(s.misfire[2]) + " / " + HeObdUds.n0(s.misfire[3]) });
        l.add(new String[] { "איחור הצתה (נקישות) 1-4", HeObdUds.n1(s.knock[0]) + " / " + HeObdUds.n1(s.knock[1]) + " / "
                + HeObdUds.n1(s.knock[2]) + " / " + HeObdUds.n1(s.knock[3]) + "°" });
        l.add(new String[] { "שמן מנוע / מומנט", HeObdUds.n0(s.oilT) + "° / " + HeObdUds.n0(s.torque) + " Nm" });
        return l;
    }
}
