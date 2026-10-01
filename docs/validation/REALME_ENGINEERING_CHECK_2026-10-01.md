# Realme supplemental engineering check — 2026-10-01

Device: RMX5070, Android 16 / API 36. Own connected device, authorized debug
build. This is internal engineering validation, not an independent penetration
assessment or completion of the closed-beta matrix.

Installed app APK SHA-256:
`10dc67828418263b5c034787a39fc4f8459ce6b68c90e3a235d045c344a5b5a7`.
The installed base APK matched the host candidate. Existing app data was preserved.

## Results

- All 29 Android instrumentation tests passed, zero failures. Four added checks
  cover Keystore nonexportability and ciphertext/key mismatch rejection,
  SQLCipher wrong-passphrase rejection and correct-key reopen, malformed transport
  framing, and media path ownership. These use isolated fixtures, not live keys.
- Both internal services rejected external shell starts; the media provider
  rejected untrusted traversal access. Package backup was disabled.
- Foreground launch, two force-stop/relaunch cycles, and a synthetic memory
  callback passed. Process survival does not prove message/ratchet durability.
- Bluetooth and airplane mode changes/restoration passed. The first Wi-Fi check
  was inconclusive with a fixed short delay; a separate bounded state-polling
  retest confirmed change, restoration and process survival. The runner now waits
  for actual state transitions. Original radio settings were restored.
- Local Tor diagnostics reported bootstrap 100. The listener closed 12 of 12
  malformed/unauthenticated connections and remained responsive. It also recovered
  after 34 bounded partial connections were closed. This uses local ADB forwarding,
  not a remote peer or sustained denial-of-service assessment.
- A final read of the device crash log buffer returned no entries.

## Local evidence

Evidence remains local under these ignored build directories:

- `android/build/device-beta/20261001T140535.048555Z/results.json`
- `android/build/device-beta/20261001T140535.048555Z/instrumentation.json`
- `android/build/device-beta/20261001T140535.048555Z/wifi-retest.json`
- `android/build/device-beta/20261001T140138.568021Z/tor-probes.json`
- `android/build/device-security-probes-build.log`
- `android/build/roadmap-phase6-17-26-validation.log` — prior candidate build and
  391 passing JVM tests; app source unchanged during these device probes.

## Still pending

Two-phone bidirectional persistence/ACKs, voice/video across networks, large-file
resume/integrity, long offline periods, physical pressure scenarios, all required
manufacturer/API cohorts, and independent external review remain open. The two
recorded internal media-expiry risks remain open. No release gate or mandatory
beta matrix row was promoted by these supplemental results.
