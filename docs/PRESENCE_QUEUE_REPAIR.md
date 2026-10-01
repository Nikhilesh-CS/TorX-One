# Presence and pending-queue repair

## Confirmed defects and observations

- PresenceService initialized an unobserved offline peer with the local clock and
  replaced that time when Nearby disconnected. Opening a chat could therefore
  show "last seen just now" without receiving any peer activity.
- Foreground presence publishing had no runtime caller. Transport readiness was
  being used as a substitute for actual app activity.
- Connection diagnostics counted all outbox deliveries, including read receipts
  and other controls, as pending messages. A configured Tor route was labelled
  "Tor ready" even without proof of recipient acceptance.
- The outbox scheduler awaited all destinations before reading newly queued
  work. A slow connection to one peer could postpone work for another peer.
- Concurrent chat-open/read observers could independently consume the same unread
  boundary and create duplicate read receipts.
- Retained Realme logs contained Tor socket failures, repeated attempts and
  192-second retry delays. A local ready state does not establish remote reachability.
  Current receivers ACK durable read receipts; the old sender comment saying
  otherwise was inaccurate.

## Changes

Unobserved presence starts UNKNOWN without a fabricated time. Authenticated
foreground/background presence updates provide the timestamp. Foreground use is
announced only while the app is unlocked, with independent periodic sends per
contact; stale online state expires. Saved timestamps are encrypted with the
existing KeyProtector and bound to their relationship. Delayed/future timestamps
and delayed cache loads cannot replace newer confirmed state. The UI shows a dated
time or "last seen unavailable".

Presence packets append a last-seen privacy flag. New receivers accept the old
packet format and clear visible/persisted last seen when the flag is hidden.
Older receivers ignore the extra flag, so update both phones before relying on
this display behavior. Activity observed by a peer cannot be retroactively erased.

The pending-message count is now a SQL count of distinct outgoing messages.
Background controls are counted separately; fanout copies do not masquerade as
additional messages or controls. Tor status distinguishes local connection from
unconfirmed peer delivery.

The connection panel offers Pause sending / Resume sending. The preference
survives restart and the agent checks it before each attempt. Ciphertext and
allocated sequences remain intact; an already-started send may finish. ACKs with
no conversation context continue to work. Pausing a sequenced item must block
later items on that same relationship rather than create a protocol gap.

Each destination has one delivery worker, so unrelated peers do not wait on an
offline destination. Manual Retry is scoped to the selected conversation and
does not reset active transmissions. New read receipts consume the unread boundary
under a lock/transaction and do not wait for a delivery ACK after transport
acceptance. Existing queues are preserved. Tor connect attempts now have a
45-second socket deadline; message retry/deduplication retains the same ciphertext.

## Verification

The presence-repair debug candidate built successfully with all 403 JVM tests passing,
zero failures/errors/skips. The final debug lint command also completed successfully.
The subsequent Phase 21/22 candidate adds schema 20 media-cleanup fixes and
release gates; see `PHASE_21_RELEASE_CANDIDATE.md`. The 32-device-test result below
belongs to this earlier presence candidate and does not validate those new fixes.
Added regressions cover truthful/cached/stale/private
presence, independent heartbeats and delivery workers, pause/resume and sequence
preservation, accurate connection labels, and connect deadlines.

The rebuilt Android test APK adds pending-message/control SQL, pause/resume UI,
and DataStore persistence checks. After Realme reconnected, the app and test APK
were installed with `adb install -r`, preserving app data. All 32 Android tests
passed on RMX5070 / API 36. The installed APK hash matched the candidate:
`8293930b186a5f74e141572c65ce8825da844327496b0d317d0a9211f9fe64d3`.
Sanitized evidence is under
`android/build/device-beta/20261001T150217.838225Z/`.
Live two-phone delivery and presence exchange remain unverified; both phones
must have the update and a reachable authenticated path.
The user also confirmed on the installed app that the connection panel offers
Pause sending and the last-seen display looks correct. This is a local UI check,
not proof of remote delivery or both phones' presence exchange.

Build logs: `android/build/presence-queue-fix-device-candidate.log` and
`android/build/presence-queue-fix-lint.log`. Candidate APK:
`android/app/build/outputs/apk/debug/app-debug.apk`.
