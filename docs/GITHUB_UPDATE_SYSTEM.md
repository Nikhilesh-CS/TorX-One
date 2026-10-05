# GitHub update subsystem — implementation and release handoff

Implemented in source on 2026-10-05. **No Gradle command, build, APK generation, phone installation or remote publication was performed for this task**, at the user's explicit request. The existing `release-artifacts/release.apk` predates this subsystem and does not include it. Implementation is not an end-to-end verified release.

## Audit before implementation

| Area | Actual project state |
| --- | --- |
| Package/version | `com.torxone.app`, versionCode **3**, versionName **0.1.0** |
| SDK | minSdk 26, targetSdk 36, compileSdk 37.1 |
| Networking | No OkHttp/Retrofit/Ktor client dependency; updater uses platform HTTPS with system TLS |
| Persistent jobs | WorkManager 2.12.0 already used for scheduled messages |
| Existing FileProvider | `${applicationId}.media`, limited to media paths; unchanged |
| TLS policy | Existing network-security config disallows public cleartext and trusts system certificates; unchanged |
| Settings | Existing app settings DataStore and SettingsViewModel unchanged; update preferences have a separate `torx_updates` DataStore |
| Notifications | Messaging/call channel owners unchanged; new quiet update/download channels and distinct IDs |
| Local signing | Existing `.release-signing/sign-release.ps1` uses the existing production identity. Keys/passwords are ignored by Git. No signing operation or key change in this task |
| CI before change | Tag-triggered gates, signed candidate verification, inventories, attestations and uploaded workflow artifact; no GitHub release or updater metadata |

Production signer previously verified: SHA-256 `ee9d73b0c70e66855a6cbf73dda8c5b0d5e26e9015f29d55a1e96875b5fe607a`. Future builds must preserve the existing key. GitHub release environment secrets/variables were not read or provisioned; availability of the matching key there remains an operator requirement. Missing secrets or trusted certificate fail the existing pipeline checks.

## Architecture decision

| Approach | Security/storage | Background/restart | UX | Decision |
| --- | --- | --- | --- | --- |
| GitHub API + DownloadManager | System downloads commonly target external app storage; redirect control is less explicit | Durable system-owned download and progress | Familiar progress, extra completion reconciliation | Not selected: weaker control over redirects and the strict private-storage requirement |
| GitHub API + WorkManager/platform HTTP | Exact hosts, bounded redirects, private storage and fresh APK verification | Durable unique work, persisted UUID/metadata, foreground notification, bounded retries | In-app progress/cancel; restart from zero after interruption | Selected; reuses installed job library without introducing another HTTP library |
| Browser-only redirect | Browser owns the download; app cannot establish end-to-end verification | Independent of app lifetime | Manual downloads, no reliable verified-install state | Only full-release-note links use a browser |

WorkManager downloads use an eight-minute bounded transfer attempt and at most three network attempts with exponential backoff. They restart from zero; HTTP byte-range resume is intentionally not implemented. Each attempt uses its own private partial file. The job UUID is persisted, so an old failed same-version job cannot masquerade as the new download. Ready APKs are reverified after process restart even if completed WorkManager records have been pruned. Android job/foreground quotas and force-stop can defer or prevent execution; the UI exposes pending/retry/failure instead of promising exact scheduling.

Source wiring:

`MainActivity.onCreate → UpdateRuntime.start → coroutine check + unique daily job`

`Settings → About → App updates → private UpdateActivity → UpdateRuntime → UpdateRepository → GitHubReleaseClient`

`User confirms download → UpdateDownloadManager → UpdateDownloadWorker → private partial file → UpdateInstaller.verify → ready APK → explicit Install tap → fresh verify → Android installer`

MainActivity only starts scheduling and opens the update activity; it contains no GitHub HTTP logic. Updater classes do not access the message database, identity, ratchet, outbox, pairing, calls or Tor transport. UpdateNotifications reads only the existing app notification preference. Notification links open the update-only activity; this activity exposes no chats or private identity, and does not unlock protected keys.

## Discovery, policy and privacy

