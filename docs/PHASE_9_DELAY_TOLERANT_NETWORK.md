# Phase 9 — Store-Carry-Forward / Delay-Tolerant Networking

## Status

Complete at the automated implementation gate.

## Implemented foundation

- Added a SQLCipher-backed durable relay packet queue.
- Added Room schema migration 11 to 12 for relay packets and delivery receipts.
- Stored packets contain only opaque end-to-end encrypted mesh frames and routing metadata.
- TTL is decremented before durable storage and expired packets are pruned.
- Packet IDs and durable receipts prevent duplicate re-storage after forwarding.
- Added global packet and byte limits.
- Added per-source packet and byte quotas.
- Added per-ingress-peer packet and byte rate limits with cooldown blocking.
- Added explicit authenticated-peer relay authorization.
- Added priority-ordered retry selection.
- Added bounded exponential retry and maximum-attempt handling.
- Added low-priority eviction when the global relay store is full.
- Added split-horizon retry so a stored packet is not returned to its ingress peer.
- Added a background retry and pruning worker in `TorXOneApplication`.
- Added a contact-later test: the relay stores an opaque packet without a route, learns a route later, forwards it byte-identically, records a receipt, and rejects replay.

## Completed integration

- Added explicit `TXCA` hop custody acknowledgement frames.
- A stored packet remains durable after transmission until the exact authenticated next hop acknowledges custody.
- Acknowledgements bind packet ID and expiry and are rejected from any other peer.
- Lost acknowledgements trigger bounded retransmission after a custody timeout.
- Duplicate downstream delivery sends custody acknowledgement again, allowing recovery when the original acknowledgement was lost.
- Newly learned direct or propagated routes wake the durable retry queue immediately.
- A periodic worker remains as a recovery backstop and prunes expired packets and receipts.
- SQLCipher database version 12 stores relay packets, retry state, expected next hop, and replay receipts across process restarts.

## Verification

On 2026-09-29:

- `:app:testDebugUnitTest` passed: 262 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` passed.
- Tests cover delayed contact, opaque payload preservation, TTL reduction, durable retry, exact-peer custody acknowledgement, acknowledgement replay suppression, quotas, expiry, rate limiting, congestion, and anti-loop routing.

Physical acceptance still requires devices that encounter each other at different times: A hands a message to B, B later meets C, and C later reaches D. The final ciphertext must arrive once, while relay databases expose no message plaintext.
