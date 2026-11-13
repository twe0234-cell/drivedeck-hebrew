package ru.drivedeck.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;

/** Foreground keep-alive; also detects "car woke up" through screen on after a sleep. */
public class KeepAliveService extends Service {
    private BroadcastReceiver screenReceiver;
    private static volatile long sScreenOffAt = -1L;

    public KeepAliveService() { }

    public static void start(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) { }
    }

    public IBinder onBind(Intent i) { return null; }

    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService("notification");
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("drivedeck_keepalive", "DriveDeck", 1);
                ch.setDescription("DriveDeck");
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) { }
        try {
            startForeground(7701, new Notification.Builder(this, "drivedeck_keepalive")
                    .setContentTitle("DriveDeck")
                    .setContentText("פועל ברקע")
                    .setSmallIcon(17301569)
                    .setOngoing(true)
                    .build());
        } catch (Throwable ignored) { }
        try {
            screenReceiver = new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    String a = i == null ? null : i.getAction();
                    long now = SystemClock.elapsedRealtime();
                    if ("android.intent.action.SCREEN_OFF".equals(a)) {
                        sScreenOffAt = now;
                    } else if ("android.intent.action.SCREEN_ON".equals(a)) {
                        long off = sScreenOffAt;
                        sScreenOffAt = -1L;
                        // a real sleep (car was switched off), not a short screen blank
                        if (off < 0 || now - off >= 30000L) RootBootEnforcer.onCarWake(c);
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction("android.intent.action.SCREEN_ON");
            f.addAction("android.intent.action.SCREEN_OFF");
            registerReceiver(screenReceiver, f);
        } catch (Throwable ignored) { }
    }

    public int onStartCommand(Intent i, int flags, int id) { return 1; }

    public void onDestroy() {
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
