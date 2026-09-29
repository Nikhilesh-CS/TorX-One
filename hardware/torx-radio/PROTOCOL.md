# TorX Radio protocol v1

## Recognition and connection

The phone scans for BLE service `7e582001-7c69-4f72-9858-746f72786f6e`. Finding that UUID marks a candidate as recognized, but not trusted. The app connects, enables notifications, and sends a 32 byte random `HELLO_REQUEST` challenge.

The radio responds with `HELLO_RESPONSE` containing length-prefixed device ID, firmware version, board, radio chip, regulatory region, maximum payload, its 32 byte Ed25519 public key, and a 64 byte Ed25519 signature over `challenge || capability_fields`.

The first connection enters `NeedsPairing`. After the owner verifies the physical device, the app saves the public key fingerprint. Future connections are automatic and require a fresh valid signature, so copying the advertised name or UUID is insufficient.

## Frame format

All integers are big endian.

| Field | Bytes |
|---|---:|
| Magic `TXR1` | 4 |
| Version | 1 |
| Kind | 1 |
| Payload length | 2 |
| Sequence | 4 |
| Payload | 0 to 480 |
| CRC32 | 4 |

Kinds: hello request/response, send/received packet, transmission result, status request/response, ping/pong. CRC detects link corruption; TorX message encryption and authentication remain end to end above this hardware link.

## BLE characteristics

| Purpose | UUID | Direction |
|---|---|---|
| Control | `7e582002-7c69-4f72-9858-746f72786f6e` | request/response |
| Phone to radio | `7e582003-7c69-4f72-9858-746f72786f6e` | write |
| Radio to phone | `7e582004-7c69-4f72-9858-746f72786f6e` | notify |

`SEND_PACKET` payload is one byte destination ID length, destination ID, then opaque encrypted TorX mesh bytes. The firmware must not parse application plaintext.

## USB path

USB is part of the host contract, but automatic Android USB filtering cannot be finalized until TorX receives its production USB vendor/product IDs. The app must verify the same challenge-response protocol after opening USB; a USB name, VID/PID, or serial string alone never establishes trust.
