# TorX One Brand System

## Mark

The mark shows four independent routes converging on a protected core. It contains no letters, avoids the Tor Project onion, and remains recognizable in monochrome and at notification size. Keep clear space equal to one quarter of the mark width. Do not rotate, recolor individual route arms, add text inside it, or place it on low-contrast imagery.

## Palette

| Token | Hex | Use |
|---|---:|---|
| Obsidian | `#07140F` | Dark background |
| Signal | `#35E58D` | Primary action and healthy state |
| Violet | `#B8A7FF` | Private identity and secondary emphasis |
| Mist | `#E2F1E7` | Dark-theme foreground |
| Warning | `#FFD166` | Degraded state |
| Danger | `#FFB4AB` | Destructive and error state |

Signal green is reserved for actionable or healthy states. Color never carries status alone; pair it with text or an icon.

## Type, spacing, shape and motion

- Use the Android system sans family for script coverage and predictable rendering.
- Base spacing unit is 4 dp. Standard content spacing is 16 dp and section spacing is 24–32 dp.
- Controls use 12–16 dp corners; cards use 16–24 dp; feature surfaces may use 32 dp.
- Motion durations are 120 ms, 220 ms and 360 ms. Respect the platform animator scale and reduced-motion settings.
- Interactive targets must be at least 48 dp, support font scaling, expose meaningful accessibility labels, and preserve WCAG AA text contrast.

## Assets

- `torx-mark.svg`: website and general use
- `torx-mark-monochrome.svg`: one-color contexts
- `github-social-preview.svg`: GitHub social preview
- Android adaptive, monochrome, notification, splash and QR assets live under `android/app/src/main/res`.
