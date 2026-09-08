# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.26] - 2026-09-09
### Added
- **Universal QR Scanner Screen (`ScanQrScreen.kt`)**: Immediate full-screen entry point launched from the home screen top-right QR button. Real-time dual detection automatically handles:
  - **TorX Contact QR**: Parses identity strings via `CryptoManager.parseContactString` with contact preview card and 1-tap "Add Contact".
  - **TorX Group QR**: Validates group invite tokens via `GroupManager.validateInviteToken` with group preview card and 1-tap "Join Group".
- **Unified Dialog System**: Standardized all modal dialogs (`AddContactDialog`, `ShareContactKeyDialog`, Listen Together, delete confirmation) into the single TorX Professional Light system with `#FFFFFF` surface cards, `#0F172A` headings, `#475569` text, `#94A3B8` placeholders, `#2563EB` actions, and `#E2E8F0` borders. Zero purple, zero dark translucent backgrounds.
- **Group Info Screen Redesign**: Restructured into identity-first hierarchy:
  - Header: Group avatar with interactive edit badge (`✎`) opening photo options modal (Choose from gallery, Take photo, Remove photo) with integrated `AvatarCropperScreen`.
  - Group Settings: Approval toggle for new members.
  - Notifications: Mute notifications toggle.
  - Group Invitation: Dedicated shortcut to Universal QR scanner and invite QR generator.
  - Group Details: Group name, description, and save button.
  - Members: Clean member cards with avatar, name, role badge, and role management actions.
- **Streamlined Navigation & Settings**:
  - Bottom navigation simplified from 5 tabs to 4 clean tabs (`Messages | Contacts | Security | Settings`), removing developer-style network universe from standard navigation.
  - Removed "Test Tor Connection" diagnostic button and "Mesh Dashboard" developer link from Settings, keeping all Tor and mesh networking operating seamlessly in the background.

## [1.0.25] - 2026-09-09
### Added
- Unified TorX Professional Light System across all app screens: Strict 90% neutral, 8% brand blue, 2% semantic/transport color rule.
- Home Screen & TorX One Music redesign: Eliminated dark cyberpunk gradient, glowing circular avatars, and neon borders. Replaced with clean white cards, subtle metadata badges, and high-contrast slate typography.
- Settings Battery & Performance panel: Converted from dark translucent panel to clean white SaaS card with semantic status indicators, active component metrics, and clean optimization controls.
- Lock Screen overhaul (`LockScreen.kt`): Fixed invisible white-on-white text bug, replaced floating square with clean full-screen SaaS security card, high-contrast dark slate text, branded security badge, and refined biometric/backup password controls.
- Mesh Dashboard & Security Center overhaul: Replaced all legacy dark aurora gradients, neon stat colors, and dark canvas backgrounds with clean cards, crisp borders, and calm icon chips.
- Avatar typography: Refined avatar gradients to professional calm tones (Linear / SaaS style).

## [1.0.24] - 2026-09-09
### Added
- TorX Professional Light UI System: Complete design overhaul featuring clean SaaS aesthetics (white/slate neutrals with restrained blue brand actions).
- Strict onboarding & landing redesign (`SetupScreen.kt`): Replaced dark/aurora/neon treatment with clean #F8FAFC canvas, #FFFFFF cards, #0F172A text, and dedicated vector badging.
- Decoupled transport status colors from primary text/actions, ensuring brand consistency across all screens.
- Redesigned message bubbles, composer, bottom navigation, contacts list, network universe, settings, and biometric authentication dialogs with uniform design tokens.

## [1.0.23] - 2026-09-09
### Added
- Double Ratchet session protocol implementation (Phase 2) with forward secrecy and break-in recovery.
- Complete Phase 0 security model documentation: threat models, trust boundaries, cryptographic inventory, and key lifecycle.
- Replay protection with windowed monotonic counter validation.
- Fixed Android 14 Foreground Service start crash for incoming WebRTC calls.

## [1.0.22] - 2026-09-08
### Added
- Background call stability: Partial CPU wake lock and proximity screen-off wake lock to prevent call/audio from cutting off when the screen turns off.
- Minimized In-Call banner: Allows browsing other chats, groups, and settings while the call continues with quick mute and hang up controls.
- Outgoing ringback tone: Standard telephony supervisory ringback tone plays during outgoing calls until answered or ended.
- Store-and-forward missed-call events: Calling an offline peer queues a call event delivered when the peer reconnects.
- Interactive group avatar picker: Group creators and admins can upload and update group photos directly from Group Info.
- Dedicated owner group deletion: Cleanly dissolves group and removes local and remote records.
- 3-dot conversation menu: Quick access to Group Info, Search, Mute, Clear Chat, and Group Leave/Delete.

### Fixed
- Chat auto-scroll race condition: Sending or receiving messages now immediately and smoothly scrolls to the newest message.
- Group member and key wipe: Replaced `insertGroup(REPLACE)` with atomic update queries to eliminate SQLite foreign key cascading deletions.
- Exhaustive `Transport.PENDING` handling in call routing and messaging.

## [1.0.21] - 2026-09-08
### Added
- Complete group messaging system with creation, invites, and membership management.
- Group end-to-end encryption (Phase 3).
- Group notification hardening.
- WebRTC voice calling with WhatsApp-style InCallScreen UI.
- ICE candidate queuing, TURN fallback, and ICE restart logic.
- Dynamic IceServerProvider for STUN/TURN configuration.

### Fixed
- Group authentication and membership validation hardening.
- Group message routing and missing handler fixes.
- WebRTC lifecycle races: await setLocalDescription, guard cleanup, remove GlobalScope.
- ICE renegotiation with 30s reconnect timeout.
- Call improvements and updater fixes.

## [1.0.10] - 2026-07-08
### Added
- Premium glassmorphism UI refresh across Messages, Contacts, Settings, Security, Network, dialogs, and setup.
- TorX One Music note row with one active note per user, delete support, manual note fallback, album-art fallback, and Listen Together invite flow.
- Contact profile shared Media, Files, and Links tabs.

### Fixed
- Profile pictures now appear in chat headers, chat list, contacts, Settings, and TorX One Music when synced.
- Profile photo sync now sends optimized avatar images so other users can receive them more reliably.
- Messages screen preserves list state after returning from Settings.
- Tor route availability no longer incorrectly marks contacts as Online.
- Profile save path now updates identity name and avoids stale picker state.
- Release updater no longer requests direct APK install permission.

## [1.0.7] - 2026-07-07
### Fixed
- Published a fresh Android release build for in-app GitHub updates.
- Kept release build fixes for R8, lifecycle Compose, and OneDrive-safe Gradle output.

## [1.0.0] - 2026-07-05
### Added
- Tor embedded binaries.
- Onion V3 hidden service routing.
- Material 3 UI for chat and contacts.
- P2P message delivery logic.
