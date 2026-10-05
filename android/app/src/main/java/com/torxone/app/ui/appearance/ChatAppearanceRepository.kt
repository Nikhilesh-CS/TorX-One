package com.torxone.app.ui.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.torxone.app.data.entity.ConversationAppearanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

private val Context.chatAppearanceDataStore by preferencesDataStore("torx_chat_appearance")

/** Local UI storage. No profile, media transport, or outbox dependencies. */
class ChatAppearanceRepository(context: Context, preferenceStore: DataStore<Preferences>? = null) {
    private val context = context.applicationContext
    private val store = preferenceStore ?: this.context.chatAppearanceDataStore
    private val writes = sharedWrites
    private val defaultKey = stringPreferencesKey("default_v1")
    val assets = WallpaperAssetStorage(this.context)
    fun observeDefault(): Flow<ChatAppearance> = store.data.map { AppearanceCodec.decode(it[defaultKey]) }
    fun observeConversation(id: String, legacyFlow: Flow<ConversationAppearanceEntity?>): Flow<ResolvedChatAppearance> =
        combine(store.data, legacyFlow) { prefs, legacy -> AppearanceCodec.resolve(prefs[key(id)], AppearanceCodec.decode(prefs[defaultKey]), legacy) }
    suspend fun setDefault(config: ChatAppearance) = writes.withLock {
        val safe = checked(config)
        store.edit { it[defaultKey] = AppearanceCodec.encode(safe) }
    }
    suspend fun setOverride(id: String, config: ChatAppearance) = writes.withLock {
        val safe = checked(config)
        store.edit { it[key(id)] = AppearanceCodec.encodeRecord(AppearanceRecord(config = safe)) }
    }
    suspend fun useDefault(id: String) = writes.withLock {
        // Explicit inheritance prevents a legacy Room override resurfacing.
        store.edit { it[key(id)] = AppearanceCodec.encodeRecord(AppearanceRecord(inherit = true)) }
    }
    suspend fun discardDraftAsset(id: String) = writes.withLock {
        if (id !in referenced(store.data.first())) withContext(Dispatchers.IO) { assets.delete(id) }
    }
    suspend fun cleanUnusedAssets() = writes.withLock {
        val prefs = store.data.first()
        if (!AppearanceCodec.canCleanGlobal(prefs[defaultKey]) || prefs.asMap().any { (key, value) ->
            key.name.startsWith("chat_") && (value !is String || AppearanceCodec.record(value)?.version != 1)
        }) return@withLock
        val refs = referenced(prefs)
        withContext(Dispatchers.IO) { assets.cleanUnused(refs) }
    }
    private fun referenced(prefs: Preferences): Set<String> = buildSet {
        AppearanceCodec.decode(prefs[defaultKey]).wallpaperAssetId?.let(::add)
        prefs.asMap().forEach { (key, value) -> if (key.name.startsWith("chat_") && value is String) {
            AppearanceCodec.record(value)?.config?.wallpaperAssetId?.let(::add)
        } }
    }
    private fun checked(config: ChatAppearance): ChatAppearance {
        val safe = config.normalized()
        if (safe.wallpaperType == "PHOTO") require(assets.resolve(safe.wallpaperAssetId) != null) { "Choose the wallpaper photo again" }
        return safe
    }
    private companion object { val sharedWrites = Mutex() }
    private fun key(id: String): Preferences.Key<String> {
        require(id.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("chat_$digest")
    }
}
