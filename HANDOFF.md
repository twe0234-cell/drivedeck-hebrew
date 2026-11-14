# DriveDeck Hebrew – handoff

**Reply to the owner in Hebrew.** Rooted Topway TS18 (UIS8581A, Android 10), Seat Leon 5F 1.2 TSI (CYVB). No adb:
diagnose from the remote log (zemer-drive, branch `drivedeck-logs`).

## State (he-7, voice 4)
* DriveDeck runs as `android.uid.system` (signed with the AOSP platform test key, `build/keys`).
* Hooks in the smali tree (`build/drivedeck-apktool-src.tgz`, already applied):
  `MainActivity.c()/dispatchKeyEvent/onNewIntent/onResume` and `LauncherAccessibilityService.onKeyEvent` →
  `HebrewVoiceBridge`; `c.c9.i` (update fetcher) → `HeRemote.guardOfficialUpdate` (official channels off);
  `RootBootEnforcer.initAndEnforce` → `HeRemote.start`; `VoiceBootReceiver` takes `ru.drivedeck.he.*` commands.
* ZemerDrive on the home screen (`HeMedia`): `c.V4.a/d` recognise ZemerDrive's TOGGLE_LIKE (Hebrew names), `c.P3.onTouchEvent`
  liked-heart tap sends `DRIVE_FOLLOW_ARTIST`; voice LIKE/FOLLOW_ARTIST go through `ru.drivedeck.he.*` → active ZemerDrive session.
  ZemerDrive's DRIVE_FOLLOW_ARTIST is committed on zemer-drive `ccr-a8a92c49-4lnpyb`, queued for the owner's next batch build.
* Voice app: `Parser` (pure Java, canonical spelling, JVM-testable) → `Commands` (actions, logcat tag `HebrewVoice`, history).
  "תנגן את X" → broadcast `ru.drivedeck.he.PLAY` (extra `query`) → `HeMedia.playSearch` → ZemerDrive `playFromMediaId("search/X/voice")`.
* Launcher guard (`HeHome`): 2 min after boot/wake, a foreign HOME app (or a `dofun` package) in front is replaced by
  DriveDeck (a11y hook in `LauncherAccessibilityService.onAccessibilityEvent`); the log shows `foreground after wake:`.
* Voice app `il.carvoice.he` is signed with a private key kept in the PRIVATE repo zemer-drive
  (branch `ccr-a8a92c49-4lnpyb`, `drivedeck/keys/`), not here.
* Seen in the unit's log (14/11): OTA install to he-6 and the hourly log upload work. Voice commands, the launcher guard and
  ZemerDrive like/follow/play are not confirmed on the unit yet: look for `HebrewVoice` and `DriveDeckHe` lines.

## Next
1. Read `drivedeck-logs` once the owner has a token: check update installs, OBD auto-connect, steering-wheel key.
2. If `pm install` as system fails on the unit, the log shows it; root is the fallback.
