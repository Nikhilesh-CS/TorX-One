# Release signing and gates

Phase 21 applies a feature freeze and final candidate verification. See
[release candidate checks](PHASE_21_RELEASE_CANDIDATE.md) and the
[store metadata draft](STORE_METADATA.md). Candidate preparation is not release
approval; audit, beta, signing and production device gates must have evidence.
The final [1.0 sign-off](PHASE_22_RELEASE_SIGNOFF.md) records the remaining
acceptance work and promotion steps.

`security-ci.yml` runs PR/main/manual/weekly checks. `release.yml` invokes those same gates for a `v*` tag, then builds, signs and verifies an APK, generates SHA-256 checksums and GitHub provenance, and retains artifacts. It does not automatically publish to users.

Before use, configure protected `release` environment with required human reviewers and tag restrictions. Protect main and tags; require CI jobs before merge. Enable GitHub private vulnerability reporting and CodeQL for this repository. Require CodeQL findings review/code scanning protection: successful analysis alone does not mean no alerts were found. These host settings cannot be enforced by a committed workflow file.

Environment secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The workflow decodes the keystore only after checks pass; `apksigner` reads passwords from environment variables and verifies the result. Missing secrets fail the job. Do not use debug certificates for production. Keep an encrypted offline keystore backup and password recovery procedure; losing the signing key prevents ordinary updates. Rotation needs a tested Android signing lineage/distribution plan.

Set the protected environment variable `TRUSTED_SIGNING_CERT_SHA256` from an
independently trusted production certificate record. The signed-candidate tool
rejects a missing/mismatched fingerprint, Android debug certificates, a wrong
package or a debuggable APK. It invokes `apksigner` and `aapt` on the actual file;
a detached verification report is not accepted as proof of unrelated bytes.
For 1.0, final APK, certificate and version must also match release approval,
and the APK hash must match the audit and beta ledgers before artifact upload.

Use increasing versionCode and matching versionName before tagging; the current 0.1.0 is not a 1.0 release. Review R8 output and release startup/device behavior. Download signed APK, checksum, SBOM, inventory and signing certificate report from CI. Verify SHA-256 and `apksigner verify --verbose --print-certs`; compare certificate fingerprint with a separately trusted published fingerprint. Verify provenance using `gh attestation verify FILE --repo Nikhilesh-CS/TorX-One`.

CI bounded mutation tests are regression fuzzing, not exhaustive coverage. Emulator tests do not replace real two-phone messaging, process death/offline recovery, transport fallback, calling and hardware checks. External cryptographic review remains Phase 19. A signed artifact is not evidence of product readiness.

Official master-plan phases [19](PHASE_19_EXTERNAL_SECURITY_REVIEW.md) and
[20](PHASE_20_CLOSED_BETA.md) have a review handoff and evidence ledger under
`docs/validation/`. CI checks ledger structure; tags whose major version is 1 or
higher also require the complete evidence gate before release building/signing.
Pre-1.0 assessment candidates can be built while these results remain pending.
Passing the ledger validator confirms recorded hashes and coverage, not the
truth of a human assessment. Required human reviewers must inspect the evidence.
Use a sanitized assessment/sign-off document in the ledger when the full report
is confidential; retain the full report through the agreed private channel.

`release-approval.json` retains final Phase 21 review. Each mandatory check needs
passing hashed evidence, and human approval must name the candidate and reviewer.
Validate it with `python android/tools/validate_release_approval.py`; use
`--require-complete` to enforce readiness. Tests of this gate use synthetic data.
