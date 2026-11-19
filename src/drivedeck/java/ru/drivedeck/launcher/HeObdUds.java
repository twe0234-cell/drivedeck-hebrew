package ru.drivedeck.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extended drive check, pure part (no Android classes, so it runs on a plain JVM): which manufacturer values
 * (UDS service 22) are read from which control unit, how an ELM327 answer is put back together (ISO-TP frames),
 * how a value is decoded, and the findings for a recorded drive.
 *
 * Addresses, identifiers and formulas come from the vehicle profile DriveDeck already ships for this engine
 * ("MQB, PQ26: TSI/TFSI EA211", assets/he_obd/profiles/8816d932...): value = raw * MUL / DIV + OFS, raw = DL bytes
 * big-endian from byte SBI of the data after "62 <id>". Only read services (22, 19) are ever sent.
 *
 * Car: Seat Leon 5F 1.2 TSI (CYVB), 7-speed dry DSG DQ200, indirect tyre-pressure warning (the ABS unit compares
 * wheel speeds: a soft or different tyre turns faster).
 */
public final class HeObdUds {
    private HeObdUds() { }

    public static final String ECM = "7E0", TCM = "7E1", ABS = "713";
    static final String[] WHEEL = { "קדמי שמאלי", "קדמי ימני", "אחורי שמאלי", "אחורי ימני" };

    /** The CAN id the unit answers from. */
    static String reply(String header) {
        if (ECM.equals(header)) return "7E8";
        if (TCM.equals(header)) return "7E9";
        if (ABS.equals(header)) return "77D";
        return null;
    }

    static String unitOfReply(String id) {
        if ("7E8".equals(id)) return ECM;
        if ("7E9".equals(id)) return TCM;
        if ("77D".equals(id)) return ABS;
        return null;
    }

    static String unitName(String header) {
        if (ECM.equals(header)) return "מנוע";
        if (TCM.equals(header)) return "גיר DSG";
        if (ABS.equals(header)) return "ABS";
        return header;
    }

    /** One value inside one identifier's answer. */
    public static final class Did {
        final String unit, cmd, key;
        final int sbi, dl;
        final double mul, div, ofs;

        Did(String unit, String cmd, String key, int sbi, int dl, double mul, double div, double ofs) {
            this.unit = unit; this.cmd = cmd; this.key = key; this.sbi = sbi; this.dl = dl;
            this.mul = mul; this.div = div; this.ofs = ofs;
        }
    }

