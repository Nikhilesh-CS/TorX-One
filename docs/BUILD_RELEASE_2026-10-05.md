# TorX One 0.1.1 / versionCode 4 — build and release evidence

Date: 2026-10-05. This report supersedes the source-only validation status in the updater and media/reaction/profile implementation reports. Phone tests were explicitly excluded by the user. No phone install, Git commit, push, tag or GitHub publication was performed.

## Result

The current source builds successfully in debug and minified release variants. The signed release contains the GitHub updater, full emoji reaction picker, inline image previews and contact/group photo viewer.

| Check | Result |
| --- | --- |
| Debug APK and Android test APK compilation | Passed |
| Complete JVM unit suite | 556 tests; 0 failures, 0 errors, 0 skipped |
| Debug lint | 0 errors; 46 advisory warnings |
| Release build, resource shrinking and R8 optimization | Passed |
| Release lint | 0 errors; 46 advisory warnings |
| Dependency inventory / SBOM generation | Passed; not a vulnerability scan |
| Python emoji catalogue checks | 3 passed |
| Python updater metadata tests | 8 passed |
| Python signing-policy tests | 8 passed |
| Application logging privacy check | Passed |
| Protected backend boundary | 113 source files byte-identical to baseline |
| Signed APK identity / version / certificate verification | Passed |
| APK ZIP alignment, including 16 KB native-library alignment | Passed |
| Tor JNI contract in the signed APK | Class, 4 instance fields and 6 native method contracts verified |
| Native APK inventory | 28 entries inventoried; not a vulnerability scan |

Initial lint found an emoji classification constant exposed only from API 28 while the app supports API 26. This was fixed with an API guard and a bundled-catalogue fallback on older devices. A regression test covers variation selectors, skin tones and joined sequences. The final full build/test/lint command succeeded after that change, and the API warning is absent.

Remaining warnings include dependency-update suggestions, Kotlin/API deprecations and style suggestions. The singleton context warning refers to `UpdateRuntime`, whose private construction always uses the application context; it does not retain an Activity. Warnings were not globally suppressed. Passing these checks does not prove absence of all runtime bugs.

## APK and GitHub assets

- User-facing APK: `release-artifacts/release.apk`.
- Archived signed APK: `release-artifacts/release-version4.apk`.
- GitHub APK asset: `release-artifacts/torxone-v0.1.1-build4.apk`.
- GitHub updater metadata: `release-artifacts/update.json`.
- GitHub checksums: `release-artifacts/SHA256SUMS`.
- Suggested GitHub release tag: `v0.1.1-build4`.

All three APK filenames contain identical signed bytes:

`dcdf516dfd1361ddf28830b670f5c5450666e3b83ae0448d236ab711a5ef33c0`

Verified package: `com.torxone.app`; versionName: `0.1.1`; versionCode: `4`; minSdk: `26`; targetSdk: `36`. APK Signature Schemes v2 and v3 verified with one signer. Certificate SHA-256:

`ee9d73b0c70e66855a6cbf73dda8c5b0d5e26e9015f29d55a1e96875b5fe607a`

The previous versionCode 3 APK remains in `release-artifacts/release-version3.apk`. Signing credentials were not changed, printed or included in Git. The R8 mapping is archived as `release-artifacts/mapping-v0.1.1-build4.txt`.

Upload the uniquely named GitHub APK, `update.json` and `SHA256SUMS` as assets of the same GitHub release. These files are release assets, not source files to commit. Keep signing material private. A future update must use a versionCode greater than 4 and regenerated metadata matching its signed APK.

## Reproducible commands and logs

Use the installed Android Studio JBR as `JAVA_HOME`. Run from `android/`:

```powershell
.\gradlew.bat --no-daemon --no-parallel :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:lintRelease
```

Final completed log: `android/build/latest-update-final-checks.log` (`BUILD SUCCESSFUL in 8m 51s`). Initial build logs: `latest-update-debug-checks.log` and `latest-update-release-checks.log`. Test XML reports are in `android/app/build/test-results/testDebugUnitTest/`; lint reports are in `android/app/build/reports/`.

Artifact-specific verification records: `release-artifacts/verified-release-v4.json`, `tor-jni-release-v4.json`, `zipalign-v4.log` and `native-libraries-v4.json`. On Windows, pass absolute native SDK tool paths to the Python verification/metadata tools; the successful runs used `.android-sdk\build-tools\36.0.0\apksigner.bat` and `aapt.exe` with their full paths.

The added updater persistence, media preview and Compose interaction tests compile but were not executed on a device, as requested. Live two-device synchronization, installer upgrade/data preservation, and actual GitHub release discovery/download were not exercised in this run. Old device results are not evidence for this binary.

## Commit and push

Current branch: `codex/release-hardening`; remote: `origin`. Commit the source, tests, version bump, implementation/release notes and CI updates together. The commands deliberately omit older untracked audit/device notes and all ignored build/signing/release artifacts.

From `C:\Projects\torX One`:

```powershell
git add .github/workflows/release.yml .github/workflows/security-ci.yml android/app/build.gradle.kts android/app/src android/tools/generate_update_metadata.py android/tools/test_update_metadata.py android/tools/test_emoji_catalogue.py docs/GITHUB_UPDATE_SYSTEM.md docs/GITHUB_RELEASE_NOTES.md docs/MEDIA_REACTION_PROFILE_UX.md docs/BUILD_RELEASE_2026-10-05.md
git diff --cached --stat
git commit -m "Add verified GitHub updates and improve reaction, media and profile UX"
git push -u origin codex/release-hardening
```

This pushes the current branch. It does not merge into `main` or publish a GitHub release.
