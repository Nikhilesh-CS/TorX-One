# Phase 16 — Product Design and Branding

## Completed system

TorX One now uses a text-free routing/privacy mark across the launcher, Android adaptive icon, themed Android monochrome icon, notification icon, splash screen, onboarding, QR invitations, website asset, and GitHub preview. The symbol uses four routes around a protected center and does not copy the Tor Project onion identity.

The Compose theme now has stable light and dark palettes, typography, a 4 dp spacing scale, corner tokens, motion durations, semantic success/warning/error colors, and accessible contrast. Dynamic wallpaper colors are opt-in so the TorX identity remains consistent across devices.

Existing Material 3 buttons, forms, chat bubbles, group screens, call screens, indicators, and error surfaces inherit these finalized tokens. Notification surfaces no longer use generic Android system icons. QR codes use high error correction and a bounded central TorX mark.

## Acceptance checks

- Verify adaptive and monochrome launcher icons on Android 8, 12 and 13+ launchers.
- Verify notification visibility in light and dark status bars.
- Scan branded invitations on low-end and current devices at several brightness levels.
- Exercise font scale at 100%, 130% and 200%, TalkBack navigation, landscape, and reduced animation settings.
- Keep the SVG sources and Android vectors visually aligned when the mark changes.
