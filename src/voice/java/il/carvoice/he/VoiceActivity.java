package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hebrew in-car voice commands. Started from the app icon, the steering-wheel voice key (DriveDeck routes it here)
 * or VOICE_COMMAND / ASSIST.
 *
 * Voice 8: listens in place (SpeechRecognizer) with a small panel at the bottom of the screen instead of Google's
 * full recogniser screen; prefers the online recogniser and falls back to the on-device one when there is no
 * internet; answers out loud (Hebrew text-to-speech); and when a sentence is not a command it does not stop at
 * "not understood": it asks about the most likely meaning ("לנגן את X?") and listens for yes / no / a choice.
 * Without the microphone permission or a recognition service it uses Google's recogniser screen as before.
 */
public class VoiceActivity extends Activity {
    private static final int REQ = 7301, REQ_PERM = 7302;
    static final int VERSION = 8;
    private static final String MIC = "android.permission.RECORD_AUDIO";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private boolean offline;              // retry on the on-device recogniser after a network error
    private boolean confirming;           // the last question was "did you mean ...?"
    private List<Parser.Cmd> options = new ArrayList<Parser.Cmd>();
    private String asked = "";
    private boolean closing;

    private TextView title, heard;
    private LinearLayout choices;
    private Commands commands;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return; // recreated while Google's recogniser screen is open: its result still arrives here
        commands = new Commands(this);
        buildPanel();
        try {
            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status != 0 || tts == null) return;
                    int r = tts.setLanguage(new Locale("he", "IL"));
                    ttsReady = r >= 0;
                    if (!ttsReady) Log.i(Commands.TAG, "no Hebrew text-to-speech voice (" + r + ")");
                }
            });
        } catch (Throwable e) {
            Log.w(Commands.TAG, "tts: " + e);
        }
        if (checkSelfPermission(MIC) != 0) {
            requestPermissions(new String[] { MIC }, REQ_PERM);
            return;
        }
        start();
    }

    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_PERM) return;
        if (res != null && res.length > 0 && res[0] == 0) start(); else legacy();
    }

    private void start() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                rec = SpeechRecognizer.createSpeechRecognizer(this);
                rec.setRecognitionListener(listener);
                listen();
                return;
            }
        } catch (Throwable e) {
            Log.w(Commands.TAG, "in-place recogniser: " + e);
        }
        legacy();
    }

    private void listen() {
        if (closing || rec == null) return;
        Intent i = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        i.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        i.putExtra("android.speech.extra.LANGUAGE", "he-IL");
        i.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "he-IL");
        i.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true);
        i.putExtra("android.speech.extra.PARTIAL_RESULTS", true);
        i.putExtra("android.speech.extra.MAX_RESULTS", 5);
        i.putExtra("calling_package", getPackageName());
        if (offline) i.putExtra("android.speech.extra.PREFER_OFFLINE", true);
        title.setText(confirming ? asked : offline ? "מקשיב… (בלי אינטרנט)" : "מקשיב…");
        try {
            rec.startListening(i);
            Log.i(Commands.TAG, "listening in place (voice app " + VERSION + (offline ? ", offline" : "") + ")");
        } catch (Throwable e) {
            Log.w(Commands.TAG, "startListening: " + e);
            legacy();
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        public void onReadyForSpeech(Bundle p) { }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float db) { }
        public void onBufferReceived(byte[] buf) { }
        public void onEndOfSpeech() { if (!confirming) title.setText("רגע…"); }
        public void onEvent(int type, Bundle p) { }

        public void onPartialResults(Bundle p) {
            ArrayList<String> l = p == null ? null : p.getStringArrayList("results_recognition");
            if (l != null && !l.isEmpty() && l.get(0) != null) heard.setText(l.get(0));
        }

        public void onResults(Bundle p) {
            ArrayList<String> l = p == null ? null : p.getStringArrayList("results_recognition");
            Log.i(Commands.TAG, "heard " + l);
            if (confirming) answer(l); else handle(l);
        }

        public void onError(int err) {
            Log.i(Commands.TAG, "recogniser error " + err + (offline ? " (offline)" : ""));
            // 1 network timeout, 2 network, 4 server: try the on-device recogniser once
            if (!offline && (err == 1 || err == 2 || err == 4)) { offline = true; listen(); return; }
            if (err == 9) { legacy(); return; } // no permission for the service
            String msg;
            if (err == 6 || err == 7) msg = confirming ? "לא שמעתי תשובה" : "לא שמעתי כלום";
            else if (offline) msg = "אין אינטרנט, ועברית לזיהוי דיבור בלי אינטרנט לא מותקנת (הגדרות Google ← קול ← זיהוי דיבור אופליין ← עברית)";
            else msg = "זיהוי הדיבור נכשל (" + err + ")";
            commands.note("", msg);
            done(msg, false);
        }
    };

    /** A sentence was heard: run it, or ask about its most likely meaning. */
    private void handle(List<String> l) {
        if (l == null || l.isEmpty()) { done("לא שמעתי כלום", false); return; }
        String first = l.get(0);
        heard.setText(first);
        Parser.Cmd c = Smart.understand(l);
        if (c != null) { run(first, c); return; }
        options = Smart.guesses(first);
        if (options.isEmpty()) {
            commands.note(first, "לא הבנתי");
            done("לא הבנתי. אפשר לומר \"עזרה\" לרשימת הפקודות", true);
            return;
        }
        ask(first);
    }

    private void ask(String first) {
        confirming = true;
        asked = "לא בטוח שהבנתי. " + Smart.describe(options.get(0)) + "?";
        title.setText(asked);
        choices.removeAllViews();
        for (final Parser.Cmd o : options) {
            Button btn = new Button(this);
            btn.setText(Smart.describe(o));
            btn.setTextSize(20);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { stopListening(); run(heard.getText() == null ? "" : heard.getText().toString(), o); }
            });
            choices.addView(btn);
        }
        choices.setVisibility(View.VISIBLE);
        say(asked, new Runnable() {
            public void run() {
                if (rec != null) { listen(); return; }
                // Google's screen cannot be asked again: the buttons stay for a while
                main.postDelayed(new Runnable() { public void run() { if (confirming) close(); } }, 12000L);
            }
        });
    }

    /** The answer to "did you mean ...?". */
    private void answer(List<String> l) {
        confirming = false;
        String s = l == null || l.isEmpty() ? "" : l.get(0);
        if (s == null) s = "";
        int yn = Smart.yesNo(s);
        if (yn == 1) { run(s, options.get(0)); return; }
        if (yn == 0) { commands.note(s, "בוטל"); done("בסדר", false); return; }
        int k = Smart.pick(s, options);
        if (k >= 0) { run(s, options.get(k)); return; }
        Parser.Cmd c = Smart.understand(l);
        if (c != null) { run(s, c); return; }
        commands.note(s, "לא הבנתי");
        done("לא הבנתי", false);
    }

    private void run(String said, Parser.Cmd c) {
        confirming = false;
        choices.setVisibility(View.GONE);
        String r = commands.execSafe(c);
        Log.i(Commands.TAG, "\"" + said + "\" -> " + c + " -> " + r);
        commands.note(said, r);
        done(r, false);
    }

    /** Show and say the outcome, then close (after the sentence was spoken). */
    private void done(String text, boolean longer) {
        title.setText(text);
        final long until = System.currentTimeMillis() + (longer ? 3500L : 1200L);
        say(text, new Runnable() {
            public void run() {
                long left = until - System.currentTimeMillis();
                main.postDelayed(new Runnable() { public void run() { close(); } }, left > 0 ? left : 0);
            }
        });
    }

    /** Speak a sentence (if a Hebrew voice exists), then run next once; next runs anyway after a time limit. */
    private void say(String text, final Runnable next) {
        final boolean[] once = { false };
        final Runnable go = new Runnable() {
            public void run() { if (once[0]) return; once[0] = true; if (!closing) next.run(); }
        };
        if (!ttsReady || !speechOn()) { main.postDelayed(go, 300L); return; }
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { }
                public void onDone(String id) { main.post(go); }
                public void onError(String id) { main.post(go); }
            });
            tts.speak(text, 0, null, "say");
            main.postDelayed(go, 1500L + text.length() * 110L);
        } catch (Throwable e) {
            main.postDelayed(go, 300L);
        }
    }

    private boolean speechOn() {
        try { return getSharedPreferences("voice", 0).getBoolean("speak", true); } catch (Throwable e) { return true; }
    }

    private void stopListening() {
        try { if (rec != null) rec.cancel(); } catch (Throwable ignored) { }
    }

    private void close() {
        if (closing) return;
        closing = true;
        stopListening();
        finish();
    }

    protected void onDestroy() {
        closing = true;
        try { if (rec != null) rec.destroy(); } catch (Throwable ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the panel

    private void buildPanel() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x55000000);
        root.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { close(); } });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(1);
        card.setLayoutDirection(1);
        card.setBackgroundColor(0xF0202124);
        card.setPadding(48, 28, 48, 28);
        card.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { } }); // taps on the card stay

        title = new TextView(this);
        title.setTextSize(24);
        title.setTextColor(0xFFFFFFFF);
        title.setText("🎤 מקשיב…");
        card.addView(title);

        heard = new TextView(this);
        heard.setTextSize(20);
        heard.setTextColor(0xFFB0C4FF);
        heard.setPadding(0, 10, 0, 6);
        card.addView(heard);

        choices = new LinearLayout(this);
        choices.setOrientation(0);
        choices.setLayoutDirection(1);
        choices.setVisibility(View.GONE);
        card.addView(choices);

        root.addView(card, new FrameLayout.LayoutParams(-1, -2, 80)); // MATCH_PARENT x WRAP_CONTENT, BOTTOM
        setContentView(root);
    }

    // ------------------------------------------------------------------ Google's recogniser screen (fallback)

    private void legacy() {
        Intent i = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        i.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        i.putExtra("android.speech.extra.LANGUAGE", "he-IL");
        i.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "he-IL");
        i.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true);
        i.putExtra("android.speech.extra.PROMPT", "דבר עכשיו…");
        i.putExtra("android.speech.extra.MAX_RESULTS", 5);
        try {
            startActivityForResult(i, REQ);
            Log.i(Commands.TAG, "listening with Google's screen (voice app " + VERSION + ")");
        } catch (Throwable e) {
            Log.w(Commands.TAG, "no speech recogniser: " + e);
            done("לא נמצא זיהוי דיבור של Google – יש להתקין את אפליקציית Google", true);
        }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        if (commands == null) commands = new Commands(this);
        ArrayList<String> list = data == null ? null : data.getStringArrayListExtra("android.speech.extra.RESULTS");
        Log.i(Commands.TAG, "recogniser result code " + res + ", " + (list == null ? "no" : String.valueOf(list.size())) + " candidates");
        if (title == null) { // recreated activity: no panel, the old behaviour
            if (res == -1 || (list != null && !list.isEmpty())) commands.run(list);
            finish();
            return;
        }
        if (list == null || list.isEmpty()) { close(); return; }
        handle(list);
    }
}
