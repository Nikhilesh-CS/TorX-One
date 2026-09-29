# TORX ONE — OFFICIAL MASTER PLAN

## 1. Product Mission

**TorX One is a secure, offline-first communication platform designed to keep people connected even when direct Internet access is unavailable.**

TorX should automatically use the best available communication path:

Nearby communication → local mesh → long-range radio → Tor → store-and-forward.

The user should not need to understand or manually choose the transport.

---

# 2. Core Principles

These are permanent TorX requirements.

1. Security and privacy come before features.
2. All messages remain end-to-end encrypted regardless of transport.
3. TorX must work locally without Internet.
4. Tor is a core part of TorX, not an optional future feature.
5. TorX must eventually support multi-hop offline communication.
6. TorX must support store-carry-forward when no live route exists.
7. The user's phone should not require direct Internet access for communication whenever another TorX route exists.
8. Transport switching should happen automatically.
9. Intermediate relay devices must never read message contents.
10. Use current, production-grade, actively supported technology.
11. Do not use experimental technology merely because it is newer.
12. Every protocol change must be versioned and migration-safe.
13. Never advertise functionality that has not actually shipped.
14. Every important subsystem must survive crashes, restarts, duplication, packet loss and network changes.
15. TorX should remain decentralized and infrastructure-optional wherever practical.

---

# 3. Target Architecture

```text
                         TORX ONE

                            │
                            ▼

                    Application Layer
              Chat / Groups / Calls / Media

                            │
                            ▼

                    TorX Secure Protocol

         Identity + Authentication + Sequencing
              + End-to-End Encryption
                 + Double Ratchet

                            │
                            ▼

                    Durable Message Layer

                  SQLCipher Database
                       Outbox
                  Retry / Recovery
                   Store-Forward

                            │
                            ▼

                    TorX Network Layer

             Discovery / Routing / Relay
           Deduplication / TTL / Congestion
              Store-Carry-Forward

                            │
                            ▼

                   Transport Router

       ┌──────────┬──────────┬──────────┬──────────┐
       │          │          │          │          │
       ▼          ▼          ▼          ▼          ▼

     Nearby      Wi-Fi      LoRa      HaLow       Tor
 Bluetooth     Direct/LAN              │
       │          │          │          │
       └──────────┴──────────┴──────────┴──────────┘

                            │
                            ▼

                         Peer
```

The TorX encrypted protocol must remain independent from the transport carrying it.

---

# PHASE 1 — Fix the Current Security Core

## Status

**CURRENT PHASE**

No major new features should be added until this phase is complete.

## Required fixes

* Fix Android Keystore / KeyProtector dependency injection.
* Add safe migration for incorrectly stored cryptographic secrets.
* Fix receive-sequence reservation race conditions.
* Require authenticated receiver acknowledgment for durable sequenced operations.
* Separate ephemeral traffic such as typing/presence from the durable message ratchet.
* Make durable encryption + outbox persistence crash-safe.
* Recover incomplete contact/bootstrap states after process restart.
* Fix group epoch recovery and partial fan-out failures.
* Fix group routing/topology assumptions.
* Bind delivery/read receipts to the authenticated relationship.
* Make edit/delete/reaction mutation atomic with outbound events.
* Introduce correct media offer/accept behavior.
* Remove remaining protocol parsing inconsistencies.
* Fix remaining call-state correctness problems.

## Exit criteria

```text
Critical security findings = 0
High release blockers = 0
```

Direct messages must survive:

* app process kill
* device restart
* network loss
* duplicated packet
* reordered packet
* delayed packet
* offline recipient
* retry after hours
* database reopen

without losing cryptographic synchronization.

---

# PHASE 2 — Protocol Stability and Testing

Freeze the TorX v1 messaging protocol temporarily.

Create extensive automated verification.

## Testing

* unit tests
* integration tests
* Android instrumentation tests
* property-based tests
* state-machine tests
* protocol fuzzing
* malformed-packet tests
* crash-injection tests
* SQLCipher migration tests
* process-death tests
* restart recovery tests
* packet duplication tests
* packet reordering tests
* packet-loss simulations

