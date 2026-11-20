#!/bin/sh
# Build the NFC Bridge APK with the bare SDK tools (no Gradle, no downloads).
set -eu
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar

rm -rf build
mkdir -p build/classes
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$JAR" --debug-mode \
    --min-sdk-version 26 --target-sdk-version 35 -o build/base.apk
javac --release 11 -classpath "$JAR" -encoding UTF-8 \
    -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out build/nfc-bridge.apk build/aligned.apk
"$BT/apksigner" verify build/nfc-bridge.apk
echo "built $(pwd)/build/nfc-bridge.apk"
