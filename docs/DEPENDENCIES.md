# Dependency and SBOM policy

Authoritative versions: Gradle build files. Monitor SQLCipher, Bouncy Castle, AndroidX/Room/Compose, WebRTC, Kotlin/coroutines/serialization, Nearby Play services and Tor/jtorctl; also camera/QR, build plugins and CI actions. Dependabot runs weekly for Gradle and GitHub Actions. Review release notes and advisories before upgrades; run the complete pipeline after changes.

CI exports resolved release runtime dependencies and a CycloneDX 1.5 SBOM using `:app:securityInventory`. Maven components use package URLs; the vendored Tor AAR has a SHA-256 hash and explicit native Tor version component. Local binary provenance/licensing must be manually reviewed: dependency discovery cannot reconstruct a binary's transitive native supply chain.

Anchore scans that SBOM and fails on high/critical findings. A scanner outage is failure, not a clean result. Absence of matches is not proof of absence of vulnerabilities; Maven/native advisory coverage can be incomplete. No blanket suppression file is provided. Any future exception must document advisory, affected path, mitigation, owner and expiry.

Keep SBOM and dependency inventory with each release, including license review for redistribution. The documentation does not grant redistribution rights. Actions are pinned to resolved commit SHAs; Dependabot updates must be reviewed. Gradle downloads are currently version-pinned, not a fully verified offline supply chain; checksum verification metadata and reproducible-build proof remain follow-up hardening.
