# TorX One — Security Threat Model (Phase 0)

**Document Version:** 1.0.0  
**Status:** Baseline Security Audit  
**Date:** September 2026  
**Target Codebase:** TorX One (Android / iOS / Desktop)  
**Package:** `com.torxone.app`

---

## 1. Executive Summary

TorX One is a peer-to-peer, zero-knowledge metadata-minimizing communication system designed to operate resiliently across heterogeneous physical and overlay transports:
1. **Nearby Direct** (Bluetooth LE / Wi-Fi Aware via Google Nearby Connections `P2P_CLUSTER`)
2. **Nearby Mesh Relay** (multi-hop store-and-forward peer flooding)
3. **Tor Onion Services** (v3 hidden services `.onion` over SOCKS5 proxy)
4. **Local Wi-Fi Direct** (P2P direct sockets)

This document formalizes the **Threat Model** under Phase 0 of the TorX Security Baseline. It establishes the security guarantees, delineates trust boundaries, profiles potential adversaries, and explicitly defines what TorX One protects against and what falls outside its defensive scope.

---

## 2. Threat Actors and Adversary Profiles

We evaluate the system against eight specific threat classes (A through H):

| Adversary ID | Designation | Capabilities | Scope & Position |
|:---:|:---|:---|:---|
| **A** | **Passive Network Observer** | Eavesdrops on radio frequency (Wi-Fi, Bluetooth LE) or Internet transit (ISP, Tor exit/guard nodes, local LAN routers). Records packets, inspects timings, packet sizes, and headers. | External wire / physical proximity / ISP |
| **B** | **Malicious Relay Node** | TorX peer participant forwarding mesh packets (`MeshProtocol.TYPE_RELAY`) or routing Tor traffic. Can drop, reorder, delay, duplicate, tamper with, or replay envelopes. | Active participant on mesh path |
| **C** | **Malicious Contact** | Authenticated peer on the user's contact list with whom an active session exists. Can export plaintexts, craft out-of-order protocol packets, or attempt impersonation. | Authorized communication endpoint |
| **D** | **Removed Group Member** | Former member of a group chat whose membership was revoked or who left the group. Retains historic keys and past messages. | Insider / ex-group member |
| **E** | **Compromised Phone (OS / Physical)** | Adversary obtains unlocked physical access, extracts SQLite database, executes malicious code with app privileges, or gains root access on the Android OS. | Local endpoint host |
| **F** | **Malicious Co-located App / Process** | Non-root third-party application running on the same device without root access, probing Android IPC, local loopback sockets (127.0.0.1), shared storage, or notifications. | Local sandbox neighbor |
| **G** | **Replay Attacker** | Network observer capturing valid intercepted ciphertext envelopes and transmitting them again later over Nearby or Tor to confuse state or trigger duplicate actions. | External active network injector |
| **H** | **Malformed / Malicious Packet Sender** | Sends arbitrarily corrupted, mutated, oversized, or nested JSON/binary frames to crash the daemon, exhaust memory, or exploit serialization parsers. | Remote or proximity fuzzer |

---

## 3. Adversary Capabilities & Defensive Matrix

| Adversary | Security Objective | Does TorX Protect? | Mechanism & Baseline Reality | Remaining Risk / Vulnerability |
|:---|:---|:---:|:---|:---|
| **A: Network Observer** | Intercept message content | **YES** 🟢 | Direct messages are encrypted with libsodium `crypto_box_easy` (X25519 + XSalsa20-Poly1305). Tor links are multi-hop TLS/onion encrypted. | Traffic volume, packet timing, and packet length metadata leaks. Plaintext recipient signing key `to` is visible on Nearby mesh envelopes. |
| **A: Network Observer** | Unlink sender & receiver | **PARTIAL** 🟡 | On Tor transport: High anonymity via v3 onion hidden services (`.onion` to `.onion`). On Nearby: Sender/receiver signing keys (`from`, `to`) are exposed in unencrypted JSON envelope headers. | Nearby observer within 30m can map persistent Ed25519 addresses to physical presence. |
| **B: Malicious Relay** | Read relayed message payload | **YES** 🟢 | Relay payloads encapsulate inner `ciphertextHex`, `nonceHex`, and `signatureHex`. Relay cannot decrypt without recipient X25519 secret key. | Relay can log transmission graph: `from` and `dest` Ed25519 public keys, timestamp, packet size, and TTL. |
| **B: Malicious Relay** | Modify relayed payload | **YES** 🟢 | Tampering with ciphertext or nonce invalidates Ed25519 signature and Poly1305 MAC tag. Recipient drops modified payloads. | Relay can drop messages (denial of service) or delay delivery. |
| **B: Malicious Relay** | Replay relayed payload | **PARTIAL** 🟡 | Message ID deduplication in database (`db.messageDao().getMessageById(messageId)`) drops duplicates if ID matches, but message ID is outside signature. | Replay protection is not enforced at session cryptographic layer; relies on app database checks. |
| **C: Malicious Contact** | Forge messages from victim | **YES** 🟢 | Ed25519 signatures are detached and verifiable only with sender's private signing key. Contact cannot produce valid signatures for other keys. | None on signature forgery. |
| **C: Malicious Contact** | Replay old messages | **PARTIAL** 🟡 | TorX lacks a Double Ratchet or dynamic ratcheting session counter; relies on static keys and `messageId` deduplication in Room DB. | If database is cleared or `msgId` stripped/mutated, old ciphertexts can be re-decrypted by recipient. |
| **C: Malicious Contact** | Prove to 3rd party victim sent message (Repudiation) | **NO** 🔴 | TorX uses detached digital signatures (`crypto_sign_detached` via Ed25519). The signature is non-repudiable proof that the sender signed the ciphertext. | Unlike Signal or OTR (which use deniable MACs/ring signatures), Ed25519 provides mathematical non-repudiation. |
| **D: Removed Member** | Read future group messages | **YES** 🟢 | Group creator/admin rotates group AES-256-GCM key upon removal (`rotateAndDistribute`) and excludes removed member from key delivery. | Forward secrecy is preserved *if and only if* key rotation message reaches all members before new messages are posted. |
| **D: Removed Member** | Read past group messages | **NO** (By Design) 🟡 | Group keys prior to removal were known to the member. Historic messages already decrypted remain in local cache. | Backward secrecy is not retroactive to historic data already received. |
| **E: Compromised Phone** | Extract private keys | **YES** (Non-root) 🟢 / **NO** (Root) 🔴 | Keys stored in `EncryptedSharedPreferences` backed by Android Keystore (`MasterKey.KeyScheme.AES256_GCM`). Hardware TEE/StrongBox protects keys against non-root apps. | Root access or memory dumps of running TorX process can extract keys in RAM (`cachedEncSec`, `Identity`). |
| **E: Compromised Phone** | Extract chat database | **NO** 🔴 | Room database file `astra-mesh-db` is standard unencrypted SQLite. Group AES keys are stored plaintext in `group_keys.aesKeyBase64`. | Unencrypted at rest. An attacker with physical access or forensic extraction can read all messages and group keys. |
| **F: Malicious App** | Read keys via IPC / prefs | **YES** 🟢 | Android app sandbox enforces UID isolation. `EncryptedSharedPreferences` files cannot be read without app UID or Keystore authorization. | Local loopback `127.0.0.1:$port` for Tor socket server is reachable by other apps on device unless Unix domain sockets or auth tokens are used. |
| **G: Replay Attacker** | Replay direct messages | **PARTIAL** 🟡 | Handled via Room DB lookup: `if (messageDao().getMessageById(messageId) != null) ignore`. However, `messageId` is in plaintext JSON outside signature. | Mutating `msgId` in packet allows re-submission of ciphertext. Recipient will decrypt identical plaintext and insert duplicate message! |
| **H: Malformed Packet** | Memory corruption / RCE | **YES** 🟢 | Implemented in memory-safe Kotlin with JVM garbage collection. Maximum frame limit `MAX_FRAME_BYTES = 2MB`. | JSON parser denial-of-service (deeply nested JSON causing stack overflow or excessive GC pressure). |

