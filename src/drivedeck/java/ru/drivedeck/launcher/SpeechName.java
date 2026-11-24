package ru.drivedeck.launcher;

/**
 * he-16, pure (JVM-tested in test/CallTest.java): the file /data/tw/speech_name that tells com.tw.service which app
 * the steering-wheel voice key opens (line 1 = package, line 2 = activity).
 *
 * com.tw.service special-cases some packages: for Google (com.google.android.googlequicksearchbox) it fires an
 * implicit VOICE_COMMAND, which DriveDeck and the Hebrew voice app also answer. So the key is pointed at the voice
 * app's WheelKeyActivity, which opens what the driver chose there (Gemini by default) explicitly.
 */
final class SpeechName {
    static final String PKG = "il.carvoice.he";
    static final String CLS = "il.carvoice.he.WheelKeyActivity";
    static final String CONTENT = PKG + "\n" + CLS + "\n";

    /** True when the file already names the wheel-key activity (blank lines and spaces ignored). */
    static boolean pointsAtWheelKey(String content) {
        if (content == null) return false;
        String first = null, second = null;
        for (String line : content.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (first == null) first = l;
            else if (second == null) second = l;
            else break;
        }
        return PKG.equals(first) && CLS.equals(second);
    }

    /** One line for the log. */
    static String oneLine(String content) {
        if (content == null) return "(missing)";
        String s = content.trim().replace('\n', '|');
        return s.isEmpty() ? "(empty)" : s;
    }
}
