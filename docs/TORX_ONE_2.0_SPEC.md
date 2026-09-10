# TorX One 2.0: Technical Specifications

This document defines the foundational engineering specifications for the SimpleX-inspired TorX One 2.0 architecture:
- **Part 1: Concrete Protocol Specification**
- **Part 2: Room Schema Migration Specification (v26 → v27)**
- **Part 3: State-Machine Specification**

---

## Part 1: Concrete Protocol Specification

### 1.1 The 7-Layer Architectural Stack

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. Application Layer                                                   │
│    • Chat messages, group messages, voice/video calls, media transfer  │
│    • Unaware of connections, queues, encryption ratchets, or transports│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Application Payload (JSON / Binary)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. Connection Layer                                                    │
│    • Identity ≠ Connection ≠ Queue ≠ Transport Address                 │
│    • Pairwise connection state (connectionId)                          │
│    • Unidirectional queues (sendQueueId: A→B, recvQueueId: B→A)        │
│    • 6-stage queue rotation state machine                              │
│    • Sequence allocation & deferred cursor advancement                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Connection Context & Sequence
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. Agent / Delivery Layer                                              │
│    • Room-backed persistent outbox & inbox queues                      │
│    • Monotonic delivery state machine (QUEUED → DELIVERED → READ)      │
│    • Deduplication, exponential backoff retries, pruning               │
│    • Offline concept: offline is normal, never a fatal error           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Delivery Envelope
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. Protocol Layer                                                      │
│    • Formal wire envelope schema & serialization                       │
│    • Typed events: MESSAGE, ACK, READ, CALL_*, GROUP_*, QUEUE_ROTATE_* │
│    • Envelope hash chain computation H(prevHash || header || cipher)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Wire Envelope
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 5. Crypto Layer                                                        │
│    • Double Ratchet session isolation (SessionManager / SessionRatchet)│
│    • AES-GCM-256 with Authenticated Associated Data (AAD)              │
│    • Ed25519 detached envelope signatures                              │
│    • Replay protection & skipped key store                             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Encrypted Opaque Payload
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 6. Transport Router                                                    │
│    • Multi-transport priority selection & dynamic failover             │
│    • Transports know NOTHING about messages; deliver opaque bytes only │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Priority-Ordered Routing
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 7. Physical Transports                                                 │
│    • Nearby Direct (BLE/Wi-Fi P2P)                                     │
│    • Wi-Fi Direct                                                      │
│    • Tor Hidden Services (.onion SOCKS5)                               │
│    • Opaque Queue-Addressed Offline Relay (WebSocket)                  │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 1.2 Protocol Envelope Wire Schema

Every application event is serialized into a standard `ProtocolEnvelope`:

```json
{
  "version": 2,
  "connectionId": "550e8400-e29b-41d4-a716-446655440000",
  "queueId": "8f3a1c20-7b9d-4e51-9c84-112233445566",
  "sequenceNumber": 42,
  "previousHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "timestamp": 1788960000000,
  "messageType": "MESSAGE",
  "crypto": {
    "sessionId": "sess-998877",
    "msgNum": 12,
    "ratchetPub": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "ciphertext": "<base64_encoded_aes_gcm_ciphertext>",
    "iv": "<base64_encoded_12_byte_iv>",
    "signature": "<hex_encoded_64_byte_ed25519_detached_sig>"
  }
}
```

#### Protocol Envelope Fields
| Field | Type | Required | Description |
|---|---|---|---|
| `version` | `Int` | Yes | Protocol version integer (`2` for TorX One 2.0). |
| `connectionId` | `String` | Yes | Independent pairwise connection UUID. |
| `queueId` | `String` | Yes | Active unidirectional queue UUID (`sendQueueId`). |
| `sequenceNumber`| `Long` | Yes | Monotonic strictly increasing sequence (1-indexed). |
| `previousHash` | `String?` | No | SHA-256 hex string of the previous envelope in the queue. Null for sequence 1. |
| `timestamp` | `Long` | Yes | Epoch milliseconds when envelope was constructed. |
| `messageType` | `String` | Yes | Protocol event discriminator (see section 1.3). |
| `crypto` | `Object` | Yes | Cryptographic container (opaque to transport). |

---

### 1.3 Protocol Event Discriminators (`messageType`)

All features flow as first-class protocol types. Zero bypasses:

