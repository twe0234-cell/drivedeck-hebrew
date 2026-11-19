package il.carvoice.he;

import android.app.Activity;
import android.os.Bundle;

/** Launcher icon "בדיקת רכב": opens DriveDeck's car check screen (live data, drive check, codes). */
public class CarCheckActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SettingsActivity.send(this, "OBD", null, null);
        finish();
    }
}
