# Pol user guide

**Pol** makes the SIM cards in your Android phone usable from the phone in your hand (an iPhone or another
Android phone):

- texts, calls, recent calls, contacts, carrier codes, SIM and battery status;
- answering and **talking there in HD**.

Everything travels over an **encrypted Bluetooth link between your own devices**: no server, no account, no
internet.

Designed and developed by **[Virgule](https://virgule.studio)**. Free and open source under GPL-3.0.
[راهنمای فارسی](fa/README.md)

> Status: early version. The core is implemented and covered by automated tests, but it has **not yet been
> tried on real phones**. Please report results in the repository's Issues.

## What it does

Your SIM stays in a registered Android phone (the **gateway**), in a bag or on a desk. From your iPhone you:

- read, search and send texts from either SIM;
- place calls; incoming calls ring on the iPhone, and you answer and talk there;
- see recent calls and contacts;
- run carrier codes (balance `*140#`, call forwarding);
- see signal, network type and battery.

Pol never changes an IMEI and doesn't work around device registration. Only the registered gateway uses the
cellular network. The iPhone is a Bluetooth accessory to it, like a car kit or a watch.

## How it works

```
 Android phone with SIMs                          iPhone (iOS 18+) or Android phone (12+)
 ┌──────────────────────────────┐  Bluetooth LE  ┌──────────────────────────────┐
 │ Pol Gateway                  │ ◄────────────► │ Pol                          │
 │ Android SMS / calls / contacts│  end-to-end    │ Calls · Contacts · Keypad    │
 │ Call-audio helper (Shizuku)  │  encrypted     │ Messages · Gateway status    │
 └──────────────────────────────┘  Opus HD voice └──────────────────────────────┘
```

- **Two apps:** *Pol Gateway* on the Android phone with the SIMs, *Pol* on the phone in your hand.
- **Link:** direct Bluetooth LE. The Android apps have no internet permission at all.
- **Pairing:** once, by comparing a **6-digit code** on both screens. After that they reconnect by themselves.
- **Encryption:** fresh keys on every connection (P-256 ECDH, AES-256-GCM, forward secrecy). Identity keys live in
  the Secure Enclave or Android Keystore and never leave them.
- **Calls and texts:** the gateway uses Android's public APIs. The client shows and controls, and keeps nothing
  on disk.
- **Call audio:** Android lets only the *shell* user read and inject cellular call audio. Pol's small helper runs
  as that user through **Shizuku** and carries the voice both ways in **Opus wideband** (HD). The iPhone shows the
  call with its own call screen (CallKit).

Technical detail: [ARCHITECTURE.md](ARCHITECTURE.md), [protocol](../protocol/PROTOCOL.md).

## Requirements

| Device | Needs |
|---|---|
| Gateway (holds the SIMs) | Android 8+ with Bluetooth LE. **To talk on the iPhone: Android 13+** and the Shizuku app. |
| iPhone | iOS 18+ |
| or an Android phone in hand | Android 12+ |

## Download and install

Get the files from the repository's **Releases** page:

| File | Install on |
|---|---|
| `Pol-Gateway-<version>.apk` | The Android phone with the SIM cards (everyone needs this) |
| `Pol-<version>-unsigned.ipa` | Your iPhone, sideloaded with your own Apple ID |
| `Pol-<version>.apk` | An Android phone used in hand instead of an iPhone |
| `SHA256SUMS.txt` | To verify downloads |

**Android:** open the APK and allow "install unknown apps" for your browser or file manager when asked. If Play
Protect warns, choose "Install anyway". The warning is because the app isn't from Play, not because anything is
wrong.

**iPhone:** not on the App Store, so install with your own Apple ID:

- **SideStore** / **AltStore** (free): set up once with a computer, then install the IPA from the app;
- **Sideloadly** (Windows/macOS): connect the iPhone by cable and drop the IPA in.

With a free Apple ID the app must be refreshed every 7 days (SideStore and AltStore do it automatically; data and
pairing are kept). Turn on Settings › Privacy & Security › **Developer Mode**, and trust your Apple ID under
Settings › General › VPN & Device Management.

**No release yet?** Build it yourself (below). Pushing a version tag (`git tag v0.1.0 && git push origin v0.1.0`)
makes GitHub Actions build all of the files above (`.github/workflows/release.yml`).

## Setup

1. **Gateway:** open Pol Gateway and turn on **Use this phone as a gateway**. Under **Features**, turn on what
   you want: Messages, Calls, Recent calls, Share contacts, Carrier codes. Each asks for its own permission only
   then.
2. **Pair:** on the gateway tap **Pair a new device** (opens for 3 minutes). On the iPhone tap **Pair with
   Gateway**. Check the **6-digit code** is the same on both, and confirm on **both** phones.
3. **Notifications (optional):** iPhone › Gateway tab › Notifications. Previews are off by default.

### Talking on the iPhone (HD)

On the gateway (Android 13+):

1. Turn on **Calls**, then **Take calls on your other phone**.
2. Install **Shizuku** (free, open source; the app's "Get Shizuku" button links to it).
3. Enable **Wireless debugging** (once): Settings › About phone › tap *Build number* 7 times; then Settings ›
   System › Developer options › Wireless debugging. Wi-Fi must be connected; no internet is used.
4. In Shizuku, follow *Start via Wireless debugging* to pair it once, then tap **Start**.
5. Back in Pol Gateway, tap **Allow in Shizuku**. It shows **Ready**.

On the iPhone: Gateway tab › **Take Calls on This iPhone**. The microphone permission is asked only here.

- After a gateway reboot, start Shizuku again. This is automatic with root, or with Shizuku builds that offer
  "start on boot".
- Some phone makers block call-audio interception. Pol tells you if yours does, and the voice stays on the gateway.
- Opus wideband (50–7000 Hz) is used both ways. The far end sounds HD only if the cellular call is HD too
  (VoLTE / AMR-WB).

### Full call control

For hold, swap, real keypad tones and exact "answered" state, turn on **Full call control** on the gateway and
choose Pol as the default phone app. To undo: Android Settings › Default apps.

## Troubleshooting

| Problem | Fix |
|---|---|
| iPhone doesn't find the gateway | Bluetooth on both; Pol Gateway on; phones close. For pairing, the pairing window must be open. |
| Stuck on "Reconnecting…" | Toggle the iPhone's Bluetooth. On the gateway, set battery use for Pol Gateway to *Unrestricted*. |
| No call audio | Check the gateway's *Call audio* card for the missing step. Usually Shizuku stopped after a reboot. |
| "Audio system doesn't allow…" | This gateway can't redirect call audio. Talk on it or a headset paired with it. |
| iPhone app won't open after 7 days | Refresh it in SideStore, AltStore or Sideloadly. Data and pairing are kept. |
| Remove a lost device | Gateway › Paired devices › Remove. It is disconnected at once. |

## Privacy and security

Texts, calls and contacts are held only in memory on the client, and call audio is never recorded. Each permission
is asked only when you turn on its feature. See [PRIVACY.md](PRIVACY.md), [PERMISSIONS.md](PERMISSIONS.md),
[THREAT_MODEL.md](THREAT_MODEL.md) and [SECURITY.md](../SECURITY.md).

## Build from source

- **Android** (JDK 17, Android SDK 36, Gradle 8.14):
  ```bash
  cd android && gradle :app:assembleGatewayRelease :app:assembleClientRelease
  ```
- **iPhone** (macOS with Xcode and XcodeGen):
  ```bash
  cd ios && xcodegen && open Pol.xcodeproj
  ```
  Then pick your team and run.
- **Tests:**
  ```bash
  cd core && gradle test
  ```
  and `./tools/check-privacy.sh`.

More in [BUILD.md](BUILD.md).
