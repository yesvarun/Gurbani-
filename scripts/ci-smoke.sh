#!/usr/bin/env bash
# Runs inside the CI emulator: installs the APK, starts it in self-test mode and
# checks that (1) it does not crash, (2) the bundled scripture loads, (3) the
# built-in speech engine transcribes a test clip.
set -uo pipefail
APK=$(ls build/outputs/apk/debug/*.apk | head -1)
adb install -r "$APK"
adb shell pm grant com.gurbani.app android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n com.gurbani.app/.MainActivity --ez selftest true
sleep 80
adb logcat -d > /tmp/logcat.txt
echo "===== self-test lines"
grep -E "GurbaniSelfTest|GurbaniAsr" /tmp/logcat.txt | tail -40
echo "===== crashes"
grep -E "FATAL EXCEPTION|AndroidRuntime: |UnsatisfiedLink|chromium.*(Uncaught|ERROR)" /tmp/logcat.txt | grep -v "GmsCore\|com.google" | tail -40
echo "===== process"
PID=$(adb shell pidof com.gurbani.app || true); echo "pid=$PID"
ok=1
[ -n "$PID" ] || { echo "FAIL: app is not running"; ok=0; }
grep -q "GurbaniSelfTest: ASR_OK" /tmp/logcat.txt || { echo "FAIL: speech engine did not transcribe"; ok=0; }
grep -q "GurbaniSelfTest: PAGE.*offline=true" /tmp/logcat.txt || { echo "FAIL: bundled scripture did not load"; ok=0; }
[ $ok = 1 ] && echo "SMOKE PASSED" || exit 1
