# TorX One — End-to-End Data Flow & Lifecycle (Phase 0)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. Direct Message End-to-End Trace

This section documents the step-by-step transmission and reception flow of a 1-to-1 direct chat message between User Alice (Sender) and User Bob (Recipient).

```
[ Alice UI ]
     │
     ▼ (1. Message Creation)
[ encodeChatMessagePayload() ]
     │
     ▼ (2. MessageRouter.sendMessage())
[ Insert MessageEntity (status='pending') ]
     │
     ▼ (3. buildEncryptedPayload())
[ CryptoManager.encryptMessage() -> crypto_box_easy ]
     │
     ▼ (4. Digital Signature)
[ CryptoManager.sign(ciphertext + nonce) -> crypto_sign_detached ]
     │
     ▼ (5. Envelope Encoding)
[ MeshProtocol.encodeDirectMessage() / encodeRelayMessage() ]
     │
     ▼ (6. Transport Selection)
┌──────────────────┼──────────────────┐
│ (Nearby Direct)  │ (Nearby Relay)   │ (Tor .onion)
▼                  ▼                  ▼
[ Bluetooth/Wi-Fi ] [ Flooding Mesh ] [ Tor Socket ]
└──────────────────┬──────────────────┘
                   │
                   ▼ (7. Network Transit)
              [ Bob Device ]
                   │
                   ▼ (8. Transport Receiver)
[ Nearby / TorSocketServer -> MessageRouter.handleEncrypted() ]
     │
     ▼ (9. Envelope Parsing & Recipient Check)
[ MeshProtocol.parseEncrypted(): check toSigningKey == Bob's key ]
     │
     ▼ (10. Sender Contact Lookup)
[ db.contactDao().getContact(senderKey) ]
     │
     ▼ (11. Signature Verification)
[ CryptoManager.verify(ciphertext + nonce, sig, senderSigPub) ]
     │
     ▼ (12. Decryption)
[ CryptoManager.decryptMessage() -> crypto_box_open_easy ]
     │
     ▼ (13. Deduplication & DB Persistence)
[ Deduplication check -> insert MessageEntity (status='delivered') ]
     │
     ▼ (14. Delivery Confirmation ACK)
[ sendAck() -> returned to Alice via transit transport ]
     │
     ▼ (15. UI Presentation)
[ ChatScreen timeline update via Room Flow ]
```

---

### Step-by-Step Data State Analysis Matrix

| Step | State / Component | Data Format | Key Used | Plaintext? | Modifiable by Adversary? | Replayable? | Visibility Scope |
|:---|:---|:---|:---|:---:|:---:|:---:|:---|
| **1. UI Input** | `ChatScreen.kt` | Raw string typed by user | None | **Yes** | No (local memory) | N/A | App process memory only |
| **2. Envelope Assembly** | `encodeChatMessagePayload()` | JSON string: `{"astraType":"chat_message", "version":1, "text":..., "reply":...}` | None | **Yes** | No | N/A | App process memory only |
| **3. Outbox DB Write** | `db.messageDao().insertMessage()` | SQLite row in `messages` table with status `"pending"` | None | **Yes** (in SQLite) | No (requires root/local access) | N/A | Local device SQLite (`astra-mesh-db`) |
| **4. Encryption** | `CryptoManager.encryptMessage()` | Libsodium `crypto_box_easy`: Output is `ciphertext` (bytes) + 24-byte `nonce` | Alice X25519 secret (`enc_sec`) + Bob X25519 public (`enc_pub`) | **NO** 🟢 (Encrypted + Poly1305 MAC) | Modified bytes will fail Poly1305 MAC check on decryption | If replayed as raw bytes, depends on transport/receiver checks | Alice memory only |
| **5. Signature** | `CryptoManager.sign()` | 64-byte Ed25519 detached signature over `(ciphertext + nonce)` | Alice Ed25519 secret (`sig_sec`) | **NO** (Binary signature) | Any byte mutation breaks Ed25519 verification | Signature validates whenever `(ciphertext + nonce)` is replayed | Alice memory only |
| **6. Wire Envelope** | `MeshProtocol.encodeDirectMessage()` | JSON string: `{"type":"msg", "from":"<alice_sig_pub>", "to":"<bob_sig_pub>", "ciphertext":"<hex>", "nonce":"<hex>", "signature":"<hex>", "msgId":"<uuid>", "senderOnion":"<onion>"}` | None (Container) | Payload: **Encrypted**<br>Envelope Headers: **Plaintext** 🟡 | Headers can be modified (e.g. `msgId`, `senderOnion`). Tampering with `ciphertext`, `nonce`, or `from` causes signature failure. | **YES** 🟡: Envelope as a whole can be replayed on network wire. | Sender, local transports, intermediate relays, network observers |
| **7. Transport Transit** | Nearby BLE/Wi-Fi or Tor v3 Hidden Service | Network frames / TCP stream | Tor link TLS (on Tor); Nearby link encryption (on Nearby) | Inner payload encrypted; Outer routing visible to local network | Drops or delays possible; alterations detected by crypto | Network-level replay possible | Transits physical airwaves or Tor circuits |
| **8. Ingress Reception** | `NearbyConnectionManager` / `TorSocketServer` | Received raw string line | None | Same as Step 6 | Modifiable in transit, but tampered data dropped in Step 11 | Yes | Receiver process memory |
| **9. Recipient Verification** | `MessageRouter.handleEncrypted()` | Checked against `mySigningKeyHex` | Bob Ed25519 public (`sig_pub`) | Header check | If `to != mySigningKeyHex`, silently dropped | N/A | Receiver process memory |
| **10. Contact Check** | `db.contactDao().getContact()` | ContactEntity retrieved | Alice Ed25519 public (`from`) | Database lookup | If unknown sender, dropped | N/A | Receiver process memory |
| **11. Signature Verification** | `CryptoManager.verify()` | `ciphertext + nonce` checked against `signature` | Alice Ed25519 public (`sig_pub`) | Verification over encrypted bytes | **Tampering detected here.** If signature fails, dropped. | **Critical:** Signature does NOT include `toSigningKey`, `msgId`, or `messageType`! | Receiver process memory |
| **12. Decryption** | `CryptoManager.decryptMessage()` | Libsodium `crypto_box_open_easy` | Alice X25519 public + Bob X25519 secret (`enc_sec`) | **Yields UTF-8 plaintext** | Poly1305 MAC verified before plaintext release | N/A | Receiver process memory |
| **13. Deduplication & Insert** | `db.messageDao().insertMessage()` | SQLite row in `messages` table with status `"delivered"` | None | **Yes** (in SQLite) | Local DB | Checked via `getMessageById(messageId)`. Mutating `msgId` bypasses this! | Local device SQLite (`astra-mesh-db`) |
| **14. Delivery Receipt ACK** | `sendAck()` | JSON: `{"type":"ack", "msgId":..., "from":Bob, "to":Alice}` | Transport link | Plaintext JSON | Tampering could discard ACK | N/A | Network wire |
| **15. UI Render** | `ChatScreen.kt` | Jetpack Compose `LazyColumn` item | None | **Yes** (UI display) | N/A | N/A | Display screen |

