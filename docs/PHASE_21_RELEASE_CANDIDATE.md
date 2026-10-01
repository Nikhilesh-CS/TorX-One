# Phase 21 — release candidate freeze and verification

The official Phase 21 is a feature freeze and final release verification. It
does not authorize publication or waive Phases 19 and 20. During this freeze,
accept only security, correctness, severe performance, accessibility and release
blocker fixes. New features resume after an explicit decision to leave the freeze.

## Exact candidate

This checkout contains uncommitted changes. A Git HEAD alone does not identify
the candidate. Generate `python android/tools/prepare_review_bundle.py` after
all candidate edits; preserve its source overlay, per-file hashes and manifest.
Any subsequent source/build configuration change invalidates affected checks
and requires a new bundle and retesting. Do not tag a candidate before committing
the reviewed overlay and linking it to the signing/build evidence.

## Verification and honest closure

| Gate | Evidence required |
| --- | --- |
| Migrations | All migration scripts green, real Room upgrade/reopen tests, and retained encrypted identity/session/outbox fixtures from supported upgrade versions |
| Correctness/security | Complete JVM suite, Android suite, lint, sanitised device checks and resolved/retested release blockers |
| Dependencies | Resolved release SBOM and dated vulnerability scan; native libraries and tooling must also be reviewed |
| Optimized build | Successful assembleRelease/lintRelease, retained R8 mapping and release startup/device tests |
| Signing | Production-signed APK verification, independently trusted certificate fingerprint, upgrade continuity and provenance |
| Store/privacy | Reviewed [store draft](STORE_METADATA.md), actual signed configuration, privacy/support URLs, permissions and store disclosures |
| Documentation | Version/schema/transport/crypto/privacy claims match code and known limitations |
| Prior phases | Independent reviewer sign-off and all mandatory beta evidence accepted |

Final review is recorded in `docs/validation/release-approval.json`. The
`validate_release_approval.py` tool checks all thirteen required gates and hashed
evidence. CI validates its structure; the final 1.0 signed-artifact gate requires
real approval and exact APK/certificate/version agreement. See
[Phase 22 sign-off](PHASE_22_RELEASE_SIGNOFF.md).

Run from `android/` with JDK 17:

```text
gradlew --no-daemon :app:assembleRelease :app:lintRelease :app:testDebugUnitTest :app:securityInventory
```

From the repository root, scan the resolved Maven graph:

```text
python android/tools/scan_runtime_dependencies.py
python android/tools/validate_release_evidence.py --require-complete
python android/tools/validate_release_approval.py --require-complete
```

The OSV scanner sends only public Maven coordinates/versions and handles result
pagination using the [official OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/).
It fails on reported advisories or scan errors. Maven coverage does not establish
coverage for bundled native libraries. Existing CI also scans the SBOM with Grype.

No production signing key is generated or replaced by this phase. Do not use a
debug certificate as a production substitute. The protected release workflow
requires configured secrets and human review; preserve its signing certificate
report and verify it against a trusted fingerprint before distribution.

## Correctness fixes in this candidate

The two internal media-expiry findings have implementation fixes in schema 20:

- Outgoing private paths are registered durably before file creation. Incoming
  descriptors own the future plaintext destination before chunks can create it.
  Cleanup reconciles unfinished intents after restart and retries failed unlink.
- Active writers are protected from the cleanup loop. Pending plaintext paths
  are retained until association, media removal or expiry. Owned-path validation
  rejects sibling directories that merely share the storage name prefix.
- Expiry includes manually deleted messages that still have attachment or other
  cleanup work. It clears retained data without rewriting the original deletion
  timestamp or removing allocated encrypted outbox sequences.
- Media sends capture their expiry once, so changing the timer while preparing a
  file cannot make an external source file the expiring private copy.
- Debug/release Room schema export tasks are ordered to prevent simultaneous
  writes to the shared schema JSON during a combined build.
- Backup is disabled and both legacy backup and Android 12+ cloud/device-transfer
  XML rules explicitly exclude all nine supported storage domains. This addresses
  reliance on the backup flag alone; OEM transfer behavior still requires beta
  verification. See the [Android backup guidance](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore).

Regressions include three storage ownership JVM tests, three tests executing the
actual Room SQL on SQLite, and five added Android database/writer/upgrade tests.
The Android additions passed on the current debug APK on Realme RMX5070/API 36.
These fixture tests do not establish every historical encrypted user-state
upgrade. Files already orphaned before tracking was introduced are not
retroactively identified by the new intent table. Exported/source copies and
flash remnants remain outside the disappearing-message erasure claim.

