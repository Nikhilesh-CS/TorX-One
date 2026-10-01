# Architecture

Source root: `android/app/src/main/java/com/torxone/app/`.

Compose screens and ViewModels call chat/contact/group services. `TorXOneApplication` wires the database, identity, session crypto, agent, dispatcher and transports. `TorXCoreService` maintains foreground networking subject to Android lifecycle restrictions.

`identity/` owns signed invitations and identity primitives. `relationship/` establishes pairwise relationships. `connection/` owns durable send sequence allocation. `crypto/` owns session actors and ratchet state. `protocol/` defines secure and opaque envelopes. `agent/` encrypts and submits messages; `incoming/IncomingDispatcher` validates and dispatches incoming frames. `data/TorXDatabase` stores contacts, conversations, sessions, connections, messages and delivery state in SQLCipher Room database schema 19 in the current working tree.

`transport/TransportRouter` selects eligible paths. Tor uses SOCKS plus onion listeners and queue-to-onion bindings. Nearby and hardware transports carry opaque envelopes. Mesh and relay storage add intermediate forwarding; transport acceptance is not recipient delivery. ACK/READ and persisted delivery state determine user-visible progress.

`groups/` implements pairwise fan-out and control operations. Membership alone is not evidence of a usable pairwise connection. `media/` handles encrypted transfers. `calls/` manages signaling and WebRTC. WebRTC media has a separate network privacy boundary.

Deleting chat history preserves peer/session state; scanning that peer again can recreate its conversation. Forgetting a peer and uninstalling are different operations. Closing an in-memory session actor is not deletion of persisted session state.

Tests under `src/test` cover selected protocol/state/recovery behavior. Emulator tests under `src/androidTest` cover a device codec smoke path. Neither proves real Tor, radio, multi-hop or two-device delivery. Consult phase notes for deeper design details and unverified hardware behavior.
