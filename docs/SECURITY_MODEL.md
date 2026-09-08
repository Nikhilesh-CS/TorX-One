# TorX One: Security Baseline & Threat Model (Phase 0)

## 1. System Overview & Architecture Boundaries

TorX One is a decentralized, peer-to-peer, encrypted communication mesh operating over heterogeneous transports (Bluetooth Low Energy / Nearby Connections, Wi-Fi Direct, and Tor Onion Services). 

```
┌────────────────────────────────────────────────────────┐
│                        UI Layer                        │
│            (Compose UI, ChatScreen, ViewModels)        │
└───────────────────────────┬────────────────────────────┘
                            │ (Plaintext, In-Memory)
┌───────────────────────────▼────────────────────────────┐
│                    Security / Session                  │
│       SessionManager  •  SessionRatchet  •  Cipher      │
│       (Double Ratchet: X25519, AES-256-GCM, HKDF)       │
└───────────────────────────┬────────────────────────────┘
                            │ (TYPE_SESSION_MSG Envelopes)
┌───────────────────────────▼────────────────────────────┐
│                      MessageRouter                     │
│       (Hop Relaying, De-duplication, Flow Control)     │
└───────┬───────────────────┼────────────────────┬───────┘
        │                   │                    │
┌───────▼────────┐  ┌───────▼────────┐  ┌────────▼───────┐
│ Nearby BLE/WiFi│  │  Wi-Fi Direct  │  │ Tor (Hidden Sv)│
│   (Local Hop)  │  │   (Local P2P)  │  │ (Global Relay) │
└────────────────┘  └────────────────┘  └────────────────┘
```

---

## 2. Component Inventory & Responsibilities

| Area | Component | Security Responsibility |
|------|-----------|-------------------------|
| **Crypto** | `CryptoManager` | Ed25519 signing/verification, X25519 encryption/decryption, libsodium integration. |
| **Identity** | `IdentityManager` | Long-term identity key generation, Android Keystore + `EncryptedSharedPreferences` storage. |
| **Session** | `SessionManager` | Double Ratchet session lifecycle, handshake negotiation, signature authentication. |
| **Ratchet** | `SessionRatchet` | HMAC-SHA256 symmetric KDF stepping, RFC 5869 HKDF expansion, Curve25519 ECDH calculations. |
| **Cipher** | `SessionCipher` | AES-256-GCM authenticated encryption with 12-byte random IVs, canonical AAD binding, memory zeroization. |
| **Replay** | `ReplayProtection` | Monotonic session sequence tracking, duplicate detection, sliding replay window. |
| **Router** | `MessageRouter` | Transport selection (Nearby vs Tor), mesh multi-hop packet flood, retry loops. |
| **Data** | `AppDatabase` | SQLite/Room persistence of contacts, message history, active session ratchets, and skipped keys. |

---

## 3. Threat Model & Attacker Capabilities

### 3.1 Network Adversaries
1. **Passive Eavesdroppers**:
   - *Bluetooth/Wi-Fi snooping*: Captures all wireless frames broadcast over Nearby Connections.
   - *Tor exit/relay observation*: Observes packet timing and metadata on public relays.
   - *Mitigation*: End-to-end encryption via AES-256-GCM. Wireless transports only carry encrypted envelopes (`TYPE_SESSION_MSG`).
2. **Active Network Attackers (Man-in-the-Middle)**:
   - *Packet modification & bit flipping*: Attacker attempts to modify ciphertext or routing headers.
   - *Mitigation*: AES-256-GCM authentication tag (128-bit) and Ed25519 digital signature over sender, recipient, type, session, counter, timestamp, and ciphertext.
   - *Replay attacks*: Attacker intercepts and replays valid packets.
   - *Mitigation*: Monotonic per-session sequence counters (`msgNum`) tracked in SQLite and sliding replay windows.
   - *Packet re-ordering & loss*: Adversary delays or drops packets across multi-hop paths.
   - *Mitigation*: Skipped message key store allows legitimate out-of-order packets to decrypt securely while preserving forward secrecy.
3. **Malicious / Compromised Mesh Relays**:
   - *Rogue mesh nodes*: Intermediate peers who relay envelopes to bridge network gaps.
   - *Mitigation*: Relays only read unauthenticated hop metadata (`to`, `ttl`, `msgId`). Relays cannot decrypt payload or forge signatures.

### 3.2 Device & Physical Adversaries
1. **App Lock & Forensic Extraction**:
   - *Locked Device*: Biometric / PIN app lock gates UI entry and database access.
   - *Background Operation*: Background service retains active session keys in memory to process real-time mesh routing and incoming calls.
   - *Keystore Protection*: Master key is protected by Android Keystore hardware-backed security module (StrongBox / TEE).
2. **Key Compromise & Post-Compromise Security**:
   - *Ephemeral message key compromise*: Forward secrecy guarantees past messages cannot be decrypted.
   - *Temporary state compromise*: Asymmetric DH ratcheting automatically recovers secrecy as soon as an uncompromised DH keypair is introduced (Break-in Recovery).

---

## 4. Key Hierarchy & Lifecycle

```
Long-term Identity Keypairs (Ed25519 Signing + X25519 Encryption)
                          │
                          ▼ Initial Dual-ECDH Handshake
             Shared Initial Root Key (32 bytes)
                          │
            ┌─────────────┴─────────────┐
            ▼ (DH Ratchet Step)         ▼
      Next Root Key               Send/Recv Chain Keys
                                        │
                                        ▼ (Symmetric KDF Step: HMAC-SHA256)
                                 Ephemeral Message Key (32 bytes)
                                        │
                                        ▼ (AES-256-GCM Encryption)
                                 [Memory Zeroization] (Arrays.fill(0))
```

---

## 5. Wire Protocol & Cryptographic Invariants

### 5.1 Envelopes (`TYPE_SESSION_MSG`)
```json
{
  "type": "session_msg",
  "schemaVersion": 1,
  "msgId": "uuid-v4",
  "sessionId": "uuid-v4",
  "msgNum": 4,
  "innerType": "msg",
  "from": "<Ed25519-signing-pubkey-hex>",
  "fromEnc": "<X25519-encryption-pubkey-hex>",
  "to": "<Ed25519-recipient-signing-pubkey-hex>",
  "ratchetPub": "<X25519-current-ratchet-pubkey-hex>",
  "ciphertext": "<base64-aes-gcm-ciphertext-and-tag>",
  "iv": "<base64-12-byte-iv>",
  "signature": "<Ed25519-detached-signature-hex>",
  "timestamp": 1773091200000,
  "ttl": 3
}
```

### 5.2 Canonical AAD (Additional Authenticated Data)
```text
sessionId:msgNum:senderKey:recipientKey:timestamp
```
Ensures that:
1. Messages cannot be transplanted between sessions.
2. Messages cannot be replayed under a different sequence counter.
3. Messages cannot be spoofed to a different recipient.
4. Timestamps cannot be tampered with by intermediate relays.

### 5.3 Digital Signature Invariant
The sender signs:
```text
from|fromEnc|to|type|sessionId|msgNum|ratchetPub|ciphertext|iv|timestamp
```
Signature verification occurs BEFORE any ratchet step or decryption is attempted, thwarting chosen-ciphertext and state-corruption attacks.
