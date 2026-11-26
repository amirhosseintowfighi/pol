# Permissions

No permission is requested at launch. Each is requested when you turn on the feature that needs it, after the
screen explains why. Denying one turns off that feature only, and the app says so with a way to Settings.

## Android gateway (`studio.virgule.pol.gateway`)

| Permission | Feature (Setup switch) | Requested when | If denied |
|---|---|---|---|
| `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+; `BLUETOOTH`/`BLUETOOTH_ADMIN` normal permissions on 8–11) | Gateway on | Turning the gateway on | Gateway stays off; explained, with a link to Settings. |
| `POST_NOTIFICATIONS` (13+) | Gateway on (the "gateway is active" notification, pairing prompts) | Turning the gateway on | The gateway still runs; Android hides its notification. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeping the link alive in the background | Install-time (normal) | — |
| `RECEIVE_BOOT_COMPLETED` | "Start after restart" (off by default) | Install-time (normal); does nothing unless switched on | — |
| `READ_SMS`, `SEND_SMS` | Messages | Turning on Messages | Messages stay off; clients show "Messages are off". |
| `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_PHONE_STATE` | Calls (place, answer, decline, end; SIM list; call state) | Turning on Calls | Calls stay off; clients can't place or control calls. SIM details aren't shared. |
| `READ_CALL_LOG` | Recent calls (and the caller's number for ringing calls) | Turning on Recent calls | No history; incoming calls show "Unknown". |
| `READ_CONTACTS` | Share contacts (off by default) | Turning on Share contacts | Clients show numbers only; manual entry and the keypad still work. |
| `CALL_PHONE`, `READ_PHONE_STATE` | Carrier codes (USSD) | Turning on Carrier codes | Codes are unavailable. |
| Default phone app role (`ROLE_DIALER`), `USE_FULL_SCREEN_INTENT` | Full call control (off by default): hold, swap, keypad tones, exact states | Turning on "Full call control" (Android's own role dialog) | Calls work without hold/swap; keypad tones are generated into the call audio instead. |
| Shizuku permission (granted in the Shizuku app, not an Android runtime permission) | Call audio (off by default) | Tapping "Allow in Shizuku" after turning on call audio | No call audio; calls still work with voice on the gateway. |

**Call-audio helper:** with Shizuku allowed, Pol starts one small helper class as the Android **shell** user.
That user holds `CALL_AUDIO_INTERCEPTION` and `MODIFY_PHONE_STATE`. The helper only reads and writes call audio
through a socket handed to it by Pol, and answers only Pol's own uid. Shizuku itself is a separate
open-source app the owner installs and trusts. It can grant shell access to any app the owner approves in it.

**Not used:** internet and network state (stripped from the manifest), location (never needed: the gateway only
advertises), camera (pairing uses a code comparison, not a QR code), the gateway's microphone (the helper reads call
audio, not the microphone), photos/storage, default-dialer or default-SMS roles, accessibility, device admin.

## Android client (`studio.virgule.pol.client`, Android 12+)

| Permission | Feature | Requested when | If denied |
|---|---|---|---|
| `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` | Finding and connecting to the gateway | Tapping "Pair with gateway" | Pairing can't start; explained. Demo mode still works. |
| `POST_NOTIFICATIONS` | Notifications for texts and calls (off by default) | Turning on Notifications | No notifications; everything else works. |
| `RECORD_AUDIO` (+ `MANAGE_OWN_CALLS`, `USE_FULL_SCREEN_INTENT`, microphone / phone-call foreground types: install-time) | Take calls on this phone (off by default) | Turning on "Take calls on this phone" | Calls stay in the app with "voice is on the gateway". The microphone is open only while a bridged call is active. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Staying connected in the background | Install-time (normal) | — |

The client requires Android 12 so that Bluetooth scanning never needs location permission.

## iPhone

| Permission | Feature | Requested when | If denied |
|---|---|---|---|
| Bluetooth (`NSBluetoothAlwaysUsageDescription`) | Finding and connecting to the gateway | Tapping "Pair with Gateway". The Bluetooth manager isn't created before that. | Pairing can't start; the app says how to allow it in Settings. Demo mode works. |
| Notifications | New texts and incoming calls while in the background | Turning on Settings › Notifications in the Gateway tab | No notifications. |
| Microphone | Talking on this iPhone during a gateway call | Turning on "Take Calls on This iPhone" in the Gateway tab | Calls aren't brought into CallKit; they keep showing in the app with "voice is on the gateway". |
| Background modes `audio`, `voip` (with CallKit) | Keeping a bridged call's audio running when the screen locks | Declared capability (no prompt) | — |
| Background mode `bluetooth-central` | Keeping the link while the app is in the background | Declared capability (no prompt) | — |

**Not used:** contacts (names come from the gateway if you share them), camera, location, photos,
tracking (no ATT prompt; nothing to track). CallKit is used only for calls whose audio really is carried to the
iPhone. Those calls are kept out of the iPhone's Phone history (`includesCallsInRecents = false`).
