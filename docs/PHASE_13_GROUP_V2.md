# Phase 13 — Group Messaging V2

Status: design and migration decision complete; production migration intentionally gated

## Decision

TorX Group V2 will use MLS 1.0 as standardized in RFC 9420 with the mandatory X25519, ChaCha20-Poly1305, SHA-256, and Ed25519 ciphersuite. TorX will integrate a maintained MLS implementation rather than implement TreeKEM or MLS cryptography in Kotlin.

The current pairwise Double Ratchet group protocol remains the production protocol until every `GroupV2MigrationGate` requirement passes. Pairwise groups are now capped at 64 members so the current O(n) sender work cannot be presented as scalable.

## Alternatives evaluated

| Design | Offline/asynchronous | Send cost | Member removal | Forward secrecy | Post-compromise security | Decision |
|---|---|---:|---|---|---|---|
| Existing pairwise fan-out | Strong | O(n) per message | Strong after fan-out completes | Pairwise ratchets | Pairwise ratchets | Keep for small groups during migration |
| Sender keys | Strong | O(1) ciphertext, O(n) key setup | Requires full key redistribution | Requires chain deletion | Weak until sender-key rotation | Rejected as Group V2 core |
| Custom ratcheting tree | Possible | O(log n) | Possible | Possible | Possible | Rejected because custom cryptographic protocol risk |
| MLS RFC 9420 | Designed for it | O(log n) group updates | Commit removes member before new application data | Required by protocol | Required across epochs | Selected |
| Relay-assisted distribution | Delivery mechanism only | One upload | Cannot enforce cryptographic removal | Depends on key protocol | Depends on key protocol | Use beneath MLS, never instead of MLS |

## TorX MLS architecture

1. Bind each MLS credential to the existing verified TorX identity and device identity.
2. Carry KeyPackages, Welcome, Proposal, Commit, and application messages as opaque TorX payloads.
3. Use pairwise Double Ratchet channels to bootstrap invitations and recovery requests.
4. Use Nearby, mesh, LoRa, HaLow, and TorX gateways as an untrusted MLS delivery service.
5. Persist MLS state and the corresponding outbox commit atomically in SQLCipher.
6. Never send application data while a removal proposal is uncommitted.
7. Delete consumed message secrets and obsolete epoch secrets after the recovery window permitted by RFC 9420.
8. Reject lower protocol generations after a group commits to MLS V2.

## Threat model

The delivery network may observe, delay, drop, replay, reorder, duplicate, fork, or inject packets. It may be fully malicious. Group members may be compromised or malicious. A removed member may retain every secret it learned before removal. Device storage can be copied while unlocked.

The design requires confidentiality and sender authentication for application messages, authenticated membership, forward secrecy, post-compromise security after honest updates, deterministic rejection of stale epochs, fork detection, bounded parsing, crash-safe state, and explicit credential validation.

MLS does not hide group membership or traffic patterns from every delivery design automatically. TorX relay subscription identifiers must be random and unlinkable, and route-level metadata needs a separate review.

## Migration plan

1. Integrate a pinned, reviewed OpenMLS release through a narrow Rust/JNI boundary.
2. Implement the required ciphersuite only initially.
3. Add encrypted transactional storage using the existing SQLCipher database and Keystore authority.
4. Pass RFC vectors and cross-implementation interoperability tests.
5. Pass offline, delayed, duplicate, reordering, fork, crash, add, remove, and update simulations.
6. Run an independent security review of JNI, credential binding, storage, and delivery logic.
7. Create new MLS groups first. Existing pairwise groups migrate only through an authenticated unanimous upgrade commit or remain V1.
8. Remove the 64-member cap only for groups confirmed as MLS V2.

## Release gate

`GroupV2MigrationGate` is fail closed and requires evidence for implementation, reproducible Android builds, identity binding, atomic persistence, recovery, interoperability, membership operations, hostile delivery, downgrade resistance, metadata review, independent review, and physical multi-device acceptance.

No UI or remote message can enable MLS V2 before this gate passes.
