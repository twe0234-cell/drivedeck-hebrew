package ru.drivedeck.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * DriveDeck Hebrew: over-the-air updates from GitHub and a remote log, so nothing needs a cable or a reinstall.
 *
 * Updates: the public repo {@link #UPDATE_REPO} publishes releases tagged {@code he-<N>} with a {@code manifest.json}
 * asset listing the APKs (DriveDeck and the Hebrew voice app) with size and SHA-256. A higher build is downloaded,
 * verified and installed silently (DriveDeck runs as the system uid, so {@code pm install} needs no prompt; root is
 * a fallback). DriveDeck itself is installed when the OBD session is idle, at the next car wake, or at once when asked.
 * The official Russian update channels are disabled ({@link #guardOfficialUpdate}) so they never replace this build.
 *
 * Log: the unit's log (logcat; the system uid sees every app) plus DriveDeck crashes go to the PRIVATE repo
 * {@link #LOG_REPO}, branch {@link #LOG_BRANCH}, as {@code logs/<date>/<time>-<model>.log}, through the GitHub contents
 * API with a fine-grained token (Contents: read and write). The token is never in the APK: it comes from the Hebrew
 * voice app's settings screen, from {@code Download/drivedeck-token.txt} (read once, then deleted), or is reused
 * from ZemerDrive's own log token (root).
 *
 * Commands (explicit broadcast to VoiceBootReceiver): ru.drivedeck.he.SET_TOKEN (extra "token"),
 * ru.drivedeck.he.CHECK_UPDATE, ru.drivedeck.he.SEND_LOG.
 */
public final class HeRemote {
    /** This build's number; a release he-N with N greater than this one updates DriveDeck. */
    public static final int BUILD = 5;

    static final String UPDATE_REPO = "twe0234-cell/drivedeck-hebrew";
    static final String LOG_REPO = "twe0234-cell/zemer-drive";
    static final String LOG_BRANCH = "drivedeck-logs";
    static final String TAG_PREFIX = "he-";
    static final String VOICE_PKG = "il.carvoice.he";
    static final String SELF_PKG = "ru.drivedeck.launcher";

    static final String ACTION_SET_TOKEN = "ru.drivedeck.he.SET_TOKEN";
    static final String ACTION_CHECK_UPDATE = "ru.drivedeck.he.CHECK_UPDATE";
    static final String ACTION_SEND_LOG = "ru.drivedeck.he.SEND_LOG";

    private static final String TAG = "DriveDeckHe";
    private static final String PREFS = "drivedeck_he_remote";
    private static final long FIRST_DELAY_MS = 75_000L;
    private static final long LOOP_MS = 30 * 60_000L;
    private static final long UPDATE_EVERY_MS = 6 * 60 * 60_000L;
    private static final long LOG_EVERY_MS = 60 * 60_000L;
    private static final int MAX_LOG_CHARS = 700_000;

    private static volatile boolean sStarted = false;
    private static final Object sLock = new Object();
    private static volatile long sLastUpdateCheck = 0L;
    private static volatile long sLastLogUpload = 0L;

    // ================================================================== start / commands

    /** Called once per process (RootBootEnforcer.initAndEnforce). */
    public static void start(Context ctx) {
        if (ctx == null || sStarted) return;
        sStarted = true;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        installCrashHandler(app);
        SharedPreferences p = prefs(app);
        if (p.getBoolean("post_update_wake", false)) {
            // first start after a self-update: reconnect OBD like a car wake
            p.edit().putBoolean("post_update_wake", false).apply();
            p.edit().putString("update_status", stamp() + " עודכן ל-he-" + BUILD).apply();
            try { RootBootEnforcer.onCarWake(app); } catch (Throwable ignored) { }
        }
        Thread t = new Thread(new Runnable() {
            public void run() { loop(app); }
        }, "dd-he-remote");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isCommand(Intent i) {
        String a = i == null ? null : i.getAction();
        return a != null && a.startsWith("ru.drivedeck.he.");
    }

    public static void command(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final String a = intent.getAction();
        if (ACTION_SET_TOKEN.equals(a)) {
            String tok = intent.getStringExtra("token");
            saveToken(app, tok);
            toast(app, hasToken(app) ? "DriveDeck: טוקן הלוג נשמר" : "DriveDeck: טוקן הלוג נמחק");
            return;
        }
        if ("ru.drivedeck.he.LIKE".equals(a)) { toast(app, HeMedia.sendToZemer(app, HeMedia.ACTION_LIKE)); return; }
        if ("ru.drivedeck.he.FOLLOW_ARTIST".equals(a)) { toast(app, HeMedia.sendToZemer(app, HeMedia.ACTION_FOLLOW)); return; }
        new Thread(new Runnable() {
            public void run() {
                if (ACTION_CHECK_UPDATE.equals(a)) {
                    toast(app, "DriveDeck: בודק עדכונים…");
                    toast(app, "DriveDeck: " + checkUpdate(app, true));
                } else if (ACTION_SEND_LOG.equals(a)) {
                    toast(app, "DriveDeck: שולח לוג…");
                    toast(app, "DriveDeck: " + uploadLog(app, true));
                }
            }
        }, "dd-he-cmd").start();
    }

    private static void loop(Context app) {
        sleep(FIRST_DELAY_MS);
        importToken(app);
        // the previous run's crash (if any) goes up first
        if (crashFile(app).length() > 0) uploadLog(app, false);
        while (true) {
            long now = SystemClock.elapsedRealtime();
            if (sLastUpdateCheck == 0L || now - sLastUpdateCheck >= UPDATE_EVERY_MS) {
                String s = checkUpdate(app, false);
                Log.i(TAG, "update check: " + s);
            } else {
                installPendingSelfIfIdle(app);
            }
            if (sLastLogUpload == 0L || now - sLastLogUpload >= LOG_EVERY_MS) {
                String s = uploadLog(app, false);
                Log.i(TAG, "log: " + s);
            }
            sleep(LOOP_MS);
        }
    }

    // ================================================================== official updates guard

    /** Inserted at the top of DriveDeck's update fetcher (c.c9.i): the official app channels never reach this build. */
    public static void guardOfficialUpdate(String url) {
        if (url == null || url.contains("voice") || url.contains("/obd/")) return;
        if (url.contains("drivedeck-updates-ru/update.json") || url.contains("drivedeck-updates-ru/beta/")
                || url.contains("drivedeck-updates-ru/alpha") || url.contains("DriveDeck-Updates/releases/download/channels/")) {
            throw new IllegalStateException("עדכונים רשמיים כבויים בגרסה העברית – העדכונים מגיעים מ-GitHub");
        }
    }

    // ================================================================== updates

    /** Returns a short Hebrew status line. */
    static String checkUpdate(Context app, boolean userAsked) {
        synchronized (sLock) {
            sLastUpdateCheck = SystemClock.elapsedRealtime();
            String status;
            try {
                status = checkUpdateLocked(app, userAsked);
            } catch (Throwable e) {
                Log.w(TAG, "update check failed", e);
                status = "בדיקת עדכון נכשלה: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
            }
            prefs(app).edit().putString("update_status", stamp() + " " + status).apply();
            return status;
        }
    }

    private static String checkUpdateLocked(Context app, boolean userAsked) throws Exception {
        String rel = httpGet("https://api.github.com/repos/" + UPDATE_REPO + "/releases?per_page=20", null);
        JSONArray arr = new JSONArray(rel);
        int best = -1;
        JSONObject bestRel = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null || r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
            int n = buildOf(r.optString("tag_name", ""));
            if (n > best) { best = n; bestRel = r; }
        }
        if (bestRel == null) return "לא נמצאה גרסה ב-GitHub";
        JSONArray assets = bestRel.optJSONArray("assets");
        String manifestUrl = assetUrl(assets, "manifest.json");
        if (manifestUrl == null) return "בגרסה he-" + best + " אין manifest.json";
        JSONObject manifest = new JSONObject(httpGet(manifestUrl, null));
        JSONArray apps = manifest.optJSONArray("apps");
        if (apps == null) return "manifest.json פגום";

        StringBuilder done = new StringBuilder();
        File selfApk = null;
        int selfBuild = 0;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a == null) continue;
            String pkg = a.optString("package", "");
            String file = a.optString("file", "");
            String url = assetUrl(assets, file);
            if (url == null) continue;
            if (SELF_PKG.equals(pkg)) {
                int b = a.optInt("build", 0);
                if (b <= BUILD) continue;
                File f = download(app, url, file, a.optLong("size", -1L), a.optString("sha256", ""));
                if (f == null) return "הורדת DriveDeck he-" + b + " נכשלה (בדיקת תקינות)";
                selfApk = f;
                selfBuild = b;
            } else if (VOICE_PKG.equals(pkg)) {
                int vc = a.optInt("versionCode", 0);
                if (vc <= installedVersionCode(app, VOICE_PKG)) continue;
                File f = download(app, url, file, a.optLong("size", -1L), a.optString("sha256", ""));
                if (f == null) return "הורדת העוזר הקולי נכשלה (בדיקת תקינות)";
                String r = install(f);
                done.append("עוזר קולי ").append(vc).append(": ").append(r).append(". ");
            }
        }
        if (selfApk == null) {
            return done.length() > 0 ? done.toString().trim() : "מעודכן (he-" + BUILD + ")";
        }
        SharedPreferences.Editor e = prefs(app).edit();
        e.putString("pending_self", selfApk.getAbsolutePath()).putInt("pending_build", selfBuild).apply();
        if (userAsked || obdIdle(app)) {
            done.append("מתקין DriveDeck he-").append(selfBuild).append(" – המסך יתרענן");
            final File f = selfApk;
            installSelf(app, f);
            return done.toString();
        }
        done.append("DriveDeck he-").append(selfBuild).append(" הורד, יותקן בהנעה הבאה");
        return done.toString();
    }

    /** Car wake (before OBD connects) or an idle moment: installs a DriveDeck update that is already downloaded. */
    public static boolean installPendingSelf(Context app) {
        try {
            SharedPreferences p = prefs(app);
            String path = p.getString("pending_self", "");
            int b = p.getInt("pending_build", 0);
            if (path == null || path.isEmpty() || b <= BUILD) {
                if (path != null && !path.isEmpty()) p.edit().putString("pending_self", "").apply();
                return false;
            }
            File f = new File(path);
            if (!f.isFile()) { p.edit().putString("pending_self", "").apply(); return false; }
            installSelf(app, f);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "pending install failed", e);
            return false;
        }
    }

    public static boolean hasPendingSelf(Context app) {
        try {
            SharedPreferences p = prefs(app);
            String path = p.getString("pending_self", "");
            return path != null && !path.isEmpty() && p.getInt("pending_build", 0) > BUILD && new File(path).isFile();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void installPendingSelfIfIdle(Context app) {
        if (obdIdle(app)) installPendingSelf(app);
    }

    private static void installSelf(Context app, File f) {
        prefs(app).edit().putBoolean("post_update_wake", true).commit();
        Log.i(TAG, "installing DriveDeck update " + f.getName());
        String r = install(f); // on success this process is replaced and never gets here
        Log.w(TAG, "DriveDeck self-install result: " + r);
        prefs(app).edit().putBoolean("post_update_wake", false).putString("pending_self", "")
                .putString("update_status", stamp() + " התקנה נכשלה: " + r).commit();
        f.delete();
    }

    private static boolean obdIdle(Context app) {
        try {
            c.T5 t = c.T5.V(app);
            return t == null || RootBootEnforcer.isIdle(t);
        } catch (Throwable e) {
            return true;
        }
    }

    static int buildOf(String tag) {
        if (tag == null || !tag.startsWith(TAG_PREFIX)) return -1;
        try { return Integer.parseInt(tag.substring(TAG_PREFIX.length()).trim()); } catch (Throwable e) { return -1; }
    }

    private static String assetUrl(JSONArray assets, String name) {
        if (assets == null || name == null || name.isEmpty()) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && name.equals(a.optString("name", ""))) return a.optString("browser_download_url", null);
        }
        return null;
    }

    private static int installedVersionCode(Context app, String pkg) {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
            return pi == null ? -1 : pi.versionCode;
        } catch (Throwable e) {
            return -1; // not installed: install it
        }
    }

    private static File download(Context app, String url, String name, long size, String sha) throws Exception {
        File dir = new File(app.getCacheDir(), "he-update");
        if (!dir.isDirectory()) dir.mkdirs();
        File target = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
        if (target.isFile() && verified(target, size, sha)) return target;
        File part = new File(dir, target.getName() + ".part");
        HttpURLConnection c = open(url, null);
        c.setReadTimeout(60_000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                out.close();
            }
        } finally {
            c.disconnect();
        }
        if (!verified(part, size, sha)) { part.delete(); return null; }
        target.delete();
        if (!part.renameTo(target)) return null;
        target.setReadable(true, false);
        dir.setExecutable(true, false);
        return target;
    }

    private static boolean verified(File f, long size, String sha) throws Exception {
        if (size > 0 && f.length() != size) return false;
        if (sha == null || sha.isEmpty()) return size > 0;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return hex(md.digest()).equalsIgnoreCase(sha.trim());
    }

    /** pm install as the system uid (no prompt); root as a fallback. Returns "הותקן" or the error. */
    private static String install(File apk) {
        String path = apk.getAbsolutePath();
        String out = run(new String[] { "pm", "install", "-r", path }, 120_000L);
        if (out != null && out.contains("Success")) return "הותקן";
        String out2 = run(new String[] { "su", "-c", "pm install -r '" + path + "'" }, 120_000L);
        if (out2 != null && out2.contains("Success")) return "הותקן (root)";
        String err = (out2 != null && !out2.trim().isEmpty()) ? out2 : out;
        return "נכשל: " + (err == null ? "?" : err.trim().replace('\n', ' '));
    }

    // ================================================================== remote log

    /** Returns a short Hebrew status line. [full] = manual send: the whole recent device log. */
    static String uploadLog(Context app, boolean full) {
        synchronized (sLock) {
            sLastLogUpload = SystemClock.elapsedRealtime();
            String status;
            try {
                status = uploadLogLocked(app, full);
            } catch (Throwable e) {
                Log.w(TAG, "log upload failed", e);
                status = "שליחת לוג נכשלה: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
            }
            prefs(app).edit().putString("log_status", stamp() + " " + status).apply();
            return status;
        }
    }

    private static String uploadLogLocked(Context app, boolean full) throws Exception {
        String token = token(app);
        if (token == null) {
            importToken(app);
            token = token(app);
        }
        if (token == null) return "אין טוקן GitHub – הגדר אותו ב'עדכונים ולוג' של העוזר הקולי";
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        String crash = readFile(crashFile(app));
        String logcat;
        if (full) {
            logcat = run(new String[] { "logcat", "-d", "-v", "time", "-t", "8000" }, 20_000L);
            String crashBuf = run(new String[] { "logcat", "-d", "-v", "time", "-b", "crash" }, 10_000L);
            if (crashBuf != null && !crashBuf.trim().isEmpty()) logcat = logcat + "\n== crash buffer ==\n" + crashBuf;
        } else {
            long since = p.getLong("log_since", 0L);
            if (since > 0L && now - since < 3 * 60 * 60_000L) {
                String t = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(since));
                logcat = run(new String[] { "logcat", "-d", "-v", "time", "-T", t, "*:I" }, 20_000L);
            } else {
                logcat = run(new String[] { "logcat", "-d", "-v", "time", "-t", "3000", "*:I" }, 20_000L);
            }
            if ((logcat == null || logcat.trim().isEmpty()) && crash.isEmpty()) return "אין חדש";
        }
        StringBuilder body = new StringBuilder(header(app, now, full));
        if (!crash.isEmpty()) body.append("\n== DriveDeck crash ==\n").append(crash);
        body.append("\n== logcat ==\n").append(logcat == null ? "(logcat unavailable)" : logcat);
        String text = body.length() > MAX_LOG_CHARS ? "…(older lines dropped)\n" + body.substring(body.length() - MAX_LOG_CHARS) : body.toString();
        String path = logPath(now, Build.MODEL, full);
        String r = putFile(token, path, text);
        if ("branch".equals(r)) {
            createBranch(token);
            r = putFile(token, path, text);
        }
        if (!"ok".equals(r)) return "שליחת לוג נכשלה: " + r;
        p.edit().putLong("log_since", now).apply();
        if (!crash.isEmpty()) crashFile(app).delete();
        return "לוג נשלח: " + path;
    }

    private static String header(Context app, long now, boolean full) {
        StringBuilder b = new StringBuilder();
        b.append("DriveDeck Hebrew he-").append(BUILD).append(full ? " (manual send)" : " (auto)").append('\n');
        b.append("time: ").append(new Date(now)).append('\n');
        b.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / ").append(Build.DEVICE)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("build: ").append(Build.DISPLAY).append('\n');
        b.append("uptime: ").append(SystemClock.elapsedRealtime() / 60000L).append(" min\n");
        for (String pkg : new String[] { SELF_PKG, VOICE_PKG, "com.jtech.zemer.drive", "com.jtech.zemer", "com.google.android.googlequicksearchbox" }) {
            b.append("app ").append(pkg).append(": ").append(versionOf(app, pkg)).append('\n');
        }
        try {
            SharedPreferences o = app.getSharedPreferences("drivedeck_obd", 0);
            b.append("obd: adapter=").append(o.getString("name", "")).append(" addr=").append(empty(o.getString("address", "")) ? "-" : "set")
                    .append(" profile=").append(o.getString("profile_id", "")).append(" protocol=").append(o.getInt("protocol", 0))
                    .append(" error=").append(o.getString("adapter_error", "")).append(" idle=").append(obdIdle(app)).append('\n');
        } catch (Throwable ignored) { }
        try {
            android.content.ContentResolver cr = app.getContentResolver();
            b.append("a11y: ").append(android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services")).append('\n');
            b.append("assistant: ").append(android.provider.Settings.Secure.getString(cr, "assistant")).append('\n');
        } catch (Throwable ignored) { }
        SharedPreferences p = prefs(app);
        b.append("last update check: ").append(p.getString("update_status", "-")).append('\n');
        return b.toString();
    }

    private static String versionOf(Context app, String pkg) {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable e) {
            return "not installed";
        }
    }

    static String logPath(long time, String model, boolean full) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(utc);
        SimpleDateFormat clock = new SimpleDateFormat("HHmmss", Locale.US);
        clock.setTimeZone(utc);
        String dev = (model == null ? "" : model).replaceAll("[^A-Za-z0-9_-]", "_");
        if (dev.length() > 24) dev = dev.substring(0, 24);
        if (dev.isEmpty()) dev = "device";
        return "logs/" + day.format(new Date(time)) + "/" + clock.format(new Date(time)) + "-" + dev + (full ? "-full" : "") + ".log";
    }

    /** "ok", "branch" (the log branch does not exist yet) or an error. */
    private static String putFile(String token, String path, String content) throws Exception {
        JSONObject j = new JSONObject();
        j.put("message", "log: " + path);
        j.put("branch", LOG_BRANCH);
        j.put("content", Base64.getEncoder().encodeToString(content.getBytes("UTF-8")));
        HttpURLConnection c = open("https://api.github.com/repos/" + LOG_REPO + "/contents/" + path, token);
        try {
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(j.toString().getBytes("UTF-8"));
            out.close();
            int code = c.getResponseCode();
            if (code == 200 || code == 201) return "ok";
            String err = readAll(c.getErrorStream());
            if (code == 404 || (code == 422 && err.toLowerCase(Locale.ROOT).contains("branch"))) return "branch";
            if (code == 401) return "הטוקן לא תקף (401)";
            if (code == 403) return "לטוקן אין הרשאת Contents לריפו zemer-drive (403)";
            return "HTTP " + code;
        } finally {
            c.disconnect();
        }
    }

    private static void createBranch(String token) throws Exception {
        JSONObject repo = new JSONObject(httpGet("https://api.github.com/repos/" + LOG_REPO, token));
        String base = repo.optString("default_branch", "main");
        JSONObject ref = new JSONObject(httpGet("https://api.github.com/repos/" + LOG_REPO + "/git/ref/heads/" + base, token));
        JSONObject obj = ref.optJSONObject("object");
        String sha = obj == null ? null : obj.optString("sha", null);
        if (sha == null) throw new IllegalStateException("no base sha");
        JSONObject j = new JSONObject();
        j.put("ref", "refs/heads/" + LOG_BRANCH);
        j.put("sha", sha);
        HttpURLConnection c = open("https://api.github.com/repos/" + LOG_REPO + "/git/refs", token);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(j.toString().getBytes("UTF-8"));
            out.close();
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    // ================================================================== token

    private static File tokenFile(Context app) { return new File(app.getNoBackupFilesDir(), "he_github_token"); }

    static boolean hasToken(Context app) { return token(app) != null; }

    static String token(Context app) {
        String t = readFile(tokenFile(app)).trim();
        return t.isEmpty() ? null : t;
    }

    static void saveToken(Context app, String token) {
        try {
            String t = token == null ? "" : token.trim();
            File f = tokenFile(app);
            if (t.isEmpty()) { f.delete(); return; }
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(t.getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable e) {
            Log.w(TAG, "token save failed", e);
        }
    }

    /** Download/drivedeck-token.txt (deleted after reading), else ZemerDrive's log token (same private repo) via root. */
    @SuppressWarnings("deprecation")
    static void importToken(Context app) {
        if (hasToken(app)) return;
        try {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "drivedeck-token.txt");
            if (d.canRead()) {
                saveToken(app, readFile(d));
                d.delete();
                if (hasToken(app)) { Log.i(TAG, "token imported from Download"); return; }
            }
        } catch (Throwable ignored) { }
        SharedPreferences p = prefs(app);
        if (p.getBoolean("zemer_token_tried", false)) return;
        p.edit().putBoolean("zemer_token_tried", true).apply();
        String out = run(new String[] { "su", "-c",
                "cat /data/data/com.jtech.zemer.drive/no_backup/drive_github_token 2>/dev/null || cat /data/data/com.jtech.zemer/no_backup/drive_github_token 2>/dev/null" }, 15_000L);
        if (out != null) {
            String t = out.trim();
            if (t.length() >= 20 && !t.contains(" ") && !t.contains("\n")) {
                saveToken(app, t);
                Log.i(TAG, "token reused from ZemerDrive");
            }
        }
    }

    // ================================================================== crash capture

    private static File crashFile(Context app) { return new File(app.getFilesDir(), "he-crash.log"); }

    private static void installCrashHandler(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable e) {
                try {
                    FileOutputStream out = new FileOutputStream(crashFile(app), true);
                    try {
                        String s = new Date() + " FATAL in thread " + th.getName() + " (he-" + BUILD + ")\n" + Log.getStackTraceString(e) + "\n";
                        out.write(s.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(th, e);
            }
        });
    }

    // ================================================================== helpers

    private static SharedPreferences prefs(Context app) { return app.getSharedPreferences(PREFS, 0); }

    private static HttpURLConnection open(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DriveDeck-Hebrew/" + BUILD);
        if (url.startsWith("https://api.github.com/")) {
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String httpGet(String url, String token) throws Exception {
        HttpURLConnection c = open(url, token);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Throwable e) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readFile(File f) {
        try {
            if (f == null || !f.isFile()) return "";
            return readAll(new FileInputStream(f));
        } catch (Throwable e) {
            return "";
        }
    }

    /** Runs a command; stdout+stderr, or null if it could not start / timed out. */
    static String run(String[] cmd, long timeoutMs) {
        Process pr = null;
        try {
            ProcessBuilder b = new ProcessBuilder(cmd);
            b.redirectErrorStream(true);
            pr = b.start();
            final Process fp = pr;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        InputStream in = fp.getInputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            synchronized (out) { if (out.size() < 4_000_000) out.write(buf, 0, n); }
                        }
                    } catch (Throwable ignored) { }
                }
            });
            reader.start();
            reader.join(timeoutMs);
            if (reader.isAlive()) { pr.destroy(); return null; }
            pr.waitFor();
            synchronized (out) { return out.toString("UTF-8"); }
        } catch (Throwable e) {
            if (pr != null) try { pr.destroy(); } catch (Throwable ignored) { }
            return null;
        }
    }

    private static void toast(final Context app, final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try { Toast.makeText(app, text, 1).show(); } catch (Throwable ignored) { }
            }
        });
    }

    private static String stamp() { return new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date()); }

    private static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.US, "%02x", x & 0xff));
        return b.toString();
    }

    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
