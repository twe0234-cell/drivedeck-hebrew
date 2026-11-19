package ru.drivedeck.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure OBD-II maths for the Hebrew health check (no Android classes, so it runs on a plain JVM):
 * parsing an ELM327 answer, the SAE J1979 formulas, and the checks that turn a recorded drive into findings.
 *
 * Car: Seat Leon 5F 1.2 TSI (EA211, CYVB). It has NO mass-air-flow sensor (PID 0110 is not supported): the
 * engine measures air with a manifold-pressure (MAP) sensor. So instead of "clean the MAF" the check looks at
 * what really drifts on this engine: fuel trims (air leaks, injectors, fuel pressure, purge valve), MAP against
 * barometric pressure, the throttle body at idle, the thermostat, the charging voltage and the catalyst sensor.
 */
public final class HeObdMath {
    private HeObdMath() { }

    // ------------------------------------------------------------------ parsing

    /**
     * Data bytes of a mode-01 answer for [pid] (e.g. "010D"), or null. Accepts the raw text DriveDeck logs:
     * headers on or off, spaces or not, several ECUs answering (7E8 engine preferred), "NO DATA", "?", the prompt.
     */
    public static int[] parse(String raw, String pid) {
        if (raw == null || pid == null || pid.length() < 4) return null;
        String want = "4" + pid.substring(1, 2) + pid.substring(2, 4).toUpperCase(Locale.ROOT);
        String text = raw.toUpperCase(Locale.ROOT).replace(">", " ").replace("\r", "\n");
        int[] best = null;
        boolean bestIsEngine = false;
        for (String line : text.split("\n")) {
            String s = line.replace(" ", "").trim();
            if (s.isEmpty()) continue;
            boolean engine = false;
            int at = s.indexOf(want);
            if (at < 0) continue;
            // with headers: 7E8 + length byte + 41 PID ... (11-bit CAN)
            if (at >= 5 && s.startsWith("7E")) {
                engine = s.startsWith("7E8");
                int len = hex(s, 3);
                if (len > 1 && at == 5) {
                    int n = len - 2;
                    int[] b = bytes(s, at + 4, n);
                    if (b != null && (best == null || (engine && !bestIsEngine))) { best = b; bestIsEngine = engine; }
                    continue;
                }
            }
            int[] b = bytes(s, at + 4, (s.length() - at - 4) / 2);
            if (b != null && b.length > 0 && (best == null || (engine && !bestIsEngine))) { best = b; bestIsEngine = engine; }
        }
        return best;
    }

    private static int hex(String s, int i) {
        try { return Integer.parseInt(s.substring(i, i + 2), 16); } catch (RuntimeException e) { return -1; }
    }

    private static int[] bytes(String s, int from, int n) {
        if (n <= 0 || from + 2 * n > s.length()) return null;
        int[] b = new int[n];
        for (int i = 0; i < n; i++) {
            b[i] = hex(s, from + 2 * i);
            if (b[i] < 0) return null;
        }
        return b;
    }

    // ------------------------------------------------------------------ SAE J1979 formulas

    /** The value of a mode-01 PID in its usual unit, or NaN when the answer is too short or unknown. */
    public static double decode(String pid, int[] b) {
        if (b == null || b.length == 0) return Double.NaN;
        int a = b[0];
        int ab = b.length >= 2 ? a * 256 + b[1] : -1;
        switch (pid.toUpperCase(Locale.ROOT)) {
            case "0104": case "0111": case "012F": case "0145": case "0147": case "0149": case "014A": case "014C":
                return a * 100.0 / 255.0;                 // %
            case "0105": case "010F": case "0146":
                return a - 40;                             // °C
            case "0106": case "0107": case "0108": case "0109":
                return (a - 128) * 100.0 / 128.0;          // % fuel trim
            case "010B": case "0133":
                return a;                                  // kPa
            case "010C":
                return ab < 0 ? Double.NaN : ab / 4.0;     // rpm
            case "010D":
                return a;                                  // km/h
            case "010E":
                return a / 2.0 - 64.0;                     // ° before TDC
            case "0110":
                return ab < 0 ? Double.NaN : ab / 100.0;   // g/s
            case "0114": case "0115":
                return a / 200.0;                          // V (narrow-band O2), b[1] = its trim
            case "0123":
                return ab < 0 ? Double.NaN : ab * 10.0;    // kPa
            case "013C":
                return ab < 0 ? Double.NaN : ab / 10.0 - 40; // °C
            case "0142":
                return ab < 0 ? Double.NaN : ab / 1000.0;  // V
            case "0143":
                return ab < 0 ? Double.NaN : ab * 100.0 / 255.0; // %
            case "0144":
                return ab < 0 ? Double.NaN : ab * 2.0 / 65536.0; // lambda
            case "0101":
                return a;                                  // bit 7 = MIL, bits 0-6 = number of codes
            default:
                return Double.NaN;
        }
    }

