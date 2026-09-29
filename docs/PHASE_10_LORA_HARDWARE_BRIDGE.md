# Phase 10 — LoRa hardware bridge

Status: in progress

## Non-negotiable product behavior

When TorX Radio hardware is powered near or attached to the phone, TorX One recognizes it, proves that it is a real paired TorX device, connects, and exposes it to routing without requiring the user to select a transport for every message.

The first pairing requires physical owner approval. Every later BLE connection is automatic and authenticated with a fresh challenge. USB will use the same protocol after production VID/PID allocation.

## Implemented foundation

- TorX Radio BLE service and characteristic UUID contract
- filtered Android BLE discovery
- GATT connection, service discovery, notifications, and writes
- versioned binary framing with strict bounds and CRC32
- challenge-response hardware identity proof with Ed25519
- persistent public key fingerprint trust store
- Settings status row and one-time pairing approval action
- automatic reconnect manager for trusted devices
- `LORA` transport type and router fallback position
- explicit LoRa destination routing and 480 byte radio payload ceiling
- portable C firmware parser and firmware protocol documentation

## Still required for milestone one

- choose the exact ESP32/nRF board, SX1262 module, antenna, power design, and secure key storage
- implement board BLE service, SX1262 driver binding, radio queue, regional profile, airtime limits, and transmission receipts
- add an out-of-band verification code or required hardware-button confirmation to strengthen first pairing
- feed received radio packets into the authenticated mesh network layer
- allocate production USB VID/PID and implement USB link support
- test with two physical phones and two radios in airplane mode over kilometre-scale terrain

## Design decisions from existing systems

Reticulum demonstrates a clean interface boundary between the host stack and radio devices, plus strict handling for low-bandwidth links. Meshtastic demonstrates a compact phone-to-device client API. TorX keeps its own encrypted mesh packet above the radio link instead of making firmware an application-message authority.

The SX1262 can run proprietary LoRa modulation and supports region-dependent sub-GHz bands. Frequency, power, duty-cycle, and listen-before-talk behavior must be selected from the device's configured regulatory profile; the phone cannot override firmware safety limits.