- Repository: **Nikhilesh-CS/TorX-One**.
- Endpoint: `https://api.github.com/repos/Nikhilesh-CS/TorX-One/releases/latest`. Returned draft/prerelease flags are checked and rejected. The GitHub API's latest stable selection is used; semantic tag strings do not determine update availability.
- Assets: `update.json` and the uniquely named APK from that same release. Asset names, integer fields, schema/channel, SDK bounds, size and hash are validated. Cache values undergo the same release validation.
- Version authority: remote `versionCode > BuildConfig.VERSION_CODE`. Equal/older releases never offer an update. APK package/versionName/versionCode must also agree with metadata before installation.
- Metadata schema 1: versionCode, versionName, minimumSupportedVersionCode, minimumAndroidSdk, critical, apkAsset, sha256, releaseNotes, channel. The minimum-supported field only recommends an update; it does not disable messaging or force installation.
- Automatic checks: on the first launch in a process if due, plus network-constrained unique periodic work approximately every 24 hours. Cache throttles automatic attempts for 24 hours; manual checks bypass that interval but respect a server rate-limit cooldown. Duplicate checks/download taps are coalesced.
- Manual discovery has a 45-second coroutine timeout plus finite connect/read/redirect bounds. Blocking platform reads may need up to a read-timeout interval to unwind cancellation. Discovery errors are recoverable and never presented as “up to date.” Background network failure silently defers to a later check.
- Checks/downloads use **ordinary HTTPS, not Tor**. GitHub sees the network address and ordinary transport metadata. No contact, account identity, onion address, profile, message information, device ID, PAT, cookies or analytics are sent. This is explicitly disclosed in App updates.
- Allowed transport hosts: exact `api.github.com` endpoint, repository-scoped `github.com` release downloads, and exact `release-assets.githubusercontent.com`, `objects.githubusercontent.com`, `github-releases.githubusercontent.com` asset infrastructure hosts. Every redirect is validated; HTTP, credentials in URLs, nondefault ports, unrelated GitHub repositories and arbitrary subdomains fail closed. Infrastructure changes require review rather than broadening the whitelist silently.
- Persisted data: automatic preference, last attempt/success, rate-limit cooldown, validated latest release, dismissed version/reminder cooldown, notified version, selected download metadata and exact job UUID. Raw API responses are not retained.

## Download, verification and installation

- Downloads happen only after explicit user confirmation, including a mobile-data warning. They are never automatically started by checks.
- APK size capped at 512 MiB; response sizes are bounded independently. Storage capacity is checked with headroom. Partial files are deleted on handled failure/cancellation; a killed attempt is cleared on its retry. Obsolete owned files are removed after success and after an installed update is observed.
- App-private `filesDir/update-apks`; backup/data extraction remain disabled by the existing policies. A distinct nonexported `UpdateFileProvider` grants only the update directory, with temporary read permission for the installer. No `file://` or broad root/storage provider is used.
- SHA-256 and exact expected length are checked. PackageManager supplies archive package/version/signing information, compared to the installed app's **current signer set**. Android performs final signature/integrity and update compatibility enforcement. The app does not claim PackageManager parsing alone replaces Android's installer verification.
- Wrong hash/package/version/signer/SDK stops the flow; no “install anyway.” Install-time failures remove the invalid APK and restore a recoverable error/check state.
- Fresh verification runs immediately before the URI grant, including after permission settings return. Only then is `ACTION_INSTALL_PACKAGE` launched. Android owns installation confirmation; there is no silent install.
- `REQUEST_INSTALL_PACKAGES` is declared. Per-app “Install unknown apps” settings are offered only after the user selects Install, with an explanation. Permission denial/cancel is shown. Permission return waits for recovered Ready state before resuming after recreation; installer cancellation retains the verified APK.
- Signing-key rotation is deliberately fail-closed in v1: exact current signer-set matching rejects a different key/lineage. A future rotation needs explicit signing-lineage policy and upgrade tests.

## Signed metadata tradeoff

For v1, repository HTTPS + SHA-256 + same-installed-signer/package/version checks are used. The hash detects corruption, but a compromised GitHub release can replace metadata and hash together; metadata is not independently authenticated. The pinned installed signing identity prevents offering an arbitrary differently signed APK, and Android is the final signature enforcement authority. An attacker controlling release metadata can still hide an update, alter its notes/critical flag or deny service.

Phase 2 recommendation: canonical versioned `update.json` bytes and detached `update.sig`, verified with an embedded offline update public key. Store the corresponding private metadata key offline or in an approved signing service, separately from GitHub publication credentials. Define rotation, signature format, anti-rollback and recovery policies before enabling it. No private metadata key is generated or embedded in this implementation.

## Notifications

Separate low-importance update/download channels; no messaging/call notification modifications. A persistent last-notified version and stable notification ID prevent a new daily alert for the same version. Later suppresses reminders for 24 hours. Disabled OS/app notifications do not affect manual checks or in-app installation. The app does not request notification permission specifically for updates. Download work has its own foreground progress/cancel notification; completed verification can update the quiet notification to Ready to install. Exact-once notification delivery across a crash between posting and persisting is not claimed.

## Release pipeline and single version source

