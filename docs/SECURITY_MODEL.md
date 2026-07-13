# Security Model

TorX One is designed to reduce dependency on central infrastructure and protect message content during transport.

This document explains what the app protects, what it does not yet fully protect, and which assumptions matter.

## Security goals

TorX One aims to provide:

- Cryptographic identity instead of phone-number identity
- Message encryption before transport
- Sender authentication through signatures
- Onion-service reachability without exposing a public IP address
- Contact verification through identity fingerprints
- Password-protected identity backup
- Founder verification through an official signing key

## Identity

Each user has cryptographic keys. Display names are not trusted identifiers.

The identity fingerprint is the human-checkable representation of the user's cryptographic identity.

## Message protection

Messages are encrypted at the app layer before they are sent through transport.

TorX One uses libsodium-compatible primitives:

- X25519 key agreement
- XSalsa20-Poly1305 through `crypto_box_easy`
- Ed25519 signatures for sender authentication

In simple terms:

- X25519 helps two users create a shared secret without sending the secret directly.
- The message is locked with encryption before it leaves the sender's device.
- Poly1305 helps detect tampering.
- Ed25519 signatures prove which identity created the encrypted payload.

## Tor protection

Tor hidden services help hide network location.

Tor protects:

- The user's public IP address
- The need for a central public server
- Remote reachability through an onion address

Tor does not replace message encryption. Tor is the private route; app-layer encryption protects the message content.

## Founder verification

Founder status is tied to the official Founder signing public key.

This prevents a user from gaining Founder status just by changing their display name to "Nikhilesh" or "Founder".

## Backup protection

Identity backups are encrypted with a user-provided password.

The password is not recoverable by TorX One. If the password is lost, the backup cannot be restored.

## Current beta limitations

TorX One is not yet independently audited.

Known areas for future hardening:

- Forward secrecy and ratcheting
- Stronger local database encryption
- More robust replay protection
- More extensive protocol test vectors
- Full adversarial security review
- Full migration away from legacy contact URI naming if required

## Safe wording

For public presentation, describe TorX One as:

> A beta privacy-first decentralized messenger using Tor hidden services, local cryptographic identities, and app-layer encrypted message payloads.

Avoid claiming it is fully audited or suitable for high-risk operational use until an independent security review is complete.

