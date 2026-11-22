#!/usr/bin/env bash
# Installiert App und Tests im laufenden Emulator und sammelt Ergebnis, Protokoll und Bildschirmfotos ein.
set -u
PKG=io.github.phillhf.iptv.debug
mkdir -p emulator-out
adb install -r -g app/build/outputs/apk/mobile/debug/app-mobile-x86_64-debug.apk || exit 1
adb install -r -g "$(find app/build/outputs/apk/androidTest -name '*.apk' | head -1)" || exit 1
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow || true
adb logcat -c
timeout 1500 adb shell am instrument -w -r $PKG.test/androidx.test.runner.AndroidJUnitRunner > emulator-out/result.txt
adb logcat -d > emulator-out/logcat.txt
adb pull /sdcard/Android/data/$PKG/files/shots emulator-out/ || true
grep -E "INSTRUMENTATION_STATUS: (test|stack)|INSTRUMENTATION_STATUS_CODE: -[12]|^(OK|FAILURES|Tests run)" emulator-out/result.txt | tail -60
grep -q "^OK (" emulator-out/result.txt