---

## 2. Group Messaging & Management End-to-End Trace

TorX One implements multi-user group chat with administrative controls, key versioning, and an immutable signed event ledger.

```
[ Group Creator (Alice) ]
     │
     ▼ (1. Create Group)
[ GroupManager.createGroupAndInvite() ]
     │
     ├── Insert GroupEntity (creatorKey = Alice, myRole = 'owner', keyVersion = 1)
     ├── Insert GroupMemberEntity (Alice = 'owner', others = 'invited')
     ├── Generate AES-256 Key: GroupCryptoManager.newKeyBase64() -> Insert GroupKeyEntity(v1)
     ├── Create Signed Event: GROUP_CREATED (signed by Alice Ed25519)
     └── Send GROUP_INVITE via MessageRouter.sendControl() to all invitees
```

---

### 2.1 Group Administration Actions Mapping

| Group Action | Initiating Function | Initiator Role Required | Signed Event Type (`GroupEventEntity`) | Cryptographic Key Used | Wire Packet Type (`MeshProtocol`) | Key Rotation Triggered? |
|:---|:---|:---|:---|:---|:---|:---:|
| **Create Group** | `createGroupAndInvite()` | Creator | `GROUP_CREATED` | Alice Ed25519 secret (`sig_sec`) | `TYPE_GROUP_INVITE` | Initial Key (v1) |
| **Accept Invite** | `acceptInvite()` | Invitee | `MEMBER_JOINED` | Member Ed25519 secret (`sig_sec`) | `TYPE_GROUP_JOIN` | No |
| **Add Member** | `addMembers()` | Admin / Owner | `MEMBER_INVITED` | Admin Ed25519 secret (`sig_sec`) | `TYPE_GROUP_INVITE` | No |
| **Approve Join** | `approveJoin()` | Owner | `MEMBER_APPROVED` | Owner Ed25519 secret (`sig_sec`) | `TYPE_GROUP_UPDATE` | **YES** (`rotateAndDistribute`) |
| **Remove Member** | `removeMember()` | Admin / Owner | `MEMBER_REMOVED` | Admin Ed25519 secret (`sig_sec`) | `TYPE_GROUP_UPDATE` | **YES** (`rotateAndDistribute`) |
| **Leave Group** | `leaveGroup()` | Member | `MEMBER_LEFT` | Member Ed25519 secret (`sig_sec`) | `TYPE_GROUP_LEAVE` | **YES** (by Creator upon receipt) |
| **Promote / Demote**| `changeMemberRole()` | Owner | `ROLE_CHANGE` | Owner Ed25519 secret (`sig_sec`) | `TYPE_GROUP_UPDATE` | No |
| **Transfer Ownership**| `transferOwnership()`| Owner | `OWNERSHIP_TRANSFER` | Owner Ed25519 secret (`sig_sec`) | `TYPE_GROUP_UPDATE` | **YES** (`rotateAndDistribute`) |
| **Rotate Group Key**| `rotateAndDistribute()`| Owner / Admin | `KEY_ROTATED` | Sender Ed25519 secret (`sig_sec`) | `TYPE_GROUP_KEY` | New version: `v_{n+1}` |
| **Update Info** | `updateGroupMetadata()`| Admin / Owner | `GROUP_INFO_UPDATE` | Admin Ed25519 secret (`sig_sec`) | `TYPE_GROUP_UPDATE` | No |
| **Delete Group** | `deleteGroup()` | Owner | `GROUP_DELETED` | Owner Ed25519 secret (`sig_sec`) | `TYPE_GROUP_UPDATE` | Local keys deleted |

