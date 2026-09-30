# Phase 3: Tor peer transport

## Current architecture

Tor Android 0.4.9.13 is resolved from Guardian Project Maven. Compile SDK is 37.1,
with target SDK 36 and minimum SDK 26. Readiness requires bootstrap 100, SOCKS,
and a valid published v3 onion. Each installation serves virtual port 17654.

Signed invites and signed bootstrap payloads provide the peer onion endpoint.
`PeerTorEndpointEntity` stores it against the relationship in encrypted Room
(schema 14), in the same pairing transaction. Startup imports existing queue
routes once; signed Room endpoints take precedence, and imported preferences
are removed. Legacy imports retain a `LEGACY_ROUTE` provenance marker because
older versions also learned routes from unsigned transport headers.
New stream headers never update trusted endpoints.

`TransportDestination.relationshipId` locates the peer endpoint; `address` is
still the protocol queue. Established sends resolve the Room-backed cache.
Invite preferences remain temporary bootstrap capabilities and are removed on
confirmation. The cache is restored before starting the outbox agent.

`TorPeerConnectionManager` keeps up to 32 persistent outbound SOCKS streams,
serializes each peer's writes, bounds connect/write deadlines, evicts idle or
changed endpoints on reuse, and closes pending/established streams on network
or Tor readiness loss. Failed writes return to the durable agent retry engine;
transport acceptance alone does not prove delivery.

## Framing and compatibility

Each stream starts with one Java modified-UTF return-onion string, followed by
repeated big-endian length plus opaque payload frames. Each frame is bounded by
`ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES`. Clean EOF between frames ends the
stream; invalid lengths, truncated frames and rejected payloads close it.
Inbound streams are capped at 32 and have a 120-second read/idle timeout.
The return onion is untrusted framing metadata, never relationship identity.
Identity binding, queue HMAC, Double Ratchet, replay protection, dispatch and
application ACKs remain above this layer.

The new receiver accepts legacy single-frame senders. Legacy receivers require
EOF and cannot receive persistent streams correctly: upgrade both phones.
Debug PING/PONG remains separate and does not alter trusted endpoints.

## Bootstrap confirmation

The bootstrap logical ID is the signed invite ID; its independent delivery ID
is retained for exact ACK matching. It expects authenticated confirmation.
Migration 13 to 14 repairs these fields on existing pending bootstrap rows.
A consumed invite retry is accepted only from the already bound signing identity;
it sends a new authenticated ACK without resetting the relationship or ratchet.
Invite retries are serialized to prevent concurrent double initialization.
Older initiators with a discarded bootstrap rebuild a signed public handshake
from the persisted identity and ratchet public key when Tor becomes ready. If
the ratchet advanced, the responder already exists and only confirms it. This
never replaces keys or resets a session. Missing session or identity data is
left for explicit recovery rather than generating a new relationship.

## Validation

Build, JVM regression tests, lint and migration SQL checks cover implementation.
Physical runtime proof remains separate: run `android/tools/verify_tor_two_devices.py`,
then verify encrypted text and exact ACKs in both directions, repeated sends on
one stream, process restart, offline recovery and Wi-Fi/cellular switching.
Calls keep their existing signaling and media policy until that path is proven.
