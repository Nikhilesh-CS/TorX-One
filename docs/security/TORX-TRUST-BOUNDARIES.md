# TorX One — Trust Boundaries & Zones (Phase 0)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. Architectural Trust Zones

TorX One operates across six distinct trust zones, ranging from hardware-isolated environments to completely untrusted physical airwaves and adversarial relay nodes.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ZONE 0: Hardware Root of Trust (Android Keystore / TEE / StrongBox)      │
│   - MasterKey AES-256-GCM hardware key protection                        │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ (Hardware IPC / KeyMint)
┌────────────────────────────────────▼─────────────────────────────────────┐
│ ZONE 1: TorX Application Process & Runtime Memory (Trusted Core)         │
│   - IdentityManager, CryptoManager, MessageRouter, GroupManager          │
│   - Decrypted plaintexts, ephemeral memory session states                │
└───────────────────┬───────────────────────────────────┬──────────────────┘
                    │                                   │
      (POSIX I/O)   │                                   │ (Local TCP loopback)
┌───────────────────▼──────────────┐   ┌────────────────▼──────────────────┐
│ ZONE 2: Local Encrypted Storage  │   │ ZONE 3: Tor Daemon & Loopback     │
│   - EncryptedSharedPreferences   │   │   - tor process, 127.0.0.1:$port  │
│   - Unencrypted SQLite DB (Room) │   │   - filesDir/tor/hs keys          │
└──────────────────────────────────┘   └────────────────┬──────────────────┘
                                                        │
┌───────────────────────────────────────────────────────▼──────────────────┐
│ ZONE 4: Local Proximity Transports (Untrusted Physical Transit)          │
│   - Nearby Connections (Bluetooth LE, Wi-Fi Aware, Wi-Fi LAN)            │
│   - Wi-Fi Direct P2P Group Owner                                         │
└───────────────────────────────────┬──────────────────────────────────────┘
                                    │ (Store-and-forward mesh flooding)
┌───────────────────────────────────▼──────────────────────────────────────┐
│ ZONE 5: Untrusted Relay Mesh & Global Tor Transit                        │
│   - Peer relay hops (MeshProtocol.TYPE_RELAY)                            │
│   - Tor Onion relays, directory servers, rendezvous points               │
└───────────────────────────────────┬──────────────────────────────────────┘
                                    │ (End-to-End Cryptographic Boundary)
┌───────────────────────────────────▼──────────────────────────────────────┐
│ ZONE 6: Remote Peer Endpoint (Semi-Trusted Authenticated Contact)        │
│   - Contact's TorX One client instance                                   │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Trust Zone Definitions & Characteristics

### Zone 0: Hardware Root of Trust (TEE / StrongBox)
- **Components:** Android KeyStore provider (`AndroidKeyStore`), hardware-backed TEE or SE.
- **Trust Level:** **Highest (Fully Trusted).**
- **Security Guarantee:** Secret keys never leave the hardware boundary in plaintext. Cryptographic operations (AES-GCM key wrapping) occur inside the TEE.
- **Exposure / Risk:** Side-channel attacks on SoC hardware (extremely low risk in mobile threat models).

### Zone 1: Application Process Memory (Local User Core)
- **Components:** JVM heap inside Android application UID (`com.torxone.app`), Kotlin singletons (`CryptoManager`, `GroupCryptoManager`), instantiated managers (`IdentityManager`, `MessageRouter`, `GroupManager`).
- **Trust Level:** **High (Trusted).**
- **Security Guarantee:** Isolated from other apps by the Linux kernel UID process boundary.
- **Exposure / Risk:** Root compromise, memory dump of process (`/proc/$PID/mem`), or memory inspection via debuggable builds can extract keys and decrypted chat plaintexts.

### Zone 2: Local Persistent Storage (Flash Memory)
- **Components:** 
  1. `torxone_identity_prefs.xml` (EncryptedSharedPreferences — encrypted via MasterKey).
  2. `astra-mesh-db` (Room SQLite database — unencrypted).
  3. `filesDir/tor/hs/` (Tor hidden service keys — POSIX file permissions 0700).
- **Trust Level:** **Moderate.**
- **Security Guarantee:** Non-root applications cannot access files in `/data/data/com.torxone.app/`.
- **Exposure / Risk:** 
  - The SQLite database is **unencrypted**. Any device backup (if allowBackup=true, though TorX disables it), ADB backup, forensic extraction, or root access exposes full message history and group symmetric keys.

### Zone 3: Local Tor Daemon & Loopback Sockets
- **Components:** Forked native `tor` binary process, Unix loopback interface `127.0.0.1`, SOCKS5 proxy port `127.0.0.1:9050`, local socket server `127.0.0.1:$port`.
- **Trust Level:** **Medium.**
- **Security Guarantee:** Bound to localhost (`127.0.0.1`), theoretically inaccessible from outside the physical device.
- **Exposure / Risk:** Any non-root third-party application on Android with `android.permission.INTERNET` can open a TCP connection to `127.0.0.1:$port`. TorX One's `TorSocketServer` currently accepts connections without authentication tokens. However, the incoming payloads must still satisfy `MeshProtocol` cryptographic signatures.

