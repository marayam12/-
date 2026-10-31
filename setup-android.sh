#!/bin/bash
# Installs Java 17 + Android SDK + builds the app. Run from the project folder: bash setup-android.sh
set -e
PROJ="$PWD"

echo "[1/5] Installing Java 17..."
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk unzip wget
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

echo "[2/5] Downloading Android command line tools..."
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmd.zip
unzip -q -o cmd.zip
rm -rf latest
mv cmdline-tools latest
rm cmd.zip
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

echo "[3/5] Installing SDK, CMake and NDK (this takes a few minutes)..."
yes | sdkmanager --licenses > /dev/null || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "cmake;3.22.1" "ndk;26.1.10909125"

echo "[4/5] Saving settings..."
grep -q ANDROID_HOME ~/.bashrc || {
  echo "export JAVA_HOME=$JAVA_HOME" >> ~/.bashrc
  echo "export ANDROID_HOME=$ANDROID_HOME" >> ~/.bashrc
  echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
}
cd "$PROJ"
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew

echo "[5/5] Building the APK..."
./gradlew assembleDebug

echo
echo "DONE. Your APK is here:"
ls -lh app/build/outputs/apk/debug/*.apk
