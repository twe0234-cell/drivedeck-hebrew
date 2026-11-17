package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Carries out a parsed command. Every step is written to logcat under "HebrewVoice" (DriveDeck's remote log
 * picks it up) and to a short history shown on the assistant's settings screen.
 */
final class Commands {
    static final String TAG = "HebrewVoice";
    private static final String[] ZEMER = { "com.jtech.zemer.drive", "com.jtech.zemer" };
    private static final String DRIVEDECK = "ru.drivedeck.launcher";
    private static final String PREFS = "voice", KEY_HISTORY = "history";

    private final Activity a;

    Commands(Activity a) { this.a = a; }

    /** Tries each recognition candidate; the first one that is a command runs. Returns what was done, or null. */
    String run(List<String> heard) {
        if (heard == null || heard.isEmpty()) { note("", "לא נקלט דיבור"); say("לא נקלט דיבור", false); return null; }
        Log.i(TAG, "heard " + heard);
        for (String s : heard) {
            Parser.Cmd c = s == null ? null : Parser.parse(s);
            if (c == null) continue;
            String done;
            try { done = exec(c); } catch (Throwable e) { Log.w(TAG, "command failed: " + c, e); done = "שגיאה: " + e.getClass().getSimpleName(); }
            Log.i(TAG, "\"" + s + "\" -> " + c + " -> " + done);
            note(s, done);
            say("\"" + s + "\"  ←  " + done, false);
            return done;
        }
        String first = heard.get(0);
        Log.i(TAG, "no command in " + heard);
        note(first, "לא הבנתי");
        say("לא הבנתי: \"" + first + "\"\nאפשר להגיד \"עזרה\" לרשימת הפקודות", true);
        return null;
    }

    private String exec(Parser.Cmd c) {
        String k = c.kind;
        if (k.equals(Parser.DD)) return dd(c.arg);
        if (k.equals(Parser.MEDIA)) return media(Integer.parseInt(c.arg));
        if (k.equals(Parser.VOL)) return volume(Integer.parseInt(c.arg));
        if (k.equals(Parser.NAV_FAV)) return navigateFav(c.arg);
        if (k.equals(Parser.NAV)) return navigate(c.arg);
        if (k.equals(Parser.PLAY)) return play(c.arg);
        if (k.equals(Parser.ZEMER)) return openZemer();
        if (k.equals(Parser.WAZE)) return openPkg("com.waze") ? "פותח Waze" : openByName("waze");
        if (k.equals(Parser.MAPS)) return openPkg("com.google.android.apps.maps") ? "פותח מפות" : openPkg("com.waze") ? "פותח Waze" : "לא נמצאה אפליקציית מפות";
        if (k.equals(Parser.EQ)) return openPkg("com.tw.eq") ? "פותח אקולייזר" : openByName("eq");
        if (k.equals(Parser.HOME)) return home();
        if (k.equals(Parser.SETTINGS)) return start(new Intent("android.settings.SETTINGS")) ? "פותח הגדרות" : "ההגדרות לא נפתחו";
        if (k.equals(Parser.HELP)) return start(new Intent(a, SettingsActivity.class)) ? "רשימת הפקודות" : "שגיאה";
        if (k.equals(Parser.OPEN)) return openByName(c.arg);
        return "בוטל";
    }

    // ------------------------------------------------------------------ actions

    private boolean installed(String pkg) {
        try { return a.getPackageManager().getLaunchIntentForPackage(pkg) != null; } catch (Throwable e) { return false; }
    }

    private String dd(String action) {
        if (!installed(DRIVEDECK)) return "DriveDeck לא מותקן";
        SettingsActivity.send(a, action, null, null);
        if (action.equals("SEND_LOG")) return "שולח לוג";
        if (action.equals("CHECK_UPDATE")) return "בודק עדכונים";
        if (action.equals("FOLLOW_ARTIST")) return "מוסיף את האמן למעקב";
        if (action.equals("OBD")) return "פותח בדיקת רכב";
        return "לייק";
    }

    private String media(int key) {
        AudioManager am = (AudioManager) a.getSystemService("audio");
        am.dispatchMediaKeyEvent(new KeyEvent(0, key));
        am.dispatchMediaKeyEvent(new KeyEvent(1, key));
        return key == 87 ? "שיר הבא" : key == 88 ? "שיר קודם" : key == 127 ? "עצירה" : "מנגן";
    }

    private String volume(int dir) {
        AudioManager am = (AudioManager) a.getSystemService("audio");
        for (int k = 0; k < 3; k++) am.adjustStreamVolume(3, dir, k == 0 ? 1 : 0); // STREAM_MUSIC, the volume bar once
        return dir > 0 ? "מגביר" : "מנמיך";
    }

