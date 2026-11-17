# DriveDeck Hebrew – handoff

**Reply to the owner in Hebrew.** Rooted Topway TS18 (UIS8581A, Android 10), Seat Leon 5F 1.2 TSI (CYVB). No adb:
diagnose from the remote log (zemer-drive, branch `drivedeck-logs`).

## State (he-8, voice 5)
* he-8: OBD check (`HeObd`, `HeObdMath`, `HeObdScreen`). Smali hook in `c.T5.M(ZString)`: right after each `RX` Log.i
  (v10 = command, v9 = raw answer) → `HeObd.onRx`. Extra PIDs go through DriveDeck's own queue `T5.c2` (posted on `T5.c`),
  never written to the socket directly (one command in flight). Background: 0105/0142 every ~100 answers + alerts; while the
  screen is open or a check runs: one extra PID after each regular one. The screen is a Dialog over MainActivity (the
  manifest is binary): action `ru.drivedeck.he.OBD_SCREEN` via `HebrewVoiceBridge.onIntent`; broadcast `ru.drivedeck.he.OBD`.
* Log: `DriveDeckHe obd minute: speed obd=… gps=… obd/gps=…` once a minute; header line `obd speed: home=gps|obd factor=…`.
* OBD facts (audit 17/11): formulas correct; home speed = GPS unless `drive_deck/speed_obd`; OBD speed = 010D × `drivedeck_obd/speed_factor`
  (string); every PID ~1 Hz; car supports 01,03-07,0B-0F,11,13-15,1C,1F,21,23,2E,2F,30,31,33,3C,41-47,49,4A,4C,51,56 — no 0110
  (MAP engine, no MAF). 0111 reads 11-15 % closed, so the throttle check uses 014C against a stored per-car baseline.
  `VTI`/`STI` are harmless chip probes (the clone answers `?`). 0144 = FFFF in fuel cut is real.
* Test: `test/ObdTest.java` (plain JVM, decodes every RX line of the logs).

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

## he-9 (2026-11-17)
- `RootBootEnforcer.disableAndroidAuto`: disables `com.google.android.projection.gearhead` for user 0 on every process
  start (PackageManager as system uid, `su pm disable-user` fallback). Owner asked for it; it woke on every BT connect.
- Ignition blip, evidence from the 11-17 logs (ZemerDrive drive-1174 installed 14:14): ZemerDrive now resumes through
  the service with no root call (ignitions 14:19 and 15:59). After the long stop (15:59) DriveDeck's MainActivity was
  recreated ~6 s after ignition; it added a new virtual display `DRIVEDECK-TEXTURE-*` for the embedded Waze map, the
  system logged `Screen frozen for +137ms due to new-config` (16:00:02, ~9 s after ignition) and Waze was relaunched
  into it. Same sequence on 11-15 17:44, 11-16 16:30, 11-17 14:07. The short stop (14:19) kept the activity: no freeze.
  Not yet confirmed with the owner that this is the pause they see.

## he-10 (2026-11-17): extended drive check
- `HeObdUds` (pure, `test/UdsTest.java`): DIDs/formulas from the shipped profile, ISO-TP reassembly of ELM answers,
  UDS 19 02 codes, findings (wheel-speed deviation per wheel, ABS sensor dropouts, ABS 22 1821 TPMS record, misfire
  counters by gear, knock retard, per-cylinder correction, DQ200 rpm/speed spread per gear + clutch counters).
- `HeObdExt`: while the drive check runs, one batch per regular answer through `T5.c2` (`HeObd.sendBatch`), which
  appends `ATSH<T5.P>` so DriveDeck's header is restored. DriveDeck's `G(R5)` puts header+extras+command into c2 as
  one unit and `Z()` drains c2 first, so batches never split its request; `M()` attributes answers only when the
  command equals `a1.b`. ABS needs `ATCRA77D` (+ `ATFCSH713/ATFCSD300000/ATFCSM1` for long answers), restored with
  `ATFCSM0`/`ATAR`, as the profile's BCM/ACM. Adapter reports "ELM327 v1.5": FC commands may be refused (logged as
  `obd ext: adapter refused ...`); wheel speeds are single-frame and do not need FC.
- Remote log: `obd ext:` line every 10 s while checking, `obd ext stat:` at stop, the report as before.
