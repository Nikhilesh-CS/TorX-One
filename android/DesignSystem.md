# TorX One Design System & UX Standards

This document serves as the living reference for the Universal UI, UX, and Micro-Interaction Standards (Production Bible Section 01). Every screen, dialog, and component in TorX One must adhere to these guidelines to ensure WhatsApp/Telegram-level polish and consistency.

## 1. Design Tokens

Never hardcode UI dimensions. All spacing, radii, sizing, and elevations must be referenced from `AstraTheme.tokens`.

### Spacing System (8dp Grid)
*   **Tiny**: 4dp
*   **Small**: 8dp (Inner element padding)
*   **Medium**: 16dp (Standard screen padding, list item padding)
*   **Large**: 24dp (Section spacing)
*   **ExtraLarge**: 32dp (Major layout spacing)
*   **Massive**: 48dp, 56dp, 64dp
*   *Forbidden*: 13dp, 19dp, 31dp, etc.

### Corner Radii
*   **Buttons**: 16dp
*   **Cards**: 20dp
*   **Dialogs**: 28dp
*   **Bottom Sheets**: 32dp
*   **Message Bubbles**: 20dp
*   **Images**: 16dp
*   **Avatars**: 50% (Circle)

### Touch Targets & Accessibility
*   **Minimum Target Size**: 48dp x 48dp.
*   **Preferred Target Size**: 56dp x 56dp.
*   *Rule*: Icons may remain visually small (24dp), but their touch wrapper (`IconButton`, `Box` with `clickable`) must expand to the minimum target size.

## 2. Typography

All typography is strictly enforced via `AstraTheme.typography` (Inter and JetBrains Mono).

*   `headlineLarge` (Screen Title): 32sp, Bold
*   `headlineMedium` (Section Title): 22sp, Bold
*   `titleLarge` (Dialog Title): 20sp, Bold
*   `titleMedium` (Chat Name): 20sp, SemiBold
*   `bodyLarge` (Normal Text): 16sp, Normal
*   `bodyMedium` (Secondary Text): 14sp, Normal
*   `labelMedium` (Timestamp): 12sp, Medium
*   `labelSmall` (Caption): 11sp, Regular

## 3. Component Usage Rules

Never use raw Material components directly (e.g., `Button`, `Card`, `Text`). Always use the `Astra` prefixed equivalent.

### AstraButton
*   **Primary**: Filled, prominent actions.
*   **Secondary**: Outlined, alternative actions.
*   **Danger**: Red background, destructive actions.
*   *States*: Must support `isLoading` (replaces label with spinner) and `enabled` (lowers opacity).

### AstraStates
*   **Never leave a blank screen.**
*   Use `AstraEmptyState` for zero-content views.
*   Use `AstraLoadingState` for full-screen loading.
*   Use `AstraErrorState` with user-friendly language. Never display raw exceptions.

## 4. Motion & Micro-interactions

All motion must use durations and curves from `AstraMotion`.

*   **Duration**: 100ms - 300ms.
*   **Curve**: Spring or FastOutSlowIn.
*   **Bounce Click**: Apply `Modifier.bounceClick()` to interactive elements for a premium scale-down effect on press.
*   **Skeleton Loading**: Apply `Modifier.skeletonLoading()` to content blocks before data arrives.

## 5. Security & Stability

*   **Edge-to-Edge**: Do not hardcode padding for status bars. Use `WindowInsets`.
*   **Recomposition**: Ensure parameters passed to components are `@Stable` or primitive types to prevent unnecessary recompositions.
*   **Crash Resilience**: ViewModels must handle process death gracefully via `SavedStateHandle`. UI should never crash due to unexpected null states.

## 6. Debugging UI

In debug builds, you can wrap components with `Modifier.auditPadding()` or `Modifier.auditTouchTargets()` to visually inspect for violations of the design system. Do not ship these modifiers in release code.
