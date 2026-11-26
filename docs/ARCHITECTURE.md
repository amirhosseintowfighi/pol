# Architecture

How Pol is put together, for contributors and reviewers. Wire details are in
[protocol/PROTOCOL.md](../protocol/PROTOCOL.md); the security design is in [THREAT_MODEL.md](THREAT_MODEL.md).

## Components

```
                               ┌─────────────────────── core/ (Kotlin/JVM, no Android) ──────────────────────┐
                               │ crypto/  P-256, HKDF, AES-GCM, identity interface                           │
                               │ link/    records, pairing + resume handshakes, sealed channel (Links.kt)    │
                               │ proto/   JSON control messages, validation, de-duplication, paging          │
                               │ audio/   Opus (Concentus) + G.711 codecs, frames, jitter buffer              │
                               │ client/  ClientModel: gateway messages → UI snapshot, actions → requests    │
                               │ demo/    DemoGateway: simulated gateway speaking the real protocol           │
                               │ billing/ plan terms and the payment-provider boundary (no provider)          │
                               └───────────────▲─────────────────────────────────────────▲───────────────────┘
                                               │ same library                            │ same library
 ┌──────────── android/app, flavor "gateway" ──┴───────────────┐   ┌──── android/app, flavor "client" ──┴──────┐
 │ GatewayService (foreground) → GatewayRuntime                 │   │ ClientRepository: BleCentral + ClientLink  │
 │   GattServer: BLE peripheral, per-device send queues         │   │   + ClientModel → StateFlow → Compose UI   │
 │   GatewayLink per connection (core)                          │   │ ClientCalls: self-managed Telecom calls,    │
 │   Telephony: SMS, call log, contacts, dial/answer/end, USSD  │   │   ClientAudio (mic/speaker, Opus)          │
 │   CallRegistry ← PolInCallService (when default phone app)   │   │ ClientService (foreground)                 │
 │   CallAudioBridge ⇄ CallAudioHelper (shell uid, Shizuku)     │   └────────────────────────────────────────────┘
 │   Compose UI: setup, pairing, devices, features, call audio  │
 └──────────────────────────────────────────────────────────────┘
 ┌──────────── ios/ (SwiftUI, iOS 18+) ─────────────────────────────────────────────────────────────────────────┐
 │ AppModel (@Observable, main actor): mirrors ClientModel; BleCentral (CoreBluetooth); ClientLink (CryptoKit)  │
 │ CallCoordinator: CallKit provider + CallAudioEngine (AVAudioEngine, voice processing, libopus via COpus)     │
 │ DeviceIdentity: Secure Enclave key; Keychain (this device only)                                              │
 │ Views: Calls, Contacts, Keypad, Messages, Gateway, Onboarding/Pairing, In-call                               │
 └──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Data flow of a text

1. On the iPhone, the user taps Send. `AppModel.sendSms` validates it and creates a client message id (`cmid`),
   shows a "Sending…" bubble and sends `sms.send`.
2. The record is sealed (AES-GCM, counter n) and cut into BLE writes.
3. On the gateway, `GattServer` → `GatewayLink.onBytes` → `GatewayRuntime.handle`. The gateway re-validates, rate
   limits and drops the request if it has seen that `cmid` before (a retry after a reconnect).
4. `Telephony.sendSms` hands it to `SmsManager` on the chosen SIM and acks `pending`, then `sent` and `delivered`.
5. Android writes the sent text to its SMS store. The gateway's content observer sees it and sends `sms.new` with
   the `cmid`, and the iPhone replaces the bubble with the stored message.

## Data flow of a bridged call

1. A cellular call rings on the gateway. In plain mode, the phone-state broadcast reports it; in full-control mode,
   Telecom's `Call` object does. The gateway sends `call.state` (ringing).
2. With "Take calls on this iPhone", `CallCoordinator` reports it to CallKit, and the iPhone rings with the system
   UI, lock screen included.
3. The user answers. CallKit's answer action sends `call.action answer` and `audio.start {codecs: [opus, pcmu]}`.
4. The gateway answers (`TelecomManager.acceptRingingCall` or `Call.answer`). Once the call is active,
   `CallAudioBridge.begin` asks the shell-user helper to open Android 13's call-audio interception at 16 kHz, falling
   back to 8 kHz. It replies `audio.state {active, codec: opus, rate}`.
5. Each 20 ms:
   - gateway: downlink PCM → Opus (Concentus) → sealed audio record → BLE notify;
   - iPhone: microphone (with voice processing) → 48 kHz → Opus (libopus) → BLE write → gateway decodes
     (48 kHz, then down to 16 kHz) → helper → uplink injection.
6. Late audio is dropped *before* sealing, so record counters stay contiguous. Receivers buffer 60–200 ms.
7. On hang-up from either side, `audio.stop` and `call.state` without the call, then CallKit reports ended.

## Threads

- **Gateway:** one `HandlerThread` ("pol-link") runs Bluetooth callbacks, all `GatewayLink`s and request handling.
  Provider queries run on a single IO executor and post results back. Audio capture and encoding run on the
  bridge's thread; uplink writes run on a single writer executor.
- **Android client:** one `HandlerThread` for Bluetooth and the link. Mic and speaker threads run inside
  `ClientAudio`.
- **iPhone:** main actor for model, link and CoreBluetooth (queue: main). The audio tap and render callbacks are
  real-time threads, synchronised with `os_unfair_lock`, and hand frames to the main actor.

## Storage

| Where | What |
|---|---|
| Gateway | Android Keystore: identity key and the AES key sealing the paired-device file (no-backup storage); SharedPreferences for feature switches |
| Android client | Keystore identity, sealed file with the paired gateway, preferences |
| iPhone | Secure Enclave identity (wrapped blob in the Keychain, this-device-only), gateway record in the Keychain, UserDefaults for settings |
| Everywhere | No message, call, contact or audio data at rest (the gateway's data stays in Android's own providers) |

## Testing

- `core/`: 35 JUnit tests: handshakes, MITM aborts, impostors, replay and tamper, version refusal, malformed input,
  validation, de-duplication, paging, demo flow, subscription terms, G.711 vectors, Opus wideband and loss
  concealment, audio over the encrypted link.
- `protocol/vectors.json`: generated by an independent Python implementation and asserted by the Kotlin and Swift
  tests.
- `tools/android-typecheck`: compiles all Android Kotlin against the Android 16 framework and Compose APIs without
  the SDK.
- `tools/check-privacy.sh`: fails on network APIs, network or location permissions, content logging, backups, or
  unguarded exported components.
