# Phase 4: Automatic Transport Routing

## Routing policy

`TransportRouter` receives one destination and one opaque TorX transport envelope. It evaluates routes in this order:

1. Nearby, when the destination queue is bound to an authenticated ready endpoint.
2. Tor, when the destination queue has a persisted v3 onion route and Tor is ready.
3. Future Wi-Fi Direct and relay transports when registered and routeable.
4. Failure to the caller when no transport accepts the envelope.

`TorXAgent` treats the final failure as a retryable delivery failure and leaves durable messages in the outbox with bounded backoff. When Nearby authenticates or Tor becomes ready, the agent is awakened immediately.

## Preserved invariants

- The router passes the same byte array to every attempted transport. It does not decrypt, encrypt, re-sign, or rebuild an envelope.
- The durable outbox item and delivery ID remain unchanged during failover.
- Per-relationship application sequence scheduling remains owned by `TorXAgent`.
- Receiver acknowledgements remain the authority for delivery; transport acceptance does not advance the conversation sequence.
- Identity, conversation, relationship, Double Ratchet state, receipts, and retry history are independent of transport selection.

## Failure behavior

An unavailable transport is skipped. A synchronous send failure advances to the next route. If all candidates fail, the combined failure is returned to `TorXAgent`, which schedules the existing item for retry. Routing events expose attempts, skips, failovers, and acceptance for diagnostics without exposing plaintext.

## Acceptance scenarios

- Nearby and Tor available: Nearby is selected.
- Nearby route absent: Tor is selected.
- Nearby send fails: the identical ciphertext is attempted through Tor.
- Both unavailable: the message remains queued.
- Transport changes between messages: the same relationship and application sequence lane is used.
