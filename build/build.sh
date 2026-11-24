#!/usr/bin/env bash
# Rebuilds DriveDeck Hebrew + the Hebrew voice app and writes release/ (APKs + manifest.json).
#
#   build/build.sh [voice-key.pk8 voice-cert.x509.pem]
#
# * DriveDeck build number = HeRemote.BUILD in src/drivedeck/java/ru/drivedeck/launcher/HeRemote.java
# * voice versionCode     = versionCode in src/voice/proj/AndroidManifest.xml (keep VoiceActivity.VERSION equal)
# Raise the number(s) first, then run this, commit release/ and push: the workflow publishes release he-<N>
# and every unit updates itself (DriveDeck checks GitHub every few hours).
#
# Without a voice key only DriveDeck is rebuilt (the voice APK in release/ is kept as is). The voice key is
# private and not in this public repo.
#
# Needs: JDK 17+, python3, curl, zip/unzip.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$ROOT/.work}"
TOOLS="${TOOLS:-$WORK/tools}"
mkdir -p "$WORK" "$TOOLS"

fetch() { [ -s "$TOOLS/$1" ] || curl -fsSL -o "$TOOLS/$1" "$2"; }
fetch apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
fetch r8.jar      https://dl.google.com/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
fetch apksig.jar  https://dl.google.com/android/maven2/com/android/tools/build/apksig/8.7.3/apksig-8.7.3.jar

# Windows (Git Bash): java takes ';' between classpath entries, there is no zip, grep -P needs a UTF-8 locale, and
# WORK must be a case-sensitive folder (fsutil file setCaseSensitiveInfo <dir> enable): the smali tree has
# file names that differ only in case.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) cpath() { echo "$(cygpath -w "$1");$(cygpath -w "$2")"; } ;;
  *) cpath() { echo "$1:$2"; } ;;
esac
export LC_ALL="${LC_ALL:-C.UTF-8}"
zipdex() { if command -v zip >/dev/null; then zip -q "$1" classes.dex; else jar cfM "$1" classes.dex; fi; }

mkdir -p "$WORK/signer"
javac -d "$WORK/signer" -cp "$TOOLS/apksig.jar" "$ROOT/build/Sign.java"
sign() { java -cp "$(cpath "$TOOLS/apksig.jar" "$WORK/signer")" Sign "$1" "$2" "$3" "$4"; }

# java sources -> smali (against compile-only stubs; the device provides the real classes)
java2smali() { # <stubs dir> <src dir> <out smali dir> [extra classpath jar]
  local o="$WORK/j.$RANDOM"; mkdir -p "$o/stub" "$o/cls" "$o/dex"
  javac -nowarn -Xlint:-options --release 8 -d "$o/stub" $(find "$1" -name '*.java')
  javac -nowarn -encoding UTF-8 -Xlint:-options --release 8 -cp "$o/stub" -d "$o/cls" $(find "$2" -name '*.java')
  (cd "$o/stub" && jar cf ../stubs.jar .)
  java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 26 --classpath "$o/stubs.jar" --output "$o/dex" $(find "$o/cls" -name '*.class')
  (cd "$o/dex" && zipdex ../fake.zip)
  java -jar "$TOOLS/apktool.jar" d -r -f -o "$o/fake" "$o/fake.zip" >/dev/null
  mkdir -p "$3"; cp -r "$o/fake/smali/." "$3/"
}

BUILD=$(grep -oP 'public static final int BUILD = \K[0-9]+' "$ROOT/src/drivedeck/java/ru/drivedeck/launcher/HeRemote.java")
echo "== DriveDeck he-$BUILD"
rm -rf "$WORK/build_src"; tar -xzf "$ROOT/build/drivedeck-apktool-src.tgz" -C "$WORK"
java2smali "$ROOT/src/drivedeck/stubs" "$ROOT/src/drivedeck/java" "$WORK/build_src/smali"
java -jar "$TOOLS/apktool.jar" b "$WORK/build_src" -o "$WORK/dd-unsigned.apk"
rm -f "$ROOT"/release/DriveDeck-*.apk
DD="DriveDeck-0.6.7-Hebrew-he$BUILD.apk"
sign "$ROOT/build/keys/platform.pk8" "$ROOT/build/keys/platform.x509.pem" "$WORK/dd-unsigned.apk" "$ROOT/release/$DD"

VC=$(grep -oP 'android:versionCode="\K[0-9]+' "$ROOT/src/voice/proj/AndroidManifest.xml")
VOICE="HebrewVoice-Car-$VC.apk"
if [ $# -ge 2 ]; then
  echo "== voice $VC"
  rm -rf "$WORK/voice"; mkdir -p "$WORK/voice"; cp -r "$ROOT/src/voice/proj/." "$WORK/voice/"
  java2smali "$ROOT/src/voice/stubs" "$ROOT/src/voice/java" "$WORK/voice/smali"
  java -jar "$TOOLS/apktool.jar" b "$WORK/voice" -o "$WORK/voice-unsigned.apk"
  rm -f "$ROOT"/release/HebrewVoice-*.apk
  sign "$1" "$2" "$WORK/voice-unsigned.apk" "$ROOT/release/$VOICE"
else
  VOICE=$(cd "$ROOT/release" && ls HebrewVoice-*.apk | head -1)
  VC=$(echo "$VOICE" | grep -oP 'HebrewVoice-Car-\K[0-9]+')
  echo "== voice: no key given, keeping $VOICE"
fi

python3 - "$ROOT/release" "$BUILD" "$DD" "$VC" "$VOICE" <<'EOF'
import hashlib, json, os, sys
rel, build, dd, vc, voice = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5]
def ent(f):
    b = open(os.path.join(rel, f), "rb").read(); return len(b), hashlib.sha256(b).hexdigest()
s1, h1 = ent(dd); s2, h2 = ent(voice)
m = {"build": build, "apps": [
    {"package": "ru.drivedeck.launcher", "build": build, "file": dd, "size": s1, "sha256": h1},
    {"package": "il.carvoice.he", "versionCode": vc, "file": voice, "size": s2, "sha256": h2}]}
open(os.path.join(rel, "manifest.json"), "w").write(json.dumps(m, indent=2) + "\n")
print(json.dumps(m, indent=2))
EOF
echo "Now: write release/NOTES.md, commit release/ and push. The workflow publishes he-$BUILD."
