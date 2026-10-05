package com.torxone.app.updates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private class Store(var value: UpdateSnapshot = UpdateSnapshot()) : UpdatePersistence {
        override suspend fun snapshot() = value
        override suspend fun attempt(now: Long) { value = value.copy(lastAttempt = now) }
        override suspend fun success(now: Long, release: UpdateRelease) { value = value.copy(lastCheck = now, latest = release) }
        override suspend fun rateLimit(until: Long) { value = value.copy(retryAt = until) }
    }
    private fun release(code: Long = 4) = UpdateRelease(code, "0.1.1", 1, 26, false,
        "torxone.apk", "a".repeat(64), "Fixes",
        "https://github.com/Nikhilesh-CS/TorX-One/releases/download/v0.1.1/torxone.apk",
        "https://github.com/Nikhilesh-CS/TorX-One/releases/tag/v0.1.1", 100)
    @Test fun checkingTransitionsToAvailable() = runTest {
        val started = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        val repository = UpdateRepository(ReleaseSource { started.complete(Unit); proceed.await(); release() }, Store(), 3, 36) { 100 }
        val job = launch { repository.check(true) }; started.await()
        assertEquals(UpdateState.Checking, repository.state.value)
        proceed.complete(Unit); job.join()
        assertTrue(repository.state.value is UpdateState.Available)
    }
    @Test fun duplicateTapsProduceOneRequest() = runTest {
        var calls = 0; val started = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        val repository = UpdateRepository(ReleaseSource { calls++; started.complete(Unit); proceed.await(); release() }, Store(), 3, 36) { 100 }
        val job = launch { repository.check(true) }; started.await()
        assertNull(repository.check(true)); assertEquals(1, calls)
        proceed.complete(Unit); job.join()
    }
    @Test fun offlineIsRecoverableAndNeverCurrent() = runTest {
        var fail = true
        val repository = UpdateRepository(ReleaseSource { if (fail) throw UpdateFailure("Offline", true); release() }, Store(), 3, 36) { 100 }
        repository.check(true); assertEquals(UpdateState.Error("Offline"), repository.state.value)
        fail = false; repository.check(true); assertTrue(repository.state.value is UpdateState.Available)
    }
    @Test fun equalAndOlderRemoteVersionsNeverOfferInstall() = runTest {
        for (code in listOf(2L, 3L)) {
            val repository = UpdateRepository(ReleaseSource { release(code) }, Store(), 3, 36) { 100 }
            repository.check(true); assertEquals(UpdateState.UpToDate, repository.state.value)
        }
    }
    @Test fun automaticDisabledAndCachedChecksDoNotHitNetwork() = runTest {
        var calls = 0
        val store = Store(UpdateSnapshot(automatic = false))
        val repository = UpdateRepository(ReleaseSource { calls++; release() }, store, 3, 36) { 100 }
        repository.check(false); assertEquals(0, calls)
        repository.check(true); assertEquals(1, calls)
        store.value = store.value.copy(automatic = true)
        repository.check(false); assertEquals(1, calls)
    }
    @Test fun rateLimitPersistsAndManualCannotHammerServer() = runTest {
        var calls = 0; val store = Store()
        val repository = UpdateRepository(ReleaseSource { calls++; throw UpdateFailure("Rate limited", true, 1000) }, store, 3, 36) { 100 }
        repository.check(true); repository.check(true)
        assertEquals(1, calls); assertEquals(1000L, store.value.retryAt)
        assertTrue(repository.state.value is UpdateState.Error)
    }
}
