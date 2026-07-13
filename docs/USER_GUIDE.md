# User Guide

This guide explains the main user flows in TorX One.

## Create an identity

When TorX One starts for the first time, it creates a local identity. This identity contains cryptographic keys used to represent you inside the network.

Your display name is only a label. The real identity is the keypair and fingerprint.

## Verify your security status

Open the Security screen to check:

- End-to-end encryption status
- Identity fingerprint
- Tor network status
- Onion address
- Nearby transport status

If Tor is connected and an onion address is active, your device can be reached through Tor hidden service routing.

## Add a contact

You can add contacts in two ways:

1. Paste a contact key.
2. Scan an identity QR code.

The QR scanner is available beside the contact key field.

## Share your identity

Use the identity QR code or contact key when you want another trusted user to add you.

Only share your identity with people you trust. Anyone with your current contact data can attempt to contact your node through the supported transport paths.

## Send messages

After adding a contact, open the chat and send a message. TorX One chooses the available route based on contact data and transport state.

Possible route states include:

- Nearby route available
- Tor route available
- Route standby
- Secure route standby

## Founder profile

The Founder profile is a special verified profile. It is not granted by display name. It is tied to the official Founder signing public key.

This means a normal user changing their name to "Nikhilesh" does not receive Founder status.

## Identity backup

Use identity backup before uninstalling or changing devices.

The backup is password-protected. If the password is lost, TorX One cannot recover the backup for you.

