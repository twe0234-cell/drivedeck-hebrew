package il.carvoice.he;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Launcher icon "אקולייזר": opens the unit's own equalizer (com.tw.eq), which drives the DSP through the MCU. */
public class EqActivity extends Activity {
    static final String EQ_PKG = "com.tw.eq", EQ_ACTIVITY = "com.tw.eq.EQChoiceActivity";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!open(this)) {
            try { android.widget.Toast.makeText(getApplicationContext(), "לא נמצא אקולייזר במערכת", 0).show(); } catch (Throwable ignored) { }
        }
        finish();
    }

    static boolean open(Activity c) {
        try {
            c.startActivity(new Intent().setClassName(EQ_PKG, EQ_ACTIVITY).addFlags(0x10000000));
            return true;
        } catch (Throwable e) {
            Log.w(Commands.TAG, "eq activity: " + e);
        }
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(EQ_PKG);
            if (i == null) return false;
            c.startActivity(i.addFlags(0x10000000));
            return true;
        } catch (Throwable e) {
            Log.w(Commands.TAG, "eq launch: " + e);
            return false;
        }
    }
}
