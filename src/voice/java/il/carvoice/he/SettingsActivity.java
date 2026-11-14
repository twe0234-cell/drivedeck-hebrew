package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The assistant's own screen (second icon, "עוזר קולי – הגדרות"): what can be said, what was heard lately,
 * a typed command for testing without the microphone, and DriveDeck's GitHub updates / remote log. The DriveDeck
 * actions are explicit broadcasts to DriveDeck (the system app does the work and answers on screen).
 */
public class SettingsActivity extends Activity {
    static final String DD_PKG = "ru.drivedeck.launcher";
    static final String DD_RECEIVER = "ru.drivedeck.launcher.VoiceBootReceiver";

    static void send(android.content.Context c, String action, String key, String value) {
        Intent i = new Intent("ru.drivedeck.he." + action);
        i.setClassName(DD_PKG, DD_RECEIVER);
        if (key != null && value != null) i.putExtra(key, value);
        c.sendBroadcast(i);
    }

    private static final String HELP =
            "מוזיקה: \"הבא\", \"קודם\", \"עצור\", \"המשך\", \"תגביר\", \"תנמיך\"\n"
            + "ניגון: \"תנגן את ישי ריבו\", \"שירים של אברהם פריד\"\n"
            + "זמר דרייב: \"לייק\", \"עקוב אחרי האמן\"\n"
            + "ניווט: \"נווט הביתה\", \"נווט לעבודה\", \"קח אותי לירושלים\"\n"
            + "אפליקציות: \"פתח וויז\", \"פתח יוטיוב\", \"פתח הגדרות\" (כל אפליקציה בשמה), \"מסך הבית\"\n"
            + "מערכת: \"בדוק עדכונים\", \"שלח לוג\", \"עזרה\"";

    private TextView history;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("עוזר קולי – הגדרות");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(1);
        col.setPadding(48, 32, 48, 32);
        col.setLayoutDirection(1);

        col.addView(text("מה אפשר להגיד", 26));
        col.addView(text(HELP, 18));

        col.addView(text("בדיקה בלי מיקרופון", 26));
        final EditText typed = new EditText(this);
        typed.setHint("הקלד פקודה, למשל: פתח וויז");
        typed.setSingleLine(true);
        col.addView(typed);
        col.addView(button("בצע את הפקודה", new View.OnClickListener() {
            public void onClick(View v) {
                CharSequence s = typed.getText();
                String t = s == null ? "" : s.toString().trim();
                if (t.isEmpty()) { toast("הקלד פקודה קודם"); return; }
                java.util.ArrayList<String> one = new java.util.ArrayList<String>();
                one.add(t);
                new Commands(SettingsActivity.this).run(one);
                showHistory();
            }
        }));

        col.addView(text("מה נשמע לאחרונה", 26));
        history = text("", 16);
        col.addView(history);
        showHistory();

        col.addView(text("DriveDeck – עדכונים ולוג", 26));
        col.addView(text("עדכונים: DriveDeck והעוזר הקולי בודקים לבד כל כמה שעות אם יש גרסה חדשה ב-GitHub ומתקינים אותה. "
                + "עדכון של DriveDeck מותקן בהנעה הבאה (לפני שה-OBD מתחבר), או מיד בלחיצה על הכפתור.", 18));
        col.addView(text("לוג: הלוג של המולטימדיה נשלח לריפו הפרטי zemer-drive, ענף drivedeck-logs. צריך טוקן GitHub "
                + "(Fine-grained, הרשאת Contents: Read and write לריפו zemer-drive). אם ZemerDrive כבר מוגדר עם טוקן – "
                + "DriveDeck ישתמש בו לבד (root), ואין צורך להזין שוב.", 18));

        final EditText token = new EditText(this);
        token.setHint("github_pat_…  (טוקן GitHub)");
        token.setSingleLine(true);
        token.setInputType(0x81); // text | password
        col.addView(token);
        col.addView(button("שמור טוקן", new View.OnClickListener() {
            public void onClick(View v) {
                CharSequence s = token.getText();
                String t = s == null ? "" : s.toString().trim();
                if (t.isEmpty()) { toast("הדבק טוקן קודם"); return; }
                send(SettingsActivity.this, "SET_TOKEN", "token", t);
                token.setText("");
            }
        }));
        col.addView(button("בדוק עדכונים עכשיו", new View.OnClickListener() {
            public void onClick(View v) { send(SettingsActivity.this, "CHECK_UPDATE", null, null); toast("נשלח ל-DriveDeck"); }
        }));
        col.addView(button("שלח לוג מלא עכשיו", new View.OnClickListener() {
            public void onClick(View v) { send(SettingsActivity.this, "SEND_LOG", null, null); toast("נשלח ל-DriveDeck"); }
        }));
        col.addView(text("עוזר קולי גרסה " + VoiceActivity.VERSION, 14));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private void showHistory() {
        String h = Commands.history(this);
        history.setText(h.isEmpty() ? "עדיין לא נאמר כלום" : h);
    }

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, 12, 0, 12);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(20);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        try { Toast.makeText(getApplicationContext(), s, 0).show(); } catch (Throwable ignored) { }
    }
}
