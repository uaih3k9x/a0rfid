#!/bin/bash
# 轻量 APK 构建：javac + d8 + aapt2 + apksigner（无需 Android Studio / Gradle）
set -e
cd "$(dirname "$0")"
SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex

echo "[1/6] javac"
$JAVA_HOME/bin/javac --release 11 -nowarn -cp $JAR -d $OUT/classes $(find app/src -name "*.java")

echo "[2/6] d8 (dex)"
$BT/d8 --release --lib $JAR --min-api 24 --output $OUT/dex \
  $(find $OUT/classes -name "*.class")

echo "[3/6] aapt2 资源"
$BT/aapt2 compile --dir app/res -o $OUT/res.zip

echo "[4/6] aapt2 链接"
$BT/aapt2 link -o $OUT/base.apk -I $JAR \
  --manifest AndroidManifest.xml --version-code 1 --version-name 1.0 \
  $OUT/res.zip --auto-add-overlay

echo "[5/6] 打包 dex"
cd $OUT && zip -q base.apk dex/classes.dex && cd ..

echo "[6/6] 对齐 + 签名"
$BT/zipalign -f 4 $OUT/base.apk $OUT/aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias rfid -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=RFID Helper,O=dev,C=CN" >/dev/null 2>&1
fi
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "RFID助手.apk" $OUT/aligned.apk
$BT/apksigner verify "RFID助手.apk" && echo "✔ 构建完成: $(pwd)/RFID助手.apk"
