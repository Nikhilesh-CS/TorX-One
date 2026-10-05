# TorX One UI redesign execution record

Baseline recorded 2026-10-04: `498157ccd0d18dfd23bb98d88c32969bad33edbd`.

Read [the source audit and design contract](UI_UX_REDESIGN_AUDIT.md) for protected architecture, migration requirements and the ordered implementation plan.

The user explicitly requested local work without requiring a repository branch after Git metadata denied branch creation. The recoverable committed-source archive is `android/build/ui-redesign/baseline/known-good-source.zip`, SHA-256 `57113353ad957f5ab732e00b44ab6aec9a6632d46d1cec5c16f85cb3ceb92a96`.

## Phase ledger

| Phase | Source implementation | Build/unit/lint | Device/accessibility/performance evidence |
|---|---|---|---|
| A: design system, theme wiring and branding | Complete | Passed: 516 unit tests; both APKs; lint | 2 Realme appearance fixtures passed; broader release checks remain in J |
| B: conversation list | Complete | Passed: both APKs, unit suite and lint | Fixtures prepared; final device pass pending |
| C: chat timeline | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| D: composer and reactions | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| E: appearance and private wallpaper | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| F: optional Depth and motion | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| G: settings categories | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| H: profile/contact/group | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| I: calls/media/onboarding | Implemented; documented API limits below | Passed: final APK builds, unit suite and lint | Final device pass pending |
| J: final accessibility/performance/regression | Fixtures and local checks prepared | Passed locally: 538 tests; debug/test/release builds; lint | Final physical-device checks pending |

Each entry reports actual completed commands and applicable artifact/source scope. An APK assembly, static inspection or fixture cannot substitute for physical two-device, TalkBack or measured performance evidence. Prior messaging success is the user's accepted baseline; it is not new post-redesign proof.

## Phase A scope

Neutral graphite/warm-neutral light foundation, curated accent roles, complete intentional typography scales, real optional Android dynamic colors, appearance settings wiring, shared silver/white T vectors and splash/system-chrome contrast. Settings keys remain local; no protocol/transport/encryption/delivery/call/media ownership changes are included.

Initial foundation validation passed: `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` completed with exit 0. The unit suite reported 516 tests, zero failures/errors/skips. The protected-source guard confirmed 113 backend files remain byte-identical to the baseline. Evidence: `android/build/ui-redesign/phase-a-validation.log` and the generated Gradle reports.

The user then supplied the final branding reference: the metallic ribbon T with a muted green leaf. Launcher foreground, legacy launcher, monochrome, splash and onboarding vectors now trace that reference using five shaded regions and a thin fold edge. The adaptive foreground excludes the reference image's outer rounded tile and white margin; the platform supplies the mask and graphite background. The monochrome version retains a leaf-shaped cutout. The final resource revision passed build/unit/lint validation as recorded below. An SVG preview generated directly from the Android foreground paths is saved at `android/build/ui-redesign/brand-preview.svg`.

Final branding revision: the same four Gradle tasks passed with exit 0 (`phase-a-final-validation.log`); 516 unit tests passed. Both `AppearanceSettingsInteractionTest` fixtures passed on Realme RMX5070 / API 36 (`phase-a-device-fixtures.log`), including callback selection and 320dp / font-scale 1.5 wrapping. Debug and instrumentation APKs were signed with the existing certificate for data-preserving replacement; the pulled known-good release APK was restored successfully using `adb install -r` afterward. No app data was erased.

These isolated presentation fixtures do not establish TalkBack usability, launcher rendering across OEM masks, real DataStore/process-death behavior or fresh two-device messaging. Those checks remain explicit final-validation work. ADB currently sees only the Realme RMX5070.

## Consolidated integration, 2026-10-05

The user requested that implementation be completed before any further phone operations. No phone installation or repeated device test is part of the current integration work; one final physical-device pass remains after the source and local checks are complete.

Phase B validation completed successfully (`phase-b-final-validation.log`). Foundation and conversation-list changes are integrated with categorized settings, supporting screens, staged attachment previews, grouped chat bubbles, entry unread markers, anchored reactions, and private per-chat/default wallpaper editing. Two delegated reviews stopped at the account usage limit; their partial changes were retained and are being reviewed by the primary agent rather than reported as complete agent approvals.

Large chat history now uses an additive, read-only Room DAO and bounded 500-message windows with overlap and navigation to older messages or reply/search targets. Thumbnails are read for composed image bubbles rather than all 500 records. No database version or entity schema is changed. ViewModel presentation listeners stop when the chat closes; accepted send/edit/react operations are allowed to finish before the presentation store is cleared. Foreground tracking and timeline reads follow the screen lifecycle. Private wallpaper cleanup is connected on startup and after Apply.

Prepared but not yet executed on a device: the 100,000-record Room history fixture, hidden-message/unread fixture, appearance editor/storage fixtures, chat/composer interactions and settings navigation fixtures. Actual two-phone messaging, calls/media, TalkBack and frame/memory measurements remain final validation requirements. A successful local build is not equivalent to that evidence.

### Integration correction and retry boundary

The first consolidated run assembled both debug APKs and generated 533 passing unit reports, but failed lint on a Flow operator created during composition in MainActivity. That operator was moved inside `remember`; this run is not counted as a green validation. Evidence: `integration-validation.log`.

The final source pass adds reactive reduced-motion call visuals, a wrapping contextual reaction tray with an expanded accessible emoji chooser, deleted-message placeholders for group quotes, lifecycle gating of UI-triggered read actions, surfaced observer/read errors and regression fixtures for metadata equality, timeline cancellation/resume and overlapping navigation.

