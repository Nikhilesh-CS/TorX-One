package com.torxone.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class EmojiCatalogueTest {
    private fun catalogue() = EmojiCatalogue.parse(sequenceOf(
        "# group: People & Body", "# subgroup: person-role",
        "1F469 1F3FD 200D 1F4BB ; fully-qualified # 👩🏽‍💻 E4.0 woman technologist: medium skin tone",
        "1F469 200D 1F4BB ; fully-qualified # 👩‍💻 E4.0 woman technologist",
        "# group: Flags", "# subgroup: country-flag",
        "1F1EE 1F1F3 ; fully-qualified # 🇮🇳 E2.0 flag: India",
        "2764 ; unqualified # ❤ E0.6 red heart",
        "2764 FE0F ; fully-qualified # ❤️ E0.6 red heart"
    ))

    @Test fun preservesJoinedToneFlagsAndVariationSelectors() {
        val entries = catalogue()
        assertEquals(listOf("👩🏽‍💻", "👩‍💻", "🇮🇳", "❤️"), entries.map { it.emoji })
        assertEquals("woman technologist: medium skin tone", entries.first().name)
        assertEquals("People & Body", entries.first().category)
        assertEquals(listOf(0x1F3FD), EmojiCatalogue.tone(entries.first()))
    }

    @Test fun searchCategoryAndSkinFiltersCompose() {
        val toned = catalogue().first()
        assertTrue(EmojiCatalogue.matches(toned, "TECHNOLOGIST", "People & Body", 0x1F3FD))
        assertFalse(EmojiCatalogue.matches(toned, "technologist", "Flags", -1))
        assertFalse(EmojiCatalogue.matches(toned, "", "All", 0))
        assertFalse(EmojiCatalogue.matches(toned, "", "All", 0x1F3FF))
        assertTrue(EmojiCatalogue.matches(catalogue()[1], "", "All", 0))
    }

    @Test fun duplicateDefinitionsDoNotDuplicateGridKeys() {
        val definition = "1F44D ; fully-qualified # 👍 E0.6 thumbs up"
        assertEquals(1, EmojiCatalogue.parse(sequenceOf(definition, definition)).size)
    }

    @Test fun olderPlatformKeyboardFallbackPreservesToneAndJoinedSequences() {
        assertTrue(EmojiCatalogue.keyboardEquivalent("❤️", "❤"))
        assertTrue(EmojiCatalogue.keyboardEquivalent("🏳️‍🌈", "🏳‍🌈"))
        assertTrue(EmojiCatalogue.keyboardEquivalent("👩🏽‍💻", "👩🏽‍💻"))
        assertFalse(EmojiCatalogue.keyboardEquivalent("👩🏽‍💻", "👩‍💻"))
        assertFalse(EmojiCatalogue.keyboardEquivalent("❤️", "A"))
    }
}
