# Overview

TorX One is a privacy-first Android messenger for decentralized, serverless communication.

The project is built around one idea: private communication should not depend on a central server, phone number, or platform-controlled account system.

Each device creates its own cryptographic identity. That identity can be shared with trusted contacts through a contact key or QR code. Messages can then travel through Tor hidden services for remote communication or nearby transport such as Bluetooth and Wi-Fi Direct when devices are close to each other.

## Problem

Most messaging systems depend on centralized infrastructure:

- A company controls account creation.
- A phone number or email often becomes the user's identity.
- Central servers can see metadata such as who is online, who talks to whom, and when messages are delivered.
- If the server is blocked, down, compromised, or restricted, the communication network is weakened.

TorX One explores a different model: the user's device becomes the communication node.

## Solution

TorX One combines three layers:

1. Local cryptographic identity
2. Tor hidden service reachability
3. Nearby mesh transport

This allows users to communicate without requiring a permanent central messaging server.

## Core capabilities

- Local identity generation
- Identity fingerprint verification
- Contact sharing through keys and QR codes
- Tor v3 onion address support
- Nearby Bluetooth and Wi-Fi Direct transport
- App-layer encrypted message payloads
- Password-protected identity backup
- GitHub Releases based APK distribution

## What makes it different

TorX One is not just a private chat UI. It is designed as a communication node:

- The app can create a reachable onion address.
- Contacts are identified by cryptographic keys, not display names.
- The Founder profile is verified by a signing key, not by the name "Nikhilesh".
- The same encrypted message payload can move across different transports.

## Current limitations

TorX One is still beta software.

Current limitations include:

- Android is the primary working platform.
- Group messaging is not complete.
- Forward secrecy and advanced ratcheting are roadmap items.
- Local database storage still needs additional hardening.
- The protocol should receive independent security review before high-risk use.

