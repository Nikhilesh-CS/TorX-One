# TorX One — Cryptographic & Plaintext Inventory (Phase 0)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. Security Component Inventory

The TorX One architecture already contains a substantial and operational cryptographic foundation. The goal of this inventory is to map all existing security, identity, networking, and data structures to avoid redundant managers.

```
com.torxone.app/
├── crypto/
│   └── CryptoManager.kt          # libsodium wrapper: X25519, Ed25519, crypto_box, crypto_sign
├── identity/
│   ├── IdentityManager.kt        # MasterKey + EncryptedSharedPreferences key storage & lock
│   ├── backup/
│   │   ├── BackupCrypto.kt       # PBKDF2-HMAC-SHA256 (100k iter) + AES-256-GCM backup encryption
│   │   ├── IdentityBackupDto.kt  # Backup JSON container schema
│   │   ├── IdentityBackupManager.kt   # Export encrypted backup package
│   │   └── IdentityRestoreManager.kt  # Decrypt, validate schema, atomic rollback swap
│   └── profile/
│       ├── ProfileRepository.kt  # SHA-256 profile hashing & metadata
│       └── ProfileSyncManager.kt # Profile packet distribution over mesh
├── network/
│   ├── MessageRouter.kt          # Core engine: encryption, signing, transport routing, ACKs, retries
│   ├── MeshProtocol.kt           # Wire frame constants, envelope parser, frame validator
│   ├── NearbyConnectionManager.kt# Google Nearby Connections (P2P_CLUSTER) Bluetooth LE / Wi-Fi
│   ├── WifiDirectManager.kt      # Android Wi-Fi Direct (P2P) listener & channel manager
│   ├── TorManager.kt             # Embedded Tor v0.4.8 binary manager, v3 onion hidden service
│   ├── TorSocketServer.kt        # Local loopback TCP server bound to 127.0.0.1 (target of Tor HS)
│   └── TorState.kt               # Tor connection state machine
├── group/
│   ├── GroupManager.kt           # Lifecycle: create, invite, join, roles, kick, key rotation
│   ├── GroupCryptoManager.kt     # Application-level AES-256-GCM symmetric encryption with AAD
│   ├── GroupEventManager.kt      # Tamper-proof signed event ledger & replay guard
│   └── GroupPermission.kt        # Group role-based access control (RBAC) policy engine
├── security/
│   └── BiometricAuthManager.kt   # AndroidX BiometricPrompt + PBKDF2 app lock password hashing
└── data/
    ├── AppDatabase.kt            # Room database (SQLite: messages, contacts, groups, events, keys)
    ├── PendingEncryptedPayload.kt# Stash table for deferred messages received while app is locked
    └── SettingsManager.kt        # Jetpack DataStore preferences (Tor, motion, dark mode)
```

---

## 2. Component Deep Dive

### 2.1 `crypto/CryptoManager.kt`
- **Underlying Engine:** `com.goterl.lazysodium.LazySodiumAndroid` over `SodiumAndroid` (C libsodium JNI bindings).
- **Primitives Implemented:**
  - `cryptoBoxKeypair()`: Generates 32-byte Curve25519 (X25519) encryption keypair.
  - `cryptoSignKeypair()`: Generates 32-byte Ed25519 public key and 64-byte secret key (seed + public key).
  - `encryptMessage()`: Calls `cryptoBoxEasy()` (X25519 ECDH + XSalsa20 stream cipher + Poly1305 MAC). Generates a 24-byte random nonce via `lazySodium.nonce(24)`. Output ciphertext includes a 16-byte Poly1305 MAC tag.
  - `decryptMessage()`: Calls `cryptoBoxOpenEasy()`. Verifies Poly1305 MAC before returning decrypted UTF-8 plaintext.
  - `sign()`: Calls `cryptoSignDetached()`. Generates 64-byte Ed25519 detached signature.
  - `verify()`: Calls `cryptoSignVerifyDetached()`. Validates 64-byte signature over input bytes with 32-byte Ed25519 public key.
  - Contact String Serialization: Generates URI `astra:<base64(JSON({n, e, s, o}))>`.

### 2.2 `identity/IdentityManager.kt`
- **Keystore Integration:** Uses `androidx.security.crypto.MasterKey` with `MasterKey.KeyScheme.AES256_GCM`.
- **Encrypted Storage:** Uses `EncryptedSharedPreferences` with:
  - Key encryption: `AES256_SIV`
  - Value encryption: `AES256_GCM`
