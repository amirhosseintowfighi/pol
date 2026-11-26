# Building and verifying

## Layout

| Path | What |
|---|---|
| `core/` | Kotlin/JVM library: protocol, crypto handshake, validation, client model, demo gateway, billing model. No Android or network dependencies. |
| `android/` | One Android app with two flavors: `gateway` (holds the SIMs) and `client` (Android 12+). Uses `core` via an included build. |
| `ios/` | SwiftUI iPhone client (iOS 18+), generated with XcodeGen from `project.yml`. |
| `protocol/` | Wire specification and test vectors shared by all implementations. |
| `tools/` | Test-vector generator, string generators, privacy check, SDK-free Android type-check. |

## Commands

```bash
# Shared core: unit tests (JDK 17+)
cd core && gradle test
```

```bash
# Android (needs the Android SDK, platform 36)
cd android && gradle :app:assembleGatewayRelease :app:assembleClientRelease :app:lintGatewayRelease
```

```bash
# iPhone (macOS with Xcode and XcodeGen)
cd ios && xcodegen && xcodebuild -scheme Pol -destination 'platform=iOS Simulator,name=iPhone 16' test
```

```bash
# Static privacy guard
./tools/check-privacy.sh
```

```bash
# Android type-check without the SDK (framework + Compose APIs)
cd tools/android-typecheck && gradle compileKotlin
```

After editing strings, regenerate them with `python3 tools/android_strings.py` and `python3 tools/ios_strings.py`.
After a protocol change, regenerate the vectors with `python3 tools/vectors.py` and update the tests.

## Dependencies and update practice

| Dependency | Where | Why |
|---|---|---|
| kotlinx-serialization-json 1.8.1 | core, Android | JSON control messages |
| Kotlin stdlib 2.1.21, kotlinx-coroutines 1.10.2 | core tests, Android | Language runtime, StateFlow |
| AndroidX Compose BOM 2025.05.01, Material 3, activity-compose, lifecycle, core-ktx | Android | UI |
| JUnit 5 | core tests | Tests |
| Shizuku api + provider 13.1.5 (Apache-2.0, RikkaApps) | Android gateway only | Starting the call-audio helper as the shell user, over Binder |
| Concentus 1.0.2 (BSD-3-Clause, pure-Java Opus) | core (gateway and Android client) | Opus encode/decode for HD call audio |
| libopus 1.5.2 (BSD-3-Clause, Xiph.Org), vendored unmodified in `ios/COpus` | iOS | Opus encode/decode for HD call audio (floating point, no assembly, no DNN) |
| — | iOS | Apple frameworks only (SwiftUI, CoreBluetooth, CryptoKit, Security, UserNotifications). No third-party packages. |

Practice: pin exact versions (no `+` or dynamic ranges). Review release notes and diffs before updating, and run
the full test suite and `check-privacy.sh` after each update. Watch GitHub security advisories for these
artifacts. Before shipping, add Gradle dependency verification (`gradle --write-verification-metadata sha256`)
so artifacts are checked by hash. Don't add networking, analytics, crash-reporting or ads libraries.

## Opus interoperability check

Concentus (gateway and Android client) and libopus (iPhone) were checked against each other here: the vendored
`ios/COpus` sources were compiled with gcc using the same defines as `Package.swift`, and tones at 440 Hz and 5 kHz
were sent each way.

- Concentus at 16 kHz → libopus at 48 kHz: both tones kept.
- libopus (with the shim's voice settings) at 48 kHz → the gateway's `OpusVoiceDecoder`: wideband SILK packets
  (TOC 0x48), about 31 kbit/s, both tones kept.

This found that Concentus 1.0.2 outputs silence when it decodes *fullband* packets straight to 16 kHz. The gateway
therefore always decodes at 48 kHz and down-samples, and all encoders are capped at wideband.

## Release hardening checklist

- `isMinifyEnabled = true` (R8) for release; debug builds use a separate application id suffix.
- Sign with your own release keys. Never commit keystores, `DEVELOPMENT_TEAM` values or provisioning profiles.
- Confirm the merged manifest has no `INTERNET` permission
  (`app/build/intermediates/merged_manifests/*/AndroidManifest.xml`).
- Run Android lint and fix all security warnings; test on Android 8, 12, 14 and the newest version.
- Test on a real iPhone: background reconnection, Bluetooth off/on, gateway reboot, app termination.
