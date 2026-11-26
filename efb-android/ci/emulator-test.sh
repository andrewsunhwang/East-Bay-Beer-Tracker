#!/usr/bin/env bash
# Runs the instrumented smoke test on a booted emulator, collects screenshots, and prints small
# JPEG versions as base64 into the log (so they can be inspected without downloading artifacts).
set -u
cd "$(dirname "$0")/.."

APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb install -r "$APP" || exit 1
adb install -r "$TEST" || exit 1

# Put the emulator's GPS at Oakland (OAK).
adb emu geo fix -122.2208 37.7213 || true
sleep 2
adb emu geo fix -122.2208 37.7213 || true

adb logcat -c || true
out=$(adb shell am instrument -w -e class com.simpleefb.SmokeTest com.simpleefb.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
echo "$out" | tail -80

echo "----- smoke test log -----"
adb logcat -d -s SimpleEFB-Smoke:I | tail -60

mkdir -p build/screens
for f in $(adb shell run-as com.simpleefb ls files/screens 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as com.simpleefb cat "files/screens/$f" > "build/screens/$f"
done
ls -la build/screens

for f in build/screens/*.jpg; do
  [ -f "$f" ] || continue
  echo "=====BEGIN $(basename "$f")"
  base64 -w 160 "$f"
  echo "=====END $(basename "$f")"
done

if ! echo "$out" | grep -q "^OK ("; then
  echo "Instrumentation FAILED"
  adb logcat -d | grep -E "AndroidRuntime|FATAL|simpleefb|SimpleEFB|osmdroid" | tail -120
  exit 1
fi
echo "Smoke test passed"
