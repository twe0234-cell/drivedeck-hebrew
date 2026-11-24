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

## he-11: drive-check data reaches the remote log
- he-10 logs from 11-18 had no `obd ext` lines: DriveDeck runs as uid 1000, logcat's chatty filter expires most of its
  lines ("uid=1000(system) ru.drivedeck.launcher expire N lines"), and the ring buffer holds only minutes anyway.
- `HeLog`: HeObd/HeObdExt/HeObdScreen lines also go to `files/he-obd.log` (cap 1.5 MB). `HeRemote.uploadLog` takes it
  (rename to `.sending`, deleted only after a good upload) as section `== DriveDeck OBD log ==`.
- Header now has `drive check:` state and `== last drive check report (...) ==` (prefs `obd_report`, `obd_report_time`).
- `stopCheck` uploads at once; during a check an `obd ext:` sample line is logged every 2 s.

## he-12 · voice 6
- 11-19 logs: two drive checks started (01:21, 01:43) were lost: DriveDeck's process is restarted at every ignition
  (Topway quick boot, new pid, `ACC_ON` broadcast), and the check lived only in memory.
- `HeObd`: a running check is kept in prefs `drivedeck_he/check_start` and `files/he-check.csv` (one `Sample.csv()`
  line per sample, flushed every 5). `init` → `resumeCheck` reloads it. The limit is 45 min of driving (sample gaps
  capped at 5 s) or 4 h wall time. `obd minute` shows `check=N samples/M min`.
- `BACKGROUND_EXTRAS` now include 0106/0107 (fuel trims were always "-" outside the check screen).
- Voice app 6: launcher activities `CarCheckActivity` ("בדיקת רכב", sends `ru.drivedeck.he.OBD`) and `EqActivity`
  ("אקולייזר", `com.tw.eq/.EQChoiceActivity`, then the launch intent), with vector icons `ic_car_check` / `ic_eq`.
- `RootBootEnforcer.replaceEqShortcut`: disables `ru.drivedeck.eqshortcut` (an old shortcut app with a plain icon,
  source not here) once voice ≥ 6 is installed.

## he-13: why every manufacturer request got NO DATA
- 11-19 checks (02:29, 15:57): every 22/19 to 7E0, 7E1 and 713 → `NO DATA` after ~1.25 s, never a 7F; mode 01 on 7DF
  fine. Each miss stalled DriveDeck's loop, so a check got one sample per ~80 s.
- `HeObdExt` backoff: a unit with 6 misses in a row is asked again only every 3 min (`backoff=` in `obd ext stat`).
- `HeObdUds.probeBatch` (first start batch): `ATSH7E0 ATCRA7E8 0100 22F190 ATCAF0 0322F19000000000 ATCAF1 ATAR
  ATSH7DF 22F190`. Tells apart: physical addressing at all (0100 on 7E0), UDS on 7E0, a clone sending short
  frames (padded raw frame), functional UDS. `obd ext rx CMD: raw` lines (first 80 per check) show the answers.

## he-15 · voice 8 (2026-11-23)
- Owner: steering-wheel voice key and phone key stopped bringing up their screens; on a call both the Topway and the
  DriveDeck call windows showed. Logs are too thin to see the key path (no `HebrewVoice` / key lines survive chatty),
  but HeHome was the one piece that pushes other apps away: it counted every `dofun` package as a launcher, was
  re-armed by `com.unisound.intent.action.DO_WAKEUP` (the voice wake-up, added as a "car wake" in he-7) and stayed armed
  for 2 min regardless of what the driver did. 11-14..11-22 logs: 30 replacements, all of `com.dofun.carsetting`.