- **Stored Keys:**
  - `enc_pub`: Hex representation of 32-byte X25519 public key
  - `enc_sec`: Hex representation of 32-byte X25519 secret key
  - `sig_pub`: Hex representation of 32-byte Ed25519 public key (stable contact identity)
  - `sig_sec`: Hex representation of 64-byte Ed25519 secret key
  - `onion_address`: v3 onion address (`[a-z2-7]{56}.onion`)
- **Key Access & Lock State:**
  - `lockSession()`: Flips boolean flag `isSessionUnlocked = false`.
  - `clearPrivateKeysFromPrefs()`: **Intentionally kept as a no-op** in existing code to allow the background Android Service (`TorXOneService`) to continue decrypting incoming messages and executing mesh transfers in the background.

### 2.3 `group/GroupCryptoManager.kt`
- **Algorithm:** `AES/GCM/NoPadding` (AES-256-GCM) via Java standard `javax.crypto.Cipher`.
- **Key Derivation:** 32-byte random key generated via `SecureRandom().nextBytes()` and Base64-encoded.
- **IV:** 12-byte random IV per message generated via `SecureRandom().nextBytes()`.
- **Authentication Tag:** 128-bit authentication tag appended to ciphertext.
- **Additional Authenticated Data (AAD):** Explicitly binds group ID and key version: `"$groupId:$version".toByteArray(Charsets.UTF_8)`.

### 2.4 `group/GroupEventManager.kt`
- **Purpose:** Audit log of all group administrative events (`GROUP_CREATED`, `MEMBER_INVITED`, `MEMBER_JOINED`, `GROUP_INFO_UPDATE`, `MEMBER_REMOVED`, `MEMBER_LEFT`, `ROLE_CHANGE`, `OWNERSHIP_TRANSFER`, `KEY_ROTATED`, `GROUP_DELETED`).
- **Signature Body:** Canonical string: `canonical(id, groupId, type, actor, targetKey, version, keyVersion, payload)`.
- **Signature Primitives:** Ed25519 signed by actor's signing secret key.
- **Replay Protection:** `processed_group_events` table enforces that an event ID can only be processed once per group.

---

## 3. Comprehensive Plaintext Inventory

A full codebase search was performed across all source files for plaintext occurrences, sensitive key variables, unencrypted databases, and logging calls.

### 3.1 Plaintext & Key Storage Audit Table

| Data Item | Exact Location | Why it Exists | Security Evaluation & Audit Recommendation |
|:---|:---|:---|:---|
| **Direct Message Plaintext** | `AppDatabase` (`messages` table, `text` column) | Render chat timeline in Compose UI (`ChatScreen.kt`). | 🔴 **Audit / Risk:** Stored in unencrypted SQLite (`astra-mesh-db`). Any root app or physical extraction can read the complete chat history. Should be encrypted at rest (e.g. SQLCipher or app-level database encryption). |
| **Direct Message Plaintext in Memory** | `MessageRouter.kt` (`handleEncrypted`, `sendMessage`) | Message processing, serialization, and deserialization. | 🟢 **Acceptable:** Ephemeral in memory during active processing, cleaned up by JVM GC. |
| **Private Encryption Key (`enc_sec`)** | `EncryptedSharedPreferences` (`torxone_identity_prefs`) | Required to decrypt pairwise incoming direct messages. | 🟢 **Acceptable:** Backed by Android Keystore hardware-backed TEE master key (AES-256-GCM). |
| **Private Signing Key (`sig_sec`)** | `EncryptedSharedPreferences` (`torxone_identity_prefs`) | Required to sign outgoing direct messages and group events. | 🟢 **Acceptable:** Backed by Android Keystore hardware-backed TEE master key (AES-256-GCM). |
| **Tor Hidden Service Secret Key** | Disk: `filesDir/tor/hs/hs_ed25519_secret_key` | Required by Tor C binary to host the v3 Onion Hidden Service. | 🟡 **Medium Risk:** Stored in app private directory (`mode 0700`), but not encrypted with Android Keystore because the native Tor process requires raw POSIX file access. |
| **Group AES-256 Key** | `AppDatabase` (`group_keys` table, `aesKeyBase64` column) | Decrypt group messages locally across sessions. | 🔴 **Critical Audit Item:** Stored in unencrypted SQLite database. Root extraction yields all group symmetric keys, allowing decryption of all group message history. Should be encrypted using Keystore-derived key. |
| **Group AES-256 Key in Events** | `AppDatabase` (`group_events` table, `payload` column) | Audit log record of `KEY_ROTATED` event. | 🔴 **Critical Audit Item:** The `payload` JSON string in `group_events` contains `{"aesKeyBase64":"..."}` unencrypted in SQLite. Must be redacted or encrypted. |
| **Raw Encrypted Payload Backlog** | `AppDatabase` (`pending_encrypted_payloads` table, `rawJson` column) | Allows deferred decryption when app is locked. | 🟡 **Medium:** Payload itself is encrypted with `crypto_box_easy`. However, `rawJson` contains metadata: `from`, `to`, `msgId`, `senderOnion`, and `receivedAt`. |
| **Pending Group Event Payloads** | `AppDatabase` (`pending_group_events` table, `payload` column) | Outbox store-and-forward queue for offline group members. | 🟡 **Medium:** Stores encrypted ciphertext payloads (`schemaVersion 2`), but includes recipient signing key and retry counter. |
| **User Profile Data** | `AppDatabase` (`profiles` table: `bio`, `statusMessage`) | Render contact profile screens and status. | 🟡 **Medium:** Unencrypted local storage of user and contact profiles. |
| **Routing Metadata** | Wire envelopes (`MeshProtocol`) | Route packets across multi-hop Nearby mesh and Tor network. | 🟡 **Medium:** Exposed to immediate peers on Nearby mesh (`from` and `to` public keys). |

