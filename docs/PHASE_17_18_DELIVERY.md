# Phase 17 and 18 delivery record

## Phase 17

Repository deliverables: root README and SECURITY policy; architecture, V1 envelope specification, threat/security/privacy models, build instructions, signing instructions and dependency/SBOM policy. Experimental transports, custom cryptography, WebRTC outside Tor, visible metadata and deletion/uninstall boundaries are explicitly documented. The official master plan remains the roadmap.

Public distribution still needs a working private disclosure channel, owner approval of policy, redistribution/license review and evidence that product claims match the shipped release configuration.

## Phase 18

Implemented: SHA-pinned workflows for build/unit/protocol/state-machine/bounded fuzz tests, Android instrumentation, lint, CodeQL, Gitleaks, dependency inventory/CycloneDX SBOM and blocking high/critical CVE scan. Weekly Gradle/action update monitoring. Tagged release workflow waits for every CI job, checks tag/version agreement, builds and lints release, signs via environment secrets, verifies certificate, records commit/checksums and creates GitHub artifact provenance.

Local evidence on 2026-09-30:

- 292 unit tests, zero failures/errors.
- `securityInventory`, `testDebugUnitTest`, `assembleDebugAndroidTest` and `lintDebug` completed successfully (Gradle BUILD SUCCESSFUL).
- Android test APK built; protocol device test passed on RMX5070, serial e4a7f4c0. Only obsolete test harness replaced; main app data retained.
- Generated SBOM: 140 components, including Tor binary SHA-256.
- YAML parsed and workflow action pins checked; Actionlint v1.7.12 passed.
- Documentation links checked.

These results do not establish a green remote run. Push changes, configure protected release environment/signing secrets and code scanning protections, enable private disclosure, and run Security CI. No signed production artifact, CVE/secret/CodeQL result, CI emulator run or provenance attestation has been verified locally. Repository configuration alone cannot complete those operational gates. Bounded mutation testing is not coverage-guided fuzzing or an independent audit.
