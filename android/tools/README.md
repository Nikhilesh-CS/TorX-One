# Independent Tor transport check

## Official master plan: external review and closed beta

`prepare_review_bundle.py` generates a local hashed source overlay and bounded
test/artifact manifest under `android/build/review/`. Nothing is uploaded.
`validate_release_evidence.py --init-beta-results` creates the required 180-case
NOT_RUN ledger without replacing existing results. Its default mode checks
structure; `--require-complete` fails when audit, device, result or finding gates
are incomplete. See `docs/PHASE_19_EXTERNAL_SECURITY_REVIEW.md` and
`docs/PHASE_20_CLOSED_BETA.md`. Human results must be backed by sanitized evidence,
not inferred from a local build or an onion diagnostic.

Build requirements: JDK 17, Android SDK platform `platforms;android-37.1`, and the
build tools selected by Gradle. The target SDK remains 36 and minimum SDK remains 26.
Tor Android is resolved from Guardian Project's Maven repository at version 0.4.9.13.

## Two-device gate

Install the current debug APK on both phones using `adb install -r` to retain data.
Unlock the app on each device and wait for Tor readiness. Then run:

```powershell
python android/tools/verify_tor_two_devices.py --adb C:/Android/sdk/platform-tools/adb.exe --phone-a SERIAL_A --phone-b SERIAL_B
```

The check verifies bootstrap 100, SOCKS availability, valid distinct v3 onion
addresses, and a nonce-matching PONG in each direction. It uses the first phone's
SOCKS proxy to reach the other phone's onion endpoint, then reverses direction.
The debug endpoint bypasses contacts, Room, ratchets, messages, UI, and calls.
It is disabled in release builds and does not print onion addresses.

A successful check proves onion connectivity for this run; it does not prove
contact pairing, text persistence, ACK delivery, call ringing, or media transport.

## Implementation after the transport preparation

The user authorized continuing code work without waiting for physical phones.

- Standard calls and files use ICE ALL: LAN, Google STUN, and optional TURN.
  Direct peers can learn network addresses.
- Maximum Call Privacy in Settings applies to new sessions: relay-only ICE,
  no STUN, and rejection of direct candidates. Requires configured working TURN.
  Selected-pair stats omit IP addresses.
- Call UI and notifications distinguish preparation, queued offer, transport
  acceptance, remote ringing, ICE negotiation, and connected media.
- Notification setup requests permission and warns when background ringing or
  lock-screen full-screen presentation is disabled. In-app ringtone playback
  follows the Activity lifecycle and the phone's ringer mode.
- ICE disconnects trigger encrypted restart offer/answer signaling. The original
  caller owns restart initiation to avoid simultaneous renegotiation.
- Files can use a separate silent DATA session with no audio/video tracks, or an
  existing authenticated call's DataChannel. Both phones need this protocol version.
- RTC packets are bounded to 16 KB fragments, with one bounded chunk assembly,
  buffered-send backpressure, receiver acceptance ACKs, and ACK timeout fallback.
  Existing per-chunk AEAD, persisted progress, resume, and final SHA-256 checks apply.
- If the RTC lane is unavailable, encrypted chunks use the existing routed
  transport. Temporary failures stay queued with backoff and startup recovery.
- Chat/control fallback order is Tor, Nearby, Wi-Fi Direct, then other configured
  transports. Standard RTC media can work without TURN; restrictive NATs may
  still require it. ICE selects a working pair, not sequential transport retries.

## Remaining live gates from the repair plan

Complete them in order after both directions above pass:

1. Contact bootstrap, repeated bidirectional text persistence and matching ACKs.
2. CALL_OFFER acceptance and incoming ringing on the receiving phone.
3. Configured, live TURN allocations over UDP, TCP, and TLS, and selected relay-pair proof.
4. Call delivery states and incoming permission/lifecycle checks on real devices.
5. Bidirectional voice on different networks, hangup and connection recovery.
6. Video after voice passes.
7. Large RTCDataChannel file transfer, interrupted/resumed transfer, SHA-256
   integrity, and Tor fallback when the peer is offline or WebRTC is unavailable.

These gates require live devices. Maximum Call Privacy and networks blocking
direct connectivity require a working TURN relay.
The Tor dependency upgrade and a green unit suite do not close those gates.
The public Open Relay probe timed out for UDP/TCP/TLS from this computer; no public
relay was enabled. Supply a working relay via the documented TURN build settings.

## Persistent peer transport and migration

