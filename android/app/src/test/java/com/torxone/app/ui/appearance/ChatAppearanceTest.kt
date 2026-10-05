package com.torxone.app.ui.appearance

import com.torxone.app.data.entity.ConversationAppearanceEntity
import org.junit.Assert.*
import org.junit.Test

class ChatAppearanceTest {
    @Test fun legacyRecordsKeepTheirVisualChoicesWithoutRoomMigration() {
        val legacy = ConversationAppearanceEntity("id", "OCEAN", "WARM", "SQUARE")
        val result = AppearanceCodec.resolve(null, ChatAppearance(preset = "DEPTH"), legacy)
        assertFalse(result.inheritsDefault)
        assertEquals("OCEAN", result.config.preset)
        assertEquals("WARM", result.config.wallpaperType)
        assertEquals("COMPACT", result.config.bubbleStyle)
        assertEquals("OCEAN", result.config.effectiveAccentId)
    }
    @Test fun explicitInheritanceSuppressesLegacyAndTracksCurrentDefault() {
        val old = ConversationAppearanceEntity("id", "FOREST", "COOL")
        val marker = AppearanceCodec.encodeRecord(AppearanceRecord(inherit = true))
        val first = AppearanceCodec.resolve(marker, ChatAppearance(preset = "DEPTH"), old)
        val second = AppearanceCodec.resolve(marker, ChatAppearance(preset = "OCEAN"), old)
        assertTrue(first.inheritsDefault)
        assertEquals("DEPTH", first.config.preset)
        assertEquals("OCEAN", second.config.preset)
    }
    @Test fun untouchedLegacyDefaultInheritsRatherThanDuplicatingGlobal() {
        val global = ChatAppearance(preset = "VIOLET")
        assertEquals(ResolvedChatAppearance(global, true), AppearanceCodec.resolve(null, global, ConversationAppearanceEntity("id")))
        assertEquals(ResolvedChatAppearance(global, true), AppearanceCodec.resolve(null, global, null))
    }
    @Test fun localPhotoAndCropConfigurationRoundTrips() {
        val original = ChatAppearance(preset = "DEPTH", wallpaperType = "PHOTO", wallpaperAssetId = "a".repeat(32),
            dim = .5f, blur = 12f, photoZoom = 2f, photoPanX = -.4f, photoPanY = .6f, motionMode = "FULL")
        assertEquals(original, AppearanceCodec.decode(AppearanceCodec.encode(original)))
        val record = AppearanceCodec.encodeRecord(AppearanceRecord(config = original))
        assertEquals(ResolvedChatAppearance(original, false), AppearanceCodec.resolve(record, ChatAppearance(), null))
    }
    @Test fun invalidAndFuturePreferencesFailSafelyWithoutThrowing() {
        assertEquals(ChatAppearance(), AppearanceCodec.decode("broken-json"))
        assertEquals(ChatAppearance(), AppearanceCodec.decode("{\"version\":2,\"preset\":\"future\"}"))
        val config = ChatAppearance(preset = "bad", wallpaperAssetId = "../profile/avatar", dim = Float.NaN, blur = Float.POSITIVE_INFINITY, photoZoom = -9f).normalized()
        assertEquals("GRAPHITE", config.preset)
        assertNull(config.wallpaperAssetId)
        assertEquals(.2f, config.dim, .001f)
        assertEquals(0f, config.blur, .001f)
        assertEquals(1f, config.photoZoom, .001f)
        assertTrue(AppearanceCodec.resolve("{\"version\":2}", ChatAppearance(), ConversationAppearanceEntity("id", "FOREST")).inheritsDefault)
    }
    @Test fun reducedMotionIsMandatoryForBatterySaverAndDisabledAnimations() {
        assertFalse(shouldReduceAppearanceMotion("STANDARD", false, 1f))
        assertTrue(shouldReduceAppearanceMotion("FULL", true, 1f))
        assertTrue(shouldReduceAppearanceMotion("FULL", false, 0f))
        assertTrue(shouldReduceAppearanceMotion("REDUCED", false, 1f))
        assertTrue(shouldReduceAppearanceMotion("FULL", false, Float.NaN))
    }
}
