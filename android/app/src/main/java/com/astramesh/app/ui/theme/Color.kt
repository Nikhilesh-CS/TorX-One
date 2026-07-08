package com.astramesh.app.ui.theme

import androidx.compose.ui.graphics.Color

// Premium Dark Palette
val DeepSpace = Color(0xFF05070C) // Premium dark graphite, not pure black
val AmoledBlack = Color(0xFF000000) // Pure black for AMOLED theme
val SurfaceDark = Color(0xFF111827) // Frosted glass base
val SurfaceDarker = Color(0xFF0B1020)
val TextPrimary = Color(0xFFF6F7FF)
val TextSecondary = Color(0xFFB9C3D4)
val TextMuted = Color(0xFF7B8496)
val OutlineColor = Color(0x1FFFFFFF)

// Transport-Aware Accents
// Bluetooth: Blue ambient accents
val BluetoothAccent = Color(0xFF38BDF8)
val BluetoothGlow = Color(0x3338BDF8)

// Wi-Fi: Green flowing gradients
val WiFiAccent = Color(0xFF00E5A8)
val WiFiGlow = Color(0x3300E5A8)

// Tor: Deep violet breathing motion
val TorAccent = Color(0xFF8B5CF6)
val TorGlow = Color(0x338B5CF6)

// Default / Disconnected State
val DisconnectedAccent = Color(0xFF7B8496)
val DisconnectedGlow = Color(0x337B8496)

// Semantic Colors
val ErrorRed = Color(0xFFFF5252)
val SuccessGreen = Color(0xFF00E5A8)
val WarningYellow = Color(0xFFFFD740)
val InfoBlue = Color(0xFF38BDF8)

// Legacy Aliases for compilation compatibility
val DeepBlack = DeepSpace
val SoftWhite = TextPrimary
val MutedGray = TextSecondary
val DimGray = TextMuted
val AccentCyan = BluetoothAccent
val AccentViolet = TorAccent
val AccentPink = Color(0xFFFF4081)
val NeonGreen = WiFiAccent
val CardSurface = SurfaceDark
val DarkSurface = SurfaceDark
val AccentBlue = BluetoothAccent

