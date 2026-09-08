package com.torxone.app.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────── TorX Professional Light Palette ────────────────
// Neutral surfaces and structure (Linear / Modern SaaS direction)
val AppBackground = Color(0xFFF8FAFC)       // Very light gray background
val SurfaceCard = Color(0xFFFFFFFF)         // White main surface/cards
val SurfaceSecondary = Color(0xFFF1F5F9)    // Soft slate secondary surface
val BorderColor = Color(0xFFE2E8F0)         // Light slate borders
val OutlineColor = BorderColor

// Typography
val PrimaryText = Color(0xFF0F172A)         // Dark slate primary text
val SecondaryText = Color(0xFF475569)       // Slate secondary text
val TextMuted = Color(0xFF64748B)           // Gray muted text

// TorX Brand Blue (Reserved for actions, buttons, and active states)
val TorXPrimary = Color(0xFF2563EB)         // Professional blue
val TorXPrimaryDark = Color(0xFF1D4ED8)     // Primary pressed/hover blue
val TorXPrimarySoft = Color(0xFFEFF6FF)     // Pale blue soft background

// Semantic Colors
val SuccessGreen = Color(0xFF16A34A)        // Green
val WarningAmber = Color(0xFFD97706)        // Amber
val ErrorRed = Color(0xFFDC2626)            // Red
val InfoBlue = TorXPrimary

// Transport Accents (Used ONLY where transport status is indicated)
val BluetoothAccent = Color(0xFF0284C7)     // 🔵 Bluetooth
val BluetoothGlow = Color(0x1F0284C7)

val WiFiAccent = Color(0xFF059669)          // 🟢 Wi-Fi Direct
val WiFiGlow = Color(0x1F059669)

val TorAccent = Color(0xFF7C3AED)           // 🟣 Tor Onion Services
val TorGlow = Color(0x1F7C3AED)

val DisconnectedAccent = Color(0xFF64748B)  // ⚪ Disconnected
val DisconnectedGlow = Color(0x1F64748B)

// Compatibility Aliases for existing components
val DeepBlack = PrimaryText
val DeepSpace = AppBackground
val AmoledBlack = AppBackground
val SurfaceDark = SurfaceCard
val SurfaceDarker = SurfaceSecondary
val TextPrimary = PrimaryText
val TextSecondary = SecondaryText
val CardSurface = SurfaceCard
val DarkSurface = SurfaceSecondary
val SoftWhite = PrimaryText
val MutedGray = SecondaryText
val DimGray = TextMuted
val AccentCyan = BluetoothAccent
val AccentViolet = TorAccent
val AccentPink = Color(0xFFE11D48)
val NeonGreen = WiFiAccent
val AccentBlue = TorXPrimary
val WarningYellow = WarningAmber
