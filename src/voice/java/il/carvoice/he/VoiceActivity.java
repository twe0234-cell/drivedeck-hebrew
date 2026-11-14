package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;

/**
 * Hebrew in-car voice commands (Google speech recognition, he-IL).
 * Started from the app icon, the steering-wheel voice key (VOICE_COMMAND / ASSIST) or DriveDeck's microphone.
 * Parsing is in Parser, the actions in Commands.
 */
public class VoiceActivity extends Activity {
    private static final int REQ = 7301;
    static final int VERSION = 4;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return; // recreated while the recogniser is open: its result still arrives here
        Intent i = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        i.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        i.putExtra("android.speech.extra.LANGUAGE", "he-IL");
        i.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "he-IL");
        i.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true);
        i.putExtra("android.speech.extra.PROMPT", "דבר עכשיו…");
        i.putExtra("android.speech.extra.MAX_RESULTS", 5);
        try {
            startActivityForResult(i, REQ);
            Log.i(Commands.TAG, "listening (voice app " + VERSION + ")");
        } catch (Throwable e) {
            Log.w(Commands.TAG, "no speech recogniser: " + e);
            new Commands(this).say("לא נמצא זיהוי דיבור של Google – יש להתקין את אפליקציית Google", true);
            finish();
        }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ) {
            ArrayList<String> list = data == null ? null : data.getStringArrayListExtra("android.speech.extra.RESULTS");
            Log.i(Commands.TAG, "recogniser result code " + res + ", " + (list == null ? "no" : String.valueOf(list.size())) + " candidates");
            if (res == -1 || (list != null && !list.isEmpty())) new Commands(this).run(list);
        }
        finish();
    }
}