    static final Did[] DIDS = {
        // engine
        new Did(ECM, "22114F", "mis0", 0, 2, 1, 1, 0), new Did(ECM, "221152", "mis1", 0, 2, 1, 1, 0),
        new Did(ECM, "221150", "mis2", 0, 2, 1, 1, 0), new Did(ECM, "221151", "mis3", 0, 2, 1, 1, 0),
        new Did(ECM, "2210E2", "kr0", 0, 1, 0.75, 1, 0), new Did(ECM, "2210E5", "kr1", 0, 1, 0.75, 1, 0),
        new Did(ECM, "2210E3", "kr2", 0, 1, 0.75, 1, 0), new Did(ECM, "2210E4", "kr3", 0, 1, 0.75, 1, 0),
        new Did(ECM, "22171A", "corr0", 0, 2, 7.999, 65535, 0), new Did(ECM, "22171D", "corr1", 0, 2, 7.999, 65535, 0),
        new Did(ECM, "22171B", "corr2", 0, 2, 7.999, 65535, 0), new Did(ECM, "22171C", "corr3", 0, 2, 7.999, 65535, 0),
        new Did(ECM, "2211BD", "oilT", 0, 2, 0.1, 1, -273.1),
        new Did(ECM, "221010", "torque", 0, 2, 0.1, 1, 0),
        new Did(ECM, "2210E0", "odo", 0, 4, 1, 1, 0),
        // DSG DQ200
        new Did(TCM, "223816", "gearRaw", 0, 1, 1, 1, 0),
        new Did(TCM, "22102B", "c1T", 0, 2, 1, 1, 0), new Did(TCM, "22102C", "c2T", 0, 2, 1, 1, 0),
        new Did(TCM, "221191", "dsgP", 0, 2, 0.01, 1, 0),
        new Did(TCM, "221062", "c1Fric", 0, 2, 0.09766, 1, 0), new Did(TCM, "221077", "c2Fric", 0, 2, 0.09766, 1, 0),
        new Did(TCM, "2210C9", "c1Ok", 0, 2, 1, 1, 0), new Did(TCM, "2210CC", "c1Fail", 0, 2, 1, 1, 0),
        new Did(TCM, "2210EA", "c2Ok", 0, 2, 1, 1, 0), new Did(TCM, "2210ED", "c2Fail", 0, 2, 1, 1, 0),
        new Did(TCM, "221069", "c1Prot", 0, 4, 1, 1, 0), new Did(TCM, "22107E", "c2Prot", 0, 4, 1, 1, 0),
        new Did(TCM, "2210CA", "c1AdKm", 0, 2, 10, 1, 0), new Did(TCM, "2210EB", "c2AdKm", 0, 2, 10, 1, 0),
        // ABS: raw wheel speeds (the scale is picked against the OBD speed, the profile has two variants)
        new Did(ABS, "221800", "wRaw0", 0, 2, 1, 1, 0), new Did(ABS, "221801", "wRaw1", 0, 2, 1, 1, 0),
        new Did(ABS, "221802", "wRaw2", 0, 2, 1, 1, 0), new Did(ABS, "221803", "wRaw3", 0, 2, 1, 1, 0),
        new Did(ABS, "221804", "steer", 10, 2, 0.4375, 1, 0),
        // ABS tyre-pressure (indirect) record: calibrations and warnings by odometer, suspicion per wheel
        new Did(ABS, "221821", "tpmsAvail", 0, 1, 1, 1, 0),
        new Did(ABS, "221821", "tpmsCal0", 1, 4, 1, 1, 0), new Did(ABS, "221821", "tpmsCal1", 5, 4, 1, 1, 0),
        new Did(ABS, "221821", "tpmsCal2", 9, 4, 1, 1, 0), new Did(ABS, "221821", "tpmsCal3", 13, 4, 1, 1, 0),
        new Did(ABS, "221821", "tpmsWarn0", 17, 4, 1, 1, 0), new Did(ABS, "221821", "tpmsWarn1", 21, 4, 1, 1, 0),
        new Did(ABS, "221821", "tpmsWarn2", 25, 4, 1, 1, 0), new Did(ABS, "221821", "tpmsWarn3", 29, 4, 1, 1, 0),
        new Did(ABS, "221821", "tpmsDiff0", 33, 1, 1, 1, 0), new Did(ABS, "221821", "tpmsDiff1", 34, 1, 1, 1, 0),
        new Did(ABS, "221821", "tpmsDiff2", 35, 1, 1, 1, 0), new Did(ABS, "221821", "tpmsDiff3", 36, 1, 1, 1, 0),
        new Did(ABS, "221821", "tpmsFlat0", 37, 1, 1, 1, 0), new Did(ABS, "221821", "tpmsFlat1", 38, 1, 1, 1, 0),
        new Did(ABS, "221821", "tpmsFlat2", 39, 1, 1, 1, 0), new Did(ABS, "221821", "tpmsFlat3", 40, 1, 1, 1, 0),
    };

    static final String WHEEL_SCALE_A = "0.1", WHEEL_SCALE_B = "921.59/65535";

    // ------------------------------------------------------------------ batches (adapter commands)

    /** Restores the adapter after a batch to ABS (the profile's "after" commands for that unit). */
    private static final String[] ABS_IN = { "ATSH713", "ATCRA77D" };
    private static final String[] ABS_IN_LONG = { "ATSH713", "ATFCSH713", "ATFCSD300000", "ATFCSM1", "ATCRA77D" };
    private static final String[] ABS_OUT = { "ATAR" };
    private static final String[] ABS_OUT_LONG = { "ATFCSM0", "ATAR" };

    static List<String> batch(String[] in, String[] cmds, String[] out) {
        List<String> l = new ArrayList<String>();
        for (String s : in) l.add(s);
        for (String s : cmds) l.add(s);
        for (String s : out) l.add(s);
        return l;
    }

    /** Once at the start: odometer, stored codes of the three units, DSG counters, the tyre-pressure record. */
    static List<List<String>> startBatches() {
        List<List<String>> b = new ArrayList<List<String>>();
        b.add(probeBatch());
        b.add(batch(new String[] { "ATSH7E0" }, new String[] { "2210E0", "1902FF" }, new String[0]));
        b.add(batch(new String[] { "ATSH7E1" }, new String[] { "221062", "221077", "2210C9", "2210CC", "2210EA", "2210ED",
                "221069", "22107E", "2210CA", "2210EB", "1902FF" }, new String[0]));
        b.add(batch(ABS_IN_LONG, new String[] { "221821", "1902FF", "221804" }, ABS_OUT_LONG));
        return b;
    }

    /**
     * Once at the start, to see why physical requests got NO DATA (11-19: every 22/19 to 7E0, 7E1 and 713): the same
     * engine asked physically for a standard PID (0100), for the VIN in UDS (22F190), the VIN again as a raw padded
     * frame (CAF0: rules out a clone that sends short frames), and the VIN functionally on 7DF. All reads.
     */
    static List<String> probeBatch() {
        return batch(new String[] { "ATSH7E0", "ATCRA7E8" },
                new String[] { "0100", "22F190", "ATCAF0", "0322F19000000000", "ATCAF1", "ATAR", "ATSH7DF", "22F190" },
                new String[0]);
    }