Upgrade both phones: old receivers require EOF after one frame and cannot consume
persistent streams. Schema 14 stores signed Tor endpoints by relationship in
encrypted Room; old queue preferences are imported once and marked as legacy.
Run `python android/tools/verify_peer_endpoint_migration.py` from the repository
root to check the actual migration against both exported Room schemas and prove
fixture preservation. See `docs/PHASE_3_TOR_TRANSPORT.md` for the framing contract.

Raw PING/PONG still checks one diagnostic connection. It does not prove stream
reuse or encrypted delivery: verify repeated chat messages, exact ACKs, process
restart and Wi-Fi/cellular recovery on upgraded devices afterward.

## Tor media fallback repair

New transfers negotiate 16 KB chunks for every lane. Existing transfers keep
their recorded geometry: dedicated ciphertext above the 64 KB transport ceiling
is split into 16 KB pieces by the routed fallback and reassembled before the
existing per-chunk AEAD checks. Assemblies are limited to 32, at most 256 KB plus
framing each, with a 120-second expiry. The generic transport limit is unchanged.
Both phones need this version to resume older 256 KB transfers through fallback.
The JVM regression suite includes a 4 MB transfer with RTC unavailable, receiver
completion, ciphertext SHA-256 and decrypted-byte equality. Live-phone proof
remains separate.

Security/release CI overrides setup-android's obsolete default `tools` package
with platform-tools, platform 37.1 and build-tools 36.0.0. It retains the complete
unit suite results before filtered protocol gates replace Gradle's report files.
The next remote workflow run is required to verify CI execution itself.

Run `python android/tools/verify_contact_selection.py` to exercise the actual
Room lookup SQL in all 24 insertion orders. It checks that a healthy active
connection outranks broken/newer rows and that a current connection generation
wins before timestamps. Signing identities retain their separate secure lanes;
QR selection additionally checks the session through SessionCrypto.
# Supplemental real-device automation

## Release candidate dependency verification

Generate the resolved release inventory with `:app:securityInventory`, then run
`python android/tools/scan_runtime_dependencies.py`. This sends only public Maven
coordinates to OSV and writes a dated JSON report. Findings, empty inventories,
network errors and pagination errors fail the command. Native/build-tool coverage
remains a separate gate. Run `python android/tools/test_runtime_dependency_scan.py`
for the response-handling regression suite. See `docs/PHASE_21_RELEASE_CANDIDATE.md`.

`inventory_apk_native.py --apk APK --output JSON` hashes actual packaged shared
libraries without extraction. It supplies byte identity for native review, not a
CVE or licensing result. `test_native_inventory.py` exercises bounded ZIP parsing.

The final signed-candidate gate invokes actual `apksigner` and `aapt` binaries,
rejects debug certificates/debuggable packages, and compares an independently
trusted certificate. For 1.0 it also requires the audit/beta APK hash and complete
`release-approval.json` with matching version/certificate. The protected release
workflow calls it automatically. Tool regressions are `test_signed_candidate.py`
and `test_release_approval.py`; they use synthetic records, not release approvals.

`test_media_expiry_queries.py` executes the production Room SQL on SQLite;
`verify_privacy_scheduling_migrations.py` now covers schema 17 → 18 → 19 → 20.
Device writer/restart/upgrade regressions live in `DisappearingDatabaseTest`.
`test_backup_policy.py` verifies explicit legacy/cloud/device-transfer exclusions;
these source checks do not simulate an OEM migration utility.

After installing the matching debug app and Android test APK, run from the repository root:

```powershell
python -u android/tools/run_device_beta.py --adb C:/Android/sdk/platform-tools/adb.exe --serial DEVICE_SERIAL --network-cycles
python -u android/tools/probe_tor_listener.py --adb C:/Android/sdk/platform-tools/adb.exe --serial DEVICE_SERIAL
```

The beta runner checks the installed APK hash, backup flag, component/provider
access controls, Android instrumentation suite, relaunch, synthetic memory
callback and actual radio toggles. Original radio states are restored in a
`finally` block. Keep the phone unlocked; these checks do not clear app data.
The listener probe discovers this device's local Tor endpoint and uses a bounded
set of malformed connections through temporary ADB forwarding, then removes
the forwarding. It does not contact another person's server.

Sanitized JSON results are saved under `android/build/device-beta/`. Use these as
supplemental engineering evidence: they do not close independent review, paired
message/call delivery, physical memory pressure or the multi-device beta matrix.
