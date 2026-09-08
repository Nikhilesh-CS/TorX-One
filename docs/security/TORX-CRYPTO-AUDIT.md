# TorX One — Cryptographic Implementation Audit (Phase 1)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. Audit of `CryptoManager`

`CryptoManager` (`com.torxone.app.crypto.CryptoManager.kt`) wraps `lazysodium-android` (libsodium C binding via JNI). This audit evaluates its key generation, encoding, encryption, nonce generation, decryption, signing, and verification.

```
Key Generation:
  encKeyPair = lazySodium.cryptoBoxKeypair()    [X25519: 32B pub, 32B sec]
  sigKeyPair = lazySodium.cryptoSignKeypair()   [Ed25519: 32B pub, 64B sec]

Encryption:
  nonce = lazySodium.nonce(Box.NONCEBYTES)      [24B random]
  cryptoBoxEasy(ciphertext, plaintext, len, nonce, recipientEncPub, senderEncSec)
    -> Curve25519 ECDH + HSalsa20 + XSalsa20 + Poly1305 MAC tag (16B)

Signing:
  cryptoSignDetached(signature, data, len, secretKey)
    -> Ed25519 detached signature (64B)
```

---

### 1.1 Detailed Cryptographic Answers

#### Encryption Questions
1. **Which algorithm?**
   - **Algorithm:** Libsodium `crypto_box_easy`.
   - **Underlying Primitives:** Curve25519 Diffie-Hellman key exchange, HSalsa20 key derivation, XSalsa20 stream cipher for encryption, and Poly1305 MAC for message authentication (RFC 7539 / Bernstein).
2. **Which nonce? Who generates nonce? Can nonce repeat?**
   - **Length:** 24 bytes (`Box.NONCEBYTES = 24`).
   - **Generator:** `lazySodium.nonce(24)` which invokes libsodium's `randombytes_buf()` using `/dev/urandom` / Android Linux CSPRNG.
   - **Can it repeat?** The nonce space is 192 bits ($2^{192}$). By the birthday paradox, a collision probability reaches $2^{-32}$ only after $2^{80}$ messages exchanged under the same keypair. Because TorX pairwise keys are static, nonce collision is cryptographically negligible under normal conditions. However, lack of message counters means out-of-order replay cannot be detected by the cipher itself.
3. **What exactly is authenticated?**
   - `crypto_box_easy` authenticates the ciphertext using the Poly1305 MAC tag (16 bytes). Any alteration of the ciphertext or nonce will cause `crypto_box_open_easy` to fail and return `false`.
4. **Is recipient binding authenticated?**
   - 🔴 **NO.** `crypto_box_easy` does not include Additional Authenticated Data (AAD). The recipient's public key is not explicitly authenticated inside the MAC tag beyond the Diffie-Hellman calculation. More critically, the detached Ed25519 signature in `MessageRouter.buildEncryptedPayload()` only signs `ciphertext + nonce`:
     ```kotlin
     val signature = CryptoManager.sign(ciphertext + nonce, identity.signingSecretKey)
     ```
     The signature does **NOT** bind the recipient's identity (`toSigningKey`). A malicious relay or observer can take the signed `(ciphertext, nonce, signature)` and present it to another recipient if the same shared secret exists, or replay it.

---

#### Signing Questions
1. **What exactly is signed?**
   - **Direct Messages (`MessageRouter.kt:1139`):** Exactly the raw byte concatenation of `ciphertext + nonce`.
   - **Group Events (`GroupEventManager.kt:23`):** UTF-8 byte representation of `canonical(...)` JSON string: `canonical(id, groupId, type, actor, targetKey, version, keyVersion, payload)`.
   - **Group Invites (`GroupManager.kt:45`):** Pipe-delimited ASCII string: `"$inviteId|$groupId|$actorKey|$expiresAt|$maxUses"`.