---

### 2.2 Group Message Lifecycle Trace

When Bob sends a message in Group X:
1. **Permission Check:** `GroupPermission.canSendMessages(group, member)` verifies Bob is an active member and not restricted by `whoCanSend`.
2. **Local DB Insertion:** Message saved locally in `messages` table with `conversationType = "group"` and `status = "pending"`.
3. **Key Retrieval:** Bob fetches latest symmetric key from `group_keys` table (`GroupKeyEntity(groupId, keyVersion)`).
4. **Inner Payload Construction:**
   ```json
   {
     "astraType": "chat_message",
     "version": 1,
     "type": "TEXT",
     "messageId": "<bob_uuid>",
     "senderKey": "<bob_sig_pub>",
     "timestamp": 1725820000000,
     "text": "Hello group",
     "reply": null,
     "mentions": []
   }
   ```
5. **Symmetric Encryption:** `GroupCryptoManager.encrypt(key, innerPayload)` performs AES-256-GCM encryption with a random 12-byte IV and AAD `"$groupId:$keyVersion"`.
6. **Outer Wire Envelope (Schema Version 2):**
   ```json
   {
     "type": "group_msg",
     "schemaVersion": 2,
     "groupId": "<group_uuid>",
     "keyVersion": 1,
     "ciphertext": "<base64_ciphertext>",
     "iv": "<base64_iv>"
   }
   ```
7. **Pairwise Transport Fan-out:** Bob sends the outer envelope to every active group member individually via `MessageRouter.sendRawPayload()`.
   - Each pairwise transmission is **doubly encrypted**: first with the group AES-256 key, then with each recipient's pairwise libsodium `crypto_box_easy`.
   - If a member is offline, the payload is stored in `pending_group_events` for store-and-forward retry.
8. **Receiver Processing (`MessageRouter.handleGroupMessage()`):**
   - Strips outer pairwise libsodium layer.
   - Enforces `schemaVersion == 2` (rejects legacy unencrypted frames).
   - Verifies recipient and sender are active members of `groupId`.
   - Fetches matching `keyVersion` from local `group_keys`.
   - Decrypts AES-256-GCM ciphertext and validates 128-bit authentication tag.
   - Verifies inner `senderKey` matches outer pairwise `senderKey` to prevent sender spoofing.
   - Dispatches message to Room database and UI.

---

### 2.3 Group Database State Structures

The Room database tracks group state across seven specialized entities:

1. **`groups` (`GroupEntity`):** Stores metadata, current key version (`currentKeyVersion`), roles, metadata version (`metadataVersion`), and policy flags (`whoCanSend`, `whoCanAddMembers`, `whoCanEditInfo`, `approvalRequired`).
2. **`group_members` (`GroupMemberEntity`):** Composite primary key `[groupId, memberKey]`. Foreign key CASCADE on group deletion. Tracks role (`owner`, `admin`, `member`, `invited`, `pending`) and membership state (`MEMBER`, `INVITED`, `PENDING_APPROVAL`, `REMOVED`, `LEFT`).
3. **`group_keys` (`GroupKeyEntity`):** Composite primary key `[groupId, keyVersion]`. Stores the symmetric key in `aesKeyBase64` alongside distribution timestamp.
4. **`group_events` (`GroupEventEntity`):** Immutable append-only audit log. Primary key `eventId`. Stores event type, actor key, target key, group version, key version, payload JSON, and Ed25519 signature.
5. **`processed_group_events` (`ProcessedGroupEventEntity`):** Primary key `[groupId, eventId]`. Replay protection table recording processed control events.
6. **`pending_group_events` (`PendingGroupEventEntity`):** Composite primary key `[eventId, recipientKey]`. Outbox store-and-forward queue with exponential backoff (`nextRetryAt`, `retryCount`, `expiresAt`).
7. **`group_sync_state` (`GroupSyncStateEntity`):** Tracks synchronization watermark (`lastKnownGroupVersion`, `lastKnownEventId`, `lastSyncAt`) for catching up offline members via `TYPE_GROUP_SYNC_REQUEST` and `TYPE_GROUP_SYNC_RESPONSE`.
