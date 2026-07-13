# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Initial GitHub project documentation.
- Automated GitHub Releases updater architecture.

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