2. **Is serialization deterministic?**
   - 🔴 **NO for Group Events.** `GroupEventManager` constructs a `JSONObject` and calls `.toString()`. In Android's `org.json.JSONObject`, key ordering is based on `HashMap` iteration, which is **not deterministic** across different JVM runtimes, Android API levels, or restarts. Furthermore, `payload` is an arbitrary `JSONObject` whose sub-keys may be ordered unpredictably.
   - 🟢 **YES for Direct Messages.** Directly signs raw binary `ciphertext + nonce` bytes. No JSON serialization is involved in the signature itself.
3. **Can fields be reordered?**
   - In Group Events, yes, because `JSONObject` has no guaranteed key order. While `canonical()` in `GroupEventManager.kt` constructs the top-level keys in a fixed order, any nested `payload` keys or serialization differences on other platforms (iOS `swift-sodium` / Swift JSON) will produce mismatched hashes and signature validation failures!
4. **Is the signature bound to message type?**
   - 🔴 **NO for Direct Messages.** The signature signs only `ciphertext + nonce`. It does not include `messageType` (`TYPE_MSG`, `TYPE_MEDIA_OFFER`, `TYPE_CALL_OFFER`, etc.).
5. **Is it bound to recipient?**
   - 🔴 **NO for Direct Messages.** The recipient public key (`toSigningKey`) is omitted from the signature.
   - 🟡 **PARTIAL for Group Events.** `targetKey` is included if present, but the event is broadcast to all members.

---

## 2. Comprehensive Crypto Calls Audit Matrix

Search targets: `CryptoManager.*`, `SecretKeySpec`, `Cipher.*`, `GCMParameterSpec`, `cryptoBox*`, `cryptoSign*`, `MessageDigest`, `SecureRandom`, `Base64`.

