# TorX One

Android messaging prototype with encrypted pairwise sessions, Tor onion transport and experimental offline transports. Current version: 0.1.0; Android 8 (API 26) or newer. This is pre-release software with custom protocol code, not an independently audited messenger or a Signal-compatible client.

## Start here

- [Build and verification](docs/BUILD.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Protocol](docs/PROTOCOL.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Security model](docs/SECURITY_MODEL.md)
- [Privacy policy and metadata](docs/PRIVACY.md)
- [Report a vulnerability](SECURITY.md)
- [Signing and release gates](docs/RELEASE.md)
- [Dependencies and SBOM](docs/DEPENDENCIES.md)
- [Official roadmap](docs/TORX_ONE_MASTER_PLAN.md)

## Transport and feature status

Tor messaging is implemented; the router currently tries Tor first and then available fallback transports. Nearby direct communication is distinct from multi-hop mesh. Nearby uses Google Play services. Wi-Fi Direct, mesh relay/store-forward, BLE LoRa, HaLow and gateways require compatible peers, permissions and sometimes external hardware; treat these as experimental. Hardware specifications live under `hardware/`.

Calls use authenticated signaling and WebRTC media. Media does not travel through Tor; STUN/TURN or direct peers can observe network addresses. Empty ICE configuration limits connectivity. Groups use pairwise fan-out; Group V2 and post-quantum research are not shipping security guarantees.

Public release remains gated on successful CI, signing setup, independent review and fresh two-device delivery/recovery testing. See the roadmap for product gaps. No software can promise permanent freedom from bugs.