    /** "P0171"-style codes from a mode-03 / mode-07 answer (data bytes after 43/47, header-stripped lines). */
    public static List<String> dtcs(String raw, char mode) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        String marker = "4" + mode;
        for (String line : raw.toUpperCase(Locale.ROOT).replace(">", " ").replace("\r", "\n").split("\n")) {
            String s = line.replace(" ", "").trim();
            int at = s.indexOf(marker);
            if (at < 0) continue;
            String data = s.substring(at + 2);
            // CAN answers carry a count byte first
            if (s.startsWith("7E") && data.length() % 4 == 2) data = data.substring(2);
            for (int i = 0; i + 4 <= data.length(); i += 4) {
                String c = data.substring(i, i + 4);
                if (c.equals("0000")) continue;
                int first = Character.digit(c.charAt(0), 16);
                if (first < 0) continue;
                String code = "PCBU".charAt(first >> 2) + Integer.toString(first & 3) + c.substring(1);
                if (!out.contains(code)) out.add(code);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the drive check

    /** One moment of the drive (NaN = not read). */
    public static final class Sample {
        public long t;
        public double rpm = Double.NaN, speed = Double.NaN, gpsSpeed = Double.NaN, gpsAccuracy = Double.NaN;
        public double coolant = Double.NaN, iat = Double.NaN, ambient = Double.NaN;
        public double stft = Double.NaN, ltft = Double.NaN, map = Double.NaN, baro = Double.NaN;
        /** Commanded throttle actuator (PID 014C), not the absolute sensor 0111 (that one reads 11-15% shut). */
        public double load = Double.NaN, throttle = Double.NaN, voltage = Double.NaN;
        public double o2up = Double.NaN, o2down = Double.NaN;
        // extended check (HeObdUds): other control units over the same adapter
        /** ABS wheel speeds FL, FR, RL, RR (km/h); steering angle (deg, unsigned raw as the profile reads it). */
        public double[] wheel = nan(4);
        public double steer = Double.NaN;
        /** DSG gear (1-7, 0 = N, -1 = R), clutch temperatures, gearbox oil pressure (bar). */
        public double gear = Double.NaN, clutch1T = Double.NaN, clutch2T = Double.NaN, dsgPressure = Double.NaN;
        /** Engine: misfire counters, knock retard (deg), fuel correction factor per cylinder 1-4; oil temp, torque (Nm). */
        public double[] misfire = nan(4), knock = nan(4), corr = nan(4);
        public double oilT = Double.NaN, torque = Double.NaN;

        /** csv() order: t, the SCALARS below in order, then wheel, misfire, knock, corr (4 each). */
        private static final int SCALARS = 23, FIELDS = 1 + SCALARS + 4 * 4;

        /** One line for the drive-check file (survives DriveDeck restarts at ignition). */
        public String csv() {
            StringBuilder b = new StringBuilder().append(t);
            double[] v = { rpm, speed, gpsSpeed, gpsAccuracy, coolant, iat, ambient, stft, ltft, map, baro, load, throttle,
                    voltage, o2up, o2down, steer, gear, clutch1T, clutch2T, dsgPressure, oilT, torque };
            for (double d : v) b.append(',').append(c(d));
            for (double[] a : new double[][] { wheel, misfire, knock, corr }) for (double d : a) b.append(',').append(c(d));
            return b.toString();
        }

        private static String c(double d) {
            if (Double.isNaN(d)) return "";
            long l = Math.round(d);
            return l == d ? Long.toString(l) : Double.toString(Math.round(d * 1000) / 1000.0);
        }

        /** The reverse of {@link #csv}; null for a broken line. */
        public static Sample fromCsv(String line) {
            try {
                String[] f = line.split(",", -1);
                if (f.length != FIELDS) return null;
                Sample s = new Sample();
                s.t = Long.parseLong(f[0]);
                double[] v = new double[FIELDS - 1];
                for (int i = 0; i < v.length; i++) v[i] = f[i + 1].isEmpty() ? Double.NaN : Double.parseDouble(f[i + 1]);
                s.rpm = v[0]; s.speed = v[1]; s.gpsSpeed = v[2]; s.gpsAccuracy = v[3]; s.coolant = v[4]; s.iat = v[5];
                s.ambient = v[6]; s.stft = v[7]; s.ltft = v[8]; s.map = v[9]; s.baro = v[10]; s.load = v[11];
                s.throttle = v[12]; s.voltage = v[13]; s.o2up = v[14]; s.o2down = v[15]; s.steer = v[16]; s.gear = v[17];
                s.clutch1T = v[18]; s.clutch2T = v[19]; s.dsgPressure = v[20]; s.oilT = v[21]; s.torque = v[22];
                double[][] arrays = { s.wheel, s.misfire, s.knock, s.corr };
                for (int a = 0; a < arrays.length; a++) System.arraycopy(v, SCALARS + 4 * a, arrays[a], 0, 4);
                return s;
            } catch (RuntimeException e) {
                return null;
            }
        }

        static double[] nan(int n) {
            double[] d = new double[n];
            java.util.Arrays.fill(d, Double.NaN);
            return d;
        }
    }

    public static final int OK = 0, INFO = 1, WARN = 2, BAD = 3;

    public static final class Finding {
        public final int level;
        public final String title;
        public final String detail;

        Finding(int level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }

        @Override
        public String toString() { return "[" + level + "] " + title + ": " + detail; }
    }

    static boolean ok(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<Double>(v);
        java.util.Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static String f(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    /**
     * The findings for a recorded drive. [mil] = mode-01 PID 01 byte A (-1 unknown); [codes] stored + pending;
     * [throttleBaseline] = this car's first idle throttle opening (NaN before the first check).
     */
    public static List<Finding> evaluate(List<Sample> samples, int mil, List<String> codes, double throttleBaseline) {
        List<Finding> out = new ArrayList<Finding>();
        List<Double> idleTrim = new ArrayList<Double>(), cruiseTrim = new ArrayList<Double>();
        List<Double> idleMap = new ArrayList<Double>(), offMap = new ArrayList<Double>(), baro = new ArrayList<Double>();
        List<Double> idleThrottle = new ArrayList<Double>(), runVolt = new ArrayList<Double>(), offVolt = new ArrayList<Double>();
        List<Double> ratio = new ArrayList<Double>(), o2down = new ArrayList<Double>();
        double maxCoolant = Double.NaN;
        long firstT = -1, warmT = -1, lastT = -1;
        double startCoolant = Double.NaN, ambient = Double.NaN;
        Sample prev = null;
        for (Sample s : samples) {
            if (firstT < 0) firstT = s.t;
            lastT = s.t;
            if (ok(s.coolant)) {
                if (!ok(startCoolant)) startCoolant = s.coolant;
                if (!ok(maxCoolant) || s.coolant > maxCoolant) maxCoolant = s.coolant;
                if (warmT < 0 && s.coolant >= 80) warmT = s.t;
            }
            if (ok(s.ambient)) ambient = s.ambient;
            if (ok(s.baro)) baro.add(s.baro);
            boolean running = ok(s.rpm) && s.rpm > 400;
            boolean warm = ok(s.coolant) && s.coolant >= 75;
            if (!running && ok(s.rpm) && ok(s.map)) offMap.add(s.map);
            if (ok(s.voltage)) (running ? runVolt : offVolt).add(s.voltage);
            boolean idle = running && s.rpm < 1100 && ok(s.speed) && s.speed < 2;
            double trim = ok(s.stft) && ok(s.ltft) ? s.stft + s.ltft : Double.NaN;
            if (warm && idle) {
                if (ok(trim)) idleTrim.add(trim);
                if (ok(s.map)) idleMap.add(s.map);
                if (ok(s.throttle)) idleThrottle.add(s.throttle);
            }
            if (warm && running && s.rpm > 1800 && s.rpm < 3200 && ok(s.speed) && s.speed > 40 && ok(trim)) cruiseTrim.add(trim);
            if (warm && running && s.rpm > 1500 && ok(s.o2down)) o2down.add(s.o2down);
            // speed against GPS: steady, fast enough, a good fix
            if (prev != null && ok(s.speed) && ok(s.gpsSpeed) && ok(prev.speed) && s.speed > 40 && s.gpsSpeed > 40
                    && Math.abs(s.speed - prev.speed) <= 2 && s.t - prev.t < 5000
                    && (!ok(s.gpsAccuracy) || s.gpsAccuracy <= 15)) {
                ratio.add(s.speed / s.gpsSpeed);
            }
            prev = s;
        }

        // the check-engine light and codes
        if (mil >= 0) {
            boolean on = (mil & 0x80) != 0;
            int n = mil & 0x7F;
            if (on) out.add(new Finding(BAD, "נורת מנוע דולקת", n + " תקלות שמורות. יש לבדוק במוסך."));
            else if (n > 0) out.add(new Finding(WARN, "תקלות שמורות", n + " תקלות שמורות, הנורה כבויה."));
        }
        if (codes != null && !codes.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String c : codes) {
                if (b.length() > 0) b.append(", ");
                b.append(c);
                String he = codeHebrew(c);
                if (he != null) b.append(" (").append(he).append(')');
            }
            out.add(new Finding(WARN, "קודי תקלה", b.toString()));
        } else if (mil >= 0 && (mil & 0x7F) == 0) {
            out.add(new Finding(OK, "אין קודי תקלה", "המחשב לא שמר תקלות."));
        }

        // fuel trims: the mixture correction the engine needs (normal within about ±8%)
        double ti = median(idleTrim), tc = median(cruiseTrim);
        if (idleTrim.size() >= 10 || cruiseTrim.size() >= 10) {
            String d = (idleTrim.size() >= 10 ? "בסרק " + sign(ti) + "%" : "") + (cruiseTrim.size() >= 10
                    ? (idleTrim.size() >= 10 ? ", " : "") + "בנסיעה " + sign(tc) + "%" : "");
            if (ok(ti) && ti > 10 && (!ok(tc) || tc < ti - 6)) {
                out.add(new Finding(WARN, "תערובת רזה בסרק",
                        d + ". נכנס אוויר שלא נמדד: דליפת ואקום, צינור/אטם בסעפת היניקה או שסתום PCV. לבדוק דליפות לפני ניקוי."));
            } else if ((ok(ti) && ti > 10) || (ok(tc) && tc > 10)) {
                out.add(new Finding(WARN, "תערובת רזה",
                        d + ". המנוע מוסיף דלק בכל המצבים: לחץ דלק נמוך, מזרקים סתומים או חיישן MAP שמודד נמוך."));
            } else if ((ok(ti) && ti < -10) || (ok(tc) && tc < -10)) {
                out.add(new Finding(WARN, "תערובת עשירה",
                        d + ". המנוע מוריד דלק: מזרק דולף, שסתום טיהור אדים (EVAP) תקוע פתוח או חיישן MAP שמודד גבוה."));
            } else {
                out.add(new Finding(OK, "תיקוני דלק תקינים", d + " (תקין עד ±8%)."));
            }
        } else {
            out.add(new Finding(INFO, "תיקוני דלק", "לא נאספו מספיק נתונים: צריך מנוע חם, קצת סרק ונסיעה של כמה דקות."));
        }

        // MAP sensor: engine off it must read the outside pressure; warm idle a deep vacuum
        double b = median(baro), off = median(offMap), im = median(idleMap);
        if (offMap.size() >= 3 && ok(b)) {
            double diff = off - b;
            if (Math.abs(diff) > 5) {
                out.add(new Finding(WARN, "חיישן לחץ סעפת (MAP)",
                        "במנוע כבוי הוא מודד " + f(off, 0) + " kPa ולחץ האוויר " + f(b, 0) + " kPa (הפרש " + f(diff, 0)
                                + "). חיישן מלוכלך או תקול: ניקוי חיישן ה-MAP (ספריי ניקוי לחיישנים) או החלפה."));
            } else {
                out.add(new Finding(OK, "חיישן לחץ סעפת (MAP)", "במנוע כבוי תואם ללחץ האוויר (" + f(off, 0) + " / " + f(b, 0) + " kPa)."));
            }
        }
        if (idleMap.size() >= 10) {
            if (im > 45) {
                out.add(new Finding(WARN, "ואקום חלש בסרק",
                        "לחץ הסעפת בסרק " + f(im, 0) + " kPa (רגיל 25–40). דליפת אוויר, מצערת מלוכלכת או בעיה מכנית."));
            } else {
                out.add(new Finding(OK, "ואקום בסרק", f(im, 0) + " kPa, תקין."));
            }
        }

        // throttle body: carbon makes the engine open the throttle further to keep the idle. The commanded opening
        // (PID 014C) differs from car to car, so it is compared with this car's own first measurement.
        double th = median(idleThrottle);
        if (idleThrottle.size() >= 10) {
            if (!ok(throttleBaseline)) {
                out.add(new Finding(INFO, "גוף מצערת", "פתיחה בסרק " + f(th, 1) + "%. נשמר כערך בסיס; בדיקות הבאות יושוו אליו."));
            } else if (th > throttleBaseline * 1.3 + 0.5) {
                out.add(new Finding(WARN, "גוף מצערת",
                        "בסרק המצערת פתוחה " + f(th, 1) + "% לעומת " + f(throttleBaseline, 1)
                                + "% בבדיקה הראשונה: כנראה פחם על המצערת. ניקוי גוף המצערת ואז התאמה (כיול) במחשב."));
            } else {
                out.add(new Finding(OK, "גוף מצערת", "פתיחה בסרק " + f(th, 1) + "% (בסיס " + f(throttleBaseline, 1) + "%), אין סימן ללכלוך."));
            }
        }

        // thermostat: the EA211 should reach 80°C within about 10 minutes of driving
        if (ok(maxCoolant) && lastT > firstT) {
            long minutes = (lastT - firstT) / 60000;
            if (warmT > 0) {
                long warmMin = (warmT - firstT) / 60000;
                if (ok(startCoolant) && startCoolant < 40 && warmMin > 15) {
                    out.add(new Finding(WARN, "התחממות איטית", "המנוע הגיע ל-80° רק אחרי " + warmMin + " דקות: לבדוק תרמוסטט."));
                } else {
                    out.add(new Finding(OK, "טמפרטורת מנוע", "הגיע לחום עבודה (" + f(maxCoolant, 0) + "°)."));
                }
                if (maxCoolant > 112) out.add(new Finding(BAD, "התחממות יתר", "המנוע הגיע ל-" + f(maxCoolant, 0) + "°: לעצור ולבדוק נוזל קירור."));
            } else if (minutes >= 15 && (!ok(ambient) || ambient > 0)) {
                out.add(new Finding(WARN, "המנוע לא מתחמם", "אחרי " + minutes + " דקות רק " + f(maxCoolant, 0) + "°: תרמוסטט תקוע פתוח."));
            }
        }

        // charging
        double rv = median(runVolt), ov = median(offVolt);
        if (runVolt.size() >= 10) {
            if (rv < 12.6) out.add(new Finding(BAD, "טעינה", "מתח " + f(rv, 1) + "V במנוע פועל: האלטרנטור לא טוען."));
            else if (rv > 15.2) out.add(new Finding(BAD, "טעינה", "מתח " + f(rv, 1) + "V: טעינת יתר, לבדוק ווסת."));
            else out.add(new Finding(OK, "טעינה", f(rv, 1) + "V במנוע פועל, תקין."));
        }
        if (offVolt.size() >= 3 && ov < 12.2) {
            out.add(new Finding(WARN, "מצבר", "במנוע כבוי " + f(ov, 2) + "V: המצבר חלש או לא טעון."));
        }

        // catalyst: the sensor after it should stay steady (high) when the engine is warm
        if (o2down.size() >= 30) {
            double mean = 0, sw = 0;
            for (double v : o2down) mean += v;
            mean /= o2down.size();
            for (int i = 1; i < o2down.size(); i++) if ((o2down.get(i - 1) - 0.45) * (o2down.get(i) - 0.45) < 0) sw++;
            double perMin = sw / Math.max(1.0, o2down.size() / 60.0);
            if (perMin > 20 && mean < 0.55) {
                out.add(new Finding(WARN, "ממיר קטליטי", "החיישן שאחרי הממיר מתנודד כמו החיישן שלפניו: ייתכן שהממיר נחלש."));
            } else {
                out.add(new Finding(OK, "ממיר קטליטי", "החיישן שאחרי הממיר יציב (" + f(mean, 2) + "V)."));
            }
        }

        // speed: OBD against GPS
        if (ratio.size() >= 20) {
            double r = median(ratio);
            double pct = (r - 1) * 100;
            String d = "מהירות OBD לעומת GPS: " + sign(pct) + "% (" + ratio.size() + " מדידות).";
            if (Math.abs(pct) > 3) out.add(new Finding(WARN, "דיוק מהירות", d + " כדאי לכייל את מקדם המהירות."));
            else out.add(new Finding(OK, "דיוק מהירות", d + " תקין."));
        } else {
            out.add(new Finding(INFO, "דיוק מהירות", "צריך נסיעה יציבה מעל 40 קמ\"ש עם GPS כדי להשוות."));
        }
        return out;
    }

    /** Median commanded throttle at warm idle, to keep as this car's baseline (NaN without enough idle). */
    public static double idleThrottle(List<Sample> samples) {
        List<Double> v = new ArrayList<Double>();
        for (Sample s : samples) {
            if (ok(s.rpm) && s.rpm > 400 && s.rpm < 1100 && ok(s.speed) && s.speed < 2 && ok(s.coolant) && s.coolant >= 75
                    && ok(s.throttle)) v.add(s.throttle);
        }
        return v.size() >= 10 ? median(v) : Double.NaN;
    }

    /** The OBD speed correction factor (GPS / OBD), or NaN without enough steady samples. */
    public static double speedFactor(List<Sample> samples) {
        List<Double> r = new ArrayList<Double>();
        Sample prev = null;
        for (Sample s : samples) {
            if (prev != null && ok(s.speed) && ok(s.gpsSpeed) && ok(prev.speed) && s.speed > 40 && s.gpsSpeed > 40
                    && Math.abs(s.speed - prev.speed) <= 2 && (!ok(s.gpsAccuracy) || s.gpsAccuracy <= 15)) {
                r.add(s.gpsSpeed / s.speed);
            }
            prev = s;
        }
        return r.size() >= 20 ? median(r) : Double.NaN;
    }

    static String sign(double v) {
        return (v >= 0 ? "+" : "") + f(v, 1);
    }

    /** Hebrew names of the codes that are common on the EA211; null for the rest. */
    static String codeHebrew(String code) {
        switch (code) {
            case "P0171": return "תערובת רזה";
            case "P0172": return "תערובת עשירה";
            case "P0300": return "החטאות הצתה";
            case "P0301": return "החטאה בצילינדר 1";
            case "P0302": return "החטאה בצילינדר 2";
            case "P0303": return "החטאה בצילינדר 3";
            case "P0304": return "החטאה בצילינדר 4";
            case "P0420": return "יעילות ממיר קטליטי נמוכה";
            case "P0106": return "חיישן MAP לא סביר";
            case "P0128": return "המנוע לא מגיע לחום (תרמוסטט)";
            case "P0441": case "P0455": case "P0456": return "מערכת אדי דלק (EVAP)";
            case "P0507": return "סרק גבוה מהצפוי";
            case "P2181": return "מערכת קירור";
            case "P0562": return "מתח נמוך";
            default: return null;
        }
    }
}
