# TorX Protocol implementation specification

Scope: current V1 binary envelope format in `protocol/ProtocolCodec.kt`. This is an implementation specification, not a standardized or independently reviewed cryptographic protocol. Reference code and tests take precedence if a discrepancy is discovered; report it.

All integer fields are Java DataOutputStream big-endian. Strings use Java **modified UTF-8** with unsigned 16-bit encoded byte length (`writeUTF`), not ordinary UTF-8. Field order is fixed; trailing bytes are rejected. Version is a signed 16-bit field, currently 1.

## Secure envelope (inside authenticated ciphertext)

| Order | Field | Encoding |
| --- | --- | --- |
| 1 | magic | int32 `0x54585345` (TXSE) |
| 2 | protocolVersion | int16, 1 |
| 3–7 | logicalMessageId, conversationId, senderIdentity, recipientBinding, messageType | five writeUTF strings; type is enum name |
| 8 | timestamp | int64, positive milliseconds |
| 9 | replyToMessageId | writeUTF; empty means absent |
| 10 | directionSequence | int64, nonnegative |
| 11–12 | payload length, payload | int32 then exact bytes |
| 13 | group presence | one Java boolean byte |
| 14–16 | optional groupId, groupEpoch, keyVersion | writeUTF, int32, int32 |

Payload limit: 32 KiB. Decoder total limit: payload limit + 1024 bytes. Identifiers: at most 128 characters; message/sender/group identifiers are nonblank. Epoch and key version are nonnegative. Sequence zero is used by some control paths; replay and authorization semantics belong to dispatch/session state, not this codec. Text policy limit is 10,000 characters and timestamp skew policy is 24 hours (`ProtocolLimits`); those constants are not proof every consumer enforces them.

## Opaque transport envelope

| Order | Field | Encoding |
| --- | --- | --- |
| 1–2 | magic, version | int32 `0x54585445` (TXTE), int16 1 |
| 3–4 | envelopeId, queueAddress | writeUTF, nonblank, at most 128 characters |
| 5–6 | authenticator length, bytes | int16 32, 32 bytes |
| 7–8 | ciphertext length, ciphertext | int32 positive length, exact bytes |

Decoder total frame limit is 64 KiB, including headers. Encoder ciphertext limit is also 64 KiB, so callers must budget header overhead to remain decodable. Queue authentication is separate from end-to-end encryption. Outer queue identifiers, lengths and routing metadata remain observable.

## Authentication, sessions and controls

Signed invitations/bootstrap authenticate Ed25519 identity material and X25519 setup. `relationship/ContactBootstrapPayload` may append the initiator onion address, covered by the signature. New parsers accept legacy payloads without this extension; old parsers may reject the extension. It has no separate negotiated extension version. Never treat unsigned route metadata as authenticated identity.

`crypto/SessionRatchet` and `SessionCrypto` define encrypted framing/key progression. `connection/` owns durable sequence allocation; retries should reuse durable encrypted frames. `incoming/` validates authenticated sender, recipient, replay/order and dispatches receipts, messages and controls. Consult these source modules and their tests for state transition details; this document does not claim interoperable Signal wire framing.

Groups currently fan out over pairwise sessions. Epoch controls require ordered durable recovery and each recipient needs a usable relationship; group membership is not an automatic link. Media/call/group subcodecs are defined in their respective modules and codec tests. Hardware framing is separately specified in `hardware/torx-radio/PROTOCOL.md` and `hardware/torx-halow-gateway/PROTOCOL.md`.

Malformed magic/version/type, truncated fields, out-of-range lengths and trailing bytes are rejected. CI adds reproducible bounded random/mutation input testing. Future changes need version negotiation, compatibility fixtures and independent cryptographic review before claiming a stable protocol.
