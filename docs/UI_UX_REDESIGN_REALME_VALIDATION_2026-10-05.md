# Realme UI redesign validation — 2026-10-05

Scope: the user authorized checking only the Realme RMX5070. The Vivo is not part of this pass. Android API 36, physical display 1080×2400, existing density override 408; that override was not changed.

## Data preservation and setup

The installed APK was pulled to `android/build/ui-redesign/realme-before-final-pass.apk`. Debug and instrumentation APKs were replaced using `adb install -r`, with the existing signing certificate; neither package data nor identity was erased. The installed chat list and saved appearance were observed after a cold MainActivity launch (reported launch time 1,893 ms; this single measurement is not a benchmark).

The global `stay_on_while_plugged_in` value was temporarily changed from 0 to 3 for the test run. It must be restored to 0 before finishing. A local settings snapshot was copied within the app's cache to `torx-ui-audit-settings-backup.preferences_pb`; no actual appearance selections have yet been changed. Delete that owned snapshot after verification. Do not remove unrelated app files.

## Instrumentation outcomes

The consolidated batch includes 55 cases: app appearance, conversation list, chat/composer/reactions, editor Apply/Cancel, preview text, private wallpaper import, read-only 100,000-message history, categorized settings, existing UI interaction, connection presentation and invite confirmation fixtures.

Attempt 1: 53 passed, 2 failed (`realme-final-fixtures.log`). Attempt 2: 54 passed, 1 failed (`realme-final-fixtures-confirmed.log`). Both history DAO cases passed, including bounded 500-row queries, old-anchor reachability, hidden-message filtering and unchanged read state. These are functional history-reader checks, not frame-time measurements of a 100,000-message UI.

## Issues found and corrections

### Major: latest-message action overlaps composer

Location: `android/app/src/main/java/com/torxone/app/ui/screens/ChatScreen.kt`, floating Scroll to latest button.

The content container extends behind Scaffold's bottom bar. The list used the bottom inset, but the floating button used only 12dp padding and sat behind the composer. The reachability test still failed after a five-second wait, ruling out the initial immediate-assertion assumption. The fix positions the button above `innerPadding.calculateBottomPadding()` plus 12dp. The same regression case must pass on the corrected APK; this is still pending.

### Major: appearance fixture must isolate wallpaper cleanup

Location: `android/app/src/androidTest/java/com/torxone/app/ui/ChatAppearanceEditorInteractionTest.kt`, withFixture.

An injected preference store previously shared production filesDir. Apply's cleanup could inspect the real wallpaper directory against test preferences. Before running the fixtures, storage was redirected through an application-context wrapper to a unique fixture directory, deleted after the test. Production wallpaper storage and cleanup behavior were not changed.

### Minor: obsolete gallery-empty-state assertion

Location: `android/app/src/androidTest/java/com/torxone/app/ui/UiInteractionTest.kt`, sharedGalleryShowsTruthfulEmptyStateAndBackAction.

The test expected the removed label No shared media or links yet. It now checks Nothing shared yet and retains the Back action assertion. That case passed in attempt 2.

## Current interruption

The Realme disconnected from ADB while the latest-action fix was building. ADB now sees only a Vivo V2059. No Vivo installation or UI action was performed. The Realme still needs the corrected debug regression check, final release replacement, restart/crash checks and cleanup of the temporary test settings. The corrected build log is `realme-scroll-fix-build.log`.

## Evidence still required

- Successful latest-action regression and final applicable fixture batch on the corrected APK.
- Final release install/restart on the Realme, preference restoration, runtime-error review and removal of temporary keep-awake configuration.
- Real persisted appearance edits/process restart if the phone remains available.
- Human TalkBack navigation and measured scrolling/sensor/memory checks have not been established by the passing fixtures.
- Fresh two-phone message, call and media delivery remains untested because this pass is limited to one phone.

## Corrected local artifact status

The latest-action padding fix passed debug/test/release assembly and lint with exit 0 (`realme-scroll-fix-build.log`) and the complete 538-test unit suite with zero failures/errors/skips (`realme-scroll-fix-unit.log`). All three signed APKs were regenerated and verified with the existing certificate. All 113 protected backend sources still match the baseline. The final on-device regression and installation remain pending; reconnect the Realme, not the Vivo, to resume this authorized pass.

## Completed Realme pass

The Realme reconnected and the corrected debug and test APKs were installed using data-preserving updates. The consolidated batch passed **55/55 tests** (`realme-corrected-final-fixtures.log`, `OK (55 tests)`, 75.918 seconds), including `incomingWhileReadingHistoryShowsReachableLatestAction`. This establishes the latest-message button fix on the physical device.

The signed release APK was then installed successfully with `install -r`. A forced-stop/cold launch returned Status ok, LaunchState COLD, TotalTime 644 ms; this single measurement is not a performance benchmark. The real application hierarchy showed TorX One, Settings and New chat, and its process remained running. Captured release startup/restart logs contained no FATAL EXCEPTION, app ANR, or fatal-signal markers. This is a bounded startup check, not proof of absence of all runtime defects.

The temporary keep-awake setting was restored and read back as 0. The owned cached settings snapshot and remote UI dump/screenshot files were removed. No app data was cleared or main package uninstalled. No appearance selections were intentionally edited during the pass. The current settings-file hash differs from the earlier snapshot, so byte-identical preference preservation is not asserted; persisted appearance-edit/restart testing remains unestablished. The Vivo was not installed or operated.

Remaining limits: human TalkBack review, measured scrolling/sensor/memory performance and fresh two-phone text/call/media delivery have not been established. No additional phone is requested for this Realme-only pass.