## Important invariants

TorX should prove things such as:

```text
A message sequence is never accepted twice.

N+1 cannot permanently overtake unresolved N.

A persisted ciphertext always has matching persisted ratchet state.

A valid relationship always has consistent crypto/session state.

Invalid packets never mutate application state.

Process death cannot permanently desynchronize a session.
```

## Exit criteria

Direct messaging becomes boringly reliable.

No protocol changes without explicit versioning after this point.

---

# PHASE 3 — Real Tor Transport

Tor must become a real production transport.

Create a Tor transport module.

```text
transport/
    nearby/
    tor/
        TorTransport
        TorController
        TorBootstrapManager
        TorConnectionState
        TorHealthMonitor
        TorRouteManager
        OnionEndpointManager
```

## Required behavior

Tor carries already-encrypted TorX ciphertext.

```text
Message
   ↓
TorX E2EE
   ↓
Durable Outbox
   ↓
TorTransport
   ↓
Tor network
   ↓
Peer
```

Tor never replaces TorX encryption.

## First milestone

Two Android devices exchange:

* text
* ACK
* reactions
* edits
* deletes
* receipts
* basic media control

through Tor while using the same TorX relationship/session model.

## Exit criteria

TorX genuinely works through Tor.

---

# PHASE 4 — Automatic Transport Routing

Unify Nearby and Tor behind TransportRouter.

Example:

```text
Send message
     │
     ▼
Peer reachable nearby?
     │
     ├── YES → Nearby
     │
     └── NO
            │
            ▼
       Tor available?
            │
            ├── YES → Tor
            │
            └── NO → Durable queue
```

Later this becomes more sophisticated.

## Requirement

Changing transport must not break:

* identity
* conversation
* message ordering
* Double Ratchet
* receipts
* retries

Example:

```text
College → Nearby

Bob goes home

TorX automatically switches → Tor

Same contact
Same conversation
Same cryptographic relationship
```

---

# PHASE 5 — Modernize Android Technology

After protocol stability, upgrade the Android platform.

Target current supported stable versions of:

* Android SDK / target SDK
* Kotlin
* Android Gradle Plugin
* Gradle
* Jetpack Compose
* Material 3
* Room
* DataStore
* CameraX
* Biometric
* Navigation
* Lifecycle
* Coroutines
* AndroidX libraries

Keep:

* SQLCipher
* Bouncy Castle
* Android Keystore
* Java/JVM 17 or appropriate supported version

## Rule

Upgrade one subsystem at a time.

Every upgrade must pass the complete protocol/security test suite.

---

# PHASE 6 — Security Storage Architecture V2

Create one authoritative cryptographic security subsystem.

```text
Android Keystore
       │
       ▼
SecurityRepository
       │
       ├── identity secrets
       ├── relationship secrets
       ├── queue authentication keys
       ├── session protection
       └── local security material
```

Remove insecure defaults such as production classes automatically using NoOp protectors.

## Storage format

Use explicit versions:

```text
CryptoFormatVersion 1
CryptoFormatVersion 2
CryptoFormatVersion 3
```

Each format must have explicit migration logic.

Use hardware-backed Keystore/StrongBox when appropriate and available.

---

# PHASE 7 — Dedicated Secure Media Transport

Large files should not behave like thousands of normal chat messages.

Architecture:

```text
Double Ratchet
      ↓
FILE_OFFER
      ↓
transfer key
      ↓
FILE_ACCEPT
      ↓
dedicated encrypted media stream
      ↓
chunks
      ↓
resume / pause / cancel
      ↓
FILE_COMPLETE
```

## Goals

* constant-memory streaming
* crash recovery
* resume support
* authenticated chunking
* bandwidth-efficient transfers
* auto-download enforcement
* large-file support

Target at least approximately 1 GB transfers reliably before considering the media system mature.

---

# PHASE 8 — Offline Multi-Hop Mesh

