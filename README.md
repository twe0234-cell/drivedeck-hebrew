# DriveDeck Hebrew

A Hebrew build of DriveDeck 0.6.7 for a Topway TS18 (Seat Leon 5F 1.2 TSI), plus a Hebrew voice assistant.

| | |
|---|---|
| `release/` | The current APKs and `manifest.json` (published as GitHub release `he-<N>` by `.github/workflows/publish-release.yml`) |
| `src/drivedeck/java` | Java added to DriveDeck: Hebrew defaults and OBD for the Seat (`RootBootEnforcer`), voice routing (`HebrewVoiceBridge`), updates and remote log (`HeRemote`) |
| `src/voice` | The Hebrew voice assistant `il.carvoice.he` (Google speech recognition, he-IL) |
| `build/` | `build.sh`, the patched apktool tree of DriveDeck (`drivedeck-apktool-src.tgz`), the AOSP platform test key |

## Updates (no reinstalling)

DriveDeck he-4 and later check this repo's releases every 6 hours (and on demand). A release `he-N` with N above
the installed build is downloaded, checked (size + SHA-256 from `manifest.json`) and installed silently
(`pm install` as the system app, root as a fallback). The voice assistant updates the same way. A DriveDeck update
installs when OBD is idle, at the next car start before OBD connects, or at once from "בדוק עדכונים".

To publish: raise `HeRemote.BUILD` (and/or the voice `versionCode`), run `build/build.sh [voice.pk8 voice.pem]`,
write `release/NOTES.md`, commit `release/` and push. The workflow creates the release.

## Remote log

The unit's logcat (the system app sees every app) and DriveDeck crashes go to the **private** repo
`twe0234-cell/zemer-drive`, branch `drivedeck-logs`, `logs/<date>/<time>-<model>.log`: a few minutes after start,
then hourly when there is something new; a manual send ("שלח לוג") adds the full log. It needs a fine-grained token
with Contents read/write on zemer-drive: reused automatically from ZemerDrive (root), or entered in the voice app's
"עדכונים ולוג" screen, or saved as `Download/drivedeck-token.txt`. The token is never in the APK or this repo.

Read the logs: `git fetch origin drivedeck-logs` in zemer-drive.
