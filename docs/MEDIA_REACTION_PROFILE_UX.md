# Media, reaction and profile UX addendum

Implemented in source on 2026-10-05. Initial work followed the source-only instruction. Subsequently authorized build/test and signed release results are in [BUILD_RELEASE_2026-10-05.md](BUILD_RELEASE_2026-10-05.md), which supersedes the initial validation status below. Phone tests were excluded by the user.

## Reactions

Both message reaction menus keep the six quick actions: 👍 ❤️ 😂 😮 😢 🙏. The adjacent plus action opens `ui/components/EmojiReactionPicker.kt`; the old twelve-item dialog is removed. The quick actions never load the catalogue or wait for history writes.

The picker uses the complete bundled Unicode emoji-test 18.0 snapshot: 3,972 fully-qualified/component entries, including variation selectors, flags, skin tones and joined sequences. The data and Unicode license are under `app/src/main/assets/emoji/`. Source: https://unicode.org/Public/emoji/latest/emoji-test.txt; license: https://www.unicode.org/license.txt. This is a pinned local asset, not a runtime network dependency.

Categories, English name/subgroup search, skin-tone filters, recent and frequent selections are available. Platform `TextPaint.hasGlyph` excludes unsupported glyphs. The device keyboard also accepts a single supported emoji grapheme, preserving its Unicode sequence and the existing protocol's 32 UTF-16-unit limit. Emoji rendering ultimately depends on the device font/Unicode support.

Selection closes the picker before dispatching, performs subtle haptic feedback, and records small bounded local usage history asynchronously. Reaction strings still travel through the existing reaction service and secure envelopes. There is no independent optimistic reaction map: the existing Room observation owns displayed counts and selected state.

`chat/ChatViewModel.kt` and `groups/GroupChatViewModel.kt` serialize toggles and read authoritative Room state inside the serialized action. This prevents quick repeated taps from choosing ADD twice from a stale UI snapshot. Direct chat also checks whether the service committed the requested local change, exposing a recoverable error when the existing service returns without committing. Group rejection and thrown errors are surfaced in the existing chat error UI.

Existing delivery guarantees remain unchanged. In particular, the group service's pre-existing per-peer fan-out error handling is not redesigned here; local reaction persistence is not proof of delivery to every participant.

## Inline image previews

The old static-image `ImageBubbleView` ignored `localPath`, although the fullscreen media viewer already used it. It also imposed a fixed 180 dp height.

`ui/components/LocalImagePreview.kt` now supplies the inline priority:

1. Decode the stored thumbnail, if usable.
2. Otherwise sample the local original, including when a stored thumbnail is corrupt.
3. Display the existing placeholder only when neither source decodes.

All bubble decoding runs on `Dispatchers.IO`. Two-pass bounds decoding caps inline originals at approximately 1,024 pixels on their longest side, applies EXIF orientation, and avoids a full-resolution allocation. Lazy DAO thumbnail observation remains in place so descriptors can render before the full download.

The send viewmodels generate JPEG previews off the main thread, with a maximum 320-pixel dimension and 16 KB compressed size. Compression quality and dimensions reduce when needed. The size leaves room for Base64 expansion and existing descriptor/group headers inside the secure payload limit. Originals are neither rewritten nor duplicated in a second media transfer.

Data flow uses the existing service unchanged:

`selected original bytes → bounded preview → MediaService saves original and persists thumbnailData → existing descriptor.thumbnailBase64 → receiver persists thumbnailData → inline preview → existing full-file download/decryption policy → existing MediaViewer on tap`.

Existing stored images without thumbnails also gain previews from their available local files. Room receives only compressed thumbnail bytes, not raw bitmaps. Bubble height follows image aspect ratio within 80–360 dp; `ContentScale.Fit` preserves the full picture. Animated GIF lifecycle handling is retained, with fit scaling rather than cropping.

## Profile photos

`ui/components/ProfilePhotoViewer.kt` is a reusable dark full-screen dialog with the contact/group name, back/close, aspect-preserving rendering, bounded pinch zoom/pan and reset. It has no editing controls.

`ProfileAvatar` remains independently sampled to a 256-pixel bound. Viewing resolves and reads the underlying local profile/group avatar file separately with a 2,048-pixel safety bound; it does not enlarge the list bitmap. Current synchronized contact avatars are already capped at 512 pixels by existing profile storage, so the viewer cannot invent a higher-resolution original.

Wired interactions:

- Chat avatar opens its actual photo; contact name/header retains Contact Info navigation.
- Contact Info's large avatar opens the same viewer.
- Conversation-list avatar opens the photo; the remainder of the row opens the conversation.
- Group chat/list/info use the same photo viewer. Existing group photo management remains available through its edit action.

Only successfully decoded actual photos receive a preview click handler. Missing/invalid photos retain initials without opening a fullscreen initials placeholder. If an underlying file disappears while opening, the viewer dismisses. Profile synchronization, identity, encryption and secure message transport are unchanged.

## Verification and limits

Executed without an Android build:

- `python android/tools/test_emoji_catalogue.py`: 3 tests passed, covering complete repertoire, existing protocol length, categories, quick reactions, complex sequences, tones and bundled license.
- `python android/build/ui-redesign/verify_boundary.py`: all 113 protected backend files remain byte-identical to the existing baseline.
- `python android/tools/check_logging_privacy.py`: passed.
- `git diff --check`: passed; only existing line-ending conversion warnings were emitted.

Added coverage (Android tests compiled but not executed; see the subsequent release report):

- `EmojiCatalogueTest`: 4 JVM tests for sequence preservation, composed filters, duplicate grid keys and older-platform keyboard equivalence. All four passed in the subsequently authorized full suite.
- `MediaPreviewDeviceTest`: 5 device tests for compression bounds/original preservation, thumbnail priority, corrupt/missing thumbnail fallback, separate higher-resolution avatar loading, and unavailable-source placeholders.
- `ProfilePhotoInteractionTest`: 2 Compose tests for independent avatar/parent navigation and no-photo behavior.
- `EmojiPickerInteractionTest`: 2 Compose tests for searchable catalogue selection, dismissal order and keyboard skin-tone sequences.

The catalogue check is also added to the existing Security CI workflow. No workflow was dispatched.

Android compilation, the complete JVM suite and debug/release lint subsequently passed. Device interaction checks, large-text/narrow-screen checks and real two-device media/reaction synchronization were excluded from this run. Old device results must not be treated as validation of this addendum. The newly signed `release-artifacts/release.apk` contains this addendum and the GitHub updater, with versionCode 4 and SHA-256 `dcdf516dfd1361ddf28830b670f5c5450666e3b83ae0448d236ab711a5ef33c0`. The prior APK is retained separately as `release-version3.apk`.
