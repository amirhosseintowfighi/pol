# Security policy

## Reporting a vulnerability

Please report security problems privately. Do not open a public issue.

- Use GitHub's **private vulnerability reporting** for this repository (Security › Report a vulnerability).
  Maintainers: turn it on under Settings › Code security before publishing.
- Include the affected component (core, Android gateway, Android client, iPhone app, protocol), the version or
  commit, steps to reproduce, and the impact you expect.
- Don't include real phone numbers, message content or other people's data in a report. Demo mode produces
  realistic test data.

We aim to acknowledge reports within 7 days and agree on a disclosure date with you. Fixes are released before
details are made public. There is no bug bounty.

## Supported versions

Only the latest release and the default branch receive security fixes.

## Design summary

- Device-to-device only: Bluetooth LE between your own paired phones. No server, account, analytics, crash
  reporting or advertising SDK. The Android build has no internet permission, and the iPhone app makes no
  network requests.
- Pairing: P-256 ECDH with commit-then-reveal and a 6-digit code that **both** people confirm. The pairing
  window is opened by the gateway's owner and limited in time and attempts.
- Reconnect: mutual ECDSA signatures over a fresh transcript. Ephemeral ECDH gives forward secrecy.
- Records: AES-256-GCM with strict counters. Replay, reordering or tampering closes the session.
- Keys: Android Keystore (StrongBox when available) and the iOS Secure Enclave, non-exportable, never backed up.

Full details, residual risks and the review performed: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).
Wire format: [protocol/PROTOCOL.md](protocol/PROTOCOL.md).

We don't claim the software is free of vulnerabilities. The design and tests are described so you can check them.