---

## 4. Deep Dive: `pending_encrypted_payloads` Table

The `pending_encrypted_payloads` table in `com.torxone.app.data.PendingEncryptedPayload.kt` presents a unique architectural behavior:

```kotlin
@Entity(tableName = "pending_encrypted_payloads")
data class PendingEncryptedPayload(
    @PrimaryKey val messageId: String,
    val fromSigningKey: String,
    val rawJson: String,
    val receivedAt: Long
)
```

### Flow & Operation:
1. **Trigger Condition:** In `MessageRouter.handleEncrypted()`, if `identity.encryptionSecretKey.isEmpty()` (which occurs when the app session is locked or private keys are unloaded):
   ```kotlin
   if (identity.encryptionSecretKey.isEmpty()) {
       db.pendingEncryptedPayloadDao().insert(
           PendingEncryptedPayload(
               messageId = messageId,
               fromSigningKey = senderKey,
               rawJson = json.toString(),
               receivedAt = System.currentTimeMillis()
           )
       )
       sendAck(messageId, senderKey, viaEndpoint, senderOnion)
       NotificationHelper.showDeferredMessageNotification(...)
       return
   }
   ```
2. **Delivery Guarantee:** An immediate ACK is returned to the sender so the sender marks the message as `delivered`.
3. **Replay & Backlog Drain:** When the user unlocks the app with their biometric or password, `MessageRouter.processEncryptedBacklog()` reads all rows from `pending_encrypted_payloads`, calls `handleEncrypted()`, and deletes the row upon successful decryption.
4. **Security Assessment:**
   - **Good:** The message text is not decrypted while the app is locked.
   - **Audit Concern:** `rawJson` contains full wire metadata (including the sender's onion address, sender signing key, and ciphertext). Storing `rawJson` verbatim in unencrypted SQLite exposes metadata to any physical database extractor.

---

## 5. Logging and Debug Information Audit

A systematic scan of `Log.`, `println`, and `Timber` across `android/app/src/main/java/` revealed:

1. **`println`:** Exactly 1 occurrence found in `SoundManager.kt` (`println("Playing sound: ${event.name}")`). No sensitive data leaked.
2. **`Timber`:** Zero occurrences (Timber is not used; Android native `android.util.Log` is used).
3. **`android.util.Log` Analysis:**
   - **Message Content:** Never logged. `MessageRouter.kt` explicitly logs only character length: `Log.d(TAG, "[RECV] Message from ${contact.name} (${chatPayload.text.length} chars)")`.
   - **Ciphertexts:** Always truncated to 20 hex characters: `payload.ciphertextHex.take(20)`.
   - **Public Keys:** Always truncated to 12 or 20 characters: `senderKey.take(20)` or `memberKey.take(12)`.
   - **Private Keys & Secret Keys:** Zero occurrences in any log call.
   - **Onion Addresses:** Logged in debug builds (`Log.d(TAG, "[TOR] Onion address generated: $onion")`). In production release builds, ProGuard/R8 should strip `Log.d` calls to eliminate onion address exposure via `adb logcat`.
