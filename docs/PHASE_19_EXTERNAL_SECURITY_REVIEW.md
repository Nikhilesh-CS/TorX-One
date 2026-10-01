# Phase 19 — External security review

Official scope: [TorX One master plan](TORX_ONE_MASTER_PLAN.md). This is an
assessment engagement package, not an independent audit report.

## Status and exit gate

Preparation implemented. No external reviewer has been appointed, no external
penetration test has run, and no independent sign-off exists. Phase 19 remains
open. An assistant review, passing CI, a signed APK and a dependency scan do not
meet the independent-review requirement.

Before starting, select a candidate commit and APK, freeze feature changes, and
record their hashes. Include any local modifications in the assessment scope;
HEAD alone does not identify an uncommitted candidate. Appoint reviewers with
Android and messaging cryptography experience, agree scope and disclosure terms,
and authorize testing only on dedicated test identities, devices and networks.
Do not send user databases, live credentials, signing keys or private identities.

## Reviewer handoff

Run `python android/tools/prepare_review_bundle.py`. Its local output includes a
source overlay, per-file hashes, HEAD/dirty-state identifiers, aggregate unit-test
results, available APK hashes and an explicit evidence-limitations record.
The overlay is applied to a checkout of the recorded HEAD; it is not a standalone
reproducible source distribution. Review its manifest before sharing it. It
excludes device logs, databases, local configuration and key files. Dependency
availability, APK-to-source correspondence and release signing still require
independent verification. Nothing is uploaded or sent to a reviewer by this tool.

Read [build instructions](BUILD.md), [architecture](ARCHITECTURE.md),
[protocol](PROTOCOL.md), [threat model](THREAT_MODEL.md),
[security model](SECURITY_MODEL.md), [privacy](PRIVACY.md),
[dependencies](DEPENDENCIES.md), [release gates](RELEASE.md) and
[responsible disclosure](../SECURITY.md).

## Required assessment surfaces

Paths below are relative to `android/app/src/main/java/com/torxone/app/`.

| Surface | Entry points and adversarial checks |
| --- | --- |
| Cryptographic protocol | `protocol/`, `crypto/`, `identity/`, `relationship/`: transcript binding, invitation signatures, replay, key derivation, ratchet/skipped-key bounds, downgrade and malformed frames. |
| Android implementation | `TorXOneApplication.kt`, `MainActivity.kt`, `services/`, Android manifest: exported components, lifecycle/background recovery, permissions, lock bypass, notification and clipboard leakage. |
| Database/key storage | `security/`, `data/TorXDatabase.kt`, session stores and secret migrations: SQLCipher initialization, authenticated key use, versioned wrapping, backup/uninstall boundaries, interrupted upgrades. |
| Tor | `transport/tor/`: authenticated relationship-to-onion binding, frame bounds, connection reuse, partial frames, reconnect, listener exposure, metadata and log leakage. |
| Nearby | `transport/nearby/`: discovery spoofing, authenticated route binding, downgrade, permissions, route expiry and duplicate connections. |
| State machine | `connection/RelationshipSendCoordinator.kt`, `agent/`, `incoming/IncomingDispatcher.kt`: atomic ratchet/outbox commit, ACK identity, sequencing, duplication, packet loss, crash injection and very delayed retry. |
| Groups | `groups/`, incoming group handlers: pairwise topology, member/role authorization, durable ordered controls, partial fan-out, removal, stale epoch and crash recovery. |
| Media | `media/`, incoming media handlers: offer/accept, chunk authentication, path traversal, decompression/resource limits, interrupted transfers, expiry, final hash verification and malicious filenames. |
| Calls | `calls/`: authenticated signaling, glare/race/restart, ringing versus transport acceptance, ICE privacy, relay-only enforcement, microphone/camera lifecycle and resource release. |
| Parsers/fuzzing | Secure/opaque envelopes, invites, radio/mesh packets and media codecs: truncation, length/overflow, duplicate/trailing fields, unknown versions and mutation of state on invalid input. |
| Privacy/metadata | Logs, notification previews, FTS, media files, stars/drafts/links, relay queues and transport endpoints; compare each public claim with actual behavior. |

## Known areas to include, not waive

The current working candidate adds local productivity, scheduling, expiry and
connection UI. Ask reviewers to cover those changes as well as the older core.
Schema 20 implements creation-intent tracking before private media writes and
expiry cleanup for manually deleted attachments. These internal findings require
independent retest, including process death before association and writers racing
cleanup. Local automatic `VERIFIED` state does not prove a
manual safety-number comparison. Group dashboards lack per-member diagnostics.
Live calling across restrictive networks needs a working TURN deployment;
configured support alone is not successful relay allocation evidence.

## Finding and remediation process

Use `docs/validation/external-review.json` and `audit-findings.csv`. Assign an ID,
severity, affected surface, reproduction, impact and evidence to every finding.
The media and backup findings are internal static-review risks, explicitly labelled
as such; they are not findings from an external auditor.
Keep exploit details private under the disclosure policy. Untriaged findings
block the gate. Critical/high findings must be fixed, linked to a regression test
and independently retested. Document lower-severity disposition; do not silently
delete findings. A maintainer cannot substitute self-verification for reviewer
retest. A changed security-sensitive candidate requires review of the delta.

Phase 19 closes only when the commissioned report covers every surface above,
the penetration test is complete, release-blocking findings are resolved, and
the reviewer records retest/sign-off against the exact candidate. Record report
and evidence hashes without publishing sensitive contents.