Two Python regressions verify backup flag wiring and exclusions in both XML
formats. Final APK inspection must also confirm the compiled rules are present.

## Local verification — 2026-10-01

Final Gradle command completed successfully in 7m 41s. It includes
`assembleDebug`, the **complete** `testDebugUnitTest`, `assembleDebugAndroidTest`,
`lintDebug`, `assembleRelease`, `lintRelease` and `securityInventory`.
Log: `android/build/phase21-22-backup-final-validation.log`.

- 406 JVM tests passed; zero failures, errors or skips.
- All 39 Python tool tests passed. Migration/contact selection scripts passed,
  including actual schema 17 → 18 → 19 → 20 SQL.
- Debug and release lint completed with zero errors/fatals; 52 and 53 warnings
  respectively remain. This is not a zero-warning claim.
- The complete Android suite passed on Realme RMX5070/API 36: 37 tests, no
  failures, runner exit 0. This includes the five new database/writer/upgrade
  regressions. The installed base APK hash matched the current debug candidate.
- Optimized unsigned APK and R8 mapping were generated. Actual package metadata
  is `com.torxone.app`, version 0.1.0/code 1, min API 26, target API 36, without
  the debuggable flag. No production signing or optimized device startup occurred.
- Actual optimized APK inspection found the compiled legacy backup exclusions
  and both cloud/device-transfer exclusions, nine domains each, with correct
  manifest resource references. Resource shrinking renamed their ZIP paths;
  inspection used the actual resource table. OEM transfer behavior is untested.
- Debug signature verified with the existing Android Debug certificate; the
  production signer check correctly rejected that certificate.
- Fresh OSV scan: 142 Maven packages, zero reported advisories. Actual APK native
  inventory: 28 shared-library entries. Native/tooling vulnerability and license
  review is not established by these results.
- Seven Bash workflow run blocks passed syntax checks. Remote GitHub execution,
  repository/environment protections and signing secrets remain unverified.

Current single-device evidence:

- `android/build/device-beta/20261001T162517.198801Z/`: instrumentation,
  installed APK match, non-exported service/provider access checks, foreground
  launch, two force-stop/relaunch cycles, synthetic memory callback, and actual
  Wi-Fi/Bluetooth/airplane toggles with verified restoration all passed.
- Its `startup-crash-check.json` records zero app crash-buffer entries since the
  test began and a live app process. A bounded crash-buffer check is not a
  guarantee of crash-free operation or proof of retained identity/message state.
- `android/build/device-beta/20261001T162648.558177Z/tor-probes.json`: Tor
  bootstrap 100, local nonce checks, 12 rejected malformed/unauthenticated frames,
  and recovery after 34 partial connections all passed. These use ADB-local
  forwarding and do not prove two-phone onion delivery or sustained DoS resistance.

Candidate hashes:

```text
debug APK: 9eccaee87532dcdc6a7961a3b2a9fc58b6af2fcd740922c5431cfed42ea0de1d
Android test APK: 1d3e64f64d76a299a037da05b4ffbe065d4cf40b480dfe839197f02f2dcda8bc
unsigned optimized APK: a24da227c8ea4a3644fc2df4f473af361ff0e94c836117a80c27f36ccf35bfd1
```

Machine-readable local report: `android/build/phase21/local-validation.json`.
Both complete readiness validators still intentionally fail: audit/beta has 203
pending checks, and final release approval has 17. Passing local tooling does not
satisfy those missing human/device/production requirements.

## Open prerequisites

The later [history repair](CHAT_HISTORY_REPAIR.md) replaces destructive parent-row
writes, and the Tor socket budgets are now 120 seconds. Earlier APK hashes and
optimized/lint evidence above describe the preceding candidate. They must not be
used to approve a later binary without affected checks and candidate evidence.

The current debug candidate passed all 408 JVM tests, 41 Python tests and 38
Android tests on each of Realme/API 36 and Vivo/API 33. Both installed APK hashes
matched `a24287c81c7c18b4807088d3fbb146ac5c0bf8fc32b7ac280046891a907f752c`.
The new history regression passed on both devices. Fresh paired delivery/ACK and
visible retention after reconnection still require confirmation. Current build
log: `android/build/conversation-preservation-validation.log`; earlier optimized
and lint results have not been rerun after this later source change.

Independent review and the multi-device beta matrix remain open. The internal
media-expiry fixes passed current-device regressions and still need independent
retest. Existing Android fixtures and local Tor
parser probes do not establish fresh two-phone delivery/calling, all historical
upgrade paths or production-signed release behavior. Phase 21 remains open until
all gates have actual evidence; "prepared" must not be recorded as "complete".
