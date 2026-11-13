# DriveDeck Hebrew – handoff

**Reply to the owner in Hebrew.** Rooted Topway TS18 (UIS8581A, Android 10), Seat Leon 5F 1.2 TSI (CYVB). No adb:
diagnose from the remote log (zemer-drive, branch `drivedeck-logs`).

## State (he-4, voice 2)
* DriveDeck runs as `android.uid.system` (signed with the AOSP platform test key, `build/keys`).
* Hooks in the smali tree (`build/drivedeck-apktool-src.tgz`, already applied):
  `MainActivity.c()/dispatchKeyEvent/onNewIntent/onResume` and `LauncherAccessibilityService.onKeyEvent` →
  `HebrewVoiceBridge`; `c.c9.i` (update fetcher) → `HeRemote.guardOfficialUpdate` (official channels off);
  `RootBootEnforcer.initAndEnforce` → `HeRemote.start`; `VoiceBootReceiver` takes `ru.drivedeck.he.*` commands.
* Voice app `il.carvoice.he` is signed with a private key kept in the PRIVATE repo zemer-drive
  (branch `ccr-a8a92c49-4lnpyb`, `drivedeck/keys/`), not here.
* Not tested on the unit yet: first install of he-4 is manual (Drive folder "נקיים להתקנה"), later ones are OTA.

## Next
1. Read `drivedeck-logs` once the owner has a token: check update installs, OBD auto-connect, steering-wheel key.
2. If `pm install` as system fails on the unit, the log shows it; root is the fallback.
