# Phase 20 — Closed beta

Preparation implemented; real-device campaign not completed. Supplemental checks
on Realme RMX5070/API 36 passed on 2026-10-01: all 37 Android tests, startup and
restart checks, radio toggles/restoration, and bounded local Tor parser probes.
The installed debug APK hash matched the current candidate. Evidence and scope
are recorded in [Phase 21](PHASE_21_RELEASE_CANDIDATE.md). These single-device
checks do not satisfy the paired-device scenarios or mandatory cohort matrix.
See the single official
[master plan](TORX_ONE_MASTER_PLAN.md) for scope and preceding release gates.

## Candidate and participation

Freeze one candidate, record APK SHA-256, source revision/overlay and signing
certificate fingerprint. Distribute privately through a maintainer-controlled
channel only after the security gates are satisfied. Do not treat a local debug
build as a production-signed beta release. Use dedicated test identities and
synthetic messages/files; explain experimental transports and call privacy to
testers. Obtain consent before collecting diagnostic evidence. Never request
private keys, raw SQLCipher databases, personal chat text or unrestricted logs.

## Device coverage

`docs/validation/beta-devices.csv` reserves Samsung, Pixel, OnePlus, Xiaomi,
Motorola, Nothing, a low-end device, the oldest supported Android (API 26), and
the newest Android available to the campaign. Record actual model, API, OS build,
RAM and candidate APK hash. A slot is not a completed device test. One device may
cover two cohorts only when the measured properties are documented. Keep extra
Realme/Vivo results as supplemental coverage, not substitutes for missing slots.

Run `python android/tools/validate_release_evidence.py --init-beta-results` to
create a NOT_RUN matrix for every required cohort and scenario. Do not overwrite
results when initializing. Record paired-device IDs and both directions for
communication cases, actual network conditions, time, observer and evidence.

## Scenarios and evidence

The scenario catalog is `beta-scenarios.csv`. It covers Bluetooth and Wi-Fi
toggles, Internet/Tor outages, network transitions, airplane mode, force-stop,
reboot, battery saver, low storage/memory, huge chats, groups, calls, large files,
long offline periods and moving nearby peers. The matrix requires every scenario
on each cohort; blocked or unavailable cases stay open instead of being called
passed. Run network tests on same Wi-Fi and separate Wi-Fi/cellular networks.

For messaging prove recipient persistence and authenticated ACK, both directions,
no duplicate visible message, and the same contact/conversation after recovery.
A sender transport-acceptance log or a diagnostic onion PONG is insufficient.
For calls prove remote ringing, answered bidirectional audio/video, hangup and
reconnect; record selected ICE path without IP addresses. For files use synthetic
content, exact input/output SHA-256, interruption/resume and measured size. Include
approximately 1 GB before closing the master plan's media maturity gate. LoRa,
HaLow and multi-hop tests need actual authorized hardware/topology; label missing
hardware BLOCKED and retain experimental claims.

Use the app's background service normally. Force-stop and reboot tests must
include relaunch/unlock where Android requires it; record that requirement rather
than promising automatic recovery from an OS force-stop. Use disposable test
devices for storage/memory-pressure tests. Avoid filling the user's personal
phone or changing its security/power settings without consent.

## Findings and closure

Record all failures in `beta-findings.csv`, attach sanitized evidence and link
fix/retest commits. Stop distributing a candidate with critical/high findings;
preserve existing test data for controlled recovery. Run the failed scenario and
related protocol/upgrade regressions again on the fixed candidate. Old passes do
not transfer automatically to a new APK.

`validate_release_evidence.py` checks ledger structure. With `--require-complete`
it also requires all device slots, every matrix case, hashed local evidence,
matching candidate hashes, independent audit completion and finding disposition.
This checks recorded evidence; it cannot independently witness a human test or
replace reviewer judgment. Phase 20 closes only after maintainers review the
real-device results, significant defects are fixed/retested, and all mandatory
coverage is complete. Unsupported/blocked scenarios cannot silently be waived.
