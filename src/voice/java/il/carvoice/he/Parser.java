package il.carvoice.he;

import java.util.ArrayList;

/**
 * Hebrew command parsing, free of Android classes so it runs in a plain JVM test.
 *
 * The recogniser's text is cleaned (letters and digits only), split into words, and every word is compared in
 * a canonical spelling (double vav / yod collapsed: "ווייז" = "וויז" = "ויז"), because Google spells the same
 * word differently from one sentence to the next.
 */
final class Parser {
    static final String DD = "dd", MEDIA = "media", VOL = "vol", NAV_FAV = "fav", NAV = "nav", PLAY = "play",
            ZEMER = "zemer", WAZE = "waze", MAPS = "maps", EQ = "eq", HOME = "home", OPEN = "open",
            SETTINGS = "settings", HELP = "help", CANCEL = "cancel";

    static final class Cmd {
        final String kind, arg;
        Cmd(String kind, String arg) { this.kind = kind; this.arg = arg; }
        public String toString() { return arg == null ? kind : kind + ":" + arg; }
    }

    /** Lower case, niqqud removed, everything that is not a letter or a digit becomes a space. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c >= '֑' && c <= 'ׇ') continue;          // niqqud / cantillation
            if (c == '\'' || c == '׳' || c == '"' || c == '״') continue; // geresh inside a word
            b.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : ' ');
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** Spelling-insensitive form of one word. */
    static String canon(String w) {
        return w.replace("וו", "ו").replace("יי", "י").replace("וו", "ו").replace("יי", "י");
    }

    private final String[] words;  // as heard
    private final String[] cw;     // canonical

    private Parser(String heard) {
        String c = clean(heard);
        words = c.isEmpty() ? new String[0] : c.split(" ");
        cw = new String[words.length];
        for (int i = 0; i < words.length; i++) cw[i] = canon(words[i]);
    }

    /** index of the word after the first occurrence of [phrase], or -1 */
    private int find(String phrase) {
        String[] p = canon(phrase).split(" ");
        for (int i = 0; i + p.length <= cw.length; i++) {
            boolean ok = true;
            for (int j = 0; j < p.length && ok; j++) ok = cw[i + j].equals(p[j]);
            if (ok) return i + p.length;
        }
        return -1;
    }

    private boolean has(String... phrases) {
        for (String p : phrases) if (find(p) >= 0) return true;
        return false;
    }

    /** the words after the first phrase that is followed by something, or null */
    private String after(String... phrases) {
        for (String p : phrases) {
            int i = find(p);
            if (i >= 0 && i < words.length) return join(i);
        }
        return null;
    }

