package com.torxone.app.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Discovery is independent of Android UI, message state and download ownership. */
class UpdateRepository(
    private val source: ReleaseSource,
    private val store: UpdatePersistence,
    private val installedCode: Long,
    private val androidSdk: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()

    suspend fun check(manual: Boolean): UpdateRelease? {
        // Duplicate taps/background checks don't queue a second HTTP request.
        if (!mutex.tryLock()) return null
        try {
            val saved = store.snapshot()
            val now = clock()
            if (!manual && (!saved.automatic || !UpdatePolicy.due(now, saved.lastAttempt, saved.retryAt))) {
                if (mutable.value == UpdateState.Idle) saved.latest?.let { publish(it) }
                return null
            }
            if (now < saved.retryAt) {
                if (manual) mutable.value = UpdateState.Error("Update checks are temporarily rate limited. Try again later.")
                return null
            }
            store.attempt(now)
            mutable.value = UpdateState.Checking
            val release = UpdatePolicy.validate(source.latest())
            store.success(clock(), release)
            publish(release)
            return release.takeIf { UpdatePolicy.newer(installedCode, it.versionCode) && androidSdk >= it.minimumAndroidSdk }
        } catch (e: CancellationException) {
            mutable.value = UpdateState.Idle
            throw e
        } catch (e: UpdateFailure) {
            if (e.retryAt > 0) runCatching { store.rateLimit(e.retryAt) }
            mutable.value = if (manual) UpdateState.Error(e.userMessage) else UpdateState.Idle
        } catch (_: Exception) {
            mutable.value = if (manual) UpdateState.Error("Can't check for updates. Please try again.") else UpdateState.Idle
        } finally { mutex.unlock() }
        return null
    }

    private fun publish(release: UpdateRelease) {
        mutable.value = when {
            !UpdatePolicy.newer(installedCode, release.versionCode) -> UpdateState.UpToDate
            androidSdk < release.minimumAndroidSdk -> UpdateState.Error("The latest update requires Android API ${release.minimumAndroidSdk} or newer.")
            else -> UpdateState.Available(release)
        }
    }
}
