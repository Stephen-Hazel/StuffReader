#!/bin/bash
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk
BT=$(ls -d $SDK/build-tools/* | tail -1)
JAR=$SDK/platforms/android-23/android.jar

# Third-party pieces for Piper voices (downloaded once into third_party/, not committed):
#  - sherpa-onnx 1.13.0 + onnxruntime native libs for arm64 (from the @siteed/sherpa-onnx.rn npm package;
#    1.13 is the last sherpa-onnx with the built-in espeak-ng phonemizer that Piper voices need)
#  - Apache commons-compress, to unpack the .tar.bz2 voice downloads on the phone
TP=third_party
CC=$TP/commons-compress-1.18.jar
if [ ! -f $CC ]; then
  mkdir -p $TP
  curl -fsSL -o $CC https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.18/commons-compress-1.18.jar
fi
if [ ! -f $TP/lib/arm64-v8a/libsherpa-onnx-jni.so ]; then
  mkdir -p $TP/npm $TP/lib/arm64-v8a
  curl -fsSL -o $TP/npm/sherpa.tgz https://registry.npmjs.org/@siteed/sherpa-onnx.rn/-/sherpa-onnx.rn-1.3.1.tgz
  tar xzf $TP/npm/sherpa.tgz -C $TP/npm 'package/android/src/main/jniLibs/arm64-v8a'
  cp $TP/npm/package/android/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so $TP/npm/package/android/src/main/jniLibs/arm64-v8a/libonnxruntime.so $TP/lib/arm64-v8a/
  rm -rf $TP/npm
fi

rm -rf build && mkdir -p build/gen build/classes build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I $JAR -F build/unsigned.apk
javac --release 8 -nowarn -cp $JAR:$CC -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v 'warning: \[options\]' || true
$BT/dx --dex --min-sdk-version=21 --output=build/dex/classes.dex build/classes $CC
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)
(cd $TP && aapt add ../build/unsigned.apk lib/arm64-v8a/libsherpa-onnx-jni.so lib/arm64-v8a/libonnxruntime.so >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Stuff Reader" 2>/dev/null
apksigner sign --ks debug.keystore --ks-pass pass:android --out StuffReader.apk build/aligned.apk
apksigner verify --print-certs StuffReader.apk | head -1
ls -la StuffReader.apk