    private String join(int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < words.length; i++) { if (b.length() > 0) b.append(' '); b.append(words[i]); }
        return b.toString();
    }

    private static String strip(String s, String... prefixes) {
        boolean again = true;
        while (again) {
            again = false;
            for (String p : prefixes) {
                if (s.startsWith(p + " ") && s.length() > p.length() + 1) { s = s.substring(p.length() + 1).trim(); again = true; }
            }
        }
        return s;
    }

    private static boolean isOneOf(String s, String... options) {
        String c = canon(s);
        for (String o : options) if (c.equals(canon(o))) return true;
        return false;
    }

    static Cmd parse(String heard) {
        if (heard == null) return null;
        Parser p = new Parser(heard);
        return p.words.length == 0 ? null : p.parse();
    }

    private Cmd parse() {
        // --- help / the assistant's own screen
        if (has("עזרה", "מה אפשר להגיד", "מה אפשר לומר", "מה אתה יודע", "רשימת פקודות", "פקודות", "הגדרות עוזר", "הגדרות העוזר"))
            return new Cmd(HELP, null);

        // --- DriveDeck maintenance
        if (has("שלח לוג", "תשלח לוג", "שלח את הלוג", "תשלח את הלוג", "לשלוח לוג", "לוג")) return new Cmd(DD, "SEND_LOG");
        if (has("בדוק עדכונים", "תבדוק עדכונים", "בדוק עדכון", "תבדוק עדכון", "עדכונים", "עדכן תוכנה", "עדכן גרסה", "עדכון תוכנה"))
            return new Cmd(DD, "CHECK_UPDATE");

        // --- the car check screen in DriveDeck (OBD)
        if (has("בדיקת רכב", "בדוק את הרכב", "תבדוק את הרכב", "בדיקת הרכב", "מצב הרכב", "מצב המנוע", "קודי תקלה", "קודי תקלות",
                "תקלות ברכב", "אבחון", "אבחון רכב", "מחשב הרכב"))
            return new Cmd(DD, "OBD");

        // --- ZemerDrive: follow the artist / like the song
        if (has("עקוב אחרי האמן", "עקוב אחרי הזמר", "תעקוב אחרי האמן", "תעקוב אחרי הזמר", "הוסף את האמן", "תוסיף את האמן",
                "הוסף את הזמר", "תוסיף את הזמר", "הוסף אמן", "תוסיף אמן", "עקוב אחריו", "תעקוב אחריו", "הוסף למעקב", "תוסיף למעקב",
                "מעקב אחרי האמן", "עקוב אחרי האומן", "תעקוב אחרי האומן"))
            return new Cmd(DD, "FOLLOW_ARTIST");
        if (has("לייק", "לייק לשיר", "תן לייק", "תעשה לייק", "אהבתי", "אני אוהב את השיר", "שיר אהוב", "הסר לייק", "תוסיף לאהובים",
                "הוסף לאהובים", "הוסף למועדפים", "תוסיף למועדפים"))
            return new Cmd(DD, "LIKE");

        // --- volume (before "שקט" = pause)
        if (has("הגבר", "תגביר", "תגבירי", "להגביר", "תעלה", "יותר חזק", "חזק יותר", "תחזק", "חזק", "הגברה")) return new Cmd(VOL, "1");
        if (has("הנמך", "תנמיך", "תנמיכי", "להנמיך", "תוריד", "יותר חלש", "חלש יותר", "תחליש", "החלש", "חלש", "יותר שקט", "שקט יותר"))
            return new Cmd(VOL, "-1");

        // --- navigation
        String dest = after("קח אותי", "תיקח אותי", "תנווט", "נווט", "ניווט", "תסע", "סע", "ניסע", "נסיעה", "להגיע", "איך מגיעים");
        if (dest != null) {
            dest = strip(dest, "אל", "ל", "עד");
            if (dest.startsWith("ל") && dest.length() > 2 && !dest.contains(" ") && isOneOf(dest.substring(1), "בית", "עבודה", "הבית", "העבודה"))
                dest = dest.substring(1);
        }
        if (dest != null || has("הביתה", "לבית", "ניווט הביתה")) {
            if (dest == null || isOneOf(dest, "בית", "הביתה", "ביתה", "הבית", "לבית")) return new Cmd(NAV_FAV, "home");
            if (isOneOf(dest, "עבודה", "העבודה", "לעבודה")) return new Cmd(NAV_FAV, "work");
            // "לתל אביב" -> "תל אביב": Waze finds both, the bare name is cleaner
            if (dest.startsWith("ל") && dest.length() > 3 && find("ל") < 0) dest = dest.substring(1);
            return new Cmd(NAV, dest);
        }

        // --- play something in ZemerDrive
        String q = after("נגן את", "תנגן את", "שים את", "תשים את", "השמע את", "תשמיע את", "נגן לי", "תנגן לי", "שים לי", "תשים לי",
                "נגן", "תנגן", "שים", "תשים", "השמע", "תשמיע", "אני רוצה לשמוע", "רוצה לשמוע", "תן לי לשמוע", "לשמוע",
                "שירים של", "שיר של");
        if (q != null) {
            q = strip(q, "את", "לי", "שירים של", "שיר של", "שירים", "של");
            if (isOneOf(q, "מוזיקה", "מוסיקה", "שירים", "שיר", "משהו", "מוזיקה בבקשה")) return new Cmd(MEDIA, "126");
            return new Cmd(PLAY, q);
        }

        // --- media transport
        if (has("הבא", "הבאה", "דלג", "תדלג", "קדימה", "תעביר", "העבר", "תעביר שיר", "שיר אחר", "תחליף שיר", "החלף שיר", "נקסט"))
            return new Cmd(MEDIA, "87");
        if (has("קודם", "הקודם", "הקודמת", "אחורה", "שיר קודם", "חזור", "תחזור")) return new Cmd(MEDIA, "88");
        if (has("עצור", "תעצור", "תעצרי", "הפסק", "תפסיק", "הפסקה", "שקט", "השתק", "תשתיק", "פאוז", "סטופ", "עצירה"))
            return new Cmd(MEDIA, "127");
        if (has("נגן", "תנגן", "המשך", "תמשיך", "הפעל מוזיקה", "תפעיל מוזיקה", "פליי", "הפעל", "תפעיל") && words.length <= 2)
            return new Cmd(MEDIA, "126");

        // --- open an app by its name: "פתח יוטיוב"
        String app = after("פתח את", "תפתח את", "הפעל את", "תפעיל את", "פתח", "תפתח", "תפתחי", "לפתוח", "הפעל", "תפעיל", "עבור אל",
                "תעבור אל", "עבור ל", "תעבור ל", "לך אל", "לך ל", "הצג את", "הצג", "תציג", "תראה לי", "הראה");
        if (app != null) {
            app = strip(app, "את", "אפליקציית", "אפליקציה", "האפליקציה");
            Cmd known = knownApp(new Parser(app));
            return known != null ? known : new Cmd(OPEN, app);
        }

        Cmd known = knownApp(this);
        if (known != null) return known;
        if (has("ביטול", "בטל", "תבטל", "לא משנה", "עזוב")) return new Cmd(CANCEL, null);
        return null;
    }

    private static Cmd knownApp(Parser p) {
        if (p.has("זמר", "זמר דרייב", "מוזיקה", "מוסיקה", "שירים", "נגן המוזיקה")) return new Cmd(ZEMER, null);
        if (p.has("ויז", "ווייז", "waze", "ניווט")) return new Cmd(WAZE, null);
        if (p.has("מפה", "מפות", "גוגל מפות", "maps")) return new Cmd(MAPS, null);
        if (p.has("אקולייזר", "איקולייזר", "אקולזר", "אקוולייזר", "equalizer", "eq", "סאונד", "הגדרות סאונד", "הגדרות שמע"))
            return new Cmd(EQ, null);
        if (p.has("מסך הבית", "מסך ראשי", "המסך הראשי", "בית", "הבית", "דרייב דק", "דרייבדק", "מחשב רכב", "מחשב דרך", "נתוני רכב",
                "נתוני הרכב", "אובידי", "obd", "מנוע", "לוח מחוונים")) return new Cmd(HOME, null);
        if (p.has("הגדרות", "הגדרות מערכת", "settings")) return new Cmd(SETTINGS, null);
        return null;
    }

    /** Hebrew names of apps whose label is English: tried against labels and package names. */
    static String[] aliases(String name) {
        String c = canon(clean(name));
        String[][] table = {
            { "יוטיוב", "youtube" }, { "כרום", "chrome" }, { "דפדפן", "chrome", "browser" }, { "גוגל", "google" },
            { "ווטסאפ", "whatsapp" }, { "וואטסאפ", "whatsapp" }, { "טלגרם", "telegram" }, { "ספוטיפיי", "spotify" },
            { "רדיו", "radio", "fm" }, { "בלוטות", "bluetooth", "bt" }, { "בלוטוס", "bluetooth", "bt" }, { "טלפון", "phone", "dialer", "bt" },
            { "מצלמה", "camera", "dvr" }, { "מצלמת רוורס", "camera" }, { "קבצים", "files", "file", "explorer" }, { "מנהל קבצים", "file" },
            { "וידאו", "video" }, { "סרטים", "video" }, { "גלריה", "gallery", "photos" }, { "תמונות", "photos", "gallery" },
            { "חנות", "play store", "vending" }, { "גוגל פליי", "play store", "vending" }, { "מחשבון", "calculator" },
            { "שעון", "clock" }, { "יומן", "calendar" }, { "מיילים", "gmail", "mail" }, { "מייל", "gmail", "mail" },
            { "מוביט", "moovit" }, { "פנגו", "pango" }, { "סלופארק", "cellopark" }, { "טורק", "torque" },
        };
        ArrayList<String> out = new ArrayList<String>();
        out.add(c);
        // "הרדיו" -> also "רדיו"
        String bare = c.startsWith("ה") && c.length() > 3 ? c.substring(1) : c;
        if (!bare.equals(c)) out.add(bare);
        for (String[] row : table) {
            String k = canon(row[0]);
            if (k.equals(c) || k.equals(bare)) for (int i = 1; i < row.length; i++) out.add(row[i]);
        }
        return out.toArray(new String[0]);
    }
}
