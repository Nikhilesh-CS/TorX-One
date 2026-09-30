# UI/UX repair — September 30, 2026

Scope: the 27 issues in the supplied UI/UX audit. Existing identity, ratchet,
transport, and conversation ownership are retained.

| Audit item | Repair |
| --- | --- |
| 1 | Chat tracks whether the reader is following the latest message; initial load and outgoing sends scroll to the end. Incoming messages preserve an older reading position. |
| 2–3 | Voice notes use MediaPlayer preparation, completion/error callbacks, actual pause/start and playback speed; audio focus and lifecycle teardown stop abandoned playback. |
| 4 | Video taps open a VideoView with playback controls. Pending downloads show download/progress state. |
| 5–6 | Documents open through a private, URI-scoped FileProvider grant. Audio attachments use the player. Paused/failed downloads call MediaService.resumeTransfer; unavailable files and readers report errors. |
| 7 | Fullscreen images load the actual local file, fit to the screen and support pinch zoom and panning. |
| 8 | Bounded attachment reads report oversized, empty, unreadable and provider failures; view-model errors are surfaced. |
| 9–10 | Outgoing call navigation checks the returned session and current call ID. An absent/idle call shows a back action without live call controls. |
| 11, 15–19 | Shared ProfileAvatar renders photos in call UI, chat list/header, Contact Info and Settings. Live contact metadata updates invalidate state equality. |
| 12–14 | Authenticated, durable profile updates include About text and a bounded photo. Content-addressed avatar storage and stored versions protect against stale updates. Matching verified identity aliases receive the same metadata. |
| 20 | Photo editor supports bounded loading, EXIF orientation, crop preview, pinch/slider zoom, drag/reposition, quarter-turn rotation, circle/square preview and a bounded final JPEG. |
| 21 | Settings About opens a dialog with app/transport information. |
| 22 | Identity ID copies the full ID and reports completion. |
| 23 | Contact Info navigates to a live media/link/document gallery with playback, open and download actions. Deleted/locally hidden messages are excluded. |
| 24 | Contact Info scrolls vertically; group details also remain scrollable with keyboard/landscape constraints. |
| 25 | QR decoding suppresses repeated codes briefly, continues after invalid codes, handles luminance row/pixel strides, runs analysis off the UI thread, and releases its own camera use cases. |
| 26 | Group creation awaits the suspend operation and clears loading in finally. Errors preserve the form for retry. |
| 27 | Compose device tests exercise incoming/outgoing scroll, preserving reading position, identity copy, group failure/retry, gallery/back, profile failure, About, actual audio play/pause/speed and paused download callbacks. |

## Profile wire/storage contract

- The legacy name/About prefix remains readable by older apps. A bounded
  `TXPROFILE2` extension carries exact About text, a version and explicit avatar
  replacement/removal. No remote URI is downloaded.
- JPEG photo data is capped at 24 KB and image dimensions at 512 pixels. The
  containing encrypted envelope remains below the unchanged transport limit.
- Updates are committed with ratchet state and durable outbox, retried until
  authenticated acknowledgement. New/restored connections also receive the
  saved profile. Profile/About visibility settings are respected.
- Room migration 14→15 only adds About and profile-version columns. Existing
  contact, relationship, session, sequence, message and outbox data is preserved.
- Both peers need the new build for photo/About synchronization and removal.

## Regression checks

```powershell
cd android
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

```powershell
python android/tools/verify_peer_endpoint_migration.py
python android/tools/verify_profile_migration.py
python android/tools/verify_contact_selection.py
```

Install the generated debug and androidTest APKs, then run:

```powershell
adb -s SERIAL shell am instrument -w -e class com.torxone.app.ui.UiInteractionTest com.torxone.app.test/androidx.test.runner.AndroidJUnitRunner
```

Physical follow-up: exchange profile photo/About updates and removal on two
updated phones; play received video/image/audio/document attachments; scan an
invalid QR followed by a valid invite; inspect landscape and large-font Contact
Info. These checks are distinct from local codec, migration and Compose tests.
The earlier bidirectional Tor/text/call checks used the preceding build; they do
not constitute live verification of this new profile/media UI.

## Recorded validation

- Debug app and instrumentation APK builds and Android lint passed. Final build
  log: `android/build/validation-ui-final.log` (`BUILD SUCCESSFUL`).
- JVM regression suite: 344 tests, zero failures or errors.
- Realme RMX5070: all 10 `UiInteractionTest` tests passed, including real audio
  playback controls, clipboard copy and group creation failure/retry.
- Schema 13→14 and 14→15 preservation checks passed; contact selection passed
  all 24 insertion orders. `git diff --check` passed.
- Updated APK installed on Realme without clearing app data. App reopened with
  no AndroidRuntime crash in the checked log. The second phone was disconnected;
  the physical follow-up listed above remains pending.
