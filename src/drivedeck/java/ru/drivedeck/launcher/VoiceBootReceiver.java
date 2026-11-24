package ru.drivedeck.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Boot / Topway wake receiver. Only reacts to known power-on actions. */
public final class VoiceBootReceiver extends BroadcastReceiver {
    public VoiceBootReceiver() { }

    private static boolean isWake(String a) {
        return "android.intent.action.BOOT_COMPLETED".equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.tw.action.QUICKBOOT_POWERON".equals(a)
                || "com.topway.action.ACC_ON".equals(a)
                || "com.topway.action.MCU_POWER_ON".equals(a);
    }

    public final void onReceive(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        String action = intent.getAction();
        if (HeRemote.isCommand(intent)) { HeRemote.command(ctx, intent); return; }
        if (!isWake(action)) return;
        HeHome.onWake(ctx, action);
        RootBootEnforcer.initAndEnforce(ctx);
        KeepAliveService.start(ctx);
        RootBootEnforcer.onCarWake(ctx);
        if (HeCall.inCall(ctx)) {
            // he-16: never put DriveDeck over the phone screen during a call
            HeLog.i("wake " + action + ": in a call, launcher not started");
            return;
        }
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            boolean voice = false;
            try { voice = new c.n1(ctx).r0(); } catch (Throwable ignored) { }
            if (voice) {
                // original DriveDeck behaviour: start the voice assistant together with the launcher
                i.setAction("ru.drivedeck.launcher.VOICE_STARTUP");
                i.addFlags(0x30000000);
            } else {
                i.setAction("android.intent.action.MAIN");
                i.addCategory("android.intent.category.HOME");
                i.addFlags(0x14000000);
            }
            ctx.startActivity(i);
            Log.i("VoiceBootReceiver", "wake " + action + ": launcher started" + (voice ? " with voice" : ""));
        } catch (Throwable e) {
            Log.w("VoiceBootReceiver", "launch failed", e);
        }
    }
}
