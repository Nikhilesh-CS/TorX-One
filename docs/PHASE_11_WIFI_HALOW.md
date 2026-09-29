# Phase 11 — Wi-Fi HaLow

Status: in progress

## Product role

Wi-Fi HaLow provides a long-range IP path for images, voice notes, files, and synchronization. LoRa remains the constrained path for text, receipts, emergency traffic, and control packets. Nearby remains the preferred short-range path.

Current Android phones cannot be assumed to contain an 802.11ah radio. The first supported deployment therefore uses a TorX HaLow gateway or bridge. The phone sees a normal local Wi-Fi or USB Ethernet network; the gateway carries the long hop over HaLow.

## Implemented foundation

- `WIFI_HALOW` transport type with routing priority after Nearby
- DNS-SD service contract for automatic gateway discovery
- Android DNS-SD discovery implementation
- network-specific TCP socket support when Android exposes the service network
- versioned binary protocol supporting payloads up to 1 MiB
- strict framing bounds, stream IDs, and CRC32
- authenticated gateway capability handshake contract
- persistent gateway public-key fingerprint trust model
- automatic startup discovery, incoming packet integration, and Settings pairing/status surface
- explicit HaLow and mesh destination routing
- gateway hardware topology and service requirements

## Required to complete Phase 11

- select and obtain certified HaLow hardware for the target countries
- build the OpenWrt/Linux gateway daemon and board image
- connect incoming gateway frames to the TorX receive pipeline
- add resumable media streams, flow control, and link-quality scheduling
- verify two phones exchange text, images, voice notes, and files over a physical HaLow link with internet disabled
- measure range, throughput, latency, power, reconnect behavior, and obstruction performance