---

## 4. What TorX One Protects Against

1. **Passive Wiretap & Interception:** All messages, media offers, call signaling, and group payloads traveling over Wi-Fi, Bluetooth LE, or Internet transit are strongly encrypted end-to-end (libsodium X25519-XSalsa20-Poly1305 and AES-256-GCM).
2. **Untrusted Intermediate Relays:** Mesh hops cannot inspect or modify payloads. Payloads remain end-to-end encrypted between the originating sender and final destination.
3. **Payload Tampering & Injection:** Cryptographic MACs (Poly1305, 128-bit GCM tags) and detached Ed25519 digital signatures guarantee message integrity; modified packets fail authentication and are dropped immediately.
4. **Identity Impersonation:** Every peer is uniquely and immutably identified by their Ed25519 signing public key. Forging an identity requires breaking 256-bit elliptic curve cryptography.
5. **Unauthorized Group Membership Actions:** Group actions (promotions, info updates, kick/bans) are gated by `GroupPermission` and cryptographically signed control events (`GroupEventManager.SignedEvent`).
6. **Future Eavesdropping by Evicted Group Members:** Evicting a group member triggers immediate AES-256 group key rotation distributed exclusively to active, authorized peers.

---

## 5. What TorX One DOES NOT Protect Against (Current Limitations)

1. **Compromised Device / Root Storage Access:**
   - The active SQLite database (`astra-mesh-db`) is stored in plaintext on the internal filesystem.
   - Group symmetric keys are stored in plaintext in the Room database (`group_keys` and `group_events` tables).
2. **Compromise of Historical Keys (Lack of Forward Secrecy / Ratchet):**
   - Pairwise messaging relies on long-term static identity keys (X25519). If an endpoint's static private encryption key is compromised at any point in the future, **all past captured ciphertexts can be decrypted**.
3. **Traffic Analysis & Proximity Metadata on Nearby Transports:**
   - Over Nearby mesh, envelopes expose `fromSigningKey` and `toSigningKey` in plaintext headers. Observers in physical proximity can detect who is communicating with whom and track physical movements.
4. **Denial of Service via Packet Dropping:**
   - Malicious mesh nodes can silently drop packets or refuse to relay, degrading network availability.
5. **Deniability (Non-repudiation Leaks):**
   - Because TorX currently signs `ciphertext + nonce` with the sender's Ed25519 signing key, the recipient can cryptographically prove to a third party that the sender authored the message.
6. **Local Loopback Probing:**
   - `TorSocketServer` listens on TCP port `127.0.0.1:port` without authentication tokens. Any local app running on the same Android device with `INTERNET` permission can connect to this localhost port and inject fake packets.

---

## 6. Trust Boundaries & Assumptions

1. **Hardware & OS Keystore:** TorX trusts the Android Keystore / KeyMint TEE to securely protect the AES-256 MasterKey used for `EncryptedSharedPreferences`.
2. **Localhost Loopback Isolation:** Assumes other applications cannot hijack or eavesdrop on local Unix sockets or that Android network sandbox prevents unauthorized inter-app socket access unless ports are explicitly exposed.
3. **Cryptographic Primitives:** Assumes the mathematical security of Curve25519, Ed25519, XSalsa20, Poly1305, and AES-256-GCM.
4. **Tor Network Integrity:** Assumes the Tor network successfully protects anonymity and prevents onion service rendezvous correlation by network adversaries.
