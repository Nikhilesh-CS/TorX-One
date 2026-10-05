package com.torxone.app.updates

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.updateDataStore by preferencesDataStore(name = "torx_updates")

data class UpdateSnapshot(
    val automatic: Boolean = true, val lastAttempt: Long = 0, val lastCheck: Long = 0,
    val retryAt: Long = 0, val latest: UpdateRelease? = null,
    val notified: Long = 0, val dismissed: Long = 0, val remindAt: Long = 0,
    val pending: UpdateRelease? = null, val downloadId: String? = null
)

interface UpdatePersistence {
    suspend fun snapshot(): UpdateSnapshot
    suspend fun attempt(now: Long)
    suspend fun success(now: Long, release: UpdateRelease)
    suspend fun rateLimit(until: Long)
}

class UpdateStore(context: Context, injectedStore: DataStore<Preferences>? = null) : UpdatePersistence {
    private val store = injectedStore ?: context.applicationContext.updateDataStore
    private object Keys {
        val auto = booleanPreferencesKey("automatic")
        val attempt = longPreferencesKey("last_attempt")
        val checked = longPreferencesKey("last_success")
        val retry = longPreferencesKey("retry_at")
        val latest = stringPreferencesKey("latest")
        val notified = longPreferencesKey("last_notified_version")
        val dismissed = longPreferencesKey("dismissed_version")
        val remind = longPreferencesKey("remind_at")
        val pending = stringPreferencesKey("download_release")
        val downloadId = stringPreferencesKey("download_id")
    }
    private fun release(raw: String?): UpdateRelease? = raw?.let {
        if (it.length > 16_384) null else runCatching { UpdatePolicy.decodeRelease(it) }.getOrNull()
    }
    val snapshots: Flow<UpdateSnapshot> = store.data.map { p -> UpdateSnapshot(
        p[Keys.auto] ?: true, p[Keys.attempt] ?: 0, p[Keys.checked] ?: 0, p[Keys.retry] ?: 0,
        release(p[Keys.latest]), p[Keys.notified] ?: 0, p[Keys.dismissed] ?: 0,
        p[Keys.remind] ?: 0, release(p[Keys.pending]), p[Keys.downloadId]) }
    override suspend fun snapshot() = snapshots.first()
    override suspend fun attempt(now: Long) { store.edit { it[Keys.attempt] = now } }
    override suspend fun success(now: Long, release: UpdateRelease) { store.edit {
        it[Keys.checked] = now; it[Keys.retry] = 0
        it[Keys.latest] = UpdatePolicy.json.encodeToString(UpdatePolicy.validate(release))
    } }
    override suspend fun rateLimit(until: Long) { store.edit { it[Keys.retry] = until } }
    suspend fun automatic(value: Boolean) { store.edit { it[Keys.auto] = value } }
    suspend fun pending(release: UpdateRelease?, id: String? = null) { store.edit {
        if (release == null) { it.remove(Keys.pending); it.remove(Keys.downloadId) }
        else {
            it[Keys.pending] = UpdatePolicy.json.encodeToString(UpdatePolicy.validate(release))
            if (id == null) it.remove(Keys.downloadId) else it[Keys.downloadId] = id
        }
    } }
    suspend fun notified(code: Long) { store.edit { it[Keys.notified] = code } }
    suspend fun dismiss(code: Long) { store.edit {
        it[Keys.dismissed] = code; it[Keys.remind] = System.currentTimeMillis() + UpdatePolicy.CHECK_INTERVAL
    } }
}
