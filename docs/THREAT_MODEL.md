# Threat model

Assets: identity secret keys, session/message keys, plaintext, contact graph, queues/onion routes, local database and release signing key.

Adversaries considered: passive network observers; malicious relays/gateways; malformed or replayed frames; a malicious authenticated peer; opportunistic access to a locked phone; compromised dependencies/build infrastructure. Intended defenses include authenticated encryption, signed invitations, recipient binding, bounded parsing, replay controls, encrypted local storage and release verification.

Trust boundaries: Android/Keystore and unlocked application process; scanning and verifying the correct peer invitation; database/session transitions; every fallback transport; hardware firmware; ICE/STUN/TURN infrastructure; developer and CI signing access. A QR code from an attacker authenticates the attacker, not the person the user intended.

Out of scope guarantees: rooted or compromised OS, plaintext extraction from an unlocked process, malicious recipients sharing messages, screenshots/external cameras, global traffic correlation, availability against jamming/DoS, secure physical erasure of flash, recovery after uninstall without an explicit validated backup and post-quantum protection.

Known release risks include Tor publication/readiness evidence, timeout/retry recovery, ordered group control recovery and pairwise roster reachability. Custom cryptography requires independent review. Passing tests is evidence for the checked behavior, not proof against all adversaries.
