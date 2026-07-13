# TorX One Shared Protocol Specification

This document defines the cryptographic and transport rules that TorX One
clients must implement to interoperate across platforms.

The intended architecture is:

- **Nearby direct** delivery for peers in range
- **Nearby mesh forwarding** through connected peers when needed
- **Tor hidden services** for long-distance delivery

Normal messaging must **not depend on a permanent central relay server**.

---

## 1. Cryptographic primitives

All clients **MUST** use **libsodium-compatible** primitives so ciphertext,
signatures, and contact data remain byte-for-byte compatible.

| Platform | Library |
|----------|---------|
| Android  | `lazysodium-android` |
| iOS      | `swift-sodium` |

### 1.1 Identity

Each identity contains two keypairs:

| Keypair | Algorithm | Size | libsodium function |
|---------|-----------|------|--------------------|
| Encryption | X25519 | 32-byte public + 32-byte secret | `crypto_box_keypair()` |
| Signing | Ed25519 | 32-byte public + 64-byte secret | `crypto_sign_keypair()` |

The Ed25519 public key is the user's stable **address key** inside TorX One.

### 1.2 Message encryption

Messages use libsodium `crypto_box_easy`:

```
Encrypt:
  nonce      = randombytes_buf(24)
  ciphertext = crypto_box_easy(
      utf8_encode(plaintext),
      nonce,
      recipientEncryptionPublicKey,
      senderEncryptionSecretKey
  )

Decrypt:
  plaintext_bytes = crypto_box_open_easy(
      ciphertext,
      nonce,
      senderEncryptionPublicKey,
      recipientEncryptionSecretKey
  )
```

`crypto_box_easy` produces `plaintext_length + 16` bytes because of the
Poly1305 authentication tag.

### 1.3 Message signing

Each encrypted payload is signed using the sender's Ed25519 secret key:

```
data_to_sign = ciphertext_bytes || nonce_bytes
signature    = crypto_sign_detached(data_to_sign, senderSigningSecretKey)
```

Verification:

```
valid = crypto_sign_verify_detached(
    signature,
    ciphertext_bytes || nonce_bytes,
    senderSigningPublicKey
)
```

### 1.4 Nonce rules

- Nonce length is **24 bytes**
- Nonces must be generated from a cryptographically secure RNG
- A nonce must never be reused with the same sender secret key and recipient public key pair

---

## 2. Contact string format

Users exchange identities with a copyable contact string.

The current URI prefix is `astra:`. This is a legacy protocol prefix from the earlier project name and is kept for compatibility with existing identities and backups until a code-level migration is implemented.

```
astra:<base64(JSON({
  "n": "<display name>",
  "e": "<x25519 public key hex>",
  "s": "<ed25519 public key hex>",
  "o": "<optional onion hostname>"
}))>
```

| Field | Required | Description | Encoding |
|-------|----------|-------------|----------|
| `n` | yes | display name | UTF-8 string |
| `e` | yes | X25519 encryption public key | 64-char lowercase hex |
| `s` | yes | Ed25519 signing public key | 64-char lowercase hex |
| `o` | no | Tor hidden-service hostname for distant messaging | lowercase `.onion` string |

### 2.1 Parsing rules

1. Trim whitespace.
2. Require prefix `astra:`.
3. Base64-decode the remainder.
4. Parse the decoded JSON object.
5. Require `n`, `e`, and `s`.
6. Decode `e` and `s` from lowercase hex.
7. If `o` exists, preserve it as lowercase text.

### 2.2 Semantics

- `s` is the contact's unique identity inside TorX One.
- `e` is used for `crypto_box` encryption.
- `o` is optional because a peer may be nearby-only, Tor-capable, or not yet ready.
- A contact string may be reshared later when the user's Tor address changes.

---

## 3. Transport model

TorX One uses the same encrypted payload across multiple transports.
Transport selection is a client policy decision, not a cryptographic change.

### 3.1 Transport priority

Recommended send order:

1. **Nearby direct** if the destination peer is currently connected
2. **Nearby mesh relay** if other nearby peers are connected
3. **Tor** if the contact has an `.onion` address and Tor is available

### 3.2 Tor transport

For long-distance delivery, each Tor-capable client exposes a local TCP server
as a Tor hidden service through Orbot or an equivalent Tor runtime.

Current Android reference behavior:

- local TCP listener on port `8765`
- one JSON frame per line
- outbound connections made through a SOCKS proxy exposed by Orbot

