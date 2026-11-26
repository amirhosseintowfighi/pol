# Privacy and data flow

Pol moves phone data only between **your own paired devices**, over an encrypted Bluetooth LE link. It has
no server, account, analytics, crash reporting, advertising or telemetry. This was checked as follows:

- The Android manifest explicitly **removes** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and the
  advertising-ID permission from the merged manifest. Neither the gateway nor the client app can open a network
  connection.
- The iPhone app's code contains no networking API (`URLSession`, `Network`, web views). Its privacy manifest
  declares no collected data and no tracking.
- `tools/check-privacy.sh` fails the build if networking or telemetry APIs, network or location permissions,
  content logging or backups appear.

**Billing exception (not active yet):** a paid subscription needs a payment provider. No provider is integrated
in this build, so **nothing is sent anywhere for billing**. When one is added, it may receive only the fields in
`BillingRequest` (`core/.../billing/Subscription.kt`): plan id, a random billing account id, amount, and for
card-to-card a bank transfer reference. **Calls, messages, contacts, call history, device identifiers and pairing
keys are never part of billing traffic**, and the billing module has no access to them. See [BILLING.md](BILLING.md).

## Data-flow table

| Data | Where it is stored | Leaves the device? | Recipient |
|---|---|---|---|
| Text messages (body, number, time, SIM, status) | Gateway: Android's own SMS store (Pol does not copy it). Client: **memory only**, gone when the app stops. | Yes, gateway → client, only if "Messages" is on. Full sync covers the newest 1000 messages from the last 90 days, then new ones live. | Your paired client(s) over encrypted BLE. |
| Text you send from a client | Client memory until confirmed; then Android's SMS store on the gateway (written by Android). | Client → gateway over BLE; then sent by the gateway's carrier as an ordinary SMS. | Your gateway, then the recipient's carrier, as with any text. |
| Call history | Gateway: Android's call log. Client: memory only (up to 1000 entries). | Gateway → client, only if "Recent calls" is on. | Paired client(s). |
| Contacts (names, numbers, labels, favourite flag) | Gateway: Android contacts. Client: memory only (up to 5000). | Gateway → client, only if "Share contacts" is on (off by default). | Paired client(s). |
| Live call state (number, state, start time, SIM) | Memory on both phones. | Gateway → client while "Calls" is on. | Paired client(s). |
| Call audio (your voice and the other party's) | **Not stored anywhere**. Only 60–200 ms of playout buffer in memory. | iPhone ↔ gateway over encrypted BLE, then to and from the carrier as the call itself. | Your gateway and the person you're calling, as with any call. |
| Bridged calls in iPhone's Phone history | Not added (`includesCallsInRecents = false`), so iCloud doesn't sync them. | No | — |
| Numbers you dial | Not stored by Pol (Android records the call in its call log). | Client → gateway; the call is placed by the gateway's carrier. | Your gateway, then the carrier. |
| Carrier (USSD) codes and replies | Memory only. | Client → gateway → carrier; the reply comes back the same way. | Your gateway, your carrier. |
| SIM labels, carrier name, signal, network type, battery | Memory on the client. | Gateway → client. | Paired client(s). |
| Identity private keys | Android Keystore / iOS Secure Enclave. Non-exportable. | **Never.** | — |
| Identity public keys, device names, pairing date | Gateway: Keystore-sealed file in no-backup storage. Client: Keychain (`ThisDeviceOnly`) / Keystore-sealed file. | During pairing, to the device being paired. | The other paired device. |
| Client device id (16-byte hash of its public key) | Derived. | Sent unencrypted in each reconnect HELLO. | Anyone listening within Bluetooth range can see it (see THREAT_MODEL T4). |
| BLE advertisement (service UUID, "pairing open" flag) | — | Broadcast by the gateway while on. | Anyone nearby. No name, number or identifier included. |
| Settings (feature switches, notification choices, demo mode) | SharedPreferences / UserDefaults, excluded from backup. | No. | — |
| Notifications | Local. Content hidden unless "Show previews" is on; private on the lock screen. | No. | — |
| Logs | Android logcat: short static messages, never content, numbers or keys; debug lines stripped in release. iOS: none. | No (nothing is uploaded). | — |
| About links (virgule.studio, source code) | — | Only if you tap them: your browser opens the page. The app itself sends nothing. | The website you open |
| Diagnostics, crash reports, analytics, usage data | Not collected. | No. | — |
| Billing data (future) | Provider's system. | Only `BillingRequest` fields, when a provider is configured. | The chosen payment provider only. |

## Retention and deletion

- **Clients keep no message, call or contact data at rest.** Quitting the app, unpairing or "Erase all data"
  clears memory. On reconnect, data is fetched again from the gateway.
- **Gateway:** Pol stores only the paired-device list. Message, call and contact data stays in Android's
  own stores, under your control in the system apps.
- **Unpair** (client): deletes the pairing locally and asks the gateway to delete it too.
- **Remove a device** (gateway): revokes it immediately.
- **Erase all data** (client) and **Remove all pairings and reset** (gateway): delete pairings, the identity key
  and settings.
- Uninstalling deletes everything Pol stored. Nothing is restorable from a backup.
