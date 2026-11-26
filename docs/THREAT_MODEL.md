# Threat model and security review

Scope: the shared core (`core/`), the Android gateway and client (`android/app`), the iPhone client (`ios/`) and
the link protocol (`protocol/PROTOCOL.md`). This document states what the design defends against, how, and what
is **not** covered. It is not a claim that the apps are free of vulnerabilities. No independent audit has been done.

## Assets

1. Message content and metadata, call history, contacts, live call state (stored by Android on the gateway).
2. The ability to act with the SIM: send texts, place/answer/end calls, run carrier (USSD/MMI) codes. These can
   cost money or redirect calls (call-forwarding codes).
3. Long-term identity keys and the list of paired devices.
4. Subscription entitlement (future; see BILLING.md).

## Assumptions

- The gateway and client phones run an unmodified, updated OS with a screen lock. App sandboxing, Android Keystore
  / Secure Enclave and file-based encryption work as documented.
- People compare the 6-digit code on both screens before confirming.
- Platform crypto (JCA/Conscrypt/BoringSSL on Android, CryptoKit on iOS) is correct.

## Threats and mitigations

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | **Lost or stolen client phone** | The client stores no message, call or contact content (memory only). The identity key is hardware-bound and non-exportable. The owner removes the device on the gateway ("Paired devices › Remove"), which closes its session at once and makes it unable to reconnect. | Until revoked, anyone who can unlock the client can use the gateway within Bluetooth range. Keys are not bound to user authentication, so the link can work in the background. |
| T2 | **Lost or stolen gateway** | The data was already on that phone; Pol adds only the paired-device list (Keystore-sealed, no backup). Clients can unpair. | Same exposure as losing any phone with your SIM. Use a screen lock and SIM PIN. |
| T3 | **Malicious nearby device connects** | It reaches only the handshake. Resume needs a paired identity key (ECDSA over a fresh transcript). Pairing needs the owner to open a 3-minute window, then a matching 6-digit code that **both** people confirm. | Denial of service: it can hold one of the 3 connection slots (handshake timeout 15 s, pairing confirm 120 s) or jam radio. |
| T4 | **Passive interception** of Bluetooth | AES-256-GCM per direction; keys from ephemeral ECDH (forward secrecy) bound to the full transcript by HKDF. | Traffic analysis (timing, sizes) is possible. The advertisement shows a Pol gateway is nearby. The resume HELLO carries the client's 16-byte id in clear, so an observer can recognise the same client over time. |
| T5 | **Active man in the middle during pairing** | Commit-then-reveal (client commits first) makes the code uniformly random to an attacker: 1-in-10^6 per attempt. Every attempt past the commit counts against the window (3 per opening), **including dropped links**. Without that, an attacker could see mismatching codes and abort for free (found and fixed in review). | Probability ≈ 3·10^-6 per opened window. Destroyed by people who confirm without comparing. |
| T6 | **Impersonation** of gateway or client on reconnect | Each side signs the transcript with its identity key; the other checks against the key stored at pairing. Tests: `impostorGatewayFailsResume`, `impostorClientFailsResume`. | — |
| T7 | **Replay / reordering / tampering** | Fresh ephemerals and nonces per connection; strict per-direction counter as nonce; any failure closes the session. Tests: `tamperedOrReplayedSealedRecordClosesSession`, Swift `testSealedRoundTripRejectsReplayAndTamper`. | — |
| T8 | **Downgrade** | Exactly one version; anything else is refused (REJECT 1). The version is in every transcript. Test: `wrongVersionIsRefused`. | — |
| T9 | **Unauthorised re-pairing** | Pairing is only possible while the owner opens the window on the gateway. One success closes it. At most 4 paired clients. | An owner tricked into opening the window and confirming a code they didn't compare. |
| T10 | **Malformed / oversized input** | Bounded record reader (8 KiB bodies), bounded cursor for every handshake field, JSON size limit, schema decode with unknown-field tolerance, `Validate` on every number/code/body, string length bounds before display. Fuzz-style test: `garbageInputNeverThrows`. | Parser bugs in kotlinx.serialization or Foundation's JSON decoder. |
| T11 | **Misbehaving or compromised paired client** | Gateway re-validates everything, accepts only well-formed carrier codes, rate-limits texts/calls/codes per minute, deduplicates by `cmid`, bounds its send queue. | A compromised client is trusted by design: it can send texts, place calls and run codes (including call forwarding) within the limits. Revoke it. |
| T12 | **Compromised or rooted gateway** | Out of scope: such a phone controls the SIM anyway. Clients bound and sanitise everything they receive. | Total for that phone's data. |
| T13 | **Local data extraction** | Client: no content at rest. Gateway: only the paired list, AES-GCM with a Keystore key, in no-backup storage. `allowBackup=false`, data-extraction rules exclude everything; iOS Keychain items are `ThisDeviceOnly`. | Forensic access to an unlocked device. |
| T14 | **Dependencies** | No networking, analytics, ads or crash-reporting libraries. Android build strips `INTERNET` and other network permissions from the merged manifest; `tools/check-privacy.sh` fails if network APIs appear. See BUILD.md for the dependency list and update practice. | Supply-chain compromise of AndroidX/Compose/kotlinx artifacts. Pin and verify (see BUILD.md). |
| T15 | **Subscription / payment fraud** | No payment flow is enabled and nothing is locked, so there is nothing to bypass today. The core has no field that could carry user data to billing. | When billing is added, a local-only trial can be reset by reinstalling. Entitlement must be verified by the provider, server-side (BILLING.md). |
| T16 | **Exported components / intents** | Only the two launcher activities are exported. Services and receivers are not exported. SMS status receivers are registered `RECEIVER_NOT_EXPORTED` with explicit, immutable PendingIntents. Phone-state receiver: protected system broadcast, `RECEIVER_NOT_EXPORTED`. | — |
| T18 | **Call-audio helper and Shizuku** | The helper runs as the shell user only while Shizuku is started. It exposes three Binder calls (probe, start, stop) and checks the caller is Pol's uid. It reads and writes PCM on a socket from Pol, runs no commands and has no network access. The Shizuku provider is exported but guarded by `INTERACT_ACROSS_USERS_FULL` (system/shell only). | Shizuku is third-party software running as shell. Wireless debugging, while on, is reachable from the local Wi-Fi network after pairing. Turn it off when not starting Shizuku. A compromised paired client could listen to calls it answers. Revoke it. |
| T19 | **New exported components for calls** | Gateway: the dialer (`DIAL`/`tel:`) only pre-fills a number and dials on a tap. The in-call service is guarded by `BIND_INCALL_SERVICE`. Android client: the connection service is guarded by `BIND_TELECOM_CONNECTION_SERVICE`. All are system-only. Call-notification receivers are not exported. | As the default phone app, Pol Gateway sees every call on that phone, which it already did through call state. |
| T17 | **Logs and notifications leaking content** | All Android logging goes through `SafeLog` with static text, and verbose levels are stripped in release. The iOS app has no logging calls. Notifications say "New message" unless the user turns on previews; channels use private lock-screen visibility. | — |

