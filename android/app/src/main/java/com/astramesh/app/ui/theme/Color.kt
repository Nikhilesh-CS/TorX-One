package com.astramesh.app.ui.theme

import androidx.compose.ui.graphics.Color

// Premium Dark Palette
val DeepSpace = Color(0xFF0D0D12) // Slightly violet deep black for background
val SurfaceDark = Color(0xFF16161D) // Slightly lighter for cards
val SurfaceDarker = Color(0xFF121218)
val TextPrimary = Color(0xFFF0F0F5)
val TextSecondary = Color(0xFFA0A0B0)
val TextMuted = Color(0xFF606070)
val OutlineColor = Color(0xFF2A2A35)

// Transport-Aware Accents
// Bluetooth: Blue ambient accents
val BluetoothAccent = Color(0xFF00B0FF)
val BluetoothGlow = Color(0x3300B0FF)

// Wi-Fi: Green flowing gradients
val WiFiAccent = Color(0xFF00E676)
val WiFiGlow = Color(0x3300E676)

// Tor: Deep violet breathing motion
val TorAccent = Color(0xFFB388FF)
val TorGlow = Color(0x33B388FF)

// Default / Disconnected State
val DisconnectedAccent = Color(0xFF606070)
val DisconnectedGlow = Color(0x33606070)

// Semantic Colors
val ErrorRed = Color(0xFFFF5252)
val SuccessGreen = Color(0xFF00E676)
val WarningYellow = Color(0xFFFFD740)

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