1. **`MESSAGE`**: 1:1 user chat message (text, media preview, reply metadata).
2. **`ACK`**: Authenticated delivery receipt confirming peer decrypted & committed to SQLite.
3. **`READ`**: Authenticated read receipt confirming peer opened message in UI.
4. **`REACTION`**: Emoji reaction modification (`add`, `remove`, `set`).
5. **`CALL_OFFER`**: WebRTC SDP Offer signaling frame.
6. **`CALL_ANSWER`**: WebRTC SDP Answer signaling frame.
7. **`ICE_CANDIDATE`**: WebRTC ICE candidate signaling trickle.
8. **`CALL_ACK`**: WebRTC signaling handshake confirmation.
9. **`CALL_END`**: WebRTC call termination / hangup frame.
10. **`GROUP_EVENT`**: Group messaging, key distribution, or member state change.
11. **`MEDIA_CONTROL`**: Chunked media transfer offer, chunk ack, resume, or cancellation.
12. **`QUEUE_ROTATE_PROPOSE`**: Request to rotate to a new unidirectional queue ID.
13. **`QUEUE_ROTATE_ACK`**: Cryptographic acknowledgment activating the new queue ID.

---

### 1.4 Cryptographic Message Hash Chain

The hash chain provides a tamper-evident, cryptographic ordering signal:

$$H_0 = \text{null}$$
$$H_N = \text{SHA256}(H_{N-1} \parallel \text{connectionId} \parallel \text{queueId} \parallel \text{sequenceNumber} \parallel \text{messageType} \parallel \text{ciphertext})$$

#### Verification & Ordering Semantics
1. **Integrity Signal**: If $H_{N-1}$ matches local record, continuity is cryptographically verified.
2. **Non-Fatal Gaps**: If $H_{N-1}$ does not match or a sequence gap is detected:
   - The delivery engine records a `SEQUENCE_GAP` event.
   - In-flight or retried messages arriving out of order are buffered.
   - Normal retransmissions/offline arrivals are processed without crashing or resetting the Double Ratchet session.

---

## Part 2: Room Schema Migration Specification (Migration 26 → 27)

### 2.1 Schema Alterations

```sql
-- =========================================================================
-- TorX One 2.0 Room Migration: Version 26 -> 27
-- =========================================================================

-- 1. Alter connection_queue table: Remove 1:1 identity constraint
DROP INDEX IF EXISTS `index_connection_queue_remotePartyKey`;
CREATE INDEX IF NOT EXISTS `index_connection_queue_remotePartyKey` ON `connection_queue` (`remotePartyKey`);

-- 2. Add 6-stage queue rotation & hash chain tracking columns
ALTER TABLE `connection_queue` ADD COLUMN `pendingSendQueueId` TEXT;
ALTER TABLE `connection_queue` ADD COLUMN `pendingRecvQueueId` TEXT;
ALTER TABLE `connection_queue` ADD COLUMN `rotationState` TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE `connection_queue` ADD COLUMN `rotationGracePeriodUntil` INTEGER;
ALTER TABLE `connection_queue` ADD COLUMN `lastCommittedHash` TEXT;

-- 3. Add lookup indices on queue identifiers
CREATE INDEX IF NOT EXISTS `index_connection_queue_sendQueueId` ON `connection_queue` (`sendQueueId`);
CREATE INDEX IF NOT EXISTS `index_connection_queue_recvQueueId` ON `connection_queue` (`recvQueueId`);

-- 4. Alter delivery_queue table: Add receipt timestamps and envelope hash
ALTER TABLE `delivery_queue` ADD COLUMN `deliveredAt` INTEGER;
ALTER TABLE `delivery_queue` ADD COLUMN `readAt` INTEGER;
ALTER TABLE `delivery_queue` ADD COLUMN `envelopeHash` TEXT;
```

---

### 2.2 Updated Room Entity Definitions

