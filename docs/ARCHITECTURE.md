# Architecture

TorX One is structured as an Android-first decentralized messenger.

## High-level model

```text
User Device A
  |
  | encrypted message payload
  v
Transport layer
  |-- Tor hidden service
  |-- Nearby direct transport
  |-- Nearby mesh forwarding
  v
User Device B
```

The transport layer moves encrypted payloads. It should not need to understand plaintext message content.

## Main components

### Identity layer

Responsible for:

- Creating local identity keys
- Exposing identity fingerprint
- Exporting contact data
- Parsing contact data
- Supporting identity backup and restore

### Cryptography layer

Responsible for:

- Encrypting message payloads
- Decrypting received payloads
- Signing outbound encrypted payloads
- Verifying inbound payload signatures

### Tor layer

Responsible for:

- Starting the embedded Tor runtime
- Bootstrapping Tor connectivity
- Creating a v3 hidden service
- Publishing an onion address
- Connecting to contact onion addresses

### Nearby transport layer

Responsible for:

- Nearby discovery
- Bluetooth / Wi-Fi Direct transport
- Local direct delivery
- Mesh-style forwarding when supported

### UI layer

Responsible for:

- Identity setup
- Chat list
- Contact management
- Security status
- Founder profile presentation
- Settings, privacy policy, license, backup and restore

## Android implementation areas

Important Android source areas:

- `crypto` - identity contact encoding, encryption, parsing
- `identity` - profile and backup/restore logic
- `network` - Tor and routing logic
- `nearby` - nearby transport integration
- `ui` - Compose screens and components
- `updater` - GitHub Releases update flow

## Data flow for sending a message

1. User types a message.
2. App resolves the recipient contact.
3. Message payload is encrypted for the recipient.
4. Encrypted payload is signed by the sender.
5. Router selects available transport.
6. Payload is delivered through nearby transport or Tor.
7. Receiver verifies the signature.
8. Receiver decrypts the payload.
9. Message appears in the chat.

## Design principle

The route is not the security boundary. The encrypted payload is the security boundary.

Tor protects network reachability and IP privacy. App-layer encryption protects message content.

