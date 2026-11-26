#!/usr/bin/env bash
# Static privacy guard: fails if app code gains network access, telemetry SDKs or content logging.
# Run from the repository root. It is a tripwire, not proof: review dependencies and manifests too.
set -euo pipefail
fail=0
say() { echo "privacy-check: $*"; fail=1; }

APP_SRC="android/app/src ios/Pol core/src/main"
# 1. No networking APIs in app code (billing integrations must live in a separate, reviewed module).
if grep -rnE 'java\.net\.(URL|HttpURLConnection|Socket)|okhttp|retrofit|ktor|URLSession|NWConnection|WKWebView|android\.webkit|Firebase|Crashlytics|Sentry|Analytics|AdMob|AppsFlyer|Amplitude|Mixpanel' $APP_SRC; then
  say "network / telemetry API found in app code"
fi
# 2. Android manifests must not request network permissions (only tools:node="remove" lines are allowed).
if grep -rn 'android.permission.INTERNET\|ACCESS_NETWORK_STATE' android/app/src --include=AndroidManifest.xml | grep -v 'tools:node="remove"'; then
  say "network permission requested in a manifest"
fi
# 3. No location permission anywhere.
if grep -rn 'ACCESS_FINE_LOCATION\|ACCESS_COARSE_LOCATION\|ACCESS_BACKGROUND_LOCATION' android/app/src --include=AndroidManifest.xml | grep -v 'tools:node="remove"'; then
  say "location permission requested"
fi
# 4. Logging only through SafeLog (Android) and never with message bodies or numbers.
if grep -rnP 'android\.util\.Log\.|(?<!Safe)\bLog\.(d|i|v|w|e)\(' android/app/src | grep -v 'common/SafeLog.kt'; then
  say "direct android.util.Log use outside SafeLog"
fi
if grep -rnE 'SafeLog\.[dw]\([^)]*\$\{?(body|text|number|address|to|name|sas|key)' android/app/src; then
  say "SafeLog call interpolates user content"
fi
if grep -rnE '\bprint\(|NSLog\(|os_log\(|Logger\(' ios/Pol; then
  say "logging call in the iOS app"
fi
# 5. Backups disabled.
grep -q 'android:allowBackup="false"' android/app/src/main/AndroidManifest.xml || say "allowBackup is not false"
# 6. Only expected exported components: two launchers, the dialer (required of a default phone app), the
#    BIND_INCALL_SERVICE-guarded in-call service, the permission-guarded Shizuku provider and the client's
#    BIND_TELECOM_CONNECTION_SERVICE-guarded connection service.
exported=$(grep -rn 'android:exported="true"' android/app/src --include=AndroidManifest.xml | wc -l)
[ "$exported" -le 6 ] || say "unexpected exported components ($exported)"
grep -q 'android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE"' android/app/src/client/AndroidManifest.xml || say "connection service not guarded"
grep -q 'android:permission="android.permission.BIND_INCALL_SERVICE"' android/app/src/gateway/AndroidManifest.xml || say "in-call service not guarded"
grep -q 'android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"' android/app/src/gateway/AndroidManifest.xml || say "Shizuku provider is not permission-guarded"

[ $fail -eq 0 ] && echo "privacy-check: OK"
exit $fail
