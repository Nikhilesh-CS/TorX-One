# Global TorX gateway service

The Phase 12 daemon extends the local HaLow service with controlled onion-to-onion forwarding.

## Required pipeline

1. Authenticate the locally paired phone.
2. Decode `GLOBAL_GATEWAY_SUBMIT` with strict limits.
3. Reject invalid, expired, duplicate, excessive-hop, or quota-exceeding packets.
4. Store the opaque packet durably before accepting custody.
5. Connect through the local Tor SOCKS port to the requested 56-character v3 onion address.
6. Authenticate gateway transit metadata with the origin gateway Ed25519 key.
7. Require a signed receipt before deleting durable custody state.
8. On the remote gateway, resolve the destination node only through authenticated local routes.
9. Deliver through HaLow, LoRa, or Nearby without inspecting application plaintext.

## Isolation

- Listen locally on a Unix socket where the Tor daemon supports it.
- Do not expose an ordinary clearnet forwarding proxy.
- Run as a dedicated unprivileged user with a read-only root filesystem where practical.
- Keep onion-service private keys outside application logs, backups, diagnostics, and update bundles.
- Bound queues by owner, packet count, bytes, expiry, and priority.
- Do not forward arbitrary onion destinations unless a valid TorX gateway route authorizes them.

The current Android submission protocol is implemented. The gateway-to-gateway signed transit envelope and daemon remain the next implementation milestone.