#### `ConnectionQueueEntity.kt`
```kotlin
@Entity(
    tableName = "connection_queue",
    indices = [
        Index(value = ["connectionId"], unique = true),
        Index(value = ["remotePartyKey"]),       // Non-unique: allows multiple/renegotiated connections
        Index(value = ["sendQueueId"]),
        Index(value = ["recvQueueId"]),
        Index(value = ["state"])
    ]
)
data class ConnectionQueueEntity(
    @PrimaryKey val connectionId: String,       // Independent pairwise connection UUID
    val localPartyKey: String,                  // Our Ed25519 signing public key hex
    val remotePartyKey: String,                 // Remote peer's Ed25519 signing public key hex
    val sendQueueId: String,                    // Active outbound queue identifier (A→B)
    val recvQueueId: String,                    // Active inbound queue identifier (B→A)
    val pendingSendQueueId: String? = null,     // Proposed new outbound queue during rotation
    val pendingRecvQueueId: String? = null,     // Proposed new inbound queue during rotation
    val rotationState: String = "ACTIVE",       // ACTIVE, PROPOSED, AUTHENTICATED, NEW_ACTIVE, OLD_DRAINING, CLOSED
    val rotationGracePeriodUntil: Long? = null, // Grace period timestamp for draining old queue
    val lastSendSeq: Long = 0,                  // Monotonic sequence number sent
    val lastRecvSeq: Long = 0,                  // Monotonic sequence number committed to SQLite
    val lastCommittedHash: String? = null,      // Hash of last successfully committed message
    val state: String = "ACTIVE",               // ACTIVE, SUSPENDED, CLOSED
    val createdAt: Long,
    val lastActiveAt: Long
)
```

#### `DeliveryQueueEntity.kt`
```kotlin
@Entity(
    tableName = "delivery_queue",
    indices = [
        Index(value = ["state", "nextRetryAt"]),
        Index(value = ["connectionId"]),
        Index(value = ["messageId"]),
        Index(value = ["expiresAt"])
    ]
)
data class DeliveryQueueEntity(
    @PrimaryKey val envelopeId: String,         // Envelope UUID
    val connectionId: String,                   // Links to connection_queue
    val messageId: String,                      // Application-level message ID
    val queueId: String,                        // Unidirectional queue ID used for delivery
    val sequenceNumber: Long,                   // Monotonic sequence number
    val previousMessageHash: String? = null,    // Hash chain pointer to previous envelope
    val envelopeHash: String? = null,           // Hash of this envelope
    val messageType: String,                    // MESSAGE, ACK, READ, CALL_*, etc.
    val encryptedPayload: String,               // Ciphertext + wire headers (opaque to transport)
    val state: String = "QUEUED",               // QUEUED, TRANSMITTING, RELAY_ACCEPTED, DELIVERED, READ, FAILED
    val createdAt: Long,
    val lastAttemptAt: Long? = null,
    val nextRetryAt: Long? = null,
    val retryCount: Int = 0,
    val expiresAt: Long,
    val transportUsed: String? = null,
    val deliveredAt: Long? = null,              // When peer ACK was received
    val readAt: Long? = null,                   // When peer READ receipt was received
    val recipientKey: String
)
```

---

## Part 3: State-Machine Specification

### 3.1 Pairwise Connection Lifecycle

```
[INITIATING] ──(Handshake / Invitation)──► [ACTIVE]
                                             │  ▲
                                  (Timeout)  │  │ (Heartbeat / Activity)
                                             ▼  │
                                         [SUSPENDED]
                                             │
                                   (Delete / Closed)
                                             ▼
                                          [CLOSED]
```

| State | Description | Inbound Allowed | Outbound Allowed |
|---|---|---|---|
| `INITIATING` | Connection proposed via invitation token; establishing DH keys. | No | Control only |
| `ACTIVE` | Normal communication active. Queues valid and operating. | Yes | Yes |
| `SUSPENDED` | No transport activity for timeout window (e.g. 7 days). | Yes (wakes connection) | Yes (wakes connection) |
| `CLOSED` | Connection terminated by user. Queues pruned. | No (drop payload) | No |

---

### 3.2 6-Stage Queue Rotation Lifecycle

To prevent long-term traffic analysis, queue identifiers rotate according to a deterministic 6-stage lifecycle:

```
[1. ACTIVE]
      │ Local generates newQueueId
      ▼
[2. ROTATION_PROPOSED] ──(sends QUEUE_ROTATE_PROPOSE)──► Peer validates
      │                                                      │
      │ ◄──────────(receives QUEUE_ROTATE_ACK)───────────────┘
      ▼
[3. ROTATION_AUTHENTICATED]
      │
      ▼
[4. NEW_QUEUE_ACTIVE] ──(Switches primary send/recv to newQueueId)
      │
      ▼
[5. OLD_QUEUE_DRAINING] ──(Grace period: accepts in-flight messages on old queue)
      │
      │ Timer expires (e.g. 5 minutes)
      ▼
[6. CLOSED] ──(Old queue retired; pending IDs cleared)
      │
      ▼
   [ACTIVE]
```