## Review performed for this change

- Design review of the protocol against T3–T10, with Kotlin tests for each property (35 tests in `core/`).
- Independent re-implementation of the key schedule, SAS, device id and record framing in Python
  (`tools/vectors.py`); Kotlin tests assert the same values, and the Swift tests read the same file.
- Manual review of Android manifests, exported components, PendingIntents, receivers, storage and logging, and of
  iOS entitlements, Info.plist, Keychain attributes and the privacy manifest.
- Static privacy guard (`tools/check-privacy.sh`) for network APIs, permissions, logging and backups.
- Type-check of all Android Kotlin against the Android 16 framework and Compose APIs (`tools/android-typecheck`).

### Findings fixed

1. **High:** a MITM could abort pairing attempts whose codes didn't match without it counting, then retry
   without limit. Fixed: every attempt past the commitment counts, including dropped links (test
   `declinedCodeFailsAndCountsAgainstWindow`).
2. **Medium:** a peer that stops reading could make the gateway buffer without bound. Fixed: 512 KiB cap per
   connection, then disconnect (Android gateway and both clients).
3. **Medium:** retrying a text after a reconnect could send it twice. Fixed: `cmid` deduplication on the gateway.
4. **Low:** the phone-state receiver was registered exported on Android 13+. Fixed: `RECEIVER_NOT_EXPORTED`.
5. **Low (reliability):** the notification hook could create a second repository instance during start-up.
   Fixed by passing settings explicitly.

### Not done / unresolved

- The Android app was **not built with the Android Gradle Plugin**, and the iOS app was **not compiled**: neither
  SDK was available here. No tests ran on devices, and Android lint did not run.
- No independent cryptographic or penetration review.
- Linkability via the cleartext client id and the advertisement (T4). A future version could encrypt the id
  under the gateway's key or use rotating identifiers.
- A 6-digit code relies on people comparing it. Pairing cannot be stronger than that without an out-of-band
  channel such as a QR code, which would need the camera.
- No in-session rekeying. Sessions rekey on every reconnection.
- The call-audio path (helper, Shizuku, CallKit, audio engine) has not run on any device. It depends on the gateway
  vendor's audio HAL honouring Android 13's interception API.
