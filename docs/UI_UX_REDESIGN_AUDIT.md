# TorX One UI redesign: baseline, audit and implementation contract

Recorded 2026-10-04 before application edits.

`KNOWN_GOOD_UI_BASELINE = 498157ccd0d18dfd23bb98d88c32969bad33edbd`

The user reports that messaging works on both physical phones and is happy with that behavior. Treat it as the protected functional baseline, not a new claim that every release gate has passed. Tracked application sources were clean at audit start. Four earlier untracked audit reports are preserved unchanged.

Git metadata is read-only in this environment. Creating `codex/ui-ux-redesign` failed with permission denied. The user explicitly directed local work without requiring a repository/branch. Work therefore stays in the local checkout; the committed baseline is archived at `android/build/ui-redesign/baseline/known-good-source.zip`. This archive excludes ignored keystores, passwords, SDKs, phone captures and build outputs. Phase results and actual validation exits will be recorded separately; no commit, push, release signing or device-data erasure is part of this redesign.

## Current UI map

All paths below are relative to `android/app/src/main/java/com/torxone/app` unless identified as resources.

| Surface | Current owner and connections | Existing behavior to preserve |
|---|---|---|
| App shell/navigation | `MainActivity.kt`, custom `Screen` stack and BackHandler | Intent launches, onboarding resolution, app lock, visible foreground-service recovery, call navigation, all callbacks |
| Theme/chrome | `ui/theme/Theme.kt`; shell reads settings | System/light/dark mode, Material shapes and typography, semantic status colors |
| Home/chats | `ui/screens/ConversationListScreen.kt`, `conversations/ConversationListViewModel.kt` | Search, QR pairing, settings, new group, archive/pin/mute/delete, persisted draft previews and delivery status |
| Conversation | `ui/screens/ChatScreen.kt`, `chat/ChatViewModel.kt` | Keyed messages, text, replies, edits, reactions, deletion, search focus, selected-message actions, media, voice, typing/presence, calls, starred/scheduled/disappearing messages |
| Composer/media entry | Private UI components and picker callbacks in `ChatScreen.kt` | Real existing send callbacks, attachment MIME handling, keyboard/IME behavior, recorder amplitude and duration |
| Per-chat appearance | `ui/components/ConversationAppearance.kt`, `chat/AppearanceOptions.kt`, `ConversationAppearanceEntity` | Local Room-backed theme/wallpaper/bubble preferences; current chip dialog applies immediately |
| Settings | `ui/screens/SettingsScreen.kt`, `profile/SettingsViewModel.kt`, `profile/AppSettingsRepository.kt` | All privacy/security/notification/connection/data toggles, actual callback wiring and persistence |
| Profile | `ui/screens/ProfileScreen.kt`, `ProfileAvatar.kt`, `AvatarEditor.kt` | Name/about/photo editing, real crop/zoom/reposition and EXIF-aware bounded decode, full identity copying, QR entry |
| Contact | `ui/screens/ContactInfoScreen.kt` | Alias, media, mute, safety verification, connection detail, existing reset/delete callbacks |
| Group | `GroupInfoScreen.kt`, `NewGroupScreen.kt`, `GroupMessageInfoDialog.kt` | Membership/admin actions, group title/photo, creation feedback and per-recipient delivery |
| Call | `ui/screens/CallScreen.kt` | Actual call-state transitions, incoming answer/decline, minimize, mute/speaker/video/camera/end; preserve signaling and WebRTC ownership |
| Onboarding | `ui/screens/LandingScreen.kt` | Existing profile creation and permission explanations; no artificial startup delay |
| Shared media | `SharedMediaScreen.kt`, `MediaActions.kt`, `MediaTransferControls.kt` | Gallery/links/files filters, player, download/pause/resume/open/save; real sampled off-thread viewer |
| Scheduled/archive | `ScheduledMessagesScreen.kt`, `ArchivedConversationsScreen.kt` | Queue/cancel scheduling and restore/archive navigation; queued is not delivered |
| Search/saved/forward | `ui/screens/ProductivityScreens.kt` | Conversation/global query, open focused result, star/unstar and supported forward operation |
| Diagnostics | `ConnectionDashboardDialog.kt`, `MessageInfoHost.kt`, `MessageInfoDialog.kt`, `DeliveryInspectorDialog.kt`, `ui/connection/*` | Authoritative statuses, safe retry/pause callbacks, detailed connection information behind an explicit action |
| Reusable resources | Avatar, QR generator/scanner, rich text, disappearing timer, launcher/splash vectors | Accessibility descriptions, URI confinement, actual consumer callbacks, safe render behavior |

## Confirmed bugs and current design debt

