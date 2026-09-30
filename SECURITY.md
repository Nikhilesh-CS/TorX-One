# Security and responsible disclosure

TorX One is pre-release. Only the current `main` branch receives fixes; there is no supported stable release yet.

Report sensitive vulnerabilities privately through the repository's **Security → Report a vulnerability** page: https://github.com/Nikhilesh-CS/TorX-One/security/advisories/new . Maintainers must enable private vulnerability reporting before public distribution. If the form is unavailable, ask for a private reporting channel in an issue without publishing exploit details or user data.

Include affected commit/version, Android version, transport, reproduction steps, impact and a minimal sanitized sample. Do not send private keys, database contents, conversation plaintext or another person's identifying information. Test only devices and accounts you control or have permission to assess.

Maintainers should acknowledge within seven days, agree a disclosure date with the reporter, reproduce the issue, prepare a regression test and publish affected/fixed versions and mitigation. These are response targets, not a staffed service guarantee. Default coordinated disclosure target is 90 days; actively exploited issues may need faster disclosure. Do not claim safe harbor beyond authority the project actually controls.

Security claims and known boundaries: [threat model](docs/THREAT_MODEL.md), [security model](docs/SECURITY_MODEL.md), [privacy](docs/PRIVACY.md).
