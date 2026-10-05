package com.torxone.app.ui.appearance

import com.torxone.app.data.entity.ConversationAppearanceEntity
import com.torxone.app.ui.theme.TorXAccent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatAppearance(
    val version: Int = 1,
    val preset: String = "GRAPHITE",
    val accentId: String? = null,
    val wallpaperType: String = "NONE",
    val wallpaperAssetId: String? = null,
    val dim: Float = 0.2f,
    val blur: Float = 0f,
    val bubbleStyle: String = "SOFT",
    val motionMode: String = "STANDARD",
    val parallaxStrength: Float = 0.3f,
    val photoZoom: Float = 1f,
    val photoPanX: Float = 0f,
    val photoPanY: Float = 0f
) {
    val effectiveAccentId: String? get() = accentId ?: when (preset) {
        "SAGE" -> "SAGE"; "OCEAN" -> "OCEAN"; "FOREST" -> "EMERALD"; "VIOLET" -> "VIOLET"; else -> null
    }
    fun normalized(): ChatAppearance = if (version != 1) ChatAppearance() else copy(
        preset = preset.takeIf { it in presets } ?: "GRAPHITE",
        accentId = accentId?.let { TorXAccent.resolve(it).name },
        wallpaperType = wallpaperType.takeIf { it in wallpapers } ?: "NONE",
        wallpaperAssetId = wallpaperAssetId?.takeIf { assetPattern.matches(it) },
        dim = bounded(dim, 0f, 0.8f, 0.2f), blur = bounded(blur, 0f, 20f, 0f),
        bubbleStyle = bubbleStyle.takeIf { it in bubbles } ?: "SOFT",
        motionMode = motionMode.takeIf { it in motions } ?: "STANDARD",
        parallaxStrength = bounded(parallaxStrength, 0f, 1f, 0.3f),
        photoZoom = bounded(photoZoom, 1f, 4f, 1f),
        photoPanX = bounded(photoPanX, -1f, 1f, 0f), photoPanY = bounded(photoPanY, -1f, 1f, 0f)
    )
    companion object {
        val presets = listOf("GRAPHITE", "DEPTH", "SAGE", "OCEAN", "FOREST", "VIOLET", "MINIMAL")
        val wallpapers = listOf("NONE", "WARM", "COOL", "PHOTO")
        val bubbles = listOf("SOFT", "COMPACT")
        val motions = listOf("FULL", "STANDARD", "REDUCED")
        val assetPattern = Regex("[a-f0-9]{32}")
        private fun bounded(v: Float, min: Float, max: Float, fallback: Float) = if (v.isFinite()) v.coerceIn(min, max) else fallback
        fun fromLegacy(value: ConversationAppearanceEntity?): ChatAppearance = ChatAppearance(
            preset = when (value?.theme) { "OCEAN" -> "OCEAN"; "FOREST" -> "FOREST"; else -> "GRAPHITE" },
            wallpaperType = when (value?.wallpaper) { "WARM" -> "WARM"; "COOL" -> "COOL"; else -> "NONE" },
            bubbleStyle = if (value?.bubbleStyle == "SQUARE") "COMPACT" else "SOFT"
        )
        fun legacyOverrides(value: ConversationAppearanceEntity?) = value != null &&
            (value.theme != "SYSTEM" || value.wallpaper != null && value.wallpaper != "NONE" || value.bubbleStyle != "ROUNDED")
    }
}

@Serializable
internal data class AppearanceRecord(val version: Int = 1, val inherit: Boolean = false, val config: ChatAppearance? = null)
data class ResolvedChatAppearance(val config: ChatAppearance, val inheritsDefault: Boolean)

internal object AppearanceCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun canCleanGlobal(raw: String?): Boolean = raw == null || runCatching { json.decodeFromString<ChatAppearance>(raw).version == 1 }.getOrDefault(false)
    fun encode(config: ChatAppearance) = json.encodeToString(config.normalized())
    fun decode(raw: String?): ChatAppearance = runCatching { json.decodeFromString<ChatAppearance>(raw ?: "").normalized() }.getOrDefault(ChatAppearance())
    fun encodeRecord(record: AppearanceRecord) = json.encodeToString(record)
    fun record(raw: String?): AppearanceRecord? = raw?.let { runCatching { json.decodeFromString<AppearanceRecord>(it) }.getOrNull() }
    fun resolve(raw: String?, global: ChatAppearance, legacy: ConversationAppearanceEntity?): ResolvedChatAppearance {
        if (raw != null) {
            val record = record(raw)
            return if (record?.version == 1 && !record.inherit && record.config != null) ResolvedChatAppearance(record.config.normalized(), false)
            else ResolvedChatAppearance(global, true)
        }
        return if (ChatAppearance.legacyOverrides(legacy)) ResolvedChatAppearance(ChatAppearance.fromLegacy(legacy), false)
        else ResolvedChatAppearance(global, true)
    }
}
