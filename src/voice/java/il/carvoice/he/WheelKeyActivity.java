package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Voice 9: what the steering-wheel voice key opens.
 *
 * The key goes from the car to the unit's system service (com.tw.service), which opens the activity named in
 * /data/tw/speech_name; DriveDeck points that file here. Opening Google from that file directly fires a generic
 * VOICE_COMMAND that DriveDeck and this app also answer, so this activity opens the chosen assistant explicitly:
 * Gemini (default) or the Hebrew assistant (settings: "כפתור הקול בהגה").
 */
public class WheelKeyActivity extends Activity {
    static final String GSA = "com.google.android.googlequicksearchbox";
    static final String PREF = "wheel_target", GEMINI = "gemini", HEBREW = "hebrew";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String target = GEMINI;
        try { target = getSharedPreferences("voice", 0).getString(PREF, GEMINI); } catch (Throwable ignored) { }
        if (GEMINI.equals(target) && openGemini()) { finish(); return; }
        openHebrew();
        finish();
    }

    private boolean openGemini() {
        for (String action : new String[] { "android.intent.action.VOICE_COMMAND", "android.intent.action.ASSIST",
                "android.intent.action.VOICE_ASSIST" }) {
            try {
                startActivity(new Intent(action).setPackage(GSA).addFlags(0x10000000));
                Log.i(Commands.TAG, "wheel voice key -> Gemini (" + action + ")");
                return true;
            } catch (Throwable e) {
                Log.w(Commands.TAG, "wheel voice key: " + action + " to Google failed: " + e);
            }
        }
        return false;
    }

    private void openHebrew() {
        try {
            startActivity(new Intent().setClassName(getPackageName(), "il.carvoice.he.VoiceActivity").addFlags(0x10000000));
            Log.i(Commands.TAG, "wheel voice key -> Hebrew assistant");
        } catch (Throwable e) {
            Log.w(Commands.TAG, "wheel voice key: Hebrew assistant failed: " + e);
        }
    }
}
