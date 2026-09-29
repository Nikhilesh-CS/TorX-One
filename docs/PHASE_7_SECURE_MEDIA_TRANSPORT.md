# Phase 7 — Dedicated Secure Media Transport

## Status

Complete at the automated implementation gate.

## Implemented foundation

- Added a dedicated media wire frame with a distinct `TXMS` magic value.
- Added a transport adapter that sends media frames directly through `TransportRouter`, outside the chat outbox model.
- Added an incoming transport split so dedicated media frames do not enter `IncomingDispatcher` as ratchet ciphertext.
- Added per-chunk AES-256-GCM authentication.
- Derived a separate HMAC-SHA256 key for every chunk index from the transfer key.
- Bound each chunk to media ID, relationship ID, chunk index, and total chunk count through authenticated data.
- Increased the dedicated plaintext chunk ceiling to 256 KiB, allowing approximately 1 GB files without exceeding the protocol chunk-count limit.
- Kept frame encoding and decoding bounded to fixed maximum sizes.

## Completed integration

- The descriptor and transfer key remain inside the Double Ratchet message.
- A receiver sends an authenticated `FILE_ACCEPT` only when auto-download policy permits the transfer.
- Production `MediaService` sends chunks through `DedicatedMediaTransport`, bypassing per-chunk ratchet and outbox records.
- Incoming dedicated frames are authenticated before being written to the temporary encrypted file.
- Upload and download progress, chunk checkpoints, pause state, resume requests, cancellation, completion, and restart recovery remain durable.
- Resume and cancel controls use the sequenced, durable control-message path.
- Duplicate chunks remain idempotent and missing chunks can be requested independently.
- A 700 KiB end-to-end test exercises offer, accept, dedicated streaming, assembly, whole-file verification, decryption, and completion.
- A protocol-boundary test verifies that an encrypted 1 GiB payload fits in 4,097 chunks.

## Verification

On 2026-09-29:

- `:app:testDebugUnitTest` passed: 251 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` passed.
- Dedicated-frame tests cover maximum-size chunks, malformed framing, ciphertext tampering, transfer-position substitution, receive-path separation, and 1 GiB geometry.

Physical-device acceptance should transfer a large file over Nearby and Tor, interrupt both applications, resume after restart, and compare the final file hash. That validates Android radio, storage, and process behavior outside the JVM environment.