    /** Which unit a batch talks to (its first header), or null. */
    static String unitOfBatch(List<String> b) {
        for (String c : b) {
            if (c.equals("ATSH7E0")) return ECM;
            if (c.equals("ATSH7E1")) return TCM;
            if (c.equals("ATSH713")) return ABS;
        }
        return null;
    }

    /** The live rotation: wheel speeds every other batch, engine and gearbox in between. */
    static List<List<String>> liveBatches() {
        List<String> abs = batch(ABS_IN, new String[] { "221800", "221801", "221802", "221803" }, ABS_OUT);
        List<String> ecmA = batch(new String[] { "ATSH7E0" },
                new String[] { "22114F", "221152", "221150", "221151", "2210E2", "2210E5", "2210E3", "2210E4" }, new String[0]);
        List<String> ecmB = batch(new String[] { "ATSH7E0" },
                new String[] { "22171A", "22171D", "22171B", "22171C", "2211BD", "221010" }, new String[0]);
        List<String> tcm = batch(new String[] { "ATSH7E1" }, new String[] { "223816", "22102B", "22102C", "221191" }, new String[0]);
        List<String> steer = batch(ABS_IN_LONG, new String[] { "221804" }, ABS_OUT_LONG);
        List<List<String>> b = new ArrayList<List<String>>();
        b.add(abs); b.add(ecmA); b.add(abs); b.add(tcm); b.add(abs); b.add(ecmB); b.add(abs); b.add(tcm); b.add(steer);
        return b;
    }

    // ------------------------------------------------------------------ parsing

    private static int hex2(String s, int i) {
        try { return Integer.parseInt(s.substring(i, i + 2), 16); } catch (RuntimeException e) { return -1; }
    }

    private static void addBytes(List<Integer> out, String s, int from) {
        for (int i = from; i + 2 <= s.length(); i += 2) {
            int v = hex2(s, i);
            if (v < 0) return;
            out.add(v);
        }
    }

