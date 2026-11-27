#!/usr/bin/env bash
# Captures screenshots of the Android client (demo mode) and gateway on a running emulator.
# Usage: tools/screenshots-android.sh <output dir>   (debug APKs must already be built)
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
CPKG=studio.virgule.pol.client.debug
GPKG=studio.virgule.pol.gateway.debug
adb install -r android/app/build/outputs/apk/client/debug/*.apk >/dev/null
adb install -r android/app/build/outputs/apk/gateway/debug/*.apk >/dev/null
# Clean status bar (System UI demo mode).
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e level 4 -e datatype lte >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

shoot() { # pkg activity lang mode screen name
  adb shell cmd locale set-app-locales "$1" --user 0 --locales "$3" >/dev/null
  adb shell cmd uimode night "$4" >/dev/null
  adb shell am force-stop "$1"
  adb shell am start -W -n "$1/$2" --es polScreenshot "$5" >/dev/null
  sleep 5
  adb exec-out screencap -p > "$OUT/android-$6.png"
  echo "captured android-$6"
}
CA=studio.virgule.pol.android.client.ClientActivity
GA=studio.virgule.pol.android.gateway.GatewayActivity
for s in pair calls keypad contacts search messages thread newchat incall gateway; do shoot $CPKG $CA fa no "$s" "client-fa-light-$s"; done
for s in calls keypad messages thread incall; do shoot $CPKG $CA en no "$s" "client-en-light-$s"; done
for s in calls thread; do shoot $CPKG $CA fa yes "$s" "client-fa-dark-$s"; done
shoot $GPKG $GA fa no main "gateway-fa-light-main"
shoot $GPKG $GA fa no pair "gateway-fa-light-pair"
shoot $GPKG $GA en no main "gateway-en-light-main"