Other platforms may use different implementation details as long as the peer
wire format below remains identical.

### 3.3 Nearby transport

Nearby peers exchange the same JSON frames over the local transport channel.
Connected peers may forward encrypted packets without decrypting them.

---

## 4. Peer wire protocol

All peer frames are JSON objects. All binary values are encoded as
**lowercase hex strings**.

### 4.1 `hello`

Used when two nearby peers first connect so they can exchange contact data.

```json
{
  "type": "hello",
  "contact": "astra:<base64-json>"
}
```

Rules:

- `contact` must be a valid TorX One contact string
- clients should send `hello` after a nearby connection is established
- receiving a valid `hello` may create or update the local contact record

### 4.2 `msg`

Direct encrypted peer-to-peer message:

```json
{
  "type": "msg",
  "from": "<sender Ed25519 public key hex>",
  "to": "<recipient Ed25519 public key hex>",
  "ciphertext": "<crypto_box ciphertext hex>",
  "nonce": "<24-byte nonce hex>",
  "signature": "<64-byte detached Ed25519 signature hex>"
}
```

Validation rules:

1. Verify `to` is the local user's signing public key
2. Resolve sender contact by `from`
3. Verify signature against `ciphertext || nonce`
4. Decrypt with sender encryption public key + recipient encryption secret key

### 4.3 `relay`

Encrypted packet forwarded through intermediate nearby peers:

```json
{
  "type": "relay",
  "dest": "<final recipient Ed25519 public key hex>",
  "from": "<original sender Ed25519 public key hex>",
  "ttl": 5,
  "ciphertext": "<crypto_box ciphertext hex>",
  "nonce": "<24-byte nonce hex>",
  "signature": "<64-byte detached Ed25519 signature hex>"
}
```

Rules:

- `dest` is the final recipient
- `ttl` is a positive integer hop limit
- a forwarding peer must decrement `ttl`
- if `dest` matches the local identity, the client should process it as a direct encrypted message
- if `ttl <= 1`, the packet must not be forwarded further
- forwarding peers must not attempt to decrypt packets not addressed to them

### 4.4 Shared encrypted payload

The effective encrypted payload in both `msg` and `relay` is:

| Field | Description |
|-------|-------------|
| `from` | sender Ed25519 public key hex |
| destination | `to` for direct frames, `dest` for relay frames |
| `ciphertext` | `crypto_box_easy` output |
| `nonce` | 24-byte random nonce |
| `signature` | Ed25519 detached signature over `ciphertext || nonce` |

---

## 5. Hex encoding rules

All binary fields use lowercase hex with no `0x` prefix:

| Data | Raw bytes | Hex chars |
|------|-----------|-----------|
| X25519 public key | 32 | 64 |
| Ed25519 public key | 32 | 64 |
| Ed25519 signature | 64 | 128 |
| Nonce | 24 | 48 |
| Ciphertext | variable | variable |

---

## 6. Identity storage at rest

Private keys should be encrypted locally before export or software storage.
A compatible approach is libsodium `crypto_pwhash` + `crypto_secretbox_easy`.

```
Derive key:
  salt = randombytes_buf(16)
  key  = crypto_pwhash(32, passphrase, salt, ...)

Encrypt blob:
  nonce = randombytes_buf(24)
  data  = crypto_secretbox_easy(
      utf8_encode(JSON({
        name, encPub, encSec, sigPub, sigSec
      })),
      nonce,
      key
  )
```

Platform-specific secure storage such as Keychain, Android Keystore, or
EncryptedSharedPreferences may be used in addition to or instead of this format.

---

## 7. Compatibility notes

- Android is currently the reference implementation for the Tor-first transport model.
- Older relay-based clients are **not** the source of truth anymore.
- If a legacy relay exists for development, it must be treated as optional tooling rather than a required delivery path.

---

## 8. Security limitations

> **No forward secrecy.** Static `crypto_box` keys mean compromise of a user's
> long-term encryption key can expose past and future messages for that peer pair.

> **No safety-number verification yet.** Contact strings are trusted as received.
> A malicious contact exchange can still introduce impersonation.

> **Tor and nearby relays still expose some metadata.** Intermediaries may learn
> timing, packet sizes, and that traffic exists, even though they cannot read plaintext.

> **No replay protection yet.** The current frame format has no sequence number,
> timestamp window, or duplicate suppression rule.
