# TorX One — Identity Model & Key Binding (Phase 1)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. The Dual-Keypair Identity Model

TorX One implements a strict separation between cryptographic **encryption** and cryptographic **signing**, adhering to standard security best practices:

```
TorX One Identity
├── 1. Encryption Keypair (X25519 / Curve25519)
│   ├── encryptionPublicKey: ByteArray (32 bytes / 64 hex chars)
│   └── encryptionSecretKey: ByteArray (32 bytes / 64 hex chars)
│
└── 2. Signing Keypair (Ed25519 / Edwards25519)
    ├── signingPublicKey: ByteArray (32 bytes / 64 hex chars)  <── Primary Identity Key
    └── signingSecretKey: ByteArray (64 bytes / 128 hex chars)
```

### Why Separate Keypairs?
- **Mathematical Isolation:** Prevents cross-protocol attacks where an encryption ciphertext could be coerced into a signature or vice versa.
- **Role Specialization:**
  - `signingPublicKey` acts as the user's permanent, canonical **Address / Account ID** across the mesh.
  - `encryptionPublicKey` is used strictly to establish pairwise Diffie-Hellman shared secrets for `crypto_box_easy`.

---

## 2. Identity Model Evaluation

### 2.1 Who generates them?
- The client app generates both keypairs locally on the user's device during initial setup via `CryptoManager.generateIdentity()`.
- No central server, certificate authority (CA), or relay is ever consulted or involved. Identity generation is 100% autonomous and zero-knowledge.

### 2.2 Where are they stored?
- In `IdentityManager`, committed to Android `EncryptedSharedPreferences` (`"torxone_identity_prefs"`).
- The storage file is encrypted using an AES-256-GCM Master Key held in the Android Keystore hardware security module (TEE/StrongBox).
- Public keys are stored as hex strings in preferences and mirrored in the Room database (`contacts` table for remote peers, `ProfileEntity` for the local user).

### 2.3 Can they change?
- In the current architecture, identities are **static and long-lived**. Once generated, the four keys remain unchanged throughout the lifetime of the installation.

### 2.4 What happens after reinstall?
- If the app is uninstalled and reinstalled without a backup, the Android Keystore master key is destroyed and all local files are purged.
- A newly generated identity will be created upon first launch with completely new X25519 and Ed25519 keypairs.
- Existing contacts who previously communicated with the user will treat the reinstalled user as an entirely new entity (or will observe a key mismatch if the user attempts to reuse an old handle).

### 2.5 What happens after backup restore?
- `IdentityRestoreManager.restoreBackup()` performs an atomic restore:
  - Both keypairs (`enc_pub`, `enc_sec`, `sig_pub`, `sig_sec`) and the Tor Hidden Service keys (`hs_ed25519_secret_key`, `hostname`) are restored from the encrypted backup package.
  - The restored client assumes the exact same cryptographic address and onion address, seamlessly reconnecting with existing contacts and groups.

### 2.6 What happens if one key is corrupted?
- If preference keys fail validation (e.g. hex length mismatch or Keystore decryption failure), `IdentityManager.loadIdentity()` returns `null`.
- The app enters an unauthenticated state, prompting the user to either restore from a valid backup or initialize a new identity.

### 2.7 Can identity be rotated?
- Currently, **no automatic or manual identity rotation protocol exists**.
- Changing an identity requires abandoning the current account, creating a new identity, and re-distributing a new `astra:` contact string to all peers.

### 2.8 How do contacts detect identity changes?
- In `AppDatabase`, the `contacts` table uses `signingPublicKey` as the **Primary Key**:
  ```kotlin
  @Entity(tableName = "contacts")
  data class ContactEntity(
      @PrimaryKey val signingPublicKey: String,
      val encryptionPublicKey: String,
      val name: String,
      val endpointId: String = "",
      val onionAddress: String = "",
      val isConnected: Boolean = false,
      val muteUntil: Long = 0L
  )
  ```
- If a contact changes their `signingPublicKey`, they are treated as a completely different contact.
- If a contact keeps their `signingPublicKey` but advertises a new `encryptionPublicKey`, `MessageRouter.handleEncrypted()` uses the updated `encryptionPublicKey` stored in `ContactEntity`.
- **Audit Flag (TOFU):** Currently, TorX One uses Trust-On-First-Use (TOFU). There is no "Safety Number" fingerprint verification UI (e.g. QR code comparison or numeric key verification) to alert users if a contact's encryption key changes unexpectedly.

---

## 3. Contact String Specification

Peers exchange identities out-of-band using the standard `astra:` URI scheme:

```
astra:<base64(JSON)>
```

### Decoded JSON Payload:
```json
{
  "n": "Alice",
  "e": "3d5f8a... (32 bytes / 64 hex chars X25519 public key)",
  "s": "a1b2c3... (32 bytes / 64 hex chars Ed25519 public key)",
  "o": "xyz...onion (56 lowercase base32 chars + .onion)"
}
```

### Parsing & Verification Rules in `CryptoManager.parseContactString()`:
1. Validates `astra:` prefix.
2. Base64 decodes and parses JSON object.
3. Enforces `n` (name) is non-blank.
4. Enforces `e` is exactly 32 bytes (64 hex characters).
5. Enforces `s` is exactly 32 bytes (64 hex characters).
6. Validates optional `o` against regex: `^[a-z2-7]{56}\.onion$`.
7. Returns `ParsedContact(name, encryptionPublicKey, signingPublicKey, onionAddress)`.

---

## 4. Architectural Recommendations for Phase 3

1. **Safety Numbers / Fingerprint Verification:**
   - Implement SHA-256 fingerprint generation derived from `(localUser.sigPub + contact.sigPub)` to enable manual out-of-band verification between peers.
2. **Detect & Alert on Key Changes:**
   - If a peer's X25519 encryption key changes for a known `signingPublicKey`, do not silently accept the change. Flag the conversation with a warning banner: *"Contact's security key has changed."*
3. **Session Independence (Phase 2):**
   - Decouple ephemeral messaging sessions from long-term identity keys by introducing the Phase 2 `SessionManager` layer.
