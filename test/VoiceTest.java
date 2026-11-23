package il.carvoice.he;

import java.util.Arrays;
import java.util.List;

/** Plain-JVM test of the voice app's understanding (Parser + Smart). Run: see the end of build/build.sh or
 *  javac -d /tmp/vt src/voice/java/il/carvoice/he/{Parser,Smart}.java test/VoiceTest.java && java -cp /tmp/vt il.carvoice.he.VoiceTest */
public class VoiceTest {
    static int fails = 0;

    static void cmd(String heard, String expect) {
        Parser.Cmd c = Smart.understand(Arrays.asList(heard));
        String got = c == null ? "null" : c.toString();
        boolean ok = got.equals(expect);
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + heard + " -> " + got + (ok ? "" : "   (expected " + expect + ")"));
    }

    static void guess(String heard, String expectFirst) {
        List<Parser.Cmd> g = Smart.guesses(heard);
        String got = g.isEmpty() ? "none" : g.get(0).toString();
        boolean ok = got.equals(expectFirst);
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + "guess " + heard + " -> " + got + (ok ? "" : "   (expected " + expectFirst + ")"));
    }

    static void yn(String heard, int expect) {
        int got = Smart.yesNo(heard);
        if (got != expect) fails++;
        System.out.println((got == expect ? "ok   " : "FAIL ") + "yes/no " + heard + " -> " + got);
    }

    public static void main(String[] a) {
        // as before
        cmd("תנגן את ישי ריבו", "play:ישי ריבו");
        cmd("נווט הביתה", "fav:home");
        cmd("הבא", "media:87");
        cmd("פתח וויז", "waze");
        // mis-heard command words are repaired
        cmd("תנגו את ישי ריבו", "play:ישי ריבו");
        cmd("ותנגן את אברהם פריד", "play:אברהם פריד");
        cmd("תדלק", "media:87");
        cmd("תגבירר", "vol:1");
        cmd("תפתך את יוטיוב", "open:יוטיוב");
        // names are not "repaired" into commands
        cmd("ישי ריבו", "null");
        cmd("מוטי שטיינמץ", "null");
        // offline answers
        cmd("מה השעה", "answer:time");
        cmd("איזה יום היום", "answer:date");
        cmd("תודה רבה", "answer:thanks");
        // guesses instead of "not understood"
        guess("ישי ריבו", "play:ישי ריבו");
        guess("רחוב הרצל 5 רמת גן", "nav:רחוב הרצל 5 רמת גן");
        guess("קניון עזריאלי", "nav:קניון עזריאלי");
        yn("כן", 1); yn("כן בבקשה", 1); yn("לא", 0); yn("לא משנה", 0); yn("נווט", -1);
        System.out.println(Smart.pick("נווט", Smart.guesses("ישי ריבו")) == 1 ? "ok   pick nav" : "FAIL pick nav");
        if (Smart.pick("נווט", Smart.guesses("ישי ריבו")) != 1) fails++;
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
