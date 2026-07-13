# Release Process

This document describes the Android release flow for TorX One.

## Release source

Public APK distribution uses GitHub Releases:

https://github.com/Nikhilesh-CS/TorX-One/releases/latest

The in-app updater checks the latest release from the TorX One GitHub repository.

## Before release

Verify:

- Repository name is `TorX-One`
- Default branch is `main`
- CI is passing
- Version name and version code are correct
- Release APK was built from the intended commit
- APK label is `TorX One`
- APK package is `com.torxone.app`

## Build commands

From the Android project:

```powershell
cd android
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

## Release checklist

1. Merge the release PR into `main`.
2. Confirm GitHub Actions passed.
3. Build or locate the final release APK.
4. Open GitHub Releases.
5. Use the correct tag, for example `v1.0.18`.
6. Attach the APK.
7. Add clear release notes.
8. Publish the release.
9. Open the latest release URL and verify the APK is visible.

## Recommended release notes format

```markdown
## TorX One vX.Y.Z

### What's new
- Short user-facing change.
- Short user-facing change.

### Security and reliability
- Security-related fix.
- Reliability improvement.

### Validation
- Android CI passed.
- Debug build passed.
- Release build passed.
- APK label verified.
```

## Important warning

Do not publish a new APK release under the old repository name. The app updater expects:

```text
https://github.com/Nikhilesh-CS/TorX-One/releases/latest
```

