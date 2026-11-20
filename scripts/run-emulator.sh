#!/usr/bin/env bash
# Boot AVD 'mp', install the debug APK, launch the app.
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/opt/android-sdk}
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH
export JAVA_HOME=${JAVA_HOME:-$HOME/opt/jdk21}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

# hw.keyboard=yes (set in AVD config by make-avd.sh) forwards laptop keys to Android.
emulator -avd mp -no-snapshot-save -no-boot-anim -gpu host >/tmp/mp-emu.log 2>&1 &
EMUPID=$!
echo "emulator pid $EMUPID (log /tmp/mp-emu.log)"

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
# Guest DNS lags boot; launching before it resolves shows "Webpage not available"
# (ERR_NAME_NOT_RESOLVED) which the WebView keeps until app restart.
until adb shell ping -c1 -W2 meningspunktet.dk >/dev/null 2>&1; do sleep 2; done
echo "device booted, dns resolves"

adb install -r "$APK"
adb shell monkey -p dk.meningspunktet.unofficial.app -c android.intent.category.LAUNCHER 1 >/dev/null
echo "launched dk.meningspunktet.unofficial.app"
