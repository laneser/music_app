#!/usr/bin/env bash
set -e
AVD=ci_avd; IMG="system-images;android-34;google_apis;x86_64"
avdmanager list avd 2>/dev/null | grep -q "$AVD" || echo "no" | avdmanager create avd -n "$AVD" -k "$IMG" --device pixel_6 >/dev/null
emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot >/tmp/emu.log 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb install -r /workspace/app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell pm grant com.cellocoach android.permission.RECORD_AUDIO || true
dump(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1; }
center(){ grep -o "resource-id=\"$1\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" /tmp/u.xml | head -1 | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
tapid(){ dump; local c; c=$(center "$1"); [ -n "$c" ] && adb shell input tap $c >/dev/null 2>&1; sleep 2; }
shot(){ adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "/workspace/$1" >/dev/null 2>&1; }
adb shell am force-stop com.cellocoach; adb shell monkey -p com.cellocoach -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
tapid home_score_option_twinkle.mxl
tapid home_start
tapid tuning_skip
sleep 5   # let the 4-beat count-in finish so there's no dim overlay
shot tw_treble.png
adb emu kill >/dev/null 2>&1 || true
echo DONE
