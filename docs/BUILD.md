# Build and verification

Install JDK 17, Android SDK platform 37.1 and build tools 36.0.0 (the CI configuration). The app targets API 36 and supports API 26 or newer. The wrapper pins Gradle 9.6.0; plugin and dependency versions are in `android/build.gradle.kts` and `android/app/build.gradle.kts`. Do not silently downgrade versions to make CI green. SDK and Maven repositories require network access on first build. The Tor AAR is resolved from the declared `info.guardianproject:tor-android` dependency; the release inventory records its selected version and hash.

Set `JAVA_HOME` and `ANDROID_HOME`, or create untracked `android/local.properties` with `sdk.dir`. From `android/`:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

On Linux use `bash ./gradlew`. Connected tests require an authorized device/emulator. Reports are under `app/build/reports`; APKs under `app/build/outputs/apk`. CI explicitly selects protocol, state-machine and bounded mutation tests as additional gates.

Optional `TORX_STUN_URLS`, `TORX_TURN_URLS`, `TORX_TURN_USERNAME`, `TORX_TURN_CREDENTIAL` Gradle properties/environment variables configure ICE. Values become APK BuildConfig strings and are extractable; use limited, short-lived credentials. Empty configuration does not guarantee Internet calls.

For a physical install, run `adb devices` then `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`. Do not uninstall or clear storage to resolve an install failure without deciding what data may be lost. Debug and production certificates cannot update one another.

Release building/signing and required repository setup are in [RELEASE.md](RELEASE.md). A clean local build is not necessarily bit-for-bit reproducible; provenance/checksums identify the artifact actually produced.
