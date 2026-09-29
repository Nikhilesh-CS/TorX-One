# Phase 5: Android Modernization

## Toolchain

| Component | Previous | Phase 5 |
|---|---:|---:|
| Android Gradle Plugin | 8.7.3 | 9.4.0 |
| Gradle | 8.10.2 | 9.6.0 |
| Kotlin compiler integration | Kotlin Android plugin 2.0.21 | AGP built-in Kotlin with compiler plugins 2.4.20 |
| KSP | 2.0.21-1.0.28 | 2.3.10 |
| Compile SDK | 35 | 36 |
| Target SDK | 35 | 36 |
| Java bytecode | 17 | 17 |

The app now targets Android 16/API 36. API 37 remains outside the runtime target because it is not the current annual stable Android target. September 2026 AndroidX releases that require compile API 37 were not selected; the newest stable API 36-compatible releases are used instead.

## Libraries

| Library | Previous | Phase 5 |
|---|---:|---:|
| Compose BOM | 2024.12.01 | 2026.03.00 |
| AndroidX Core | 1.15.0 | 1.18.0 |
| Activity Compose | 1.9.3 | 1.13.0 |
| Lifecycle | 2.8.7 | 2.10.0 |
| Navigation Compose | 2.8.5 | 2.9.8 |
| Room | 2.6.1 | 2.8.5 |
| AndroidX SQLite | 2.7.0 | 2.7.1 |
| CameraX | 1.4.1 | 1.6.0 |
| Biometric | 1.2.0-alpha05 | 1.1.0 stable |
| DataStore | 1.1.1 | 1.2.1 |
| Coroutines | 1.9.0 | 1.11.0 |
| Kotlin serialization JSON | 1.7.3 | 1.11.0 |

SQLCipher, Bouncy Castle, Android Keystore, WebRTC, Nearby Connections, and the pinned Tor Android runtime remain in place.

## Verification gate

- `gradlew help` must pass with the AGP 9 built-in Kotlin configuration.
- `:app:testDebugUnitTest` must pass the complete protocol and security suite.
- `:app:assembleDebug` must produce the debug APK.
- Device acceptance should cover startup, onboarding, QR scanning, database migration, Nearby, Tor bootstrap, notifications, media, and calls on Android 16.
