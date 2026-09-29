# Phase 3: Real Tor Transport

## Implemented architecture

TorX One embeds Guardian Project's Tor Android runtime and creates a persistent v3 onion service for each installation. The Tor layer only moves opaque bytes produced by the existing TorX protocol. Identity signatures, relationship secrets, Double Ratchet encryption, replay checks, durable outbox handling, acknowledgements, edits, reactions, deletes, receipts, and media control remain above transport.

The implementation lives in `transport/tor/`:

- `TorController` owns the embedded Tor service lifecycle and publishes connection state.
- `TorBootstrapManager` starts Tor during asynchronous application initialization.
- `OnionEndpointManager` maps the onion service to a loopback-only framed receiver.
- `TorRouteManager` stores queue-to-onion routes in encrypted preferences.
- `TorTransport` sends length-prefixed, already encrypted protocol envelopes through Tor SOCKS.
- `TorHealthMonitor` exposes Tor readiness without coupling features to Tor internals.
- `TransportRouter` honors an explicit Tor route before local Nearby discovery.

## Wire boundary

Each Tor connection contains one frame: a four-byte signed big-endian payload length followed by one TorX transport envelope. Empty frames, oversized frames, truncated frames, and trailing bytes are rejected before dispatch. The maximum is the existing `ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES` value.

Only v3 onion addresses are accepted. Hostname resolution is delegated to Tor by connecting to an unresolved `.onion` hostname through the loopback SOCKS proxy.

## Dependency compatibility

The repository includes the compatible `tor-android-0.4.8.12.aar` under `android/app/libs`. Newer published releases require compile SDK 37, while this project is on the supported API 35 / Android Gradle Plugin 8.7.3 toolchain. The local artifact prevents builds from silently selecting an incompatible release.

## Acceptance gate

Automated completion requires all JVM unit tests and `assembleDebug` to pass. Runtime completion additionally requires two physical Android devices on separate networks to bootstrap Tor, publish their onion services, exchange authenticated route information, and complete bidirectional text, acknowledgement, reaction, edit, delete, receipt, and media-control flows. Record that evidence before marking the master-plan runtime exit gate complete.
