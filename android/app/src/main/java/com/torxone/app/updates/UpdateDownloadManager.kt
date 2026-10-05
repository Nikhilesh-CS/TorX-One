package com.torxone.app.updates

import android.content.Context
import androidx.work.*
import com.torxone.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** WorkManager persists the job; DataStore binds recovery to its exact UUID, not an old same-version job. */
class UpdateDownloadManager(context: Context, private val store: UpdateStore) {
    private val wm = WorkManager.getInstance(context.applicationContext)
    private val mutex = Mutex()
    suspend fun enqueue(release: UpdateRelease): Boolean {
        if (!mutex.tryLock()) return false
        try {
            UpdatePolicy.validate(release)
            require(UpdatePolicy.newer(BuildConfig.VERSION_CODE.toLong(), release.versionCode))
            val active = withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork(UpdateWork.DOWNLOAD).get(10, TimeUnit.SECONDS) }
            if (active.any { !it.state.isFinished }) return false
            val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(workDataOf("version" to release.versionCode, "hash" to release.sha256))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            store.pending(release, request.id.toString())
            withContext(Dispatchers.IO) { wm.enqueueUniqueWork(UpdateWork.DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
                .result.get(10, TimeUnit.SECONDS) }
            return true
        } finally { mutex.unlock() }
    }
    fun cancel() { wm.cancelUniqueWork(UpdateWork.DOWNLOAD) }
}