`.github/workflows/release.yml` retains existing security/review gates, trusted signer verification and attestations. It accepts either `vVERSION_NAME` or `vVERSION_NAME-buildVERSION_CODE` tags. `android/tools/generate_update_metadata.py` independently inspects the already signed APK using aapt and apksigner, compares it to Gradle's literal version values and trusted certificate, then generates:

- `torxone-vVERSION_NAME-buildVERSION_CODE.apk`
- `update.json`
- `SHA256SUMS`

Hashes are generated from actual bytes, not edited by hand. Concise notes come from `docs/GITHUB_RELEASE_NOTES.md`. Version mismatch, wrong package, debug/unsigned/wrong-signer APK, invalid tag/notes/minimum, or changed APK bytes fail generation. Existing inventory/checksum files remain in the workflow artifact. A short-lived GitHub Actions token is used only to prepare a **draft** release; it is never in the APK. Stable discovery ignores drafts. Publication is manual after the actual upgrade test; this preserves the requested prelaunch validation instead of claiming unrun tests passed.

No tag, push, CI run, GitHub draft/public release or signing-secret setup was performed locally. If a release/tag already exists, the workflow fails rather than silently replacing its public assets.

## Files changed and added

Modified: `android/app/build.gradle.kts` (explicit lifecycle Compose dependency), `AndroidManifest.xml`, `MainActivity.kt`, `ui/screens/SettingsScreen.kt`, `.github/workflows/release.yml`, `.github/workflows/security-ci.yml`.

New runtime classes/files under `android/app/src/main/java/com/torxone/app/updates/`: `UpdatePolicy`/`UpdateRelease`/`UpdateState`/`UpdateFailure`, `GitHubReleaseClient`/`UpdateHttp`/`ReleaseSource`, `UpdateRepository`, `UpdateStore`/`UpdateSnapshot`/`UpdatePersistence`, `UpdateDownloadManager`, `UpdateCheckWorker`/`UpdateDownloadWorker`/`UpdateWork`, `UpdateInstaller`/`UpdateFiles`/`UpdateFileProvider`, `UpdateRuntime`, `UpdateNotifications`, `UpdateActivity`/`UpdateScreen`.

New resources/tests/tools/docs: `res/xml/update_paths.xml`, `UpdatePolicyTest.kt`, `UpdateRepositoryTest.kt`, `UpdatePersistenceDeviceTest.kt`, `android/tools/generate_update_metadata.py`, `android/tools/test_update_metadata.py`, this report and `docs/GITHUB_RELEASE_NOTES.md`.

## Validation performed and remaining launch gates

The initial source-only validation below is superseded for compilation, JVM tests, lint and signed packaging by [BUILD_RELEASE_2026-10-05.md](BUILD_RELEASE_2026-10-05.md). Version 0.1.1 (versionCode 4) has been built and signed; all 14 updater JVM tests passed within the 556-test complete suite. The user explicitly excluded phone tests. Live installer and published-release integration remain untested in this run.

Passed without building: eight new Python metadata-generator tests; eight existing signed-candidate verification tests; Python syntax and changed XML parsing; application logging privacy source check; whitespace/diff check. All 113 protected transport/crypto/identity/call/media/incoming/relationship/agent/connection sources remain byte-identical to the existing baseline. Workflow YAML received static review; a YAML parser was not available in this runtime. Unexpected updater coroutine failures have an isolated exception handler with a recoverable state and sanitized diagnostics, rather than an uncaught process-level failure.

Added and passed in the subsequently authorized complete suite: eight Kotlin policy tests (numeric versions, valid/cache metadata, missing/bad fields, hostile hosts, cross-release assets, changed-byte hashes, wrong package/signer/downgrade, cooldown); six repository tests (Checking/Available, duplicate taps, offline recovery, equal/older versions, disabled/cache policy, rate limiting). The isolated device persistence/restart test compiled but was not run. Fixture data never writes to production app preference files.

**Not run:** updater UI/worker/installer device fixtures, API integration with a real published metadata release, background quota/cancellation device tests, or actual signed old-version → newer-version upgrade preserving contacts/chats/relationships/settings. Kotlin compilation/unit tests, debug/release lint and manifest merger passed in the subsequently authorized build. Previous UI redesign device results do not validate this newly added subsystem. No zero-bug claim is made.

Remaining runtime validation would exercise a controlled metadata release/fixture endpoint, discovery/download/hash and signer rejection, permission denial/return, installer cancellation, process death/retry, offline/rate limit and a real newer signed upgrade preserving app data and messaging. These device exercises were excluded by the user's instruction. Verified signed APK and metadata assets were generated locally; no stable release was published.

Official references used for API/lifecycle review:

- https://docs.github.com/en/rest/releases/releases
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- https://developer.android.com/reference/android/content/pm/PackageManager
- https://developer.android.com/reference/androidx/core/content/FileProvider
