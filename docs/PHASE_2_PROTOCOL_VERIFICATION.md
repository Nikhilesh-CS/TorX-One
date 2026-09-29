# Phase 2: Protocol Verification and Test Foundation

Status: **In progress**  
Phase 1 is intentionally deferred by product direction.

## Verified baseline

- JVM unit suite: 234 tests across protocol codecs, cryptography, ratchet state,
  direct chat, groups, media, calls, Nearby routing, crash recovery, migrations,
  invite consumption, notifications, and profile policy.
- Durable application sequences are allocated through a shared
  `RelationshipSendCoordinator` and remain monotonic across concurrent feature
  sends.
- Receiver sequence reservation, commit, and release behavior is covered.
- Exact delivery acknowledgements remove one delivery, preserving other group
  recipients.
- Media tests cover chunk geometry, out-of-order delivery, duplicate chunks,
  missing-chunk recovery, integrity failure, startup recovery, simultaneous
  transfers, and chat/media concurrency.
- Nearby tests cover strict wire parsing, keyed challenge-bound capability
  hints, authenticated routing, reconnect recovery, and multi-relationship
  isolation.
- Group tests cover identity-safe wire payloads, invite handling, membership,
  epoch changes, fan-out, acknowledgements, edits, reactions, and removal.

## Protocol invariants now enforced

1. Malformed wire frames fail closed.
2. Queue authentication secrets are 32 bytes.
3. Established senders must match the identity bound to the relationship.
4. Durable sequenced sends cannot overtake an earlier sequence.
5. A coordinator reads the live connection sequence before every allocation;
   stale service references cannot reuse sequence 1.
6. Direct media chunks must match the authenticated relationship and the
   conversation recorded by the descriptor.
7. Group control envelopes must bind group ID, conversation ID, and epoch
   metadata.
8. Placeholder founder keys never grant founder status.

## Required before Phase 2 is complete

- Add an `androidTest` source set and device-backed database, process-death,
  keystore, lifecycle, and migration tests.
- Add property tests for encode/decode round trips and state-machine invariants.
- Add fuzz and malformed-input corpora for transport, secure-envelope, invite,
  group, media, and call codecs.
- Add deterministic loss, duplication, reordering, delay, reconnect, and ACK
  loss simulation across every durable message type.
- Add schema migration fixtures for every supported database version.
- Run the instrumentation matrix on supported Android API levels and record
  device evidence.

Phase 2 completion requires both the JVM suite and the device-backed matrix to
pass. A green JVM suite alone is the current foundation, not the final Phase 2
gate.
