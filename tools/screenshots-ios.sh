#!/usr/bin/env bash
# Captures App Store-style screenshots of the iPhone app in demo mode on the simulator.
# Usage: tools/screenshots-ios.sh <path to Pol.app> <output dir>
set -euo pipefail
APP="$1"; OUT="$2"; mkdir -p "$OUT"
BUNDLE=studio.virgule.pol.ios
UDID=$("$(dirname "$0")/ios-sim.sh")
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time 9:41 --dataNetwork wifi --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl install "$UDID" "$APP"

shoot() { # lang locale appearance screen
  xcrun simctl ui "$UDID" appearance "$3"
  xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE" -AppleLanguages "($1)" -AppleLocale "$2" -polScreenshot "$4" >/dev/null
  sleep 6
  xcrun simctl io "$UDID" screenshot "$OUT/ios-$1-$3-$4.png" >/dev/null
  echo "captured ios-$1-$3-$4"
}
for s in pair calls contacts keypad messages thread incall gateway search; do shoot fa fa_IR light "$s"; done
for s in pair calls messages thread incall gateway; do shoot en en_US light "$s"; done
for s in calls thread incall; do shoot fa fa_IR dark "$s"; done
