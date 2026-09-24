# Messaging recovery plan

Scope: one-to-one delivery first; no UI redesign or new features. Implementation is
not acceptance. Do not publish a release based on compilation alone.

## First fixes

- Allocate the outgoing sequence and persist its outbox row in one Room transaction.
- Do not substitute a receive-chain hash for an outgoing-chain hash.
- Fail closed on unavailable session encryption instead of falling back to legacy.
- Correlate relay acceptance with the exact sender request ID, on client and server.

## Wire protocol and connection establishment

Before enabling ProtocolEnvelope transmission, implement a signed, versioned
bootstrap exchanged over existing direct transports. Persist the agreed connection
ID and crossed send/receive queues. Resolve simultaneous initiation deterministically,
retry idempotently, and reject replay or unauthorized replacement of an active
connection. Relay-only first contact requires a separately established invitation
or bootstrap address; independently generated queue IDs cannot deliver it.

Define one envelope encoding shared by producer and consumer. Bind connection ID,
queue ID, sequence, previous hash, message type and ciphertext to authentication.
The current consumer reconstructs a session JSON object from bare ciphertext,
whereas the outbox stores a complete session JSON frame. Preserve required session
fields, including message ID, sender encryption key and inner message type.
Never enable wrapping alone. Authenticate before buffering/committing sequence.

Persist receive gaps and serialize delivery per connection. Duplicate delivery must
re-emit authenticated acknowledgement after confirming prior durable acceptance.
Keep outgoing chain heads independently of prunable outbox rows. Define expired or
failed sequence recovery so a missing message cannot stall the connection forever.

The current relay keeps queues in a JavaScript Map and acknowledges incoming frames
before application persistence. Durable offline acceptance requires server storage,
retention until recipient acknowledgement, and an application completion callback;
request-ID correlation alone does not provide restart-safe offline delivery.

## Application boundary

Text-send ViewModel calls now target ChatMessageService. Extract authenticated
receipt handlers next, then group/call/media handlers, keeping one entry point.
Remove legacy send/receive paths through an explicit protocol compatibility policy.
Review relay queue credentials separately: current challenge authentication still
reveals the long-term identity to the relay.

## Verification gates

1. Unit/integration: transaction rollback, concurrent sequence allocation, exact ACK
   correlation, session failure without downgrade, authenticated envelope roundtrip,
   bootstrap collisions/retries, malformed frames, replay, reordered/gapped delivery.
2. Build debug APK and run the regression suite.
3. Two phones: 100 messages, zero lost, duplicate or misordered messages.
4. Repeat with airplane mode, process kill/restart, Tor/Nearby reconnect and relay
   offline/online; include lost acknowledgements and relay/server restart.
5. Only after direct text passes, validate offline relay, groups, media and calls.

The client ACK-correlation change requires the matching relay server update.
No server deployment or device acceptance is implied by local source changes.
