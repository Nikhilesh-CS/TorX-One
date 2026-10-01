# Phase 22 — TorX One 1.0 release sign-off

This is the release acceptance record for the existing [official master plan](TORX_ONE_MASTER_PLAN.md), not a new feature roadmap.
The application remains version 0.1.0. No 1.0 tag, store submission or public distribution has been made.

## Implemented release controls

- Features are frozen; only the Phase 21 categories of fixes are accepted.
- Security CI retains the complete unit suite, Android/lint results and dependency inventory.
- Phases 19 and 20 have a mandatory audit and beta evidence gate for 1.0 tags.
- Phase 21 has [release approval metadata](validation/release-approval.json), with thirteen required checks. Every passing check requires hashed evidence and a real reviewer.
- The signing workflow invokes `apksigner` against the actual APK, checks an independently trusted certificate fingerprint, rejects Android debug certificates, and checks actual package/version/debuggable metadata with `aapt`.
- For 1.0, the final signed APK must match the independently reviewed, beta-tested and release-approved APK hashes. Signing an unapproved rebuild cannot satisfy the gate.
- Signed release outputs retain checksums, mapping, SBOM, native library hashes, signature/candidate verification and provenance. The workflow retains artifacts; it does not publish automatically.

The native inventory identifies packaged shared-library bytes. It does not establish their upstream versions, licensing, vulnerability status, or native code hidden in other assets.

## What still prevents release

| Requirement | Remaining evidence/action |
| --- | --- |
| Independent assessment | Commissioned report, penetration testing, exact-candidate sign-off and independent retest of security findings. |
| Real beta campaign | Complete all nine required device cohorts and twenty scenarios per cohort. Fresh paired text/ACK, groups/media, Wi-Fi/cellular recovery and voice/video calls are included. |
| Current device behavior | The current debug APK passed all 38 Android tests on Realme and Vivo, including schema 19 → 20 and history-preservation fixtures. Confirm fresh paired delivery/ACK and visible history after reconnect; verify retained encrypted user state across supported real upgrades and exercise the optimized build on devices. Debug fixture passes do not establish production-signed behavior. |
| Dependencies | Review bundled native Tor/WebRTC/SQLCipher provenance, advisories/licenses, build plugins and tooling in addition to the Maven advisory scan. |
| Production signing | Configure the protected environment, signing secrets and separately trusted certificate fingerprint; verify real upgrade continuity. No production key was generated or substituted here. |
| Store/privacy | Establish public privacy/support/security channels, inspect the exact signed manifest/configuration, complete store disclosures and review sanitized screenshots. |
| Product sign-off | Accept the evidence for every capability in the official Phase 22 checklist; complete accessibility and repository-protection review. |

## Promotion sequence

1. Set the intended release version and an increasing versionCode before selecting the final candidate. Commit the reviewed implementation and regenerate the review bundle after edits. Select one production-signed assessment candidate through the controlled assessment process; record its source, version, certificate and APK hashes.
2. Complete the external review and beta campaign on that candidate. Record truthful evidence and dispositions; retest any changed security-sensitive candidate.
3. Have the release reviewer complete `release-approval.json`, with the final candidate's source, version, signer and hashed evidence. Keep confidential reports in the agreed private channel; use sanitized sign-off evidence in the repository.
4. Run both complete ledger validators. Build/sign through the protected workflow, verify the actual final APK against the approved hashes and trusted certificate, then verify installed update behavior. Any changed binary, including a version or build-provenance change, requires candidate review; do not silently replace approved hashes to pass the gate.
5. Obtain concrete distribution approval and publish through the chosen channel. Confirm availability and retain a recovery/hotfix plan that preserves encrypted user data.

Commands from the repository root:

```text
python android/tools/validate_release_evidence.py --require-complete
python android/tools/validate_release_approval.py --require-complete
```

Both commands intentionally fail while required evidence or human approval is absent. Synthetic tool tests prove gate behavior; they are not audit, beta or release approvals. Phase 22 remains open until the product's actual acceptance requirements are met.
