# TorX One

> **Privacy isn't a feature. It's a fundamental right.**

TorX One is a research-driven Android prototype exploring private, resilient, and user-controlled communication. It combines cryptographic identity, Tor hidden-service reachability, nearby transport options, QR-based contact exchange, and a mobile-first experience.

This repository documents the research foundation, problem analysis, and architectural motivation behind the prototype.

## Executive summary

Digital communication is now essential for personal relationships, education, healthcare, journalism, business, and emergency response. End-to-end encryption has improved the confidentiality of message content, but important challenges remain: metadata exposure, platform-controlled identity, centralized infrastructure, and limited resilience during outages or network restrictions.

TorX One investigates whether communication can become more private, resilient, and user-controlled through decentralized identity and privacy-preserving networking. It is not intended to claim that existing secure messengers are ineffective. Instead, it explores an alternative combination of ideas from decentralized systems, anonymous networking, secure messaging, and nearby communication.

## Problem statement

Modern messaging platforms have made major progress in protecting message contents. However, many communication systems still depend on centralized infrastructure for identity management, contact discovery, message delivery, account recovery, and service availability.

This creates five connected problems:

1. **Identity ownership:** identities are often tied to a phone number, email address, or platform account. If that account is unavailable, users may lose access to their communication network.
2. **Metadata privacy:** even when message content is encrypted, timing, frequency, relationship patterns, and network activity can reveal sensitive information.
3. **Digital profiling:** users increasingly see services and advertisements influenced by searches, browsing, application activity, and other available signals. This has increased concern about tracking, profiling, and control over personal data.
4. **Infrastructure dependency:** centralized services can become single points of failure during outages, censorship, infrastructure failures, or regional restrictions.
5. **Resilient communication:** disasters, unstable connectivity, and restricted networks create a need for communication that can use more than one transport path.

### The gap in existing privacy-focused apps

Privacy-oriented applications already exist, but they make different trade-offs. Some prioritize excellent end-to-end encryption while retaining account or relay infrastructure. Some provide anonymity or decentralization but require a more complex setup. Some support peer-to-peer or nearby communication but have limited remote reachability.

The research gap is the opportunity to explore one understandable mobile system that integrates:

- user-owned cryptographic identity;
- privacy-oriented routing and Tor hidden-service reachability;
- nearby Bluetooth/Wi-Fi communication options;
- simple QR and fingerprint-based contact verification;
- modular transports for future extension; and
- an everyday user experience that clearly shows security state.

TorX One does not claim that existing projects are bad. Its difference is architectural: it brings these capabilities together in one Android-first prototype so that identity, reachability, routing, verification, and usability can be studied as one system.

## Research motivation

TorX One began with research rather than with a feature list. The project asks:

- Which privacy problems have already been solved?
- Which limitations remain in current systems?
- What trade-offs do existing secure messengers make?
- Can a modular architecture combine user-controlled identity with multiple transport mechanisms?

### Research themes reviewed

#### Metadata-resistant encrypted messaging

Research on metadata-resistant messaging demonstrates that protecting message text alone is not comprehensive privacy. Metadata can reveal social relationships, communication patterns, activity timelines, and network behavior.

**Insight:** encryption protects what people say; metadata may still reveal who communicates, how often, and when.

**TorX One direction:** investigate privacy at the architectural level by considering identity, routing, reachability, and transport—not only message encryption.

#### Comparative studies of Android messaging applications

Comparative research on privacy-focused Android messengers shows that systems such as Signal, Session, Briar, and SimpleX make different choices around encryption, identity, relays, decentralization, offline operation, usability, and trust assumptions.

**Insight:** no single design maximizes privacy, decentralization, offline capability, usability, and flexibility at the same time.

**TorX One direction:** explore a modular combination of user-controlled identity, Tor reachability, nearby transports, and visible verification in a single mobile prototype.

## Proposed solution

TorX One explores a communication model in which:

- a device creates and stores a local cryptographic identity;
- contacts can be exchanged through QR codes and checked using identity fingerprints;
- a device can expose a Tor v3 hidden service for private remote reachability;
- nearby Bluetooth/Wi-Fi transport can support local or intermittent connectivity scenarios; and
- application-layer cryptographic protection is used for message payloads.

Tor routing and message encryption solve different problems. Tor helps reduce exposure of a device's network location and provides onion-service reachability. The cryptographic message layer protects payload contents between authorized identities. A Tor connection by itself should not be described as end-to-end encryption.

## Core capabilities

- Local cryptographic identity and fingerprint display
- Tor v3 hidden-service address generation and connectivity status
- Nearby Bluetooth/Wi-Fi transport foundation
- QR-based identity/contact exchange and scanning
- Encrypted identity backup and restore
- Security-state visibility for users
- Modular Android architecture for future transports and features

## Technical architecture

```text
User A device                         User B device
┌─────────────────┐                  ┌─────────────────┐
│ TorX One app    │                  │ TorX One app    │
│ Identity + chat │                  │ Identity + chat │
└───────┬─────────┘                  └─────────┬───────┘
        │                                      │
        ├── App-layer encrypted payloads ──────┤
        │                                      │
   ┌────▼────┐       Tor network        ┌─────▼───┐
   │ Tor v3  │ ◄──────────────────────► │ Tor v3  │
   │ onion   │                           │ onion   │
   └─────────┘                           └─────────┘
        ▲                                      ▲
        └──── nearby Bluetooth/Wi-Fi option ──┘
```

The prototype is Android-first and uses Kotlin, Jetpack Compose, coroutines/flows, an embedded Tor binary, local persistence, and cryptographic libraries. Legacy `astra:` contact identifiers may remain for compatibility while the product branding is TorX One.

## Why it matters

TorX One is designed for people who need more control over identity and communication paths: privacy-conscious users, journalists, activists, researchers, communities in restricted networks, and people who may need communication during outages or infrastructure disruption.

The intended contribution is architectural exploration—not a claim that one prototype eliminates every privacy or availability risk. Future work includes stronger metadata resistance, formal security review, broader transport interoperability, improved offline delivery, and independent usability testing.

## Project status

TorX One is an active research prototype. Features and security properties may evolve as testing, review, and implementation continue. Do not use the prototype as the sole communication channel for high-risk situations without independently evaluating its threat model.

## License

TorX One is distributed under the MIT License. See [LICENSE](LICENSE).

## Acknowledgements

- [The Tor Project](https://www.torproject.org/)
- [Android Open Source Project](https://source.android.com/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Guardian Project](https://guardianproject.info/)