| Priority | Location | Verified problem | Presentation-safe remedy |
|---|---|---|---|
| High | `ui/theme/Theme.kt:76-79` | `dynamicColor` is ignored, despite the visible setting and shell argument. | Resolve actual Android dynamic schemes on supported OS versions; explicit TorX default and honest fallback. |
| High | `AppSettingsRepository.kt:271,283`, `SettingsViewModel.kt`, shell/theme | Font-size persistence exists but its value never reaches typography or settings UI. | Reuse the key; add four intentional typography token presets and end-to-end settings/theme wiring. |
| High | `ConversationListScreen.kt:579` | Failed last-message status falls through to the clock icon. | Failed icon with a spoken label; retain authoritative delivery semantics. |
| High | `ContactInfoScreen.kt:279,290` | Mute choices pass a duration to a callback whose existing service expects an absolute timestamp. | Correct the UI callback argument to now + duration; leave service semantics unchanged. |
| Medium | `Theme.kt`, launcher/splash/onboarding vectors | Green-tinted app surfaces and old mesh symbol contradict the requested graphite/T brand. | Neutral light/dark hierarchy, curated accents, shared safe-zone T geometry. |
| Medium | `ConversationListScreen.kt:140,449,490` | Permanent technical subtitle, individual rounded chat cards, emoji pin. | Flat continuous rows, concise chrome, consistent vector status icons and draft treatment. |
| Medium | `ChatScreen.kt` | No visual grouping, calendar separators, initial unread divider or explicit latest-message affordance. | Derive immutable presentation metadata; do not merge records or alter receive/read state. |
| Medium | `ChatScreen.kt:404-480` | Picking media invokes sending immediately. | Local pending selection and preview before invoking the existing send callback. |
| Medium | `ChatScreen.kt:1483` | Outlined composer reads like a form; voice amplitude exists but is not presented. | Tonal multiline composer and actual amplitude/duration feedback with existing recorder controls. |
| Medium | `ChatScreen.kt:1209,1221,1282` | GIF and thumbnails can decode in composition; GIF lifetime is not cleanly owned. | Bounded off-thread image work and explicit release; no per-message expensive blur. |
| Medium | `Daos.kt:83`, `ChatViewModel.kt:115` | Full ASC history and all related models are loaded/mapped before lazy viewport rendering. | Add read-only windowed UI queries if needed; no write, ACK or delivery query changes. A LazyColumn alone is not 100k-message proof. |
| Medium | `SettingsScreen.kt` | One long toggle-heavy root page; font/accent/motion/global wallpaper missing. | Category root and state-preserving subpages reusing existing callbacks. |
| Medium | `ConversationAppearance.kt`, appearance entity | Only SYSTEM/OCEAN/FOREST and NONE/WARM/COOL blends; no real photo, live preview, global inheritance controls or depth. | Versioned local appearance model and preview/apply boundary; preserve legacy values. |
| Medium | Chat list search / supporting empty surfaces | Some filtered empty states provide no explicit explanation or recovery. | Concise empty state using the current search/navigation callbacks. |
| Medium | Contact/group/profile screens | Technical identifiers and destructive actions compete with everyday controls. | Compact summaries, expandable identity/security detail and separated danger actions. |

Persistent drafts already exist: `ChatViewModel` restores and saves text/reply state, Room owns `conversation_drafts`, and the chat-list ViewModel overlays `Draft:` previews. Do not create a second draft store or send drafts through the outbox. Existing follow-latest logic and no-yank/search-focus UI tests also exist; extend them rather than replacing them wholesale.

## Three visual directions and selection

| Concept | Chrome, surfaces and interaction | Readability / identity | Performance / accessibility / maintenance |
|---|---|---|---|
| A: Graphite Minimal | Off-white/silver T, graphite hierarchy, neutral incoming bubbles, restrained sage selection; warm neutral light counterpart | Strong everyday readability, enterprise character, calm home/settings | Lowest rendering cost; consistent contrast; simple semantic Material roles |
| B: TorX Depth | Same neutral chrome, optional layered packet/node pattern and gentle background-only parallax | Most distinctive chat personality; overlay and opaque bubbles preserve text readability | Requires lifecycle/battery/reduced-motion ownership; optional rather than default chrome |
| C: Material TorX | TorX geometry and layouts with explicit Android wallpaper-derived color roles | Familiar Android integration and good light/dark support; weaker fixed brand palette | OS-gated schemes and brand fallback; no custom rendering overhead |

Selected base: **A, Graphite Minimal**. **B** becomes an optional chat preset. **C** is the explicit system-color source. Accent customization affects selected controls, links, receipts and softly tinted outgoing bubbles, not every neutral background.

## Proposed design system and component changes

- Semantic neutral light/dark ColorSchemes with complete surface-container roles; curated contrast-checked accent pairs for Sage, Emerald, Teal, Ocean/Blue, Indigo, Violet, Rose, Crimson, Amber and Graphite.
- Typed theme source and typography preferences, unknown-value fallback and explicit Small/Default/Large/Extra Large presets with all Material text roles. Android system font scaling remains active.
- Shared spacing, shape and motion tokens adopted by actual consumers. Respect platform animation scale and haptics; never animate each keystroke.
- Product-specific reusable section/category rows, compact unread badges, empty states, theme swatches and preview; keep ordinary Material components where sufficient.
- Meaningful chat components: background, message presentation/group metadata, composer, contextual reactions, calendar/unread separators and latest-message button. Preserve current callback signatures where possible.
- Unified vector T for adaptive foreground, monochrome, legacy fallback, splash and onboarding. No embedded pre-rounded adaptive tile; keep geometry inside the safe zone.

