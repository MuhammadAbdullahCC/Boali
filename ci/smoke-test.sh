#!/usr/bin/env bash
set -euo pipefail
APK="built-apk/app-debug.apk"
adb install -r "$APK"
adb logcat -c

echo "[Boali] Launching Boa screen"
adb shell am start -W -n com.lynboa.walk/.MainActivity
sleep 8
if ! adb shell pidof com.lynboa.walk > /dev/null; then
  adb logcat -d -t 500
  echo "[Boali] FAIL: Process exited during first launch"
  exit 1
fi
if adb logcat -d -s AndroidRuntime:E | grep -A 20 'FATAL EXCEPTION'; then
  echo "[Boali] FAIL: Android fatal exception on first launch"
  exit 1
fi
echo "[Boali] PASS: Boa screen process alive after cold launch"

# Tap Lyn's map tab on the emulator's 320x640 display.
adb shell input tap 243 115
sleep 8
if ! adb shell pidof com.lynboa.walk > /dev/null; then
  adb logcat -d -t 500
  echo "[Boali] FAIL: Process exited after opening route tab"
  exit 1
fi
if adb logcat -d -s AndroidRuntime:E | grep -A 20 'FATAL EXCEPTION'; then
  echo "[Boali] FAIL: Fatal exception after route tab"
  exit 1
fi
adb exec-out screencap -p > /tmp/boali-emulator-screen.png || true
echo "[Boali] PASS: App survives both Boa launch and Lyn navigation"