| File | Function / Location | Primitive / Algorithm | Key Material | Nonce / IV | Input Data | Output Data | Authentication / Integrity | Risk Level | Action Required |
|:---|:---|:---|:---|:---|:---|:---|:---:|:---:|:---|
| `CryptoManager.kt` | `generateIdentity` | `crypto_box_keypair` (X25519) + `crypto_sign_keypair` (Ed25519) | OS CSPRNG (`randombytes_buf`) | N/A | None | Two keypairs: X25519 (32B/32B), Ed25519 (32B/64B) | N/A | 🟢 Acceptable | **KEEP** — Standard libsodium primitive. |
| `CryptoManager.kt` | `encryptMessage` | `crypto_box_easy` (X25519 + XSalsa20-Poly1305) | Recipient X25519 pub (32B) + Sender X25519 sec (32B) | 24-byte CSPRNG random nonce (`lazySodium.nonce(24)`) | Plaintext UTF-8 string | Ciphertext + 16B Poly1305 MAC | Authenticated (Poly1305) | 🟡 Medium (Static keys, no forward secrecy) | **HARDEN / WRAP** — Introduce Double Ratchet / SessionManager in Phase 2. |
| `CryptoManager.kt` | `decryptMessage` | `crypto_box_open_easy` | Sender X25519 pub (32B) + Recipient X25519 sec (32B) | 24-byte nonce from wire | Ciphertext + MAC tag | Plaintext UTF-8 string | Authenticated (Poly1305 verifies sender and payload) | 🟢 Acceptable | **KEEP** |
| `CryptoManager.kt` | `sign` | `crypto_sign_detached` (Ed25519) | Sender Ed25519 sec (64B) | Deterministic (RFC 8032) | Arbitrary ByteArray (`ciphertext + nonce`) | 64-byte signature | Signature over input bytes | 🟠 High (Missing recipient binding) | **HARDEN** — Bind recipient signing key and message type into signature body in Phase 2. |
| `CryptoManager.kt` | `verify` | `crypto_sign_verify_detached` (Ed25519) | Sender Ed25519 pub (32B) | Deterministic | Arbitrary ByteArray + 64B signature | Boolean | Verifies sender authenticity | 🟢 Acceptable | **KEEP** |
| `MessageRouter.kt` | `buildEncryptedPayload` | Direct Message Envelope Construction | Sender X25519 sec, Sender Ed25519 sec | 24-byte random nonce | Wire text JSON | `EncryptedPayload` (`from`, `to`, `cipher`, `nonce`, `sig`) | Poly1305 + Ed25519 | 🟠 High (Recipient not bound to signature) | **HARDEN** — Include `toSigningKey` and `messageType` in signed envelope. |
| `GroupCryptoManager.kt` | `encrypt` | AES-256-GCM (`AES/GCM/NoPadding`) | Group AES Key (32B from `GroupKeyEntity.aesKeyBase64`) | 12-byte CSPRNG random IV (`SecureRandom().nextBytes`) | Plaintext JSON | Ciphertext Base64 + IV Base64 | 128-bit GCM tag + AAD (`"$groupId:$version"`) | 🟢 Acceptable | **KEEP** — Strong symmetric construction with AAD binding. |
| `GroupCryptoManager.kt` | `decrypt` | AES-256-GCM (`AES/GCM/NoPadding`) | Group AES Key (32B) | 12-byte IV from wire | Ciphertext Base64 | Plaintext JSON | 128-bit GCM tag + AAD | 🟢 Acceptable | **KEEP** |
| `GroupEventManager.kt` | `createLocalEvent` | Ed25519 signature over canonical JSON | Actor Ed25519 sec (64B) | Deterministic | `canonical(...)` JSON string | 64-byte signature hex | Ed25519 | 🟠 High (Non-deterministic JSON serialization) | **HARDEN** — Replace `JSONObject.toString()` with deterministic canonical JSON (RFC 8785). |
| `GroupManager.kt` | `createInviteToken` | Ed25519 signature over pipe-delimited body | Creator Ed25519 sec (64B) | Deterministic | `"$inviteId\|$groupId\|$actorKey\|$expiresAt\|$maxUses"` | 64-byte signature hex | Ed25519 | 🟢 Acceptable | **KEEP** — Deterministic ASCII format. |
| `BackupCrypto.kt` | `deriveKey` | PBKDF2WithHmacSHA256 | User Password CharArray | 16-byte random salt, 100,000 iterations | Password + Salt | 256-bit AES SecretKey | N/A (Key Derivation) | 🟢 Acceptable | **KEEP** — Standard PBKDF2 parameters. |
| `BackupCrypto.kt` | `encryptBackup` / `decryptBackup` | AES-256-GCM (`AES/GCM/NoPadding`) | PBKDF2 derived key (256-bit) | 12-byte CSPRNG IV | JSON backup DTO bytes | `[SALT(16B)][IV(12B)][CIPHERTEXT + TAG]` | 128-bit GCM tag | 🟢 Acceptable | **KEEP** — Standard envelope. |
| `BiometricAuthManager.kt` | `hashPassword` | PBKDF2WithHmacSHA256 | App lock password | 16-byte salt, 100,000 iterations | Password + Salt | 256-bit hash | N/A (Password Hash) | 🟢 Acceptable | **KEEP** |

---

## 3. Findings Classification

### 🔴 Critical Findings

1. **Unencrypted Database at Rest (`AppDatabase.kt` / SQLite):**
   - **Observation:** `astra-mesh-db` is unencrypted on flash memory. Chat messages, contact keys, profile metadata, and group symmetric keys (`group_keys.aesKeyBase64`) are stored in plaintext.
   - **Impact:** Any physical extraction, rooted device, or local forensic dump can recover all historical messages and active group AES keys.
   - **Recommendation:** Implement SQLCipher or Room database encryption using an Android Keystore-derived database passphrase.

2. **Secret Key Truncation Bug in `IdentityManager.restorePrivateKeysToPrefs()`:**
   - **Observation:** In `IdentityManager.kt:140-141`:
     ```kotlin
     if (decryptedKeys.size != 64) return
     val enc = ByteArray(32).apply { System.arraycopy(decryptedKeys, 0, this, 0, 32) }
     val sig = ByteArray(32).apply { System.arraycopy(decryptedKeys, 32, this, 0, 32) }
     ```
     Ed25519 signing secret keys in libsodium (`cryptoSignKeypair()`) are **64 bytes** (`Sign.SECRETKEYBYTES = 64`), not 32 bytes (which is only the seed). Copying only 32 bytes corrupts the signing secret key upon restore, causing subsequent signature operations to fail with an `IllegalArgumentException`!
   - **Impact:** If `unlockSession(decryptedKeys)` is called, the signing key in `EncryptedSharedPreferences` is truncated and corrupted.
   - **Recommendation:** Correct buffer sizing to 32 bytes (X25519) + 64 bytes (Ed25519) = 96 bytes total.

