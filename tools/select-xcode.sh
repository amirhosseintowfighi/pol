#!/usr/bin/env bash
# CI helper: switches to the newest stable Xcode installed on the runner (no betas or release candidates),
# so the app is built with the current iOS SDK and gets the current system design (Liquid Glass on iOS 26+).
set -euo pipefail
x=$(ls -d /Applications/Xcode_*.app 2>/dev/null | grep -viE 'beta|_rc|release.candidate' | sort -V | tail -1)
if [ -n "$x" ]; then sudo xcode-select -s "$x/Contents/Developer"; fi
xcodebuild -version
