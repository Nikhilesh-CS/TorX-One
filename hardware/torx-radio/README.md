# TorX Radio firmware

This directory is the firmware side of the Phase 10 phone-to-LoRa bridge. The first supported target is an ESP32 or nRF52 class controller connected to an SX1262 radio.

The hardware must:

1. advertise the TorX Radio BLE service UUID;
2. expose the control, phone-to-radio, and radio-to-phone GATT characteristics;
3. answer every `HELLO_REQUEST` challenge with device capabilities and an Ed25519 signature from its per-device key;
4. accept a packet only after the phone completes the authenticated handshake;
5. enforce the configured regional frequency and airtime policy in firmware;
6. send transmission results and received LoRa packets back through notifications.

The protocol is in [PROTOCOL.md](PROTOCOL.md). `include/torx_radio_protocol.h` contains constants shared by the firmware implementation. Board pin maps, SX1262 drivers, BLE stack bindings, secure key storage, and regional radio profiles remain board-specific and must be selected before producing flashable firmware.

Never ship a shared device private key. Each unit needs a unique Ed25519 identity created during provisioning and stored in protected MCU storage or a secure element.
