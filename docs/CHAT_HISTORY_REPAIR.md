# Chat history disappearing during connection/profile sync

## Confirmed cause

`ConversationDao.upsert()` used `INSERT OR REPLACE`. SQLite deletes the conflicting
conversation row before inserting its replacement. The message foreign key uses
`ON DELETE CASCADE`, so this also deleted message history and dependent local state.

`IncomingDispatcher.handleProfileUpdate()` calls this method when an authenticated
peer profile arrives. Profile sync runs when connections are restored. Group title
and avatar updates use the same DAO, so they had the same destructive behavior.
This is separate from an explicitly selected disappearing-message timer.

## Repair

The shared conversation write method now uses Room `@Upsert`, which updates an
existing parent without deleting it. This covers profile updates, group metadata,
pairing and chat creation through the same method. No database schema change or
data clearing is required. Explicit conversation deletion still cascades.

The Tor socket connect and write limits are also restored to 120 seconds. The
actual socket connector now receives the configured connect budget, matching its
deadline. An immediate SOCKS error can still fail before the maximum timeout;
these limits do not by themselves establish recipient delivery.

## Regression evidence

- `android/tools/test_conversation_preservation.py` reproduces the destructive
  replacement with the actual schema 20 SQL and guards both conversation DAO
  methods against replacing parent rows. The complete Python suite passes 41 tests.
- `FeatureDatabaseTest.conversationMetadataUpdatesPreserveHistoryAndLocalStateAcrossReopen`
  exercises repeated real Room writes and database reopening. It checks incoming
  and outgoing messages, pending delivery, drafts, stars, appearance and timer
  retention, followed by explicit deletion. It passed in the complete 38-test
  Android suite on both Realme RMX5070/API 36 and Vivo V2059/API 33.
- `TorPersistentStreamTest` checks the default 120-second socket connection budget
  and propagation of a custom budget. The history repair candidate passed all 408
  JVM tests, all 41 Python tests, and all 38 Android tests on each phone. The debug
  build completed in 3m 49s and was installed with data-preserving updates.

Current debug APK SHA-256:
`a24287c81c7c18b4807088d3fbb146ac5c0bf8fc32b7ac280046891a907f752c`.
The installed APK hash matched on both phones. Device evidence is under
`android/build/device-beta/20261001T170800.765279Z/` (Realme) and
`android/build/device-beta/20261001T170800.307210Z/` (Vivo). Fresh paired delivery,
authenticated ACK and visible retention after reconnect still require confirmation.

Existing rows already deleted by replacement cannot be restored by changing the
write method. A processed-envelope record or delivery ACK contains no message body
from which to reconstruct that history. Do not reset contacts, ratchets or delivery
sequences to try to recover it.
