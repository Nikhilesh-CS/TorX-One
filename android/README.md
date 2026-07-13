# TorX One - Android Client

Native Android client built with Kotlin and Jetpack Compose.

## Transport Layers

| Range | Transport | Requirement |
|-------|-----------|-------------|
| Nearby | Wi-Fi Direct + Bluetooth via Google Nearby Connections | Bluetooth + Location permissions |
| Distant | Tor hidden services (.onion) via **Orbot** | Install [Orbot](https://guardianproject.info/apps/orbot/) from F-Droid or Play Store |
| Mesh relay | Multi-hop through connected nearby peers | At least one intermediate peer in range |

**No central relay server.** Messages are end-to-end encrypted (libsodium `crypto_box`) before leaving the device.

## Build and Run

1. Open `android/` in Android Studio.
2. Sync Gradle and run on API 26+ device or emulator.
3. Install **Orbot** on the device for distant messaging.
4. Grant Bluetooth and Location when prompted.

## Adding a Distant Contact

1. Both users install Orbot and open TorX One (Orbot will expose a `.onion` address).
2. Tap the **copy** FAB on the chat list to share your contact key (`astra:…` includes your `.onion`).
3. The other user taps **add contact** and pastes your key.
4. Send messages — the app routes via Tor automatically when the peer is not nearby.

## Nearby Messaging

1. Open the app on two phones within range.
2. Tap a device in the **Nearby** row to connect.
3. Accept the connection request on the other phone.
4. Keys are exchanged automatically; chat is encrypted.

## Emulator Notes

- Nearby Connections requires physical devices (emulators cannot discover each other reliably).
- Tor via Orbot requires a physical device with Orbot installed.
