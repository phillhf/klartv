#!/usr/bin/env bash
# Installiert App und Tests im laufenden Emulator und sammelt Ergebnis, Protokoll und Bildschirmfotos ein.
set -u
PKG=io.github.phillhf.iptv.debug
PKG_CLASS=io.github.phillhf.iptv
mkdir -p emulator-out
adb install -r -g app/build/outputs/apk/mobile/debug/app-mobile-x86_64-debug.apk || exit 1
adb install -r -g "$(find app/build/outputs/apk/androidTest -name '*.apk' | head -1)" || exit 1
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow || true
# Hinweise des Systems würden die Bildschirmfotos verdecken
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global hide_error_dialogs 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# Nach dem Start hat der Emulator viel zu tun; hängt sich dabei der Startbildschirm auf, verdeckt sein Dialog alles
sleep 45
adb shell am force-stop com.google.android.apps.nexuslauncher
sleep 10
adb logcat -c
# Durchgang im Fernsehformat, nur mit Fernbedienungstasten
adb shell wm size 1920x1080
adb shell wm density 320
sleep 3
timeout 600 adb shell am instrument -w -r -e class $PKG_CLASS.TvTest $PKG.test/androidx.test.runner.AndroidJUnitRunner > emulator-out/result_tv.txt
adb shell wm size reset
adb shell wm density reset
sleep 3
if git log -1 --format=%s | grep -q '\[nur TV\]'; then
  adb logcat -d > emulator-out/logcat.txt
  adb pull /sdcard/Android/data/$PKG/files/shots emulator-out/ || true
  grep -E "INSTRUMENTATION_STATUS: (test|stack)|^(OK|FAILURES|Tests run)" emulator-out/result_tv.txt
  grep TVTEST emulator-out/logcat.txt
  exit 0
fi
# Durchgang im Handyformat
timeout 1200 adb shell am instrument -w -r -e class $PKG_CLASS.DeviceTest $PKG.test/androidx.test.runner.AndroidJUnitRunner > emulator-out/result.txt
adb logcat -d > emulator-out/logcat.txt
adb pull /sdcard/Android/data/$PKG/files/shots emulator-out/ || true
grep -E "INSTRUMENTATION_STATUS: (test|stack)|INSTRUMENTATION_STATUS_CODE: -[12]|^(OK|FAILURES|Tests run)" emulator-out/result.txt emulator-out/result_tv.txt | tail -80
ok=0
grep -q "^OK (" emulator-out/result.txt || ok=1
grep -q "^OK (" emulator-out/result_tv.txt || ok=1

# Die verkleinerte Fassung muss wenigstens starten: R8 kann Klassen entfernen, die nur zur Laufzeit gebraucht werden
REL=io.github.phillhf.iptv
adb install -r -g app/build/outputs/apk/mobile/release/app-mobile-x86_64-release.apk || ok=1
adb logcat -c
adb shell am start -W -n $REL/.MainActivity
sleep 8
adb exec-out screencap -p > emulator-out/release_start.png
adb logcat -d > emulator-out/logcat_release.txt
adb shell pidof $REL || { echo "Release-Fassung läuft nicht"; ok=1; }
grep -E "FATAL EXCEPTION" emulator-out/logcat_release.txt && ok=1
exit $ok
