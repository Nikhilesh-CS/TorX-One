# TorX One

<div align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Tor-Hidden%20Services-7D4698?style=for-the-badge&logo=torproject&logoColor=white" alt="Tor Hidden Services" />
  <img src="https://img.shields.io/badge/Status-Beta-orange?style=for-the-badge" alt="Beta" />
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="MIT License" />
</div>

<h3 align="center">Your Network. Your Privacy. Your Freedom.</h3>

<p align="center">
  <b>TorX One</b> is a privacy-first Android messenger for decentralized, serverless communication over Tor hidden services and local mesh transports.
</p>

<p align="center">
  <a href="https://github.com/Nikhilesh-CS/TorX-One/releases/latest"><b>Download Latest APK</b></a>
  Â·
  <a href="https://github.com/Nikhilesh-CS/TorX-One/issues">Report Issue</a>
  Â·
  <a href="SECURITY.md">Security Policy</a>
</p>

---

## Overview

TorX One is built around a simple principle: private communication should not depend on phone numbers, centralized servers, or platform-controlled identity systems.

Each Android device can run as its own private communication node. The app creates a local identity, starts an embedded Tor service, exposes a Tor v3 onion address, and allows trusted peers to connect directly. For nearby communication, TorX One also supports local Bluetooth and Wi-Fi Direct transport.

The goal is to make secure communication resilient, portable, and user-controlled.

## Why TorX One

- No phone number required
- No central messaging server
- Tor hidden service identity for remote reachability
- Bluetooth and Wi-Fi Direct support for nearby transport
- End-to-end encrypted message path using X25519 and ChaCha20-Poly1305
- Identity fingerprint and onion address visibility for verification
- Founder verification tied to a cryptographic signing key, not a display name
- In-app update flow through GitHub Releases

## Current Android Status

TorX One is currently in beta and under active development.

The latest Android build focuses on:

- Tor startup reliability on physical Android devices
- Hidden service creation and onion address availability
- Identity backup and restore hardening
- Premium verified Founder profile experience
- Secure password entry for identity backup and restore
- Cleaner release and CI workflow

For testers, use the latest APK from:

https://github.com/Nikhilesh-CS/TorX-One/releases/latest

## Core Features

### Private Identity

TorX One creates a local cryptographic identity and shows a visible fingerprint so users can verify who they are communicating with.

### Tor Hidden Service Transport

Each device can publish an onion address and communicate without exposing a public IP address or requiring a central relay server.

### Nearby Mesh Transport

Bluetooth and Wi-Fi Direct support allow local peer discovery and communication when devices are physically nearby.

### End-to-End Encryption

The Android app uses X25519 key agreement with ChaCha20-Poly1305 authenticated encryption for secure message exchange.

### Founder Verification

The official Founder profile is verified using the Founder signing public key. A user cannot gain Founder status by changing their display name.

## Architecture

```text
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”        Tor Network        â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚   TorX One App    â”‚  â—€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¶  â”‚   TorX One App    â”‚
â”‚   Android Device A  â”‚                            â”‚   Android Device B  â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜                            â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
           â”‚                                                  â”‚
           â–¼                                                  â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”                            â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ Local Tor Service   â”‚                            â”‚ Local Tor Service   â”‚
â”‚ v3 Onion Address    â”‚                            â”‚ v3 Onion Address    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜                            â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

Nearby mode can also use Bluetooth and Wi-Fi Direct for local transport when peers are close to each other.

## Technology Stack

- Kotlin
- Android SDK
- Jetpack Compose
- Material 3
- Coroutines and StateFlow
- Room
- Embedded Tor
- LazySodium / libsodium
- GitHub Releases for APK distribution

## Installation

### For Testers

1. Open the latest release:
   https://github.com/Nikhilesh-CS/TorX-One/releases/latest
2. Download the APK asset.
3. Install it on an Android device.
4. Launch TorX One and wait for Tor to show connected.
5. Share your identity QR code or onion address only with people you trust.

### For Developers

```bash
git clone https://github.com/Nikhilesh-CS/TorX-One.git
cd TorX-One/android
./gradlew assembleDebug
```

For Windows PowerShell:

```powershell
git clone https://github.com/Nikhilesh-CS/TorX-One.git
cd TorX-One\android
.\gradlew.bat assembleDebug
```

Physical Android devices are recommended for Tor and nearby transport testing.

## Requirements

- Android 8.0 or higher
- Physical Android device recommended
- Network access for Tor bootstrap
- Bluetooth and nearby permissions for local transport
- Notification/background permissions for reliable service operation

## Security Notes

TorX One is privacy-first, but it is still beta software. Do not rely on it for high-risk operational security without independent review.

Current protections include:

- Local identity generation
- Identity fingerprint visibility
- X25519 and ChaCha20-Poly1305 message encryption
- Tor hidden service transport
- Founder badge verification by signing public key
- Password-protected identity backup and restore

Security roadmap:

- Stronger forward secrecy
- More formal protocol documentation
- Expanded adversarial testing
- Independent security review
- Hardening of local storage and backup flows

## Roadmap

- Reliable peer presence and reconnect logic
- Improved identity backup recovery UX
- Stronger message delivery guarantees
- Group mesh communication
- Expanded decentralized routing
- Public protocol documentation
- Security audit preparation

## Project Status for Demonstrations

TorX One is suitable for prototype demonstrations, technical review, and controlled tester feedback. For public demos, use the latest release APK and clearly describe the app as a beta privacy communication prototype.

Recommended demo flow:

1. Show identity creation.
2. Show Tor bootstrap and connected status.
3. Show onion address generation.
4. Show identity fingerprint.
5. Show nearby transport status.
6. Show verified Founder profile.
7. Explain that identity verification is key-based, not name-based.

## Contributing

Contributions are welcome. Please open an issue first for major changes, security-sensitive changes, or protocol-level proposals.

Basic workflow:

1. Fork the repository.
2. Create a feature branch.
3. Make a focused change.
4. Run the Android build.
5. Open a pull request with a clear summary and validation notes.

See [CONTRIBUTING.md](CONTRIBUTING.md) for more details.

## Responsible Disclosure

If you discover a security vulnerability, do not open a public issue. Follow the process in [SECURITY.md](SECURITY.md).

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.

## Acknowledgements

- [The Tor Project](https://www.torproject.org/)
- [Guardian Project](https://guardianproject.info/)
- [Android Open Source Project](https://source.android.com/)
- [Material Design](https://m3.material.io/)

