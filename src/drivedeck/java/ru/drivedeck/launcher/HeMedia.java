package ru.drivedeck.launcher;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * ZemerDrive on the DriveDeck home screen.
 *
 * DriveDeck shows a heart on the music card only for a player whose like button it recognises (English/Russian
 * names). ZemerDrive's button is TOGGLE_LIKE, named "לייק" / "הסר לייק" on a Hebrew unit: recognised here.
 * A tap on the heart when the song is already liked asks ZemerDrive to follow the song's artist
 * (session command DRIVE_FOLLOW_ARTIST). The Hebrew voice assistant reaches the same commands through
 * ru.drivedeck.he.LIKE / ru.drivedeck.he.FOLLOW_ARTIST.
 */
public final class HeMedia {
    private static final String TAG = "DriveDeckHe";
    static final String ACTION_LIKE = "TOGGLE_LIKE";
    static final String ACTION_FOLLOW = "DRIVE_FOLLOW_ARTIST";

    static boolean isZemer(String pkg) {
        return "com.jtech.zemer.drive".equals(pkg) || "com.jtech.zemer".equals(pkg);
    }

    private static String norm(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }

    /** Name of a like button whose song is already liked (c.V4.a). */
    public static boolean isLikedName(String name) {
        String n = norm(name);
        return n.equals("הסר לייק") || n.equals("remove like") || n.equals("בטל לייק") || n.equals("הסר מהאהובים")
                || n.equals("הסר מהמועדפים") || n.equals("הסר מהספרייה");
    }

    /** A player's like button (c.V4.d): Zvooq as before, ZemerDrive by its action id, Hebrew names. */
    public static boolean isLikeAction(String pkg, String action, CharSequence name) {
        if ("com.zvooq.openplay".equals(pkg) && "ACTION_LIKE".equals(action)) return true;
        if (isZemer(pkg) && ACTION_LIKE.equals(action)) return true;
        String n = norm(name);
        return n.equals("לייק") || n.equals("אהבתי") || n.equals("הוסף לאהובים") || n.equals("הוסף למועדפים")
                || n.equals("הוסף לספרייה");
    }

    /** Heart tapped while the song is already liked: ZemerDrive follows the artist. Null = DriveDeck's own message. */
    public static String onLikedTap(c.V4 v) {
        try {
            MediaController mc = v == null ? null : v.a;
            if (mc == null || !isZemer(mc.getPackageName())) return null;
            mc.getTransportControls().sendCustomAction(ACTION_FOLLOW, null);
            Log.i(TAG, "follow artist sent from the heart");
            return "זמר דרייב: האמן נוסף למעקב";
        } catch (Throwable e) {
            Log.w(TAG, "follow artist failed", e);
            return null;
        }
    }

    /** Voice / broadcast: sends [action] to the active ZemerDrive session. Returns a Hebrew status line. */
    static String sendToZemer(Context app, String action) {
        try {
            MediaSessionManager m = (MediaSessionManager) app.getSystemService("media_session");
            List<MediaController> list = m == null ? null : m.getActiveSessions(null);
            if (list != null) {
                for (MediaController mc : list) {
                    if (mc != null && isZemer(mc.getPackageName())) {
                        mc.getTransportControls().sendCustomAction(action, null);
                        Log.i(TAG, "sent " + action + " to " + mc.getPackageName());
                        return ACTION_FOLLOW.equals(action) ? "האמן נוסף למעקב בזמר דרייב" : "לייק נשלח לזמר דרייב";
                    }
                }
            }
            return "זמר דרייב לא מנגן כרגע";
        } catch (Throwable e) {
            Log.w(TAG, "send to ZemerDrive failed", e);
            return "שגיאה בשליחה לזמר דרייב: " + e.getClass().getSimpleName();
        }
    }
}
