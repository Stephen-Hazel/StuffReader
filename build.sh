#!/bin/bash
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk
BT=$(ls -d $SDK/build-tools/* | tail -1)
JAR=$SDK/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I $JAR -F build/unsigned.apk
javac --release 8 -nowarn -cp $JAR -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v 'warning: \[options\]' || true
$BT/dx --dex --min-sdk-version=21 --output=build/dex/classes.dex build/classes
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Stuff Reader" 2>/dev/null
apksigner sign --ks debug.keystore --ks-pass pass:android --out StuffReader.apk build/aligned.apk
apksigner verify --print-certs StuffReader.apk | head -1
ls -la StuffReader.apk