Build the TorX Network Layer.

A message should be capable of:

```text
Phone A
   ↓
TorX Node B
   ↓
TorX Node C
   ↓
Phone D
```

even if A and D cannot communicate directly.

## Required systems

* peer discovery
* route discovery
* routing table
* next-hop selection
* packet TTL
* duplicate suppression
* congestion limits
* relay permissions
* message prioritization
* encrypted forwarding
* route expiration

## Important rule

Do not use unlimited network flooding.

The design must be capable of scaling beyond a few phones.

---

# PHASE 9 — Store-Carry-Forward / Delay-Tolerant Networking

TorX must work even when no end-to-end route currently exists.

Example:

```text
10:00
Alice sends message.

No route exists.

Relay stores encrypted packet.

12:00
Relay encounters another TorX node.

Packet moves.

15:00
Another relay reaches Bob.

Message arrives.
```

The relay never decrypts the content.

## Required systems

* expiry / TTL
* queue quotas
* deduplication
* relay authorization
* priority classes
* storage limits
* delivery acknowledgment
* anti-loop protection
* abuse protection

This is how TorX becomes genuinely offline-first.

---

# PHASE 10 — Long-Range LoRa Transport

Prototype external long-range TorX hardware.

Initial design:

```text
Android
   │
 Bluetooth LE
   │
TorX Radio
 ESP32/nRF
   +
 SX1262 LoRa
   │
 kilometres
   │
TorX Radio
   │
BLE
   │
Android
```

Research existing systems such as Reticulum and Meshtastic before designing TorX routing from scratch.

## First milestone

```text
Phone A:
Airplane mode ON

Phone B:
Airplane mode ON

Internet:
NONE

Distance:
kilometres

TorX text:
DELIVERED
```

## Second milestone

```text
Phone A
   ↓
LoRa relay
   ↓
LoRa relay
   ↓
Phone B
```

---

# PHASE 11 — Wi-Fi HaLow

Research Wi-Fi HaLow for higher-bandwidth long-range connections.

Purpose:

```text
LoRa
→ maximum range / tiny bandwidth

Wi-Fi HaLow
→ long range / much higher bandwidth

Nearby
→ short range / high bandwidth
```

HaLow could eventually carry:

* images
* voice notes
* files
* higher-volume synchronization

while LoRa handles:

* texts
* emergency messages
* receipts
* routing/control packets

---

# PHASE 12 — TorX Gateways

Connect offline TorX networks to global routes.

```text
Offline phone
      ↓
Nearby / LoRa / HaLow
      ↓
TorX Gateway
      ↓
Tor
      ↓
remote TorX gateway
      ↓
remote local mesh
      ↓
recipient
```

This allows a user's phone to have:

```text
Cellular Internet: OFF
Wi-Fi Internet: OFF
```

while TorX still eventually reaches a global destination through another node.

That is the realistic interpretation of worldwide communication without requiring direct Internet on every phone.

---

# PHASE 13 — Group Messaging V2

Current pairwise group fan-out works for smaller groups but does not scale indefinitely.

Research:

* MLS
* sender keys
* tree-based group cryptography
* pairwise hybrid models
* relay-assisted distribution

Evaluate each against:

* offline usage
* multi-hop network operation
* member removal
* forward secrecy
* post-compromise security
* metadata leakage
* large group sizes
* process recovery

Do not migrate until a design is properly threat-modeled and tested.

---

# PHASE 14 — Post-Quantum Security

Only after classical TorX is stable.

Research hybrid key establishment such as:

```text
X25519
   +
ML-KEM
   ↓
Hybrid key derivation
   ↓
TorX session
```

Maintain classical security while adding protection against future quantum-capable attacks.

Later research post-quantum ratcheting.

Do not replace mature cryptography simply because something newer exists.

---

# PHASE 15 — Scalability Engineering

Prove scalability instead of claiming it.

Test:

```text
1,000+ contacts

100,000+ messages

large encrypted databases

many active relationships

large media libraries

large groups

thousands of simulated mesh nodes

network churn

weak devices

low RAM

battery constraints
```

Build mesh/network simulators.

Measure:

* CPU
* RAM
* storage
* battery
* latency
* bandwidth
* routing overhead
* packet loss
* database performance

---

# PHASE 16 — Product Design and Branding

Finalize the complete TorX identity.

## Logo requirements

* no text inside the primary symbol
* professional
* enterprise-grade
* distinctive
* not generic blue-only branding
* recognizable at tiny icon sizes
* visually connected to routing/network/privacy/TorX
* must not copy the Tor Project onion identity

Create:

* primary logo
* Android adaptive icon
* monochrome Android icon
* notification icon
* splash icon
* website mark
* QR branding
* GitHub branding

## Design system

Finalize:

* color palette
* typography
* spacing
* corner system
* buttons
* forms
* chat bubbles
* group UI
* call UI
* status indicators
* error states
* animations
* accessibility

The design should feel modern, clean, professional and trustworthy.

---

# PHASE 17 — Documentation and Transparency

Before public release create:

* README
* architecture documentation
* TorX Protocol specification
* threat model
* privacy model
* security model
* SECURITY.md
* responsible disclosure policy
* privacy policy
* build instructions
* release signing documentation
* dependency/SBOM information

Clearly document:

```text
What TorX protects

What TorX does not protect

What metadata may remain visible

What transports are supported

Which features are experimental
```

---

# PHASE 18 — Security and Supply-Chain Pipeline

Every release should run:

```text
Commit
  ↓
Build
  ↓
Unit tests
  ↓
Protocol tests
  ↓
State-machine tests
  ↓
Instrumentation tests
  ↓
Fuzz tests
  ↓
Android Lint
  ↓
Static analysis
  ↓
Dependency/CVE scan
  ↓
Secret scan
  ↓
SBOM generation
  ↓
Release build
  ↓
Artifact signing
  ↓
Checksums / provenance
```

Monitor vulnerabilities in:

* SQLCipher
* Bouncy Castle
* AndroidX
* WebRTC
* Kotlin
* Nearby
* crypto dependencies

---

# PHASE 19 — External Security Review

Before TorX One 1.0:

Get independent review of:

* cryptographic protocol
* Android implementation
* database/key storage
* Tor transport
* Nearby transport
* message state machine
* groups
* media
* calls
* parser/fuzzing surfaces
* privacy/metadata model

Run an external penetration test.

Fix all meaningful release-blocking findings.

---

# PHASE 20 — Closed Beta

Test TorX on real devices and real environments.

Device matrix:

* Samsung
* Pixel
* OnePlus
* Xiaomi
* Motorola
* Nothing
* low-end devices
* old supported Android versions
* newest Android version

Test situations:

* Bluetooth off/on
* Wi-Fi off/on
* Internet disappearing
* Tor disconnecting
* network transitions
* airplane mode
* app killed
* device reboot
* battery saver
* low storage
* low memory
* huge chats
* groups
* calls
* large files
* long offline periods
* nearby movement

Fix all significant findings.

---

# PHASE 21 — Release Candidate

Freeze features.

Only allow:

* security fixes
* correctness fixes
* severe performance fixes
* accessibility/release blockers

Perform final:

* migration testing
* security verification
* dependency scan
* signing verification
* store metadata review
* privacy claim review
* documentation review

---

# PHASE 22 — TORX ONE 1.0

TorX One 1.0 should not release until the core product can truthfully provide:

```text
End-to-end encrypted messaging       ✅
Strong local identity security       ✅
Durable/offline message queue        ✅
Nearby offline communication         ✅
Real Tor communication               ✅
Automatic Nearby ↔ Tor routing       ✅
Reliable groups                      ✅
Secure media                         ✅
Voice/video calls                    ✅
Encrypted local database             ✅
Crash/restart recovery               ✅
Security testing                     ✅
External security assessment         ✅
Professional product/UI              ✅
Official TorX branding               ✅
Documentation/threat model           ✅
```

