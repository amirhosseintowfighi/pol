# Compatibility, gateway modes and limits

## Roles

- **Gateway:** the Android phone that holds the SIM card(s). It runs *Pol Gateway* and does the real calls
  and texts through Android's public telephony APIs.
- **Client:** the phone in your hand, an iPhone (iOS 18+) or an Android phone (12+) running *Pol Client*.
  It shows and controls what the gateway does over Bluetooth LE. It never touches its own cellular modem for this.

Both phones need Pol. The gateway is an Android phone because **iOS gives third-party apps no API to send or
read SMS, read the call log, or place/answer/end the iPhone's own cellular calls**. An iPhone therefore cannot be a
gateway, and Pol does not pretend it can.

## What "registered phone" means here

In Iran, "registered" (رجیستر شده) refers to a handset's **IMEI being recorded in the national Hamta (همتا)
registry**. When a SIM is inserted, the operator checks the IMEI against Hamta and grants network service only to
permitted devices. Unregistered imported phones, often iPhones, get no cellular service. Rules have changed
several times, and registration of some iPhone models has been closed or reopened.

Other readings exist elsewhere (SIM registration to an ID, carrier device whitelists, app accounts). Pol has
**no app account**, and it does not touch SIM registration or IMEI status.

**What Pol does and does not do:** the cellular network only ever sees the **gateway** phone, with its own
IMEI and SIM. The client is a Bluetooth accessory, much like a car kit or a smartwatch that relays notifications.
Pol does not change, hide or spoof an IMEI, does not register a device, and does not let an unregistered
phone attach to a cellular network. **An unregistered gateway gets no cellular service, so there is nothing to
relay, and Pol does not work around that.** Local rules on using a registered phone as a relay may change.
Check the regulations that apply to you.

## Compatibility matrix

"Source" = the phone with the SIM (gateway). "Destination" = the phone in your hand (client).

| Source (SIM) → Destination | Gateway registered, client registered | Gateway registered, client **unregistered** | Gateway **unregistered** |
|---|---|---|---|
| **Android → iPhone** | ✅ Supported. Both apps needed. | ✅ Supported. The iPhone uses no cellular service of its own; everything goes over Bluetooth to the registered gateway. | ❌ Not possible: the gateway has no cellular service. |
| **Android → Android** | ✅ Supported (client: Android 12+). | ✅ Supported, as above. | ❌ Not possible. |
| **iPhone → Android** | ❌ Not possible: iOS exposes no SMS, call-log or call-control APIs to apps. | ❌ | ❌ |
| **iPhone → iPhone** | ❌ Same reason. | ❌ | ❌ |

## What works with an Android gateway

| Feature | Status | Requirement / limit |
|---|---|---|
| Pairing, encrypted link, reconnect, revoke | ✅ | Bluetooth LE; gateway Android 8+ |
| SIM list, signal bars, network type, roaming, battery | ✅ | Gateway "Calls" or "Carrier codes" (READ_PHONE_STATE). Signal needs Android 9+. Service state isn't read (it would need location permission). |
| Conversations, search, send from a chosen SIM, delivery state | ✅ | Gateway "Messages". "Delivered" only if the network returns delivery reports. |
| Incoming texts live | ✅ | Gateway "Messages" (observed from Android's SMS store). |
| Read state | ⚠️ On the client only | Writing read state into Android's SMS store needs the **default SMS app** role. Pol doesn't take that role, to avoid replacing your messaging app. |
| MMS (pictures, group texts) | ❌ Not yet | Shown neither as working nor as content. Reading MMS parts is possible; it's future work. |
| Place a call from a chosen SIM | ✅ | Gateway "Calls" |
| Answer / decline / end | ✅ | Gateway "Calls", Android 9+. Uses `TelecomManager`'s public API for non-dialer apps; deprecated by Google and may stop working in a future Android version. |
| Ringing, active, waiting call state and duration | ✅ | Without the default-dialer role, the gateway can't see when the far end answers. The timer starts when the phone goes off-hook, and the UI says "Call in progress". |
| **Answer and talk on the iPhone** (CallKit) **or the Android client** (Android's call system) | ✅ with setup | Gateway **Android 13+**, "Calls" and "Call audio" on, and **Shizuku** running. Shizuku is started with Wireless debugging: once per gateway reboot on non-rooted phones; automatic with root, or with Shizuku builds that offer "start on boot". Works only on phones whose audio system allows call-audio interception; the gateway screen says if it doesn't. **Not yet tested on any real device.** |
| **HD voice** | ✅ | **Opus wideband** (16 kHz, 50–7000 Hz, ~32 kbit/s, FEC and loss concealment), both directions, when the gateway's phone allows 16 kHz interception; else Opus narrowband or G.711. The far end of the call is only as good as the cellular call itself: HD needs VoLTE/AMR-WB (or better) on the carrier side too. |
| Mute, speaker, headset/car | ✅ during bridged calls | iPhone: CallKit routes. Android client: Telecom routes (earpiece, speaker, Bluetooth). Mute silences the client microphone. |
| Hold, resume, swap two calls, keypad tones, exact "answered" state | ✅ with **Full call control** | The gateway becomes the default phone app (Android's own role dialog; reversible in Settings › Default apps). Without it, keypad tones are generated into the bridged audio instead, and hold isn't offered. |
| Recent calls | ✅ | Gateway "Recent calls" |
| Contacts and favourites | ✅ Opt-in | Gateway "Share contacts" (off by default). Manual entry always works. |
| Carrier codes (USSD), e.g. balance, call forwarding `**21*…#` | ✅ | Gateway "Carrier codes", Android 8+. Results depend on the carrier; some MMI codes are refused by Android. |
| SIM PIN, APN, VoLTE, preferred network, hotspot | ❌ | Need carrier privileges or system permissions. Shown as unavailable. |
| Notifications on the client | ✅ Opt-in | Previews off by default. |
| Demo mode | ✅ | Simulated gateway in memory. It can't dial or send: it has no Bluetooth, telephony or network access. |
| Persian / English, right-to-left | ✅ | Numbers and codes are isolated left-to-right. Android dates use the Solar Hijri calendar in Persian. iOS follows the system calendar setting. |

## Platform versions

| Component | Minimum | Notes |
|---|---|---|
| Android gateway | Android 8.0 (API 26) | Answer/end needs 9+; signal bars need 9+; **call audio needs 13+** and Shizuku. Target SDK 36. |
| Android client | Android 12 (API 31) | So that BLE scanning never needs location permission. Taking calls uses a self-managed Telecom call (Android's CallKit equivalent). |
| iPhone client | iOS 18 | Built with the current SDK, it picks up the system design (Liquid Glass) through standard controls. |
| Protocol | v1 | See protocol/PROTOCOL.md. Unknown messages and fields are ignored. |

## Reliability behaviour

- Reconnects with backoff (1, 2, 5, 10, 30 s) after a dropped link, gateway restart or Bluetooth toggle. Data
  already in memory stays visible, with a "Reconnecting…" banner.
- Texts sent while disconnected show "Sending…". They are delivered on reconnect, at most once (gateway
  deduplicates by message id), or marked "Not sent" with **Retry** after 90 s.
- The gateway can restart itself after a reboot (opt-in).
- Strict record counters: any lost or reordered record ends the session, and a fresh one starts automatically.
