# Release signing and gates

`security-ci.yml` runs PR/main/manual/weekly checks. `release.yml` invokes those same gates for a `v*` tag, then builds, signs and verifies an APK, generates SHA-256 checksums and GitHub provenance, and retains artifacts. It does not automatically publish to users.

Before use, configure protected `release` environment with required human reviewers and tag restrictions. Protect main and tags; require CI jobs before merge. Enable GitHub private vulnerability reporting and CodeQL for this repository. Require CodeQL findings review/code scanning protection: successful analysis alone does not mean no alerts were found. These host settings cannot be enforced by a committed workflow file.

Environment secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The workflow decodes the keystore only after checks pass; `apksigner` reads passwords from environment variables and verifies the result. Missing secrets fail the job. Do not use debug certificates for production. Keep an encrypted offline keystore backup and password recovery procedure; losing the signing key prevents ordinary updates. Rotation needs a tested Android signing lineage/distribution plan.

Use increasing versionCode and matching versionName before tagging; the current 0.1.0 is not a 1.0 release. Review R8 output and release startup/device behavior. Download signed APK, checksum, SBOM, inventory and signing certificate report from CI. Verify SHA-256 and `apksigner verify --verbose --print-certs`; compare certificate fingerprint with a separately trusted published fingerprint. Verify provenance using `gh attestation verify FILE --repo Nikhilesh-CS/TorX-One`.

CI bounded mutation tests are regression fuzzing, not exhaustive coverage. Emulator tests do not replace real two-phone messaging, process death/offline recovery, transport fallback, calling and hardware checks. External cryptographic review remains Phase 19. A signed artifact is not evidence of product readiness.