LoRa, HaLow and large-scale mesh may remain experimental after 1.0 if they are not mature enough.

TorX 1.0 must be stable rather than delayed forever waiting for every future networking idea.

---

# AFTER TORX ONE 1.0

## TorX 1.x

Focus on:

* reliability
* performance
* UX
* accessibility
* bug fixes
* Tor improvements
* battery optimization
* more device support

## TorX 2.x

Focus on:

* multi-hop mesh
* store-carry-forward
* LoRa
* Wi-Fi HaLow
* TorX gateways
* offline regional networks

## TorX 3.x / Future Research

Potential work:

* hybrid post-quantum cryptography
* PQ ratcheting
* advanced group protocols
* satellite NTN transport
* specialized TorX hardware
* formal protocol verification
* large decentralized TorX network

---

# DEVELOPMENT RULES

## Rule 1

There is only **one TorX Master Plan**.

Do not create separate competing roadmaps.

## Rule 2

When a new idea appears, ask:

```text
Does it support the TorX mission?
        │
      YES
        │
        ▼
Which existing phase should contain it?
```

Add it there.

Do not immediately change the current task.

## Rule 3

Security blockers always take priority over new features.

## Rule 4

Never skip phases because another technology sounds exciting.

## Rule 5

Every phase has measurable exit criteria.

## Rule 6

Do not mark a phase complete based on assumption.

Verify it from code, tests and device behavior.

## Rule 7

Use stable modern technology.

Not:

```text
old because familiar
```

and not:

```text
experimental because shiny
```

Use:

```text
current
supported
security-reviewed
production appropriate
```

## Rule 8

Never silently change cryptographic formats.

All crypto/protocol changes require explicit versioning and migration.

## Rule 9

Do not make marketing claims before implementation.

If Tor is not implemented:

Do not claim Tor.

If mesh is experimental:

Call it experimental.

If post-quantum security has not shipped:

Do not claim quantum resistance.

## Rule 10

Every transport is untrusted.

The TorX E2EE layer protects the message regardless of:

* Nearby
* Wi-Fi
* LoRa
* HaLow
* Tor
* relay
* gateway

---

# CURRENT POSITION

```text
Phase 0 — Product definition          ✅

Phase 1 — Security/Core fixes         🔴 CURRENT

Phase 2 — Protocol verification       ⏳

Phase 3 — Tor transport               ⏳

Phase 4 — Automatic routing           ✅

Phase 5 — Android modernization       ✅

Phase 6 — Security storage V2         ✅

Phase 7 — Media V2                    ⏳

Phase 8 — Multi-hop mesh              ⏳

Phase 9 — Store-carry-forward         ⏳

Phase 10 — LoRa                       ⏳

Phase 11 — Wi-Fi HaLow                ⏳

Phase 12 — Global gateways            ⏳

Phase 13 — Group V2                   ⏳

Phase 14 — Post-quantum               ⏳

Phase 15 — Scalability                ⏳

Phase 16 — Branding/UI                ⏳

Phase 17 — Documentation              ⏳

Phase 18 — Security CI                ⏳

Phase 19 — External audit             ⏳

Phase 20 — Beta                       ⏳

Phase 21 — Release Candidate          ⏳

Phase 22 — TorX One 1.0               ⏳
```

# RIGHT NOW

The only official engineering priority is:

## PHASE 1 — FINISH THE CURRENT SECURITY AND CORE CORRECTNESS WORK.

Do not begin LoRa.

Do not begin PQ crypto.

Do not redesign groups again.

Do not spend weeks on the logo.

Do not begin huge dependency migrations.

Do not add random features.

Finish Phase 1.

Then proceed to Phase 2.

Then Phase 3.

One phase at a time.

---

# TORX ONE — ONE-LINE VISION

**TorX One is a secure, offline-first communication network that keeps encrypted messages moving through nearby devices, local networks, mesh routes, long-range radios and Tor, automatically using whatever secure path is available.**