    /**
     * The UDS answer bytes put back together from the ISO-TP frames sent from [reply] (headers on, as DriveDeck
     * sets the adapter): single frame "77D 05 62 18 00 01 2C", or first frame "10 2B ..." plus "21 ..", "22 ..".
     * Null when nothing complete came from that id.
     */
    public static int[] payload(String raw, String reply) {
        if (raw == null || reply == null) return null;
        String[] tokens = raw.toUpperCase(Locale.ROOT).split("[^0-9A-F]+");
        List<Integer> data = new ArrayList<Integer>();
        int total = -1;
        for (String t : tokens) {
            if (t.length() < 5 || !t.startsWith(reply)) continue;
            String f = t.substring(3);
            int pci = hex2(f, 0);
            if (pci < 0) continue;
            int type = pci >> 4;
            if (type == 0) {
                int len = pci & 0xF;
                List<Integer> one = new ArrayList<Integer>();
                addBytes(one, f, 2);
                if (len == 0 || one.size() < len) continue;
                return toArray(one.subList(0, len));
            } else if (type == 1) {
                int lo = hex2(f, 2);
                if (lo < 0) continue;
                total = ((pci & 0xF) << 8) | lo;
                data.clear();
                addBytes(data, f, 4);
            } else if (type == 2 && total > 0) {
                addBytes(data, f, 2);
            }
        }
        if (total > 0 && data.size() >= total) return toArray(data.subList(0, total));
        return null;
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** The CAN id of the first frame in an answer (e.g. "77D"), or null. */
    static String replyId(String raw) {
        if (raw == null) return null;
        for (String t : raw.toUpperCase(Locale.ROOT).split("[^0-9A-F]+")) {
            if (t.length() >= 7 && (t.startsWith("7E") || t.startsWith("77"))) return t.substring(0, 3);
        }
        return null;
    }

    /** Data after "62 <id>" for a service-22 command, null if negative / missing. */
    static int[] data22(int[] p, String cmd) {
        if (p == null || cmd == null || cmd.length() != 6 || p.length < 3 || p[0] != 0x62) return null;
        int id = Integer.parseInt(cmd.substring(2), 16);
        if (((p[1] << 8) | p[2]) != id) return null;
        int[] d = new int[p.length - 3];
        System.arraycopy(p, 3, d, 0, d.length);
        return d;
    }

    static double decode(Did d, int[] data) {
        if (data == null || d.sbi + d.dl > data.length) return Double.NaN;
        long raw = 0;
        for (int i = 0; i < d.dl; i++) raw = (raw << 8) | data[d.sbi + i];
        return raw * d.mul / d.div + d.ofs;
    }

    /** All values one answer carries (one identifier can hold several, e.g. the tyre-pressure record). */
    static Map<String, Double> values(String unit, String cmd, int[] payload) {
        Map<String, Double> m = new LinkedHashMap<String, Double>();
        int[] data = data22(payload, cmd);
        if (data == null) return m;
        for (Did d : DIDS) {
            if (!d.unit.equals(unit) || !d.cmd.equals(cmd)) continue;
            double v = decode(d, data);
            if (!Double.isNaN(v)) m.put(d.key, v);
        }
        return m;
    }

    /** Stored codes from a "59 02 <mask> (dtc dtc dtc status)*" answer, as "P0301 00" + " ✱" when confirmed. */
    static List<String> dtcs(int[] p) {
        List<String> out = new ArrayList<String>();
        if (p == null || p.length < 3 || p[0] != 0x59 || p[1] != 0x02) return out;
        for (int i = 3; i + 4 <= p.length; i += 4) {
            int a = p[i], b = p[i + 1], c = p[i + 2], st = p[i + 3];
            if (a == 0 && b == 0 && c == 0) continue;
            String code = "PCBU".charAt(a >> 6) + Integer.toString((a >> 4) & 3) + Integer.toHexString(a & 0xF).toUpperCase(Locale.ROOT)
                    + String.format(Locale.US, "%02X %02X", b, c);
            out.add(code + ((st & 0x08) != 0 ? " ✱" : "") + ((st & 0x01) != 0 ? " (פעיל)" : ""));
        }
        return out;
    }

    /** Raw wheel value -> km/h: the profile has two scales, the one closer to the OBD speed wins (NaN if unknown). */
    static double wheelScale(double rawMean, double obdSpeed) {
        if (!HeObdMath.ok(rawMean) || !HeObdMath.ok(obdSpeed) || obdSpeed < 15 || rawMean <= 0) return Double.NaN;
        double a = rawMean * 0.1, b = rawMean * 921.59 / 65535.0;
        double ea = Math.abs(a / obdSpeed - 1), eb = Math.abs(b / obdSpeed - 1);
        double best = ea <= eb ? 0.1 : 921.59 / 65535.0;
        return Math.min(ea, eb) < 0.15 ? best : Double.NaN;
    }

    /** DQ200 reports gear + 2 (0 = R? 2 = N); as the profile's DQ200 formula. */
    static double gear(double raw) {
        if (!HeObdMath.ok(raw)) return Double.NaN;
        double g = raw - 2;
        return g >= -1 && g <= 7 ? g : Double.NaN;
    }

    // ------------------------------------------------------------------ findings

    static String f(double v, int d) { return HeObdMath.f(v, d); }

    static String sgn(double v, int d) { return (v >= 0 ? "+" : "") + f(v, d); }

    /**
     * Findings for the extended part of a drive. [stat] = one-shot values (odometer, DSG counters, tyre-pressure
     * record), [codes] = stored codes per unit (null = not read), [failed] = units that did not answer.
     */
    public static List<HeObdMath.Finding> evaluate(List<HeObdMath.Sample> samples, Map<String, Double> stat,
                                                   Map<String, List<String>> codes, List<String> failed) {
        List<HeObdMath.Finding> out = new ArrayList<HeObdMath.Finding>();
        wheels(samples, out);
        tpms(stat, out);
        misfires(samples, out);
        knock(samples, out);
        cylinders(samples, out);
        dsg(samples, stat, out);
        if (codes != null) {
            for (Map.Entry<String, List<String>> e : codes.entrySet()) {
                if (e.getValue().isEmpty()) out.add(new HeObdMath.Finding(HeObdMath.OK, "תקלות ב" + unitName(e.getKey()), "אין תקלות שמורות."));
                else out.add(new HeObdMath.Finding(HeObdMath.WARN, "תקלות ב" + unitName(e.getKey()),
                        e.getValue().size() + ": " + join(e.getValue()) + " (✱ = מאושרת)"));
            }
        }
        if (failed != null && !failed.isEmpty()) {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "לא ענו", join(failed)
                    + ". ייתכן שהמתאם לא תומך בפקודות הנדרשות, או שהיחידה לא עונה בזמן נסיעה."));
        }
        return out;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(", "); b.append(s); }
        return b.toString();
    }

    private static boolean all(double[] v) {
        for (double d : v) if (!HeObdMath.ok(d)) return false;
        return true;
    }

    /** Wheel speeds: a soft / worn / different tyre turns faster; a bad sensor or ring drops out or jumps. */
    static void wheels(List<HeObdMath.Sample> samples, List<HeObdMath.Finding> out) {
        List<List<Double>> r = new ArrayList<List<Double>>();
        for (int i = 0; i < 4; i++) r.add(new ArrayList<Double>());
        int[] spikes = new int[4];
        int moving = 0;
        for (HeObdMath.Sample s : samples) {
            if (!all(s.wheel)) continue;
            double m = (s.wheel[0] + s.wheel[1] + s.wheel[2] + s.wheel[3]) / 4;
            if (m < 15) continue;
            moving++;
            for (int i = 0; i < 4; i++) {
                double others = (m * 4 - s.wheel[i]) / 3;
                double d = s.wheel[i] / others - 1;
                if (Math.abs(d) > 0.06) {
                    boolean othersAgree = true;
                    for (int j = 0; j < 4; j++) if (j != i && Math.abs(s.wheel[j] / others - 1) > 0.02) othersAgree = false;
                    if (othersAgree) spikes[i]++;
                }
            }
            boolean straight = !HeObdMath.ok(s.steer) || s.steer < 15 || s.steer > 65536 * 0.4375 - 15;
            if (m < 30 || !straight) continue;
            for (int i = 0; i < 4; i++) r.get(i).add(s.wheel[i] / m);
        }
        if (moving < 10) {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "חיישני ABS",
                    "לא התקבלו מהירויות גלגלים (צריך נסיעה מעל 30 קמ\"ש). אם זה חוזר, המתאם לא מצליח לדבר עם יחידת ה-ABS."));
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (spikes[i] >= 3 && spikes[i] * 50 >= moving) {
                out.add(new HeObdMath.Finding(HeObdMath.BAD, "חיישן ABS " + WHEEL[i],
                        "הקריאה קופצת או נופלת " + spikes[i] + " פעמים מתוך " + moving + " דגימות, כשהגלגלים האחרים יציבים: "
                                + "חיישן, חיווט או טבעת חיישן פגומים. זה מונע מהמערכת לכייל את התראת הלחץ בצמיגים."));
            }
        }
        if (r.get(0).size() < 20) {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "השוואת גלגלים", "צריך עוד נסיעה ישרה מעל 30 קמ\"ש להשוואה."));
            return;
        }
        double[] dev = new double[4];
        int worst = 0;
        for (int i = 0; i < 4; i++) {
            dev[i] = (HeObdMath.median(r.get(i)) - 1) * 100;
            if (Math.abs(dev[i]) > Math.abs(dev[worst])) worst = i;
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 4; i++) d.append(i > 0 ? ", " : "").append(WHEEL[i]).append(' ').append(sgn(dev[i], 2)).append('%');
        String all = " (" + d + ", " + r.get(0).size() + " דגימות)";
        if (Math.abs(dev[worst]) >= 0.5) {
            out.add(new HeObdMath.Finding(HeObdMath.WARN, "גלגל " + WHEEL[worst],
                    (dev[worst] > 0 ? "מסתובב מהר יותר מהשאר: לחץ נמוך, צמיג שחוק או צמיג במידה שונה."
                            : "מסתובב לאט יותר מהשאר: ייתכן שבשאר הצמיגים הלחץ נמוך, או שהצמיג הזה במידה אחרת.")
                            + " זה בדיוק מה שמדליק את נורת הלחץ, ומעלה צריכת דלק." + all));
        } else if (Math.abs(dev[worst]) >= 0.3) {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "גלגלים", "הבדל קטן בין הגלגלים, בגבול הנורמה" + all));
        } else {
            out.add(new HeObdMath.Finding(HeObdMath.OK, "גלגלים וחיישני ABS", "4 הגלגלים מסתובבים כמעט זהה" + all));
        }
    }

    static String wheelOf(int i) { return i >= 0 && i < 4 ? WHEEL[i] : "?"; }

    /** The ABS tyre-pressure record: did the last calibration hold, when was the last warning, which wheel. */
    static void tpms(Map<String, Double> st, List<HeObdMath.Finding> out) {
        if (st == null || !st.containsKey("tpmsCal0")) {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "התראת לחץ בצמיגים", "לא התקבלה רשומת הכיול מיחידת ה-ABS (תשובה ארוכה: ייתכן שהמתאם לא תומך)."));
            return;
        }
        double odo = st.containsKey("odo") ? st.get("odo") : Double.NaN;
        StringBuilder b = new StringBuilder();
        b.append("כיול אחרון בק\"מ ").append(f(st.get("tpmsCal0"), 0));
        if (st.containsKey("tpmsCal1")) b.append(" (לפניו ").append(f(st.get("tpmsCal1"), 0)).append(')');
        if (st.containsKey("tpmsWarn0")) b.append(", אזהרה אחרונה בק\"מ ").append(f(st.get("tpmsWarn0"), 0));
        if (HeObdMath.ok(odo)) b.append(", עכשיו ").append(f(odo, 0));
        b.append('.');
        StringBuilder sus = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            Double df = st.get("tpmsDiff" + i), fl = st.get("tpmsFlat" + i);
            if ((df != null && df > 0) || (fl != null && fl > 0)) {
                sus.append(sus.length() > 0 ? ", " : "").append(WHEEL[i]);
            }
        }
        if (sus.length() > 0) b.append(" המערכת סימנה חשד ל: ").append(sus).append(" (דליפה איטית או פנצ'ר).");
        boolean recentWarn = HeObdMath.ok(odo) && st.containsKey("tpmsWarn0") && st.get("tpmsWarn0") > 0
                && st.get("tpmsWarn0") >= st.get("tpmsCal0");
        out.add(new HeObdMath.Finding(recentWarn || sus.length() > 0 ? HeObdMath.WARN : HeObdMath.INFO, "התראת לחץ בצמיגים",
                b + (recentWarn ? " האזהרה האחרונה אחרי הכיול: הכיול לא החזיק." : "")));
    }

    /** Misfire counters per cylinder: what they added during the drive, and in which gear. */
    static void misfires(List<HeObdMath.Sample> samples, List<HeObdMath.Finding> out) {
        double[] total = new double[4];
        Map<Integer, double[]> byGear = new LinkedHashMap<Integer, double[]>();
        double[] last = HeObdMath.Sample.nan(4);
        boolean any = false;
        for (HeObdMath.Sample s : samples) {
            for (int i = 0; i < 4; i++) {
                double v = s.misfire[i];
                if (!HeObdMath.ok(v)) continue;
                any = true;
                if (HeObdMath.ok(last[i]) && v > last[i]) {
                    double d = v - last[i];
                    total[i] += d;
                    int g = HeObdMath.ok(s.gear) ? (int) s.gear : -9;
                    double[] a = byGear.get(g);
                    if (a == null) byGear.put(g, a = new double[4]);
                    a[i] += d;
                }
                last[i] = v;
            }
        }
        if (!any) {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "החמצות הצתה", "מחשב המנוע לא החזיר את מוני ההחמצות."));
            return;
        }
        double sum = total[0] + total[1] + total[2] + total[3];
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 4; i++) d.append(i > 0 ? ", " : "").append("צילינדר ").append(i + 1).append(": ").append(f(total[i], 0));
        if (sum == 0) {
            out.add(new HeObdMath.Finding(HeObdMath.OK, "החמצות הצתה", "אין החמצות בנסיעה (" + d + ")."));
            return;
        }
        int worst = 0;
        for (int i = 1; i < 4; i++) if (total[i] > total[worst]) worst = i;
        StringBuilder g = new StringBuilder();
        for (Map.Entry<Integer, double[]> e : byGear.entrySet()) {
            double[] a = e.getValue();
            double n = a[0] + a[1] + a[2] + a[3];
            if (n > 0) g.append(g.length() > 0 ? ", " : "").append(e.getKey() == -9 ? "הילוך לא ידוע" : "הילוך " + e.getKey()).append(": ").append(f(n, 0));
        }
        boolean one = total[worst] >= sum * 0.7;
        out.add(new HeObdMath.Finding(sum >= 20 ? HeObdMath.BAD : HeObdMath.WARN, "החמצות הצתה",
                d + (g.length() > 0 ? ". לפי הילוך: " + g : "") + "."
                        + (one ? " רובן בצילינדר " + (worst + 1) + ": להתחיל בסליל ההצתה ובמצת שלו (אפשר להחליף סליל עם צילינדר שכן ולראות אם ההחמצה עוברת)."
                        : " מפוזרות בין הצילינדרים: מצתים שחוקים, דלק, או דליפת אוויר.")
                        + " החמצה גורמת לרעידה ולצריכת דלק גבוהה."));
    }

    /** Knock retard per cylinder (deg). */
    static void knock(List<HeObdMath.Sample> samples, List<HeObdMath.Finding> out) {
        List<List<Double>> v = new ArrayList<List<Double>>();
        for (int i = 0; i < 4; i++) v.add(new ArrayList<Double>());
        double[] max = new double[4];
        for (HeObdMath.Sample s : samples) {
            for (int i = 0; i < 4; i++) {
                if (!HeObdMath.ok(s.knock[i])) continue;
                v.get(i).add(s.knock[i]);
                max[i] = Math.max(max[i], s.knock[i]);
            }
        }
        if (v.get(0).size() < 10) return;
        double[] avg = new double[4];
        double all = 0;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            double sum = 0;
            for (double x : v.get(i)) sum += x;
            avg[i] = v.get(i).isEmpty() ? 0 : sum / v.get(i).size();
            all += avg[i] / 4;
            d.append(i > 0 ? ", " : "").append(i + 1).append(": ").append(f(avg[i], 1)).append("° (עד ").append(f(max[i], 1)).append("°)");
        }
        int worst = 0;
        for (int i = 1; i < 4; i++) if (avg[i] > avg[worst]) worst = i;
        if (avg[worst] >= 3) {
            out.add(new HeObdMath.Finding(HeObdMath.WARN, "נקישות במנוע",
                    "המחשב מאחר הצתה בגלל נקישות: " + d + ". דלק באיכות נמוכה, פחמן בתאי השריפה או מצתים. מוריד כוח ומעלה צריכה."));
        } else if (avg[worst] >= 1.5 && avg[worst] > 2 * all) {
            out.add(new HeObdMath.Finding(HeObdMath.WARN, "נקישות בצילינדר " + (worst + 1), "בולט מול השאר: " + d + "."));
        } else {
            out.add(new HeObdMath.Finding(HeObdMath.OK, "נקישות במנוע", "נמוך ואחיד (" + d + ")."));
        }
    }

    /** Fuel correction factor per cylinder: one cylinder apart = injector or compression on that cylinder. */
    static void cylinders(List<HeObdMath.Sample> samples, List<HeObdMath.Finding> out) {
        List<List<Double>> v = new ArrayList<List<Double>>();
        for (int i = 0; i < 4; i++) v.add(new ArrayList<Double>());
        for (HeObdMath.Sample s : samples) for (int i = 0; i < 4; i++) if (HeObdMath.ok(s.corr[i]) && s.corr[i] > 0) v.get(i).add(s.corr[i]);
        if (v.get(0).size() < 5) return;
        double[] m = new double[4];
        double mean = 0;
        for (int i = 0; i < 4; i++) { m[i] = HeObdMath.median(v.get(i)); mean += m[i] / 4; }
        int worst = 0;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (Math.abs(m[i] - mean) > Math.abs(m[worst] - mean)) worst = i;
            d.append(i > 0 ? ", " : "").append(i + 1).append(": ").append(f(m[i], 3));
        }
        double pct = (m[worst] / mean - 1) * 100;
        if (Math.abs(pct) >= 5) {
            out.add(new HeObdMath.Finding(HeObdMath.WARN, "תיקון דלק בצילינדר " + (worst + 1),
                    "שונה ב-" + sgn(pct, 1) + "% מהשאר (" + d + "): מזרק או דחיסה בצילינדר הזה."));
        } else {
            out.add(new HeObdMath.Finding(HeObdMath.OK, "איזון צילינדרים", "תיקוני הדלק אחידים (" + d + ")."));
        }
    }

    /** DQ200: per-gear engine-to-road ratio spread (clutch slip / judder), clutch temperatures, counters. */
    static void dsg(List<HeObdMath.Sample> samples, Map<String, Double> st, List<HeObdMath.Finding> out) {
        Map<Integer, List<Double>> ratio = new java.util.TreeMap<Integer, List<Double>>();
        double maxC1 = Double.NaN, maxC2 = Double.NaN;
        HeObdMath.Sample prev = null;
        for (HeObdMath.Sample s : samples) {
            if (HeObdMath.ok(s.clutch1T)) maxC1 = HeObdMath.ok(maxC1) ? Math.max(maxC1, s.clutch1T) : s.clutch1T;
            if (HeObdMath.ok(s.clutch2T)) maxC2 = HeObdMath.ok(maxC2) ? Math.max(maxC2, s.clutch2T) : s.clutch2T;
            if (HeObdMath.ok(s.gear) && s.gear >= 1 && HeObdMath.ok(s.rpm) && HeObdMath.ok(s.speed) && s.speed >= 15 && s.rpm >= 1000
                    && prev != null && HeObdMath.ok(prev.speed) && Math.abs(s.speed - prev.speed) <= 2) {
                List<Double> l = ratio.get((int) s.gear);
                if (l == null) ratio.put((int) s.gear, l = new ArrayList<Double>());
                l.add(s.rpm / s.speed);
            }
            prev = s;
        }
        if (!ratio.isEmpty()) {
            StringBuilder d = new StringBuilder();
            Map<Integer, Double> spread = new LinkedHashMap<Integer, Double>();
            for (Map.Entry<Integer, List<Double>> e : ratio.entrySet()) {
                List<Double> l = e.getValue();
                if (l.size() < 8) continue;
                double med = HeObdMath.median(l);
                List<Double> dev = new ArrayList<Double>();
                for (double x : l) dev.add(Math.abs(x / med - 1));
                double sp = HeObdMath.median(dev) * 100;
                spread.put(e.getKey(), sp);
                d.append(d.length() > 0 ? ", " : "").append("הילוך ").append(e.getKey()).append(": ").append(f(med, 1))
                        .append(" סל\"ד לקמ\"ש ±").append(f(sp, 1)).append("% (").append(l.size()).append(')');
            }
            if (!spread.isEmpty()) {
                List<Double> all = new ArrayList<Double>(spread.values());
                double typical = HeObdMath.median(all);
                int worst = -1;
                for (Map.Entry<Integer, Double> e : spread.entrySet()) {
                    if (e.getValue() >= 2.0 && e.getValue() >= 2 * typical && (worst < 0 || e.getValue() > spread.get(worst))) worst = e.getKey();
                }
                if (worst > 0) {
                    out.add(new HeObdMath.Finding(HeObdMath.WARN, "גיר: החלקה בהילוך " + worst,
                            "היחס בין סיבובי המנוע למהירות לא יציב בהילוך הזה (" + d + "). בגיר DQ200 ההילוכים "
                                    + (worst % 2 == 0 ? "הזוגיים (2, 4, 6) עובדים על מצמד 2" : "האי-זוגיים (1, 3, 5, 7) עובדים על מצמד 1")
                                    + ": החלקה או רעידה במצמד הזה. שווה לבצע כיול מצמדים (Basic settings) ב-OBDeleven, ואם לא עוזר, לבדוק את המצמד."));
                } else {
                    out.add(new HeObdMath.Finding(HeObdMath.OK, "גיר: יציבות בהילוכים", d + "."));
                }
            }
        } else {
            out.add(new HeObdMath.Finding(HeObdMath.INFO, "גיר", "לא התקבל הילוך מהגיר; אין השוואה לפי הילוכים."));
        }
        StringBuilder c = new StringBuilder();
        if (HeObdMath.ok(maxC1) || HeObdMath.ok(maxC2)) c.append("חום מרבי מצמד 1/2: ").append(HeObdMath.ok(maxC1) ? f(maxC1, 0) : "-").append("°/")
                .append(HeObdMath.ok(maxC2) ? f(maxC2, 0) : "-").append("°. ");
        if (st != null) {
            if (st.containsKey("c1Ok") || st.containsKey("c2Ok")) c.append("כיולים מוצלחים/נכשלים מצמד 1: ").append(n(st, "c1Ok")).append('/').append(n(st, "c1Fail"))
                    .append(", מצמד 2: ").append(n(st, "c2Ok")).append('/').append(n(st, "c2Fail")).append(". ");
            if (st.containsKey("c1AdKm") || st.containsKey("c2AdKm")) c.append("כיול אחרון בק\"מ: ").append(n(st, "c1AdKm")).append(" / ").append(n(st, "c2AdKm")).append(". ");
            if (st.containsKey("c1Prot") || st.containsKey("c2Prot")) c.append("הגנת חום הופעלה: ").append(n(st, "c1Prot")).append(" / ").append(n(st, "c2Prot")).append(" פעמים. ");
            if (st.containsKey("c1Fric") || st.containsKey("c2Fric")) c.append("מקדם חיכוך: ").append(n2(st, "c1Fric")).append(" / ").append(n2(st, "c2Fric")).append('.');
        }
        if (c.length() > 0) {
            boolean warn = (st != null && (val(st, "c1Prot") > 0 || val(st, "c2Prot") > 0 || val(st, "c1Fail") > val(st, "c1Ok") || val(st, "c2Fail") > val(st, "c2Ok")))
                    || (HeObdMath.ok(maxC1) && maxC1 >= 170) || (HeObdMath.ok(maxC2) && maxC2 >= 170);
            out.add(new HeObdMath.Finding(warn ? HeObdMath.WARN : HeObdMath.INFO, "מצמדי ה-DSG", c.toString().trim()));
        }
    }

    private static double val(Map<String, Double> st, String k) { Double v = st.get(k); return v == null ? 0 : v; }
    private static String n(Map<String, Double> st, String k) { Double v = st.get(k); return v == null ? "-" : f(v, 0); }
    private static String n2(Map<String, Double> st, String k) { Double v = st.get(k); return v == null ? "-" : f(v, 2); }

    /** One log line for a sample (remote log, every 10 s while recording). */
    static String line(HeObdMath.Sample s) {
        StringBuilder b = new StringBuilder();
        b.append("g=").append(n0(s.gear)).append(" rpm=").append(n0(s.rpm)).append(" v=").append(n0(s.speed))
                .append(" w=").append(n1(s.wheel[0])).append('/').append(n1(s.wheel[1])).append('/').append(n1(s.wheel[2])).append('/').append(n1(s.wheel[3]))
                .append(" st=").append(n0(s.steer))
                .append(" mis=").append(n0(s.misfire[0])).append('/').append(n0(s.misfire[1])).append('/').append(n0(s.misfire[2])).append('/').append(n0(s.misfire[3]))
                .append(" kr=").append(n1(s.knock[0])).append('/').append(n1(s.knock[1])).append('/').append(n1(s.knock[2])).append('/').append(n1(s.knock[3]))
                .append(" corr=").append(n3(s.corr[0])).append('/').append(n3(s.corr[1])).append('/').append(n3(s.corr[2])).append('/').append(n3(s.corr[3]))
                .append(" ct=").append(n0(s.clutch1T)).append('/').append(n0(s.clutch2T)).append(" p=").append(n1(s.dsgPressure))
                .append(" oil=").append(n0(s.oilT)).append(" tq=").append(n0(s.torque))
                .append(" stft=").append(n1(s.stft)).append(" ltft=").append(n1(s.ltft)).append(" cool=").append(n0(s.coolant));
        return b.toString();
    }

    static String n0(double v) { return HeObdMath.ok(v) ? String.valueOf(Math.round(v)) : "-"; }
    static String n1(double v) { return HeObdMath.ok(v) ? f(v, 1) : "-"; }
    static String n3(double v) { return HeObdMath.ok(v) ? f(v, 3) : "-"; }
}
