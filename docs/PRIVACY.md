# Privacy policy and metadata model

Scope: this repository's pre-release Android application, not third-party services. Review this policy and the actual release configuration before public distribution. No centralized account server is required by the core identity model. This is not a claim that all configured network services retain no data.

The app stores identity, contacts, relationship/session state, messages, delivery state, preferences and media locally. Camera, microphone, Bluetooth, nearby/location and notification permissions support requested features. Android backup is disabled. Deleting history removes conversation records; it retains the peer/session. Uninstall normally removes app-private data and Keystore entries. Shared/exported files, peers' copies and flash remnants can survive deletion.

| Observer/path | Potentially visible information |
| --- | --- |
| Recipient | Plaintext, profile information, sender identity, timestamps and group membership shared with them |
| Tor network/ISP | Tor use, timing, volume; correlation remains possible |
| Nearby/Wi-Fi Direct/radio | Proximity, discovery identifiers, timing and frame sizes |
| Relay/gateway | Queue/routing identifiers, sizes, timestamps, persistence and traffic patterns |
| WebRTC/STUN/TURN | IP addresses, call timing and media traffic metadata |
| Device/diagnostic recipient | Local stored data and logs; sanitize logs before sharing |

Tor fallback is not a Tor-only privacy promise. Nearby depends on Google Play services; Android and configured ICE/gateway providers have their own privacy policies. No retention promise is made for external operators. Configure those services knowingly. This repository does not establish a hosted analytics/crash-report service; distributors must disclose any services they add.

There is no project-hosted account deletion endpoint. Local deletion cannot retract already received content. Security/privacy inquiries use the channel in [SECURITY.md](../SECURITY.md); maintainers must establish a working private channel before release.
