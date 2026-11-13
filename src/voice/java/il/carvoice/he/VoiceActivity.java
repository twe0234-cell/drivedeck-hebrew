package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Hebrew in-car voice commands (Google speech recognition, he-IL).
 * Started from the app icon, the steering-wheel voice key (VOICE_COMMAND / ASSIST) or the DriveDeck dock.
 */
public class VoiceActivity extends Activity {
    private static final int REQ = 7301;
    static String last;
    static final int VERSION = 3;
    private static final String[] ZEMER = { "com.jtech.zemer.drive", "com.jtech.zemer" };
    private static final String DRIVEDECK = "ru.drivedeck.launcher";

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        i.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        i.putExtra("android.speech.extra.LANGUAGE", "he-IL");
        i.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "he-IL");
        i.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true);
        i.putExtra("android.speech.extra.PROMPT", "דבר עכשיו…"); // דבר עכשיו…
        i.putExtra("android.speech.extra.MAX_RESULTS", 5);
        try {
            startActivityForResult(i, REQ);
        } catch (Throwable e) {
            say("לא נמצא זיהוי דיבור של Google – יש להתקין את אפליקציית Google");
            finish();
        }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == -1 && data != null) {
            ArrayList<String> list = data.getStringArrayListExtra("android.speech.extra.RESULTS");
            boolean done = false;
            if (list != null) {
                for (String s : list) {
                    if (s != null && handle(norm(s))) { done = true; break; }
                }
                if (!done && !list.isEmpty()) say("לא הבנתי: " + list.get(0)); // לא הבנתי
            }
        }
        finish();
    }

    // ------------------------------------------------------------------ parsing

    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c >= '֑' && c <= 'ׇ') continue;            // niqqud / cantillation
            if (c == '"' || c == '\'' || c == '׳' || c == '״' || c == '.' || c == ',' || c == '?' || c == '!') continue;
            if (c == '-' || c == '־') c = ' ';
            b.append(Character.toLowerCase(c));
        }
        return (" " + b.toString().replaceAll("\\s+", " ").trim() + " ");
    }

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(" " + w + " ")) return true;
        return false;
    }

    /** returns the text after the first matching prefix word, or null */
    private static String after(String t, String... prefixes) {
        for (String p : prefixes) {
            int i = t.indexOf(" " + p + " ");
            if (i >= 0) {
                String r = t.substring(i + p.length() + 2).trim();
                if (!r.isEmpty()) return r;
            }
        }
        return null;
    }

    boolean handle(String t) {
        // --- DriveDeck maintenance (GitHub updates / remote log)
        if (has(t, "שלח לוג", "תשלח לוג", "שלח את הלוג", "תשלח את הלוג", "לוג")) return dd("SEND_LOG", "שולח לוג…");
        // --- ZemerDrive: like the song / follow its artist (through DriveDeck, which holds the media session)
        if (has(t, "עקוב אחרי האמן", "עקוב אחרי הזמר", "תעקוב אחרי האמן", "תעקוב אחרי הזמר", "הוסף את האמן", "תוסיף את האמן",
                "הוסף את הזמר", "תוסיף את הזמר", "הוסף אמן", "עקוב אחריו", "הוסף למעקב", "תוסיף למעקב"))
            return dd("FOLLOW_ARTIST", "מוסיף את האמן למעקב…");
        if (has(t, "לייק", "תן לייק", "תעשה לייק", "אהבתי", "אני אוהב את השיר", "שיר אהוב", "הסר לייק"))
            return dd("LIKE", "לייק…");
        if (has(t, "בדוק עדכונים", "תבדוק עדכונים", "בדוק עדכון", "עדכונים", "עדכן תוכנה", "עדכן גרסה")) return dd("CHECK_UPDATE", "בודק עדכונים…");

        // --- media transport
        if (has(t, "הבא", "הבאה", "דלג", "תדלג", "קדימה")) return media(87, "שיר הבא");
        if (has(t, "קודם", "הקודם", "הקודמת", "אחורה")) return media(88, "שיר קודם");
        if (has(t, "עצור", "תעצור", "הפסק", "תפסיק", "הפסקה", "שקט")) return media(127, "עצירה");

        // --- volume
        if (has(t, "הגבר", "תגביר", "תעלה", "חזק", "יותר חזק", "להגביר")) return volume(1);
        if (has(t, "הנמך", "תנמיך", "תוריד", "חלש", "יותר חלש", "להנמיך")) return volume(-1);

        // --- navigation
        String dest = after(t, "קח אותי", "תנווט", "נווט", "ניווט", "תסע", "סע");
        if (dest != null) {
            if (dest.startsWith("אל ")) dest = dest.substring(3).trim();
            else if (dest.startsWith("ל ")) dest = dest.substring(2).trim();
            else if (dest.startsWith("ל") && dest.length() > 2) dest = dest.substring(1);
        }
        if (dest != null || has(t, "הביתה", "לבית")) {
            if (dest == null || dest.equals("בית") || dest.equals("הביתה") || dest.equals("ביתה") || dest.equals("הבית")) return navigateFav("home");
            if (dest.equals("עבודה") || dest.equals("העבודה") || dest.equals("עבודה ")) return navigateFav("work");
            return navigate(dest);
        }

        // --- play something in ZemerDrive
        String q = after(t, "נגן את", "תנגן את", "שים את", "תשים את", "נגן", "תנגן", "שים", "תשים", "השמע", "תשמיע");
        if (q != null) return zemerSearch(q);
        if (has(t, "נגן", "תנגן", "המשך", "תמשיך", "הפעל מוזיקה")) return media(126, "מנגן");

        // --- apps
        if (has(t, "זמר", "מוזיקה", "מוסיקה", "שירים", "פתח זמר")) return openZemer();
        if (has(t, "וויז", "ווייז", "waze", "מפה", "מפות")) return openApp("com.waze", "Waze");
        if (has(t, "אקולייזר", "איקולייזר", "אקולייזר")) return openApp("com.tw.eq", "אקולייזר");
        if (has(t, "מסך הבית", "מסך ראשי", "בית", "דרייב דק", "דרייבדק", "מחשב רכב", "נתוני רכב", "אובידי", "obd", "מנוע")) return home();
        if (has(t, "ביטול", "בטל", "תבטל")) return true;
        return false;
    }

    // ------------------------------------------------------------------ actions

    private boolean dd(String action, String msg) {
        last = "dd:" + action;
        try { SettingsActivity.send(this, action, null); say(msg); } catch (Throwable e) { say("DriveDeck לא זמין"); }
        return true;
    }

    private boolean media(int key, String msg) {
        last = "media"+key;
        try {
            AudioManager am = (AudioManager) getSystemService("audio");
            am.dispatchMediaKeyEvent(new KeyEvent(0, key));
            am.dispatchMediaKeyEvent(new KeyEvent(1, key));
            say(msg);
        } catch (Throwable e) { say("שגיאה"); }
        return true;
    }

    private boolean volume(int dir) {
        last = "vol"+dir;
        try {
            AudioManager am = (AudioManager) getSystemService("audio");
            for (int k = 0; k < 3; k++) am.adjustStreamVolume(3, dir, k == 0 ? 1 : 0); // STREAM_MUSIC, FLAG_SHOW_UI once
        } catch (Throwable ignored) { }
        return true;
    }

    private boolean navigateFav(String fav) {
        last = "fav:"+fav;
        if (tryStart(new Intent("android.intent.action.VIEW", Uri.parse("waze://?favorite=" + fav + "&navigate=yes")).setFlags(0x10000000))) return true;
        say("לא נמצא Waze");
        return true;
    }

    private boolean navigate(String dest) {
        last = "nav:"+dest;
        String e = Uri.encode(dest);
        if (tryStart(new Intent("android.intent.action.VIEW", Uri.parse("waze://?q=" + e + "&navigate=yes")).setFlags(0x10000000))) return true;
        if (tryStart(new Intent("android.intent.action.VIEW", Uri.parse("google.navigation:q=" + e)).setFlags(0x10000000))) return true;
        say("לא נמצאה אפליקציית ניווט");
        return true;
    }

    private boolean zemerSearch(String q) {
        last = "zemer:"+q;
        for (String p : ZEMER) {
            Intent i = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH");
            i.setPackage(p);
            i.putExtra("query", q);
            i.putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*");
            i.setFlags(0x10000000);
            if (tryStart(i)) { say("מחפש בזמר: " + q); return true; }
        }
        say("ZemerDrive לא מותקן");
        return true;
    }

    private boolean openZemer() {
        last = "openZemer";
        for (String p : ZEMER) if (openApp(p, null)) return true;
        say("ZemerDrive לא מותקן");
        return true;
    }

    private boolean home() {
        last = "home";
        Intent i = new Intent("android.intent.action.MAIN");
        i.addCategory("android.intent.category.HOME");
        i.setPackage(DRIVEDECK);
        i.setFlags(0x10000000);
        if (!tryStart(i)) openApp(DRIVEDECK, "DriveDeck");
        return true;
    }

    private boolean openApp(String pkg, String nameIfMissing) {
        last = "app:"+pkg;
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(0x10000000); startActivity(i); return true; }
        } catch (Throwable ignored) { }
        if (nameIfMissing != null) say(nameIfMissing + " לא מותקן");
        return nameIfMissing != null;
    }

    private boolean tryStart(Intent i) {
        try { startActivity(i); return true; } catch (Throwable e) { return false; }
    }

    private void say(String s) {
        try { Toast.makeText(getApplicationContext(), s, 0).show(); } catch (Throwable ignored) { }
    }
}