### Zone 4: Local Proximity Transports (Nearby / BLE / Wi-Fi)
- **Components:** Nearby Connections (`P2P_CLUSTER`), Bluetooth Low Energy advertisements, Wi-Fi Direct.
- **Trust Level:** **Untrusted.**
- **Security Guarantee:** Zero trust in the physical transmission medium.
- **Protective Control:** All message payloads must be encrypted before passing to `NearbyConnectionManager.sendRaw()`.
- **Leakage:** Envelope metadata (`from`, `to`, `msgId`) is exposed in plaintext on the local airwaves.

### Zone 5: Untrusted Relay Mesh & Global Tor Transit
- **Components:** Intermediate peer nodes forwarding packets via `MeshProtocol.TYPE_RELAY` (store-and-forward flooding with TTL = 5).
- **Trust Level:** **Completely Untrusted.**
- **Security Guarantee:** Relays are treated as potential adversaries (can drop, reorder, or duplicate envelopes).
- **Protective Control:** Relays cannot read inner payloads (`ciphertextHex`) or forge signatures (`signatureHex`). Relays cannot modify the payload without causing cryptographic verification failure at the destination.

### Zone 6: Remote Peer Endpoint
- **Components:** Authenticated remote contact.
- **Trust Level:** **Semi-Trusted.**
- **Security Guarantee:** Authenticated via Ed25519 signing public key.
- **Protective Control:** Remote peer cannot forge messages from the local user or other contacts. Group permissions prevent unauthorized administrative commands.
- **Exposure / Risk:** Remote peer can capture screenshots, export plaintexts, or re-share received messages. Non-repudiable Ed25519 signatures allow the remote peer to prove local user authored the messages.

---

## 3. Trust Boundary Transitions & Verification Checklist

When data flows from an untrusted or less-trusted zone into a more-trusted zone, strict validation must occur at the boundary:

| Boundary Transition | Ingress Point | Cryptographic Validation Required | Status in Current Code |
|:---|:---|:---|:---:|
| **Zone 5/4 → Zone 1** (Network ingress to app memory) | `MessageRouter.handleNearbyPayload()` / `handleTorPayload()` | 1. Frame size check (`MAX_FRAME_BYTES = 2MB`)<br>2. JSON schema validation (`MeshProtocol.parseEncrypted()`)<br>3. Hex sanity checks (`isHex` for keys, nonces, signatures) | **ENFORCED** 🟢 |
| **Zone 5/4 → Zone 1** (Envelope authentication) | `MessageRouter.handleEncrypted()` | 1. Destination check: `payload.toSigningKey == mySigningKeyHex`<br>2. Sender contact lookup in local `contacts` table<br>3. Ed25519 signature verification: `CryptoManager.verify(ciphertext + nonce, sig, senderSigPub)` | **ENFORCED** 🟢 |
| **Zone 5/4 → Zone 1** (Payload decryption) | `MessageRouter.handleEncrypted()` | 1. Libsodium `crypto_box_open_easy()` verification of Poly1305 MAC tag<br>2. Replay duplicate check in `MessageDao` via `messageId` | **ENFORCED** 🟢 |
| **Zone 5/4 → Zone 1** (Group message authentication) | `MessageRouter.handleGroupMessage()` | 1. Outer libsodium pairwise decryption<br>2. `schemaVersion == 2` check (rejects legacy unencrypted frames)<br>3. Group membership check for sender and receiver<br>4. AES-256-GCM authentication tag check with AAD (`groupId:keyVersion`)<br>5. Cross-check: `inner.senderKey == senderKey` | **ENFORCED** 🟢 |
| **Zone 5/4 → Zone 1** (Group event audit ledger) | `GroupEventManager.verifyIncoming()` | 1. Group ID match<br>2. Replay check in `processed_group_events`<br>3. Ed25519 signature check over canonical JSON string | **ENFORCED** 🟢 (Note: JSON key ordering issue audited in Phase 1) |
| **Zone 3 → Zone 1** (Tor loopback to app) | `TorSocketServer.handleClient()` | Connection to `127.0.0.1:$port` | 🟡 **PARTIAL:** Loopback socket accepts connections without shared auth secret; relies downstream on `handleEncrypted()` cryptographic verification. |
| **Zone 1 → Zone 2** (Memory to SQLite persistence) | `db.messageDao().insertMessage()` | Writing plaintext message text and group keys to SQLite | 🔴 **UNPROTECTED:** Database is stored in plaintext SQLite on disk. |
