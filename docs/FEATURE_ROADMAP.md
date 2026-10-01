# Feature roadmap implementation ledger

Source: the user's October 1 developer roadmap (phases 0–30). The existing
messaging architecture remains authoritative: UI → feature service → atomic
Room/ratchet/outbox commit → agent → transport router. Realtime media retains its
dedicated media path. Local features do not send control envelopes.

## Current implementation

| Phase | State and evidence |
| --- | --- |
| 0 Architecture | Existing feature/transport boundaries retained; `verify_feature_foundation.py` guards the feature imports. |
| 1 Compatibility | Durable capability advertisements use the legacy PROFILE_UPDATE prefix, stored per authenticated relationship. Only registered receive features are advertised. Unknown message names survive decoding and are acknowledged after ratchet/sequence/deduplication commit; visible messages display an upgrade placeholder. Duplicate/next-message protocol test added. |
| 2 Profiles | Prior editor, shared avatar UI, version checks and bounded inline photo synchronization remain. Separate ContactProfile storage and chunked avatar descriptors are still pending. |
| 3 Productivity | Room drafts with 500 ms debounce and reply restoration; atomic draft removal on send; local stars/global starred screen; multi-select copy/star/forward/delete; forwarding creates fresh IDs, encryption and media keys through the existing services. Stable per-operation IDs skip already committed forwards on retry. |
| 4 Search | Room FTS4 body/file-name index with database triggers and legacy backfill. Global/chat search with All/Media/Links/Documents/Voice filters, results and jump to message. Hidden/deleted results excluded. |
| 5 Media browser | Media grid, Links/Docs/Voice tabs; local viewers and download actions. Schema 17 stores URL/host metadata locally, with durable indexing jobs and edit/deletion cleanup; no remote preview fetching. |
| 6 Disappearing | Sender timer, absolute authenticated expiry, receiver tombstones and durable file cleanup are being integrated. Capability checks block unsupported recipients. Combined validation pending. |
| 7–16 | View-once, shared pins, mentions/@all, polls, expanded group permissions, invite tokens/approval, member tags and events remain pending. |
| 17 Scheduling | Encrypted Room intent, WorkManager recovery, generation checks, stable logical IDs, lock deferral and edit/cancel UI implemented. Combined validation pending. |
| 18 Rich text | Safe Compose spans for bold/italic/strike/code/quote/list. Original text stays in Room and on the wire; no HTML interpretation or remote content fetching. |
| 19–21 | Stickers, contact cards and location are pending. |
| 22 Nicknames | Local nickname editor in contact info; aliases update the conversation list, direct chat header, group sender names and active call display without changing the authenticated peer profile. Room restart test covers alias persistence. |
| 23 Chat appearance | Local System/Ocean/Forest colors, plain/warm/cool wallpaper colors and rounded/square bubbles; working save dialog for direct/group chats. Room restart/cascade tests and UI save test added. |
| 24–26 | Live message info, current direct-peer connection dashboard and offline banner implemented. Missing historical evidence is labelled unrecorded. Group per-member dashboard diagnostics and combined validation remain pending. |
| 27–30 | Group calls, screen sharing, topics and communities are pending and retain the roadmap's preceding release gates. |

## Progress accounting

Eight phases were implemented and validated locally before the current integration:
0, 1, 3, 4, 5, 18, 22 and 23. That baseline passed 358 JVM tests, debug/test APK
builds and lint. Phases 6, 17 and 24–26 are now being integrated and have not yet
passed the combined run. Phase 2 remains partial. This is phase counting, not an
effort estimate or release-readiness percentage. No new roadmap device tests have
run, and the two-phone compatibility gates remain open.

## Release gates

Do not mark the entire roadmap complete based on compilation or local UI work.
Each feature needs codec/validation unit tests, real Room migration/restart tests,
protocol duplicate/retry/order/old-peer checks where relevant, and two-phone
Wi-Fi/cellular/background/restart/network-switch checks.

Schema 15→16 adds local feature/capability tables and an encrypted-database FTS
index without altering legacy keys, ratchets, relationships or delivery queues.
The SQL preservation/index tests and Android Room tests use the actual migration.
New synced features must check advertised capabilities before transmission.

Schema 16→17 adds link metadata and indexing jobs. Schema 17→18 adds authenticated
message expiry, local timers, late-media tombstones and persistent file cleanup.
Schema 18→19 adds encrypted scheduled intents. The actual migration SQL is checked
for legacy-column preservation, cascades and durable recovery metadata in CI.

Scheduling uses AndroidX WorkManager 2.12.0 (minimum SDK 24; app minimum SDK 26).
WorkManager stores only schedule identifiers and generations; message text stays
in the app's encrypted Room database. Background execution timing is approximate.
