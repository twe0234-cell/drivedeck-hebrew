package ru.drivedeck.launcher;

/** Plain-JVM test of he-16's pure call and voice-key rules (CallBook, SpeechName). Run:
 *  javac -d /tmp/ct src/drivedeck/java/ru/drivedeck/launcher/{CallBook,SpeechName}.java test/CallTest.java
 *  && java -cp /tmp/ct ru.drivedeck.launcher.CallTest */
public class CallTest {
    static int fails = 0;

    static void check(String what, boolean got, boolean expect) {
        boolean ok = got == expect;
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what + " -> " + got);
    }

    public static void main(String[] a) {
        // a call is "new" once, stays on through its states, and is over when terminated
        CallBook b = new CallBook();
        check("idle at start", b.idle(), true);
        check("incoming call is new", b.update(1, CallBook.INCOMING), true);
        check("same call answered is not new", b.update(1, CallBook.ACTIVE), false);
        check("in a call", b.idle(), false);
        check("second (waiting) call is new", b.update(2, CallBook.WAITING), true);
        check("first call ended: not new", b.update(1, CallBook.TERMINATED), false);
        check("still the second call", b.idle(), false);
        check("second call ended", b.update(2, CallBook.TERMINATED), false);
        check("idle after both", b.idle(), true);
        check("a terminated call never seen is not new", b.update(9, CallBook.TERMINATED), false);
        check("still idle", b.idle(), true);
        check("outgoing call is new", b.update(3, CallBook.DIALING), true);
        check("ringing at the other end is the same call", b.update(3, CallBook.ALERTING), false);

        // the firmware's call-state properties
        check("0 is not stuck", CallBook.isStuck("0", false), false);
        check("empty (never set) is not stuck", CallBook.isStuck("", false), false);
        check("1 with no call is stuck", CallBook.isStuck("1", false), true);
        check("3 with no call is stuck", CallBook.isStuck(" 3\n", false), true);
        check("1 during a call is not stuck", CallBook.isStuck("1", true), false);
        check("null is not stuck", CallBook.isStuck(null, false), false);

        // /data/tw/speech_name
        check("our content", SpeechName.pointsAtWheelKey(SpeechName.CONTENT), true);
        check("without the last newline", SpeechName.pointsAtWheelKey("il.carvoice.he\nil.carvoice.he.WheelKeyActivity"), true);
        check("Windows line ends and spaces", SpeechName.pointsAtWheelKey(" il.carvoice.he \r\n il.carvoice.he.WheelKeyActivity\r\n"), true);
        check("Google", SpeechName.pointsAtWheelKey("com.google.android.googlequicksearchbox\n"), false);
        check("the old Topway voice", SpeechName.pointsAtWheelKey("com.dofun.aios.voice\ncom.dofun.aios.voice.MainActivity\n"), false);
        check("the assistant itself, not the wheel key", SpeechName.pointsAtWheelKey("il.carvoice.he\nil.carvoice.he.VoiceActivity\n"), false);
        check("missing file", SpeechName.pointsAtWheelKey(null), false);
        check("log line of a missing file", SpeechName.oneLine(null).equals("(missing)"), true);
        check("log line", SpeechName.oneLine("a\nb\n").equals("a|b"), true);

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails != 0) System.exit(1);
    }
}
