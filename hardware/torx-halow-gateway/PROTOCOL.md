# TorX HaLow gateway protocol v1

## Discovery

The gateway advertises DNS-SD service `_torx-halow._tcp` on the phone-facing local network. Discovery only identifies a candidate. The service name, SSID, IP address, and MAC address are not trusted identities.

## Authentication

The phone sends a `HELLO_REQUEST` with 32 random bytes. `HELLO_RESPONSE` contains length-prefixed gateway ID, firmware version, HaLow chipset, regulatory region, maximum payload, media capability, the gateway's 32 byte Ed25519 public key, and a signature over `challenge || capability_fields`.

First use requires owner pairing. Later connections require a fresh signature matching the stored public-key fingerprint.

## Framing

All integers are big endian.

| Field | Bytes |
|---|---:|
| Magic `THG1` | 4 |
| Version | 1 |
| Kind | 1 |
| Flags | 1 |
| Reserved | 1 |
| Payload length | 4 |
| Stream ID | 4 |
| Payload | 0 to 1 MiB |
| CRC32 | 4 |

Kinds include hello, send/receive packet, send result, status, and ping/pong. TCP provides ordered delivery; stream IDs allow independent TorX transfers. CRC catches parser and storage corruption and is not a security primitive.

The `SEND_PACKET` and `RECEIVED_PACKET` payload starts with a one-byte node ID length, node ID, then opaque encrypted TorX data.
