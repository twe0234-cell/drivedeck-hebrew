package il.carvoice.he;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The layer above {@link Parser} that makes the assistant forgiving. Pure Java (no Android), tested on the JVM.
 *
 * 1. Every recognition candidate goes through Parser as heard.
 * 2. If none is a command, each word is "repaired": a prefix letter that Google glued on ("ותנגן" -> "תנגן") is
 *    removed and a word one letter away from a command word ("תנגו" -> "תנגן", "תדלק" -> "תדלג") is replaced by it;
 *    the repaired sentence goes through Parser again.
 * 3. If there is still no command, {@link #guesses} turns what was heard into a short list of likely intentions
 *    (play it / navigate to it / open the app) that the assistant asks about instead of saying "not understood".
 */
final class Smart {
    private Smart() { }

    /** Command words of Parser (canonical, final letters normalised). Names of songs, artists and places are not here. */
    private static final Map<String, String> VOCAB = new HashMap<String, String>(); // normalised -> as spelled
    static {
        String words = "עזרה פקודות הגדרות שלח תשלח לוג לשלוח בדוק תבדוק עדכונים עדכון עדכן תוכנה גרסה בדיקת רכב הרכב מצב "
                + "המנוע קודי תקלה תקלות אבחון מחשב עקוב תעקוב אחרי האמן הזמר האומן הוסף תוסיף אמן מעקב אחריו לייק אהבתי "
                + "אהוב לאהובים למועדפים הגבר תגביר להגביר תעלה יותר חזק תחזק הגברה הנמך תנמיך להנמיך תוריד חלש תחליש "
                + "החלש שקט קח אותי תיקח תנווט נווט ניווט תסע סע ניסע להגיע הביתה לבית לעבודה עבודה בית נגן תנגן שים תשים "
                + "השמע תשמיע לשמוע רוצה שירים שיר של מוזיקה מוסיקה הבא הבאה דלג תדלג קדימה תעביר העבר אחר תחליף החלף "
                + "נקסט קודם הקודם הקודמת אחורה חזור תחזור עצור תעצור הפסק תפסיק השתק תשתיק פאוז סטופ עצירה המשך תמשיך "
                + "הפעל תפעיל פליי פתח תפתח לפתוח עבור תעבור הצג תציג תראה הראה אפליקציה אפליקציית ויז מפות מפה אקולייזר "
                + "סאונד מסך הראשי ביטול בטל תבטל עזוב השעה שעה תאריך התאריך היום יום תודה";
        for (String w : words.split(" ")) VOCAB.put(norm(w), w);
    }

    /** Canonical spelling + final letters as regular letters, for comparing only. */
    static String norm(String w) {
        String c = Parser.canon(w);
        return c.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ');
    }

    /** The first candidate that is a command, as heard or repaired; null if none. */
    static Parser.Cmd understand(List<String> heard) {
        if (heard == null) return null;
        for (String s : heard) {
            Parser.Cmd c = s == null ? null : Parser.parse(s);
            if (c != null) return c;
        }
        for (String s : heard) {
            if (s == null) continue;
            String r = repair(s);
            if (r.equals(Parser.clean(s))) continue;
            Parser.Cmd c = Parser.parse(r);
            if (c != null) return c;
        }
        return null;
    }

    /** The sentence with mis-heard command words replaced. Words that are close to nothing are kept as heard. */
    static String repair(String heard) {
        String c = Parser.clean(heard);
        if (c.isEmpty()) return c;
        StringBuilder b = new StringBuilder();
        for (String w : c.split(" ")) {
            if (b.length() > 0) b.append(' ');
            b.append(fix(w));
        }
        return b.toString();
    }

    private static String fix(String w) {
        String n = norm(w);
        if (VOCAB.containsKey(n) || w.length() < 3 || w.charAt(0) < 0x5D0) return w; // known, short, or not Hebrew
        // a prefix letter glued on: "ותנגן", "ושים"
        if ("והשבלכמ".indexOf(n.charAt(0)) >= 0 && n.length() > 3 && VOCAB.containsKey(n.substring(1))) return w.substring(1);
        // one letter off (two for long words), and only if one command word is that close
        int limit = n.length() >= 7 ? 2 : n.length() >= 4 ? 1 : 0;
        if (limit == 0) return w;
        String best = null;
        int bestD = limit + 1;
        boolean tie = false;
        for (String v : VOCAB.keySet()) {
            if (Math.abs(v.length() - n.length()) > limit) continue;
            int d = dist(n, v, limit);
            if (d < bestD) { bestD = d; best = v; tie = false; } else if (d == bestD) tie = true;
        }
        return best != null && !tie ? VOCAB.get(best) : w;
    }

    /** Levenshtein distance, cut off above max. */
    static int dist(String a, String b, int max) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return max + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static final String[] PLACE_WORDS = { "רחוב", "שדרות", "שד", "כיכר", "צומת", "תחנת", "תחנה", "קניון", "בית חולים",
            "חניון", "נמל", "שכונת", "קרית", "קריית", "כפר", "מושב", "קיבוץ", "אזור תעשייה", "רמת", "גבעת", "נווה", "תל" };

    /**
     * What the driver probably meant, best first, for a sentence that is not a command: play it in ZemerDrive,
     * navigate to it, open an app with that name. Short sentences only (a long one is not a name).
     */
    static List<Parser.Cmd> guesses(String heard) {
        List<Parser.Cmd> out = new ArrayList<Parser.Cmd>();
        if (heard == null) return out;
        String q = Parser.clean(heard);
        if (q.isEmpty() || q.split(" ").length > 7) return out;
        boolean place = false;
        for (String p : PLACE_WORDS) if ((" " + q + " ").contains(" " + p + " ") || q.startsWith(p + " ")) place = true;
        Parser.Cmd play = new Parser.Cmd(Parser.PLAY, q), nav = new Parser.Cmd(Parser.NAV, q), open = new Parser.Cmd(Parser.OPEN, q);
        if (place) { out.add(nav); out.add(play); } else { out.add(play); out.add(nav); }
        if (q.split(" ").length <= 3) out.add(open);
        return out;
    }

    /** The question asked about a guess, and the button text. */
    static String describe(Parser.Cmd c) {
        if (c == null) return "";
        if (Parser.PLAY.equals(c.kind)) return "לנגן את " + c.arg;
        if (Parser.NAV.equals(c.kind)) return "לנווט אל " + c.arg;
        if (Parser.OPEN.equals(c.kind)) return "לפתוח את " + c.arg;
        return c.toString();
    }

    /** 1 = yes, 0 = no, -1 = neither. */
    static int yesNo(String heard) {
        String c = Parser.clean(heard == null ? "" : heard);
        if (c.isEmpty()) return -1;
        String first = c.split(" ")[0];
        String[] yes = { "כן", "בטח", "נכון", "יאללה", "אוקיי", "אוקי", "בסדר", "תעשה", "סבבה", "כמובן", "ok", "yes" };
        String[] no = { "לא", "ביטול", "בטל", "עזוב", "תבטל", "לא משנה", "no" };
        for (String n : no) if (first.equals(n) || c.equals(n)) return 0;
        for (String y : yes) if (first.equals(y)) return 1;
        return -1;
    }

    /** "נווט" / "תנגן" / "פתח" said alone after a question picks that guess; -1 if it is none of them. */
    static int pick(String heard, List<Parser.Cmd> options) {
        String c = norm(Parser.clean(heard == null ? "" : heard));
        String kind = null;
        if (c.startsWith("נוט") || c.startsWith("תנוט") || c.startsWith("ניוט") || c.startsWith("סע") || c.startsWith("תסע")) kind = Parser.NAV; // canon: "נווט" = "נוט"
        else if (c.startsWith("נגנ") || c.startsWith("תנגנ") || c.startsWith("שימ") || c.startsWith("תשימ") || c.startsWith("שיר")) kind = Parser.PLAY;
        else if (c.startsWith("פתח") || c.startsWith("תפתח") || c.startsWith("אפליקצי")) kind = Parser.OPEN;
        else if (c.equals("הראשונ") || c.equals("אחת") || c.equals("1")) return options.isEmpty() ? -1 : 0;
        else if (c.equals("השני") || c.equals("השניה") || c.equals("שתיימ") || c.equals("2")) return options.size() > 1 ? 1 : -1;
        if (kind == null) return -1;
        for (int i = 0; i < options.size(); i++) if (kind.equals(options.get(i).kind)) return i;
        return -1;
    }
}
