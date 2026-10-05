package com.torxone.app.ui.theme

/** Local presentation preferences; these IDs are never part of a chat protocol. */
enum class TorXThemeSource {
    TORX, SYSTEM;

    companion object {
        fun resolve(stored: String?, legacyDynamic: Boolean? = null): TorXThemeSource =
            entries.firstOrNull { it.name == stored }
                ?: if (stored == null && legacyDynamic == true) SYSTEM else TORX
    }
}

enum class TorXTypographyScale {
    Compact, Default, Large, Accessibility;

    companion object {
        fun resolve(stored: String?): TorXTypographyScale = when (stored) {
            "SMALL" -> Compact
            "LARGE" -> Large
            "EXTRA_LARGE" -> Accessibility
            else -> Default
        }
        fun storageValue(stored: String?): String = when (resolve(stored)) {
            Compact -> "SMALL"
            Default -> "MEDIUM"
            Large -> "LARGE"
            Accessibility -> "EXTRA_LARGE"
        }
    }
}

enum class TorXAccent(val label: String, val lightArgb: Long, val darkArgb: Long) {
    SAGE("TorX Sage", 0xFF416653, 0xFFA9CBB6),
    EMERALD("Emerald", 0xFF176649, 0xFF92D5B4),
    TEAL("Teal", 0xFF006A68, 0xFF8DD3CE),
    OCEAN("Ocean", 0xFF25627E, 0xFFA0CEE6),
    BLUE("Blue", 0xFF345DA8, 0xFFB3C7FF),
    INDIGO("Indigo", 0xFF57549A, 0xFFC7C2FF),
    VIOLET("Violet", 0xFF79518F, 0xFFDFB8F3),
    ROSE("Rose", 0xFF934E6C, 0xFFF1B6D0),
    CRIMSON("Crimson", 0xFF9C454C, 0xFFFFB8BD),
    AMBER("Amber", 0xFF795A13, 0xFFEAC77D),
    GRAPHITE("Graphite", 0xFF535E68, 0xFFBDC7D0);

    companion object {
        fun resolve(stored: String?): TorXAccent = entries.firstOrNull { it.name == stored } ?: SAGE
    }
}
