package ru.drivedeck.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * "בדיקת רכב": a full-screen dialog over DriveDeck's home (DriveDeck's manifest is compiled, so no new activity).
 * Opened by the action ru.drivedeck.he.OBD_SCREEN reaching MainActivity (voice "בדיקת רכב", the voice app's
 * settings screen, or `am start -a ru.drivedeck.he.OBD_SCREEN -n ru.drivedeck.launcher/.MainActivity`).
 */
public final class HeObdScreen {
    static final String ACTION = "ru.drivedeck.he.OBD_SCREEN";
    private static final int BG = 0xFF101418, FG = 0xFFECEFF1, DIM = 0xFF90A4AE, ACCENT = 0xFF26A69A;

    private HeObdScreen() { }

    /** From HebrewVoiceBridge.onIntent (MainActivity onNewIntent / onResume). */
    static boolean onIntent(Context c, Intent i) {
        if (i == null || !ACTION.equals(i.getAction())) return false;
        i.setAction(null);
        if (c instanceof Activity) show((Activity) c);
        return true;
    }

    /** Starts DriveDeck's MainActivity with the screen action (from a broadcast or another app). */
    static void open(Context c) {
        try {
            Intent i = new Intent(ACTION);
            i.setClassName("ru.drivedeck.launcher", "ru.drivedeck.launcher.MainActivity");
            i.addFlags(0x10000000 | 0x20000000); // NEW_TASK | SINGLE_TOP
            c.startActivity(i);
        } catch (Throwable t) {
            HeLog.w("obd screen open", t);
        }
    }

    static void show(final Activity a) {
        try {
            HeObd.init(a);
            final Dialog d = new Dialog(a, 0);
            d.requestWindowFeature(1); // FEATURE_NO_TITLE
            final Handler h = new Handler(Looper.getMainLooper());
            final int pad = 24;

            ScrollView scroll = new ScrollView(a);
            scroll.setBackgroundColor(BG);
            final LinearLayout root = new LinearLayout(a);
            root.setOrientation(1);
            root.setLayoutDirection(1); // RTL
            root.setPadding(pad, pad, pad, pad);
            scroll.addView(root);

            root.addView(text(a, "בדיקת רכב", 26, FG, true));
            root.addView(text(a, "סיאט לאון 1.2 TSI · נתונים מהמתאם, בלי לנתק אותו מ-DriveDeck", 14, DIM, false));

            final TextView live = text(a, "", 18, FG, false);
            root.addView(space(a));
            root.addView(text(a, "נתונים חיים", 20, ACCENT, true));
            root.addView(live);

            root.addView(space(a));
            root.addView(text(a, "בדיקת נסיעה", 20, ACCENT, true));
            root.addView(text(a, "לנסוע 15–20 דקות: התנעה, סרק, נסיעה ישרה מעל 50 קמ\"ש, וגם בהילוך שבו מורגשת הרעידה. אם אפשר, להפעיל לפני ההתנעה (מנוע כבוי) כדי לבדוק גם את חיישן ה-MAP והמצבר. הבדיקה קוראת גם את יחידת ה-ABS (מהירות כל גלגל, כיול התראת הלחץ בצמיגים), את הגיר (הילוך, מצמדים) ואת ההחמצות והנקישות בכל צילינדר. בזמן הבדיקה המסך הרגיל של DriveDeck מתעדכן לאט יותר.", 15, DIM, false));
            final TextView status = text(a, "", 16, FG, false);
            root.addView(status);
            final Button check = button(a, "");
            root.addView(check);
            final Button codes = button(a, "קרא קודי תקלה");
            root.addView(codes);
            final TextView report = text(a, HeObd.lastReport(), 17, FG, false);
            root.addView(report);

            root.addView(space(a));
            root.addView(text(a, "מהירות במסך הבית", 20, ACCENT, true));
            final TextView speedInfo = text(a, "", 15, DIM, false);
            root.addView(speedInfo);
            final Button source = button(a, "");
            root.addView(source);
            final Button calibrate = button(a, "");
            root.addView(calibrate);

            root.addView(space(a));
            Button close = button(a, "סגור");
            root.addView(close);

            final Runnable[] tick = new Runnable[1];
            tick[0] = new Runnable() {
                public void run() {
                    if (!d.isShowing()) return;
                    StringBuilder b = new StringBuilder();
                    for (String[] r : HeObd.live()) b.append(r[0]).append(": ").append(r[1]).append('\n');
                    live.setText(b.toString().trim());
                    if (HeObd.checking()) {
                        long min = (System.currentTimeMillis() - HeObd.checkStartedAt()) / 60000;
                        status.setText("בודק… " + min + " דקות, " + HeObd.sampleCount() + " דגימות");
                        check.setText("סיים והצג תוצאות");
                    } else {
                        status.setText("");
                        check.setText("התחל בדיקת נסיעה");
                    }
                    if (HeObd.codesRead()) {
                        List<String> c = HeObd.codes();
                        int mil = HeObd.mil();
                        String milText = mil < 0 ? "" : ((mil & 0x80) != 0 ? "נורת מנוע דולקת · " : "נורת מנוע כבויה · ");
                        codes.setText("קודי תקלה: " + milText + (c.isEmpty() ? "אין" : c.toString()));
                    }
                    boolean obd = HeObd.homeSpeedFromObd();
                    double ratio = HeObd.speedRatio();
                    double f = HeObd.suggestedSpeedFactor();
                    speedInfo.setText("כרגע מוצגת מהירות " + (obd ? "OBD" : "GPS") + ". מקדם OBD: " + HeObd.speedFactorSetting()
                            + (HeObdMath.ok(ratio) ? String.format(Locale.US, " · OBD לעומת GPS: %+.1f%% (%d מדידות)", (ratio - 1) * 100, HeObd.ratioCount())
                            : " · השוואה ל-GPS: צריך נסיעה יציבה מעל 40 קמ\"ש"));
                    source.setText(obd ? "להציג מהירות GPS" : "להציג מהירות OBD (מהמחשב של הרכב)");
                    calibrate.setText(HeObdMath.ok(f) ? String.format(Locale.US, "כייל מהירות OBD לפי GPS (מקדם %.3f)", f) : "כיול לפי GPS: אין עדיין מספיק נתונים");
                    calibrate.setEnabled(HeObdMath.ok(f));
                    h.postDelayed(this, 1000);
                }
            };

            check.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (HeObd.checking()) report.setText(HeObd.stopCheck());
                    else { HeObd.startCheck(); report.setText(""); }
                    tick[0].run();
                }
            });
            codes.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    HeObd.readCodes();
                    codes.setText("קורא קודי תקלה…");
                }
            });
            source.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { HeObd.setHomeSpeedFromObd(!HeObd.homeSpeedFromObd()); }
            });
            calibrate.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    double f = HeObd.suggestedSpeedFactor();
                    HeObd.applySpeedFactor(f);
                }
            });
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { d.dismiss(); }
            });
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface di) { HeObd.screenClosed(); }
            });

            d.setContentView(scroll);
            HeObd.screenOpened();
            d.show();
            if (d.getWindow() != null) d.getWindow().setLayout(-1, -1);
            tick[0].run();
            HeLog.i("obd screen shown");
        } catch (Throwable t) {
            HeLog.w("obd screen", t);
        }
    }

    private static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(0x00800003); // START (right in the RTL layout)
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD, 1);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(18);
        return b;
    }

    private static View space(Context c) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(-1, 24));
        return v;
    }
}
