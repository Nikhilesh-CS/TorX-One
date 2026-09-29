# Phase 8 — Offline Multi-Hop Mesh

## Status

Complete at the automated implementation gate.

## Implemented foundation

- Added a versioned TorX network packet carrying opaque end-to-end ciphertext.
- Added explicit source, destination, packet ID, priority, creation time, expiry, and hop TTL fields.
- Added strict packet size and field limits.
- Added an authenticated-route table with deterministic single-next-hop selection.
- Added route sequence handling, expiry, peer removal, alternate routes, and split-horizon exclusion.
- Added a bounded duplicate cache for replay and loop suppression.
- Added opt-in relay permissions scoped to authenticated peer node IDs.
- Added count and byte congestion limits.
- Added priority ordering for relay work.
- Added a forwarding engine that decrements TTL and preserves payload bytes exactly.
- Added a four-node forwarding test proving that forwarding chooses one next hop and does not flood.

## Completed integration

- Mesh node IDs are bound to authenticated TorX contact identities and Ed25519 signing keys.
- Origin-signed route announcements are exchanged only across authenticated Nearby relationships.
- Route signatures remain verifiable when an intermediate node propagates an announcement.
- Announcement lifetime, sequence replay checks, per-peer rate limits, and split horizon are enforced.
- Nearby peer readiness and disconnection update the peer directory, relay permissions, and routing table.
- Direct routes and local announcements refresh before expiry.
- `MeshTransport` is registered as the `RELAY` candidate in `TransportRouter`.
- Queue destinations resolve to mesh node IDs, allowing relay selection after direct Nearby and Tor routes fail.
- Incoming mesh packets retain their authenticated ingress relationship.
- Locally addressed opaque ciphertext enters the normal `IncomingDispatcher`; relays never decrypt it.
- A complete A to B to C to D simulation verifies two relay hops and byte-identical delivery only at D.

## Verification

On 2026-09-29:

- `:app:testDebugUnitTest` passed: 259 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` passed.
- Tests cover strict framing, TTL, expiry, duplicate suppression, relay permission, congestion bounds, priority, route selection, split horizon, route expiry, signed announcements, tamper rejection, announcement rate limits, relay transport selection, and multi-hop delivery.

Physical-device acceptance still requires four Android devices arranged so A cannot directly reach D, followed by a bidirectional encrypted message test and relay-disconnect rerouting test. JVM tests cannot reproduce real Nearby radio topology.
