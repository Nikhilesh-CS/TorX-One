# Privacy policy and metadata model

Scope: this repository's pre-release Android application, not third-party services. Review this policy and the actual release configuration before public distribution. No centralized account server is required by the core identity model. This is not a claim that all configured network services retain no data.

The app stores identity, contacts, relationship/session state, messages, delivery state, preferences and media locally. Local feature data also includes drafts, stars, search indexes, extracted message links, contact aliases, chat appearance settings, expiry policies and scheduled messages. The encrypted database protects stored records, but exported/shared files and diagnostic output require separate care. Camera, microphone, Bluetooth, nearby/location and notification permissions support requested features. Android backup is disabled. Deleting history removes conversation records; it retains the peer/session. Uninstall normally removes app-private data and Keystore entries. Shared/exported files, peers' copies and flash remnants can survive deletion.

The manifest additionally declares explicit exclusions for legacy backup and
Android cloud backup/device-to-device transfer, including credential-protected,
device-protected and app external files. The backup flag alone is insufficient
for some Android 12+ manufacturer transfer implementations, as described in the
[Android guidance](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore).
The configured rules are not a claim of verified behavior on every OEM transfer
utility, or a supported identity migration/recovery feature.

Disappearing messages are a local retention feature, not guaranteed secure erasure
or a way to retract recipient copies. Schema 20 records private media paths before
creation and retries cleanup after restart, including expired attachments on
manually deleted messages. Current-device and independent retest remain pending.
The new tracking does not identify files already orphaned before it was installed,
erase user source/exported copies or guarantee erasure of flash remnants.
Scheduled sends also depend on device/network availability;
an intended send time is not a delivery guarantee.

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
