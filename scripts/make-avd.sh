#!/usr/bin/env bash
# Create AVD 'mp' (if missing) and boot it headless-capable.
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/opt/android-sdk}
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH
export JAVA_HOME=${JAVA_HOME:-$HOME/opt/jdk21}

if [ ! -d "$HOME/.android/avd/mp.avd" ]; then
  avdmanager create avd -n mp -k "system-images;android-36;google_apis;x86_64" -d pixel_7 --force <<< "no"
fi
# avdmanager defaults hw.keyboard=no; laptop keyboard passthrough needs yes.
sed -i 's/^hw\.keyboard = no/hw.keyboard = yes/' "$HOME/.android/avd/mp.avd/config.ini"
echo "AVD ready: $(avdmanager list avd | grep -A1 'Name: mp' | tr '\n' ' ')"
