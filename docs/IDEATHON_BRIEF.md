# Ideathon Brief

## One-line pitch

TorX One is a privacy-first Android messenger that lets devices communicate through Tor hidden services and nearby mesh transport without depending on a central chat server.

## Simple explanation

Most chat apps need a company server in the middle. TorX One is different. Each phone creates its own secure identity and can become its own private communication node.

Users can share a QR code, add each other as contacts, and communicate through private routes such as Tor or nearby local transport.

## Problem statement

Digital communication often depends on centralized platforms. These platforms can collect metadata, enforce account rules, suffer outages, or become single points of control.

In sensitive situations, users may need communication that is more private, portable, and resilient.

## Proposed solution

TorX One gives each user:

- A local cryptographic identity
- A visible identity fingerprint
- A Tor hidden service address
- Nearby mesh transport support
- Encrypted message payloads
- Identity backup and restore

## Key features to demonstrate

1. App launch and identity setup
2. Security screen
3. Tor connected state
4. Onion address
5. Identity fingerprint
6. QR contact sharing
7. Contact scanning
8. Founder verified profile
9. Privacy policy and license sections

## Security explanation for judges

TorX One separates route privacy from message protection.

Tor helps hide where the device is on the network. App-layer encryption protects the message content before it is sent.

That means the route and the message security are separate layers.

## Why this matters

TorX One is useful as a prototype for:

- Serverless messaging
- Decentralized identity
- Censorship-resistant communication research
- Local mesh communication
- Privacy-first mobile design

## Current stage

TorX One is a beta Android prototype. It is ready for demonstrations and controlled testing, but it still needs more security review before being presented as production-grade secure communication software.

## Closing statement

TorX One is built on the idea that privacy should not be an optional feature. It should be part of the communication network itself.