#### Rotation Stage Rules
1. **`ACTIVE`**: Regular send/recv queues.
2. **`ROTATION_PROPOSED`**: Sender stages `pendingSendQueueId = UUID()`, transmits signed `QUEUE_ROTATE_PROPOSE(newQueueId, sig)` through existing queue.
3. **`ROTATION_AUTHENTICATED`**: Peer verifies signature, stages `pendingRecvQueueId`, replies with signed `QUEUE_ROTATE_ACK`.
4. **`NEW_QUEUE_ACTIVE`**: Both peers switch their active queue to `newQueueId`.
5. **`OLD_QUEUE_DRAINING`**: The previous queue ID remains accepted for a 5-minute grace window (`rotationGracePeriodUntil = now + 300_000L`) to process any in-flight envelopes without packet drop.
6. **`CLOSED`**: Old queue permanently decommissioned. Rotation returns to `ACTIVE`.

---

### 3.3 Truthful Delivery State Machine

```
[CREATED]
   │
   ▼
[ENCRYPTED]
   │
   ▼
[QUEUED] ◄─────────────────────────────────────┐
   │                                           │ Retry (Backoff)
   ▼                                           │
[TRANSMITTING] ────────────────────────────────┤ (Transport failure)
   ├── Sent via Offline Relay ─► [RELAY_ACCEPTED] (Buffered on relay; Bob has NOT seen it)
   │                                  │
   └── Sent via Direct Transport      │
            │                         ▼ (Recipient connects to relay / direct)
            └─────────────────► [DEVICE_RECEIVED] (Protocol header & integrity passed)
                                      │
                                      ▼ (Decrypted + SQLite committed)
                                 [DELIVERED] (Signed delivery ACK received from Bob)
                                      │
                                      ▼ (Bob opens chat screen)
                                    [READ] (Signed read receipt received from Bob)
```

#### Exact State Definitions
- **`QUEUED`**: Envelope persisted to Room `delivery_queue` outbox table before any transmission attempt. Crash-safe.
- **`TRANSMITTING`**: Envelope currently handed to transport socket/writer.
- **`RELAY_ACCEPTED`**: Opaque queue-addressed relay acknowledged receipt into memory buffer. **Recipient has not received or decrypted it.**
- **`DEVICE_RECEIVED`**: Recipient physical device received the raw bytes and passed protocol header and envelope validation.
- **`DELIVERED`**: Recipient successfully decrypted payload with Double Ratchet, committed message to SQLite database, and returned an authenticated `ACK` envelope.
- **`READ`**: Recipient application layer displayed the message in the active conversation and returned an authenticated `READ` receipt envelope.
- **`FAILED`**: Max retries exceeded or fatal cryptographic error.
- **`EXPIRED`**: Envelope TTL exceeded (e.g. 14 days without delivery).

---

### 3.4 Safe Sequence Advancement Pipeline

To eliminate sequence skipping and message loss from processing failures, receive sequence cursors advance **only after successful SQLite commitment**:

```
Inbound Payload Arrives
         │
         ▼
[1. validateRecvSequence(connectionId, seq)]
   • Checks seq > lastRecvSeq
   • Does NOT mutate database cursor!
         │
         ▼
[2. Protocol Validation & Hash Verification]
   • Verifies signature, queueId, and envelope hash signal
         │
         ▼
[3. Crypto Decryption (Double Ratchet)]
   • decrypt(ciphertext, aad)
   • If fails: ABORT (cursor remains unchanged, sender can retry)
         │
         ▼
[4. SQLite Persistence]
   • db.messageDao().insert(message)
   • If fails: ABORT (cursor remains unchanged)
         │
         ▼
[5. Return Authenticated Delivery ACK]
   • Enqueue ACK envelope back to sender
         │
         ▼
[6. commitRecvSequence(connectionId, seq, envelopeHash)]
   • lastRecvSeq = seq
   • lastCommittedHash = envelopeHash
   • Cursor officially advanced in connection_queue
```
