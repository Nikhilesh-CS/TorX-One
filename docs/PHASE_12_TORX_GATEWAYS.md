# Phase 12 — TorX gateways

Status: in progress

## Goal

An offline phone reaches a paired local TorX gateway over Nearby, LoRa, or HaLow. The gateway forwards opaque TorX packets through its v3 onion service to a remote TorX gateway, which delivers them into the recipient's local mesh.

The phone does not gain general internet access through this feature. The gateway accepts only the TorX overlay protocol and never receives message plaintext or session keys.

## Implemented foundation

- `GATEWAY` transport type and automatic router position after direct local HaLow
- bounded, versioned phone-to-gateway submission format
- v3 onion destination validation
- explicit destination gateway and destination node routing
- packet UUID, creation time, expiry, priority, and gateway hop ceiling
- replay, expiry, future-clock, size, and duplicate rejection policy
- expiring gateway route directory
- HaLow protocol commands for global submission and result reporting
- integration with the authenticated local HaLow gateway manager
- unit coverage for encoding, replay rejection, expiry, and route validation

## Trust boundaries

- Tor v3 authenticates the onion service identified by its address and encrypts the Tor path.
- TorX application encryption remains end to end between users.
- The local gateway must authenticate the paired phone and enforce quotas.
- Gateways must sign routing announcements and gateway-to-gateway transit metadata.
- A gateway learns routing metadata needed to forward packets, but must not receive application plaintext.

## Required to complete Phase 12

- implement the OpenWrt/Linux gateway daemon described in the hardware contract
- define and verify signed gateway route announcements
- add authenticated gateway-to-gateway transit envelopes and delivery receipts
- persist gateway submissions across power loss with bounded encrypted queues
- add per-phone quotas, rate limits, abuse controls, and congestion signaling
- provision and protect gateway onion-service and Ed25519 keys
- connect remote gateway delivery into LoRa, HaLow, and Nearby mesh routes
- run a two-site test with both phones offline and two gateways connected only through Tor