## Database changes and migration plan

Current Room schema is **20**. Phase A changes only local DataStore appearance preferences and requires no Room migration. Preserve SYSTEM/LIGHT/DARK and legacy SMALL/MEDIUM/LARGE keys; explicit stored legacy dynamic-color choices remain understood, while new installs use TorX Brand.

For richer per-chat appearance, add only local presentation fields needed by actual screens: preset/accent overrides, private photo reference, dim/blur and motion options. SYSTEM remains inheritance, so global values need not be duplicated. Keep old OCEAN/FOREST and WARM/COOL mappings valid. If Room columns change, use an additive 20→21 migration and exported schema with preservation fixtures covering appearances plus contacts/messages/outbox/sessions/relationships. Never destructive migration. A versioned local preference payload is an alternative if it avoids adding unused columns; select it before implementation and record the actual choice.

Photo wallpapers are app-private local copies or durably owned local references, loaded with bounded decoding and safe canonical confinement. Picker grants alone are insufficient. Wallpaper bytes/paths must have no consumers in transport, profile synchronization, bootstrap or protocol payloads. Replacement/reset must clean only wallpaper-owned assets, never avatars or message media.

## Screen-by-screen implementation order

| Phase | Scope | Must preserve | Completion gate |
|---|---|---|---|
| A | Foundation, theme/source/font/accent wiring, icons and splash | Existing settings keys and app startup | Debug assembly, unit tests, lint, focused theme fixtures |
| B | Home rows, draft/status/search/empty polish | Existing list actions and QR callback | List tests, light/dark/large-text checks |
| C | Chat grouping, dates, unread context, responsive bubbles, latest affordance | Message keys, order, read semantics and current follow logic | Calendar/group/window tests and no-yank/focus regressions |
| D | Composer, replies, contextual reactions, recorder feedback | Existing send/edit/reply/record callbacks | Composer/reply/reaction/accessibility fixtures |
| E | Appearance live preview, global/per-chat inheritance and private photo flow | Legacy appearances and all persisted messaging data | Preference/migration/confinement/apply-cancel tests |
| F | Optional Depth background and motion modes | Stable foreground messages; no permanent sensors | Lifecycle/disposal/reduced-motion/battery tests |
| G | Settings categories/subpages | Every existing callback and persistent choice | Navigation/theme/privacy/security setting tests |
| H | Profile/contact/group polish | Full identity copying, verified security and member actions | Callback/mute/error/large-font fixtures |
| I | Calls, media preview/player and onboarding polish | Actual signaling/media architecture and startup timing | Existing call/media/UI regressions; preview-before-send tests |
| J | Accessibility, performance and functional regression | Protected known-good backend | Actual measured matrix and two-device regressions; list unexecuted checks explicitly |

Each phase has a separate validation record and source snapshot. Do not describe an assembled APK as device or performance proof. Physical installation is data-preserving and must keep the existing signer; never erase devices to facilitate a UI test. No claims of complete redesign until all implemented requirements and remaining gates are documented accurately.

## Accessibility plan

Minimum 48dp product touch targets; spoken action/status labels; logical TalkBack order; semantic disabled/progress/error states; keyboard/gesture alternatives. Reply remains available in actions, and read status has a textual/spoken difference in addition to accent. Large text must wrap rather than hide critical controls. Check light/dark at system font scales 1.0/1.3/1.5+, enlarged display, landscape, small phone and 600dp+ layouts. Sensitive details remain behind explicit disclosure. Motion and haptics respect OS settings.

## Performance risks and measurement plan

Bound image decoding, remember immutable presentation metadata, preserve keyed lazy items, and keep sensor updates inside background drawing rather than whole-screen state. Full/Standard/Reduced depth modes must fall back for battery saver and reduced animations; register sensors only for an active visible Depth surface. Do not fake audio amplitude, blur per message, or promise a 100k-history target while loading every record. Validate 1k/10k/100k synthetic datasets, theme switching, media-heavy scrolling and foreground/background lifetimes; record timing/frame/memory measurements, not subjective smoothness claims.

## Protected architecture

No redesign edits to Tor transport, TransportRouter, TorXAgent, ratchet, outbox sequencing, ACK ownership, authenticated endpoint/relationship routing, pairing/bootstrap, IncomingDispatcher, call signaling, or media wire protocols. Any needed UI state must use additive ViewModel/repository presentation APIs. Delivery icons remain derived from authoritative states; clicking retry delegates to existing retry ownership. Closing an error never removes pending ciphertext.

## Execution status

First deliverable: complete source audit and design/phase contract recorded. Application implementation has not started at this point. Subsequent phase evidence will be added in `UI_UX_REDESIGN_RESULTS.md`.