- `HeHome` now: armed only by process start / boot / ACC_ON / screen on after >=30 s off; replaces only HOME apps
  (+ `com.dofun.carsetting`); 60 s window, ends 8 s after a replacement, and at once on any hardware key
  (`HebrewVoiceBridge.onKey` -> `HeHome.onKey`), on any non-launcher foreground app except passive ones (keyboard,
  Waze in DriveDeck's display, reverse camera, ZemerDrive's own resume), or on a call (`AudioManager.getMode()!=0` or
  `c.Z7.Q.a.e != 0`, DriveDeck's HFP state). `KeepAliveService` no longer listens to DO_WAKEUP.
- `HeLog` (private file, uploaded with the log) now gets `key <code> scan=… fg=…`, `foreground: pkg / class`,
  `home guard: …` lines. Header: `assistant_on`, `voice_interaction`, DriveDeck `calls_ui/voice_button/own_voice`,
  `home guard:` state. Next log answers what the wheel voice/phone keys actually send.
- `RootBootEnforcer.initDefaults` (`defaults_v3`, once): DriveDeck pref `drive_deck/voice_calls_enabled=false`
  (its own call overlay, `MainActivity.w` / `c.Z7`). `grantVoiceMic`: `pm grant il.carvoice.he RECORD_AUDIO` (system
  uid, su fallback) at process start when voice >= 8.
- Voice 8: `VoiceActivity` uses `SpeechRecognizer` in place (bottom panel), retries with `PREFER_OFFLINE` after a
  network error, Hebrew TTS (`voice/speak`, default on), `Smart` (pure Java, `test/VoiceTest.java`): word repair
  against Parser's command words, guesses (play / navigate / open) asked as a yes/no question. Fallback to Google's
  recogniser screen without the mic permission or a recognition service.
- Not done: an online LLM fallback for free-form requests. The voice app is plain Java against stubs (no Gradle), so
  the Anthropic Java SDK does not fit as is; needs a decision (and an API key) from the owner.

## he-16 · voice 9 (2026-11-24)
- Owner (after he-15): the steering-wheel phone and voice keys still open nothing; calls should go to the unit's phone
  app (Topway com.tw.bt) only, with no DriveDeck dialler, and Topway's call screen must come to the front over DriveDeck.
- Findings (11-23/24 logs + decompiled com.tw.service / com.tw.bt / DriveDeck 0.6.7):
  - The wheel phone and voice keys never reach Android as KeyEvents: the MCU sends them to com.tw.service over TWUtil
    (event 0x201, key 0x18 phone, 0x5a voice); the only `key` line in HeLog is 127 (pause) at ignition off. No root,
    Magisk, HOME-app or `assistant_on` check on that path.
  - com.tw.service ignores both keys while it believes a call is on (`Fa()`): MCU state, CarPlay/zlink flag, or the
    persist properties `persist.dialer.call.state` / `persist.hicar.call.state`, which survive a reboot.
  - Voice key: `/data/tw/speech_name` (line 1 package, line 2 activity, written by CarSetting -> Voice). Google's package
    is special-cased to an implicit VOICE_COMMAND, which DriveDeck MainActivity and il.carvoice.he also answer.
  - he-15's `voice_calls_enabled=false` only gates DriveDeck's voice dialling / contacts: its call overlay
    (FrameLayout, type 2038, `c.d8` = jadx C0039d8) still came up 0.05-0.6 s after every Topway call window
    (`com.tw.bt / LinearLayout`, CallingView, type 2002): c.Z7 polls the HFP client every second.
- Changes:
  - `build/patches/he16-phone.py` (applied to the tgz; idempotent, edits the archive in memory): `c.Z7.g()` returns at
    once (no HFP/PBAP proxies, the same path as its own `r` flag), `c.Z7.e(KeyEvent)`, `c.d8.k()`, `c.C8.d()` return
    false, `MainActivity.P()` (icon, PhoneEntry alias, home tile, PHONE action) -> `HeCall.openPhone` (Topway
    `com.tw.bt/.ATBluetoothActivity`, NEW_TASK), DriveDeck's own dialler only if that fails.
  - `HeCall`: follows `android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED` (`CallBook`, pure); a new call
    disarms the home guard and, with DriveDeck in front, opens Topway's phone screen; `inCall` (audio mode or a
    Bluetooth call) replaces the c.Z7 check in `HeHome` and stops `VoiceBootReceiver` from launching DriveDeck. A
    call-state property that is not 0 with no call on is set back to 0 (setprop as system, su fallback) at process
    start, every car wake and 5 s after the last call ends; logged as `call state … was … now …`.
  - `HeVoiceKey` + `SpeechName` (pure): with voice >= 9, `/data/tw/speech_name` is written in place (keeps its label;
    su + restorecon if refused or missing) to `il.carvoice.he` / `il.carvoice.he.WheelKeyActivity`; checked at process
    start and every car wake (Topway's voice setting screen may write it again).
  - Voice 9: `WheelKeyActivity` (NoDisplay) opens Gemini explicitly (`setPackage(GSA)`: VOICE_COMMAND, then ASSIST,
    then VOICE_ASSIST) or the Hebrew assistant; setting "כפתור הקול בהגה" (pref `voice/wheel_target`, default gemini).
  - Log header: `call: dialer=… hicar=… bt_calls=… audio_mode=…`, `voice key: speech_name=… SWC_VOICE_MODE=…`
    (0: short press opens the assistant, 1: long press).
  - `test/CallTest.java` (CallBook, SpeechName). `build/build.sh` also runs on Windows Git Bash (WORK must be a
    case-sensitive folder: `fsutil file setCaseSensitiveInfo .work enable`; the tree has case-only name pairs).
- Check in the next logs: the header `call:` values (was a property stuck?), `voice key (…)` and `phone:` lines,
  `call <id> state …` lines during a call, and that no `ru.drivedeck.launcher / android.widget.FrameLayout` follows a
  `com.tw.bt` window any more.