The existing agent's `triggerImmediateRetry` intentionally skips FAILED/EXPIRED ordered deliveries. The redesigned direct-chat control therefore offers Retry delivery for WAITING_FOR_PEER/RETRY_WAIT and opens details for terminal failures. It does not reset terminal state, manufacture a new send or promise a retry that the service cannot execute. An explicit terminal-delivery repair API remains outside this UI-only redesign; the brief's terminal-failure Tap to retry target is not fully satisfied by the current backend contract.

### Final device pass (only after local validation)

- Install with the existing signing certificate using replacement, keeping chats/identity/settings intact; no uninstall or data erasure.
- Run the prepared isolated UI, appearance-storage and 100,000-record Room tests. Confirm saved settings/wallpaper after process restart and switching theme/font/accent.
- Check small-screen and large-font layouts, TalkBack focus/labels/actions, keyboard/insets, reduced motion and battery saver; measure long-chat scrolling, memory and background sensor/listener cleanup.
- On both phones, confirm fresh bidirectional text and receipts, replies, reactions, edit/delete, image/file/voice-note delivery, audio/video calls and restart recovery using actual existing contacts.
- Record what passed and any device or transport limitations; do not label the app launch-ready from APK generation alone.

## Final local validation and artifacts

The final sources passed `testDebugUnitTest` with 538 tests, zero failures, zero errors and zero skips (`final-unit-validation.log`, Gradle exit 0). The preceding combined run failed the existing media reconciliation fixture because receiver QUEUED status was sampled before the last sender replay. The fixture now waits for the authenticated replay to drain and also asserts the resume/write budget; its quiet-period, unrelated-peer and verified-recovery assertions remain. Production media service code was not changed.

`assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease` and `lintDebug` completed with Gradle exit 0 (`final-build-lint-validation.log`). Release fatal lint also passed. Debug lint reports zero errors and 40 warnings; this is not a claim of a warning-free repository. This packaging run used two workers and a command-local 4 GB Gradle heap, without changing checked-in build settings. Protected-source verification passes for all 113 baseline backend files; `git diff --check` passes. Schema 20 and its migration chain remain unchanged.

The release artifact is `android/build/ui-redesign/torx-one-ui-redesign-release.apk`. Signature verification passed v2/v3 with the existing certificate SHA-256 `ee9d73b0c70e66855a6cbf73dda8c5b0d5e26e9015f29d55a1e96875b5fe607a`. Debug and instrumentation artifacts are prepared with the same certificate for the final data-preserving device pass. Source/artifact checksums are recorded in `android/build/ui-redesign/final-local-evidence.json`; the source overlay archive is `android/build/ui-redesign/final-source.zip`.

No post-redesign phone installation, full accessibility/performance measurement or fresh two-device delivery/call/media check has been performed during this final integration turn. The physical pass remains required. Nothing has been committed or pushed.

## Realme-only final pass, 2026-10-05

The user restricted this physical pass to the Realme RMX5070. See [the Realme evidence and remaining checks](UI_UX_REDESIGN_REALME_VALIDATION_2026-10-05.md).

The first 55-case instrumentation batch passed 53 cases; a gallery assertion was stale, and latest-message navigation failed. After correcting the gallery label and allowing five seconds for the scroll, the second batch passed 54/55. The remaining failure confirmed an actual overlap: the floating latest-message button was behind Scaffold's composer. Its bottom padding now includes the composer inset. The 100,000-record reader, hidden/unread filtering, reaction, attachment, editor and categorized settings fixtures passed in these batches. Appearance fixture storage was isolated before execution so test cleanup cannot act on production wallpaper files.

The corrected source again passed all 538 unit tests (`realme-scroll-fix-unit.log`), debug/test/release APK builds and lint (`realme-scroll-fix-build.log`). Signed release/debug/test artifacts were regenerated with the existing certificate. The Realme disconnected before installation/retesting of this final correction; only a Vivo was then visible, and no Vivo changes were made. The Realme still has the previous signed debug build, retains its app data, and needs the corrected regression run, final release replacement, restart/error checks and restoration of its temporary keep-awake setting to 0. Actual persisted appearance edits were not performed.

### Final Realme result (2026-10-05)

The corrected final fixture batch passed all 55 cases on the Realme, including the composer-overlap regression. The signed release was installed without clearing app data and cold-restarted successfully; bounded startup logs showed no crash/ANR markers. Temporary keep-awake was restored to 0 and owned audit files removed. Full evidence and remaining accessibility/performance/two-phone limits are in UI_UX_REDESIGN_REALME_VALIDATION_2026-10-05.md. Local validation remains 538 passing unit tests and successful builds/lint (40 lint warnings remain).

### Store update versionCode 3 (2026-10-05)

The user confirmed the store already has versionCode 2. `android/app/build.gradle.kts` now sets versionCode 3; versionName remains 0.1.0. The release rebuild and fatal release lint passed (`version-code-3-release-build.log`, BUILD SUCCESSFUL). `release-artifacts/release.apk` was signed with the existing certificate and verified with v2/v3 signatures; aapt confirms applicationId com.torxone.app and versionCode 3. SHA-256: 3199bb9fbeb4011b54bfc43718eb2ab2cad2c7205c397793fd0ad1af68fca1df. The earlier intermediate versionCode 2 build was not distributed. The prior 538 unit and 55 device tests cover the same app logic before this version metadata change; this versionCode 3 APK has not been installed/retested on a device. Include android/app/build.gradle.kts in the release commit. The signed release APK is an external release artifact, not a Git source file.