---

### 🟠 High Findings

1. **Lack of Recipient Binding in Direct Message Signatures:**
   - **Observation:** `MessageRouter.buildEncryptedPayload()` signs only `ciphertext + nonce`. The recipient's signing public key (`toSigningKey`) and `messageType` are not included in the signature payload.
   - **Impact:** An adversary or relay can replay a valid signature against a different recipient if the recipient shares an identical ECDH key, or re-route packets across different message type handlers.
   - **Recommendation:** Update signature payload to sign `(fromSigningKey + toSigningKey + messageType + nonce + ciphertext)`.

2. **Non-Deterministic JSON Serialization in Group Event Signatures:**
   - **Observation:** `GroupEventManager.canonical()` uses Android's standard `org.json.JSONObject.toString()`. `JSONObject` uses standard `HashMap` internally, which does not guarantee key ordering across different Java/Android versions or cross-platform clients (iOS/Desktop).
   - **Impact:** Cross-platform signature verification failures on group control events.
   - **Recommendation:** Implement deterministic JSON serialization conforming to RFC 8785 (JSON Canonicalization Scheme - JCS) or sort keys alphabetically prior to signing.

3. **No Forward Secrecy in Pairwise Messaging (Static Long-Term Keys):**
   - **Observation:** All pairwise direct messages use the static identity keypair (`crypto_box_easy`).
   - **Impact:** If a user's private encryption key is extracted at any point in the future, all historically recorded network ciphertexts can be decrypted.
   - **Recommendation:** Implement the Phase 2 Secure Session layer with an established ratchet (Signal/Noise/Double Ratchet) to generate ephemeral message keys that are deleted immediately after use.

---

### 🟡 Medium Findings

1. **`clearPrivateKeysFromPrefs()` Is a No-Op:**
   - **Observation:** In `IdentityManager.kt:132-136`, `clearPrivateKeysFromPrefs()` does not remove keys from `EncryptedSharedPreferences` because the background service needs them to decrypt incoming messages.
   - **Impact:** The "App Lock" feature does not truly isolate private keys from flash storage; keys remain in `EncryptedSharedPreferences`.
   - **Recommendation:** Clarify the lock architecture: either accept background decryption with protected Keystore keys, or use `pending_encrypted_payloads` exclusively while locked and wipe keys from memory/prefs until unlocked.

2. **Plaintext Metadata in `pending_encrypted_payloads` Table:**
   - **Observation:** While the message content is encrypted, `rawJson` stores wire headers (`from`, `to`, `msgId`, `senderOnion`) in an unencrypted Room table.
   - **Recommendation:** Strip routing metadata or encrypt the table rows.

3. **Plaintext Metadata on Nearby Airwaves:**
   - **Observation:** `MeshProtocol` envelope headers expose sender and recipient Ed25519 public keys in plaintext on Nearby mesh transmissions.
   - **Recommendation:** In Phase 3, evaluate ephemeral link identifiers or onion envelopes on mesh hops.

---

### 🟢 Acceptable (Confirmed Solid)

1. **Key Generation:** Standard libsodium implementations (`crypto_box_keypair`, `crypto_sign_keypair`) with robust CSPRNG.
2. **Authenticated Encryption:** Proper use of Poly1305 MAC in `crypto_box_easy` and 128-bit GCM tag with AAD in `GroupCryptoManager`.
3. **Backup Cryptography:** PBKDF2-HMAC-SHA256 with 100,000 iterations and random 16-byte salt, followed by AES-256-GCM.
4. **Group Key Eviction:** Immediate AES key rotation upon member removal with exclusion of evicted members.
5. **No Homemade Ciphers:** Code strictly uses established primitives (`lazysodium-android`, `javax.crypto`).
