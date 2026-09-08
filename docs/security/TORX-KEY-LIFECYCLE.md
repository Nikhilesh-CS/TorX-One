# TorX One — Key Lifecycle & Storage Architecture (Phase 1)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. Key Storage Hierarchy

TorX One leverages Android's hardware-backed Keystore system to anchor all local secrets without reinventing keystore infrastructure.

```
┌─────────────────────────────────────────────────────────────────────────┐
│ ANDROID KEYSTORE (Hardware TEE / StrongBox KeyMint)                     │
│   MasterKey: "_androidx_security_master_key_"                           │
│   Algorithm: AES-256-GCM                                                │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ (Hardware Key Wrapping)
┌────────────────────────────────────▼────────────────────────────────────┐
│ EncryptedSharedPreferences ("torxone_identity_prefs")                   │
│   Key Encryption: AES-256-SIV (Deterministic for preference keys)       │
│   Value Encryption: AES-256-GCM (Authenticated for preference values)   │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ (Hex-encoded Strings)
┌────────────────────────────────────▼────────────────────────────────────┐
│ TorX Identity Private Keys                                              │
│   - "enc_sec": 32-byte X25519 Secret Key (Hex: 64 chars)                │
│   - "sig_sec": 64-byte Ed25519 Secret Key (Hex: 128 chars)              │
│   - "onion_address": v3 Tor Onion Hostname (56 chars + ".onion")        │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Lifecycle Stages

### 2.1 Stage 1: Generation
- **Trigger:** First app launch when `hasIdentity() == false`, or explicit user onboarding.
- **Generator:** `CryptoManager.generateIdentity(name)`.
  - `lazySodium.cryptoBoxKeypair()`: Invokes libsodium C library to generate X25519 public key (32 bytes) and secret key (32 bytes).
  - `lazySodium.cryptoSignKeypair()`: Invokes libsodium to generate Ed25519 public key (32 bytes) and secret key (64 bytes: 32-byte seed + 32-byte public key).
- **Randomness Source:** `/dev/urandom` via libsodium `randombytes_buf()`.
- **Persistence:** Immediately serialized to hex and committed to `EncryptedSharedPreferences` via `IdentityManager.saveIdentity()`.

### 2.2 Stage 2: In-Memory Lifetime & Caching
- **RAM Exposure:**
  - `IdentityManager` holds ephemeral nullable references: `cachedEncSec: ByteArray?` and `cachedSigSec: ByteArray?`.
  - `TorXOneService` maintains the active `Identity` instance in its singleton `MessageRouter.identity` field throughout the service lifetime.
- **Garbage Collection Risk:** In JVM environments, `ByteArray` objects are subject to garbage collector movement and memory retention until GC sweeps. They are not pinned or memory-locked (`mlock`).
- **Audit Recommendation:** Where possible, sensitive secret byte arrays should be zeroized (`Arrays.fill(bytes, 0.toByte())`) when no longer needed in transient methods.

### 2.3 Stage 3: Storage at Rest
- **Identity Private Keys:** Protected in `torxone_identity_prefs.xml` using Keystore-backed AES-256-GCM.
- **Tor Hidden Service v3 Keys:** Stored in POSIX filesystem under `context.filesDir/tor/hs/`:
  - `hs_ed25519_secret_key` (64 bytes): Binary file containing the Ed25519 expanded private key.
  - `hs_ed25519_public_key` (32 bytes): Binary file containing the Ed25519 public key.
  - `hostname`: Plaintext onion address string.
  - *Note:* These files are set to mode `0700` (read/write by application UID only).
- **Group AES Keys:** Stored in the Room SQLite database (`group_keys` table) as Base64 strings. Currently **unencrypted** at rest.

### 2.4 Stage 4: App Lock & Session Suspension
- **Behavior in `IdentityManager.kt`:**
  ```kotlin
  fun lockSession() {
      if (isAppLockEnabled && !isAwaitingExternalActivity) {
          isSessionUnlocked = false
      }
  }
  
  fun clearPrivateKeysFromPrefs() {
      // Intentionally keep keys safely in EncryptedSharedPreferences (AES-256-GCM Keystore encrypted).
      // This ensures the background mesh service can continuously decrypt incoming messages,
      // execute file transfers, and show notifications without disturbing message delivery.
  }
  ```
- **Architectural Dual-Requirement:**
  - TorX One is designed as an always-on background mesh node (`TorXOneService` runs as an Android foreground service).
  - If private keys were completely purged from memory and storage upon screen lock, the device would be unable to decrypt incoming messages, process background mesh routing, or display incoming call/message notifications.
  - Therefore, `clearPrivateKeysFromPrefs()` leaves the Keystore-encrypted preferences intact.

### 2.5 Stage 5: Backup & Export
- **Manager:** `IdentityBackupManager.kt`.
- **Payload Schema:** `IdentityBackupDto` contains:
  - Identity keys: `encPubHex`, `encSecHex`, `sigPubHex`, `sigSecHex`
  - Tor Hidden Service keys: `torHsEd25519PublicKeyB64`, `torHsEd25519SecretKeyB64`, `onionAddress`
  - Profile metadata: `bio`, `statusMessage`, `avatarWebPB64`
- **Encryption:** `BackupCrypto.encryptBackup(jsonBytes, password)`:
  - PBKDF2-HMAC-SHA256 key derivation with 100,000 iterations and 16-byte random salt.
  - AES-256-GCM authenticated encryption with 12-byte random IV.
- **Security Guarantee:** The backup file can safely be exported to external storage or cloud drive; it is fully ciphertext protected against brute-force attacks.

### 2.6 Stage 6: Restore & Atomic Swap
- **Manager:** `IdentityRestoreManager.kt`.
- **Validation:**
  1. Decrypts backup file with user passphrase using AES-256-GCM.
  2. Parses JSON and verifies schema version (`schemaVersion <= 1`).
  3. Validates that all cryptographic keys are valid hex strings with expected byte lengths.
- **Atomic Rollback Mechanism:**
  1. Backs up existing local identity and Tor keys to memory before touching disk.
  2. Stops the running Tor daemon.
  3. Imports new Tor v3 keys and updates `EncryptedSharedPreferences`.
  4. Restores profile metadata.
  5. If any step fails, automatically rolls back to previous keys.

### 2.7 Stage 7: Key Deletion & App Uninstall
- **App Uninstall:** Android automatically deletes `/data/data/com.torxone.app/` and purges the associated keys from the Android Keystore hardware partition.
- **Account Reset / Delete Group:**
  - `GroupManager.deleteGroup()` deletes local group records, group messages, and cascades deletion of `group_keys` and `group_members`.
  - Identity reset currently requires clearing application data via Android settings or an app-level reset routine.

---

## 3. Group Key Lifecycle

Group keys follow a separate symmetric lifecycle managed by `GroupManager.kt` and `GroupCryptoManager.kt`.

```
[ Group Creation ]
       │
       ▼
Generate AES-256 Key (v1) ──► Insert into Room (group_keys)
       │
       ▼
Fan-out via Pairwise crypto_box_easy (TYPE_GROUP_KEY)
       │
       ▼
[ Group Membership Change ] (Member removed / join approved / ownership transferred)
       │
       ▼
Generate New AES-256 Key (v_{n+1}) ──► Insert into Room (group_keys)
       │
       ▼
Update GroupEntity (currentKeyVersion = n + 1)
       │
       ▼
Create Signed Event (KEY_ROTATED)
       │
       ▼
Fan-out exclusively to active members (excluding removed members)
```

### Key Versioning Rules:
1. Every group message specifies `keyVersion` in its schema header.
2. If a recipient receives a group message with a missing `keyVersion`, it sends `TYPE_GROUP_KEY_REQUEST` to the group creator.
3. Stale key versions are rejected for new messages once a newer key version is established.
4. Old key versions are retained in the local database to allow reading historic messages.
