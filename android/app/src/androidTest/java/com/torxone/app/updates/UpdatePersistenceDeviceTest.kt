package com.torxone.app.updates

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Owned fixture files only: no production preferences, wallpapers, chats or identity writes. */
class UpdatePersistenceDeviceTest {
    @Test fun processRestartRestoresExactDownloadAndNotificationSuppression() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "update-test-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "fixture.preferences_pb")
        var job: Job = SupervisorJob()
        fun openStore() = UpdateStore(context, PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        try {
            val release = UpdateRelease(4, "0.1.1", 1, 26, false, "torxone.apk", "a".repeat(64), "Fixes",
                "https://github.com/Nikhilesh-CS/TorX-One/releases/download/v0.1.1/torxone.apk",
                "https://github.com/Nikhilesh-CS/TorX-One/releases/tag/v0.1.1", 100)
            val id = UUID.randomUUID().toString()
            val first = openStore()
            first.pending(release, id); first.notified(4); first.dismiss(4)
            first.success(1234, release); first.automatic(false)
            job.cancelAndJoin()
            job = SupervisorJob()
            val recovered = openStore().snapshot()
            assertEquals(release, recovered.pending)
            assertEquals(id, recovered.downloadId)
            assertEquals(release, recovered.latest)
            assertEquals(4L, recovered.notified)
            assertEquals(4L, recovered.dismissed)
            assertTrue(recovered.remindAt > System.currentTimeMillis())
            assertFalse(recovered.automatic)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
