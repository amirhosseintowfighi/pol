# Pol · پل

Use the SIM cards in your Android phone from the phone in your hand (an iPhone or another Android phone):

- texts, calls, recent calls, contacts, carrier codes and SIM status;
- answering and **talking there in HD**.

All of it goes over an **end-to-end encrypted Bluetooth link between your own devices**: no server, no
account, no internet.

**Designed and developed by [Virgule](https://virgule.studio).** Free and open source software under the
[GNU GPL v3](LICENSE).

<div dir="rtl" lang="fa">

**پل** سیم‌کارت‌های گوشی اندرویدی شما را روی گوشی دیگرتان (آیفون یا اندروید) در دسترس می‌گذارد: پیامک، تماس، تماس‌های اخیر،
مخاطبین، کدهای اپراتور و وضعیت سیم‌کارت، و جواب دادن و صحبت کردن با کیفیت HD. همه‌چیز فقط از راه بلوتوث رمزنگاری‌شده بین
دستگاه‌های خودتان جابه‌جا می‌شود، بدون سرور، حساب کاربری یا اینترنت. پل شمارهٔ IMEI را تغییر نمی‌دهد و محدودیت رجیستری را
دور نمی‌زند: فقط گوشی دروازهٔ رجیسترشده به شبکهٔ اپراتور وصل است.

**[راهنمای کامل فارسی: نصب، راه‌اندازی و نحوهٔ کار](docs/fa/README.md)** · طراحی و توسعه: [ویرگول](https://virgule.studio)

</div>

## How it works

```
 Android phone with SIMs                         iPhone (iOS 18+) or Android phone (12+)
 ┌──────────────────────────┐   Bluetooth LE     ┌────────────────────────────┐
 │ Pol Gateway              │ ◄────────────────► │ Pol                        │
 │ Android telephony APIs   │  P-256 ECDH        │ Calls · Contacts · Keypad  │
 │ Call-audio helper        │  AES-256-GCM       │ Messages · Gateway status  │
 └──────────────────────────┘  Opus HD voice     └────────────────────────────┘
```

1. On the Android phone: install **Pol Gateway**, turn it on, and allow only the features you want.
2. Tap **Pair a new device**. On the other phone, tap **Pair with gateway**.
3. Both screens show the same 6-digit code. Confirm on both, and from then on they reconnect automatically.
4. **To answer and talk on the other phone (HD):** the gateway needs Android 13+ and
   [Shizuku](https://shizuku.rikka.app), started once per gateway reboot with Wireless debugging.
5. **Full call control** (Pol as the gateway's phone app) adds hold, swap and keypad tones.

**[User guide](docs/GUIDE.md)** · [Architecture](docs/ARCHITECTURE.md) ·
[What works and what doesn't](docs/COMPATIBILITY.md)

## Download

Files are published on the **Releases** page:

- `Pol-Gateway-<v>.apk` for the Android phone with the SIMs;
- `Pol-<v>-unsigned.ipa` for the iPhone, sideloaded with SideStore, AltStore or Sideloadly;
- `Pol-<v>.apk` for an Android phone used as the client.

A release is built automatically when a version tag is pushed (`.github/workflows/release.yml`).

## Status

| Part | State |
|---|---|
| Protocol and crypto core (`core/`) | Implemented. 35 unit tests pass (handshakes, attacks, codecs, audio over the encrypted link), cross-checked against an independent Python implementation. |
| Android gateway and client (`android/`) | Implemented. All Kotlin type-checks against the Android 16 framework and Compose APIs. **Not yet built with the Android Gradle Plugin or run on devices.** |
| iPhone client (`ios/`) | Implemented in SwiftUI with CallKit and libopus. Vendored libopus compiled and interop-tested against the Android codec. **The app itself is not yet compiled or run** (needs Xcode). |
| Call audio (HD) | Implemented both ways, Opus wideband. **Not yet run on any phone**; depends on the gateway phone allowing call-audio interception. |
| Subscription | Terms and model implemented; **no payment provider integrated**, nothing locked. See [BILLING.md](docs/BILLING.md). |

## Documentation

- [User guide](docs/GUIDE.md) · [راهنمای فارسی](docs/fa/README.md)
- [Architecture](docs/ARCHITECTURE.md) · [Protocol specification](protocol/PROTOCOL.md)
- [Compatibility and registered/unregistered phones](docs/COMPATIBILITY.md)
- [Privacy and data flow](docs/PRIVACY.md) · [Permissions](docs/PERMISSIONS.md)
- [Threat model and security review](docs/THREAT_MODEL.md) · [Security policy](SECURITY.md)
- [Subscription and payment boundary](docs/BILLING.md) · [Building, testing, dependencies](docs/BUILD.md)

## License and credits

Copyright (C) 2026 [Virgule](https://virgule.studio). Pol is free software, released under the
[GNU General Public License v3.0](LICENSE). Third-party components and their licences are listed in
[NOTICE](NOTICE): libopus, Concentus, Shizuku, Kotlin and AndroidX.

Similar products exist, such as the open-source Tether project and the SimBridge app. Pol is an independent
implementation: its protocol, code, texts and visual design are its own.