    private String navigateFav(String fav) {
        if (start(new Intent("android.intent.action.VIEW", Uri.parse("waze://?favorite=" + fav + "&navigate=yes"))))
            return fav.equals("home") ? "ניווט הביתה" : "ניווט לעבודה";
        return "לא נמצא Waze";
    }

    private String navigate(String dest) {
        String e = Uri.encode(dest);
        if (start(new Intent("android.intent.action.VIEW", Uri.parse("waze://?q=" + e + "&navigate=yes")))) return "ניווט אל " + dest;
        if (start(new Intent("android.intent.action.VIEW", Uri.parse("google.navigation:q=" + e)))) return "ניווט אל " + dest;
        return "לא נמצאה אפליקציית ניווט";
    }

    /** DriveDeck holds the media sessions: it asks ZemerDrive to search and play. */
    private String play(String q) {
        if (installed(DRIVEDECK)) {
            SettingsActivity.send(a, "PLAY", "query", q);
            return "מחפש בזמר דרייב: " + q;
        }
        return openZemer();
    }

    private String openZemer() {
        for (String p : ZEMER) if (openPkg(p)) return "פותח זמר דרייב";
        return "זמר דרייב לא מותקן";
    }

    private String home() {
        Intent i = new Intent("android.intent.action.MAIN");
        i.addCategory("android.intent.category.HOME");
        i.setPackage(DRIVEDECK);
        if (start(i) || openPkg(DRIVEDECK)) return "מסך הבית";
        Intent h = new Intent("android.intent.action.MAIN");
        h.addCategory("android.intent.category.HOME");
        return start(h) ? "מסך הבית" : "מסך הבית לא נפתח";
    }

    /** "פתח יוטיוב": the installed app whose name (or package) fits best. */
    private String openByName(String name) {
        PackageManager pm = a.getPackageManager();
        Intent main = new Intent("android.intent.action.MAIN");
        main.addCategory("android.intent.category.LAUNCHER");
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        String[] wanted = Parser.aliases(name);
        ResolveInfo best = null;
        int bestScore = 0;
        String bestLabel = null;
        if (apps != null) {
            for (ResolveInfo r : apps) {
                if (r == null || r.activityInfo == null) continue;
                CharSequence l = null;
                try { l = r.loadLabel(pm); } catch (Throwable ignored) { }
                String label = Parser.canon(Parser.clean(l == null ? "" : l.toString()));
                String pkg = r.activityInfo.packageName == null ? "" : r.activityInfo.packageName.toLowerCase(Locale.ROOT);
                int score = 0;
                for (String w : wanted) {
                    if (w.isEmpty()) continue;
                    if (label.equals(w)) score = Math.max(score, 100);
                    else if (label.startsWith(w + " ") || label.endsWith(" " + w) || label.contains(" " + w + " ")) score = Math.max(score, 80);
                    else if (w.length() >= 3 && label.contains(w)) score = Math.max(score, 60);
                    else if (label.length() >= 3 && w.contains(label)) score = Math.max(score, 50);
                    else if (w.length() >= 3 && w.charAt(0) < 128 && pkg.contains(w.replace(" ", ""))) score = Math.max(score, 40);
                }
                if (score > bestScore) { bestScore = score; best = r; bestLabel = l == null ? pkg : l.toString(); }
            }
        }
        if (best == null) return "לא נמצאה אפליקציה בשם " + name;
        Intent i = new Intent("android.intent.action.MAIN");
        i.addCategory("android.intent.category.LAUNCHER");
        i.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        return start(i) ? "פותח " + bestLabel : bestLabel + " לא נפתח";
    }

    private boolean openPkg(String pkg) {
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
            return i != null && start(i);
        } catch (Throwable e) { return false; }
    }

    private boolean start(Intent i) {
        try { i.addFlags(0x10000000); a.startActivity(i); return true; }
        catch (Throwable e) { Log.w(TAG, "start failed: " + e); return false; }
    }

    // ------------------------------------------------------------------ feedback

    void say(String s, boolean longer) {
        try { Toast.makeText(a.getApplicationContext(), s, longer ? 1 : 0).show(); } catch (Throwable ignored) { }
    }

    private void note(String heard, String done) {
        try {
            SharedPreferences p = a.getSharedPreferences(PREFS, 0);
            String line = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()) + "  \"" + heard + "\"  ←  " + done;
            String[] old = p.getString(KEY_HISTORY, "").split("\n");
            StringBuilder b = new StringBuilder(line);
            for (int i = 0; i < old.length && i < 11; i++) if (!old[i].isEmpty()) b.append('\n').append(old[i]);
            p.edit().putString(KEY_HISTORY, b.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static String history(Activity a) {
        try { return a.getSharedPreferences(PREFS, 0).getString(KEY_HISTORY, ""); } catch (Throwable e) { return ""; }
    }
}
