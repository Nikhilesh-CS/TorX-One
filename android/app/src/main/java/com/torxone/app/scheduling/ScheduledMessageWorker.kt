package com.torxone.app.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.torxone.app.TorXOneApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class ScheduledMessageWorkScheduler(context: Context) : ScheduledWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    override fun enqueue(value: ScheduledMessageEntity) {
        val request = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            // WorkManager's database is not SQLCipher: do not place text, reply or identity here.
            .setInputData(workDataOf("scheduleId" to value.scheduleId, "generation" to value.generation))
            .setInitialDelay((value.scheduledAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SchedulingPolicy.workName(value.scheduleId, value.generation),
            ExistingWorkPolicy.KEEP, request)
    }
    override fun cancel(id: String, generation: Long) {
        workManager.cancelUniqueWork(SchedulingPolicy.workName(id, generation))
    }
}

class ScheduledMessageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("scheduleId") ?: return Result.failure()
        val generation = inputData.getLong("generation", -1)
        if (generation < 1) return Result.failure()
        val app = applicationContext as? TorXOneApplication ?: return Result.failure()
        // App/vault initialization is authoritative. Never try to unlock keys from background work.
        val state = withTimeoutOrNull(15_000) {
            app.initState.first { it !is TorXOneApplication.AppInitState.Initializing }
        }
        if (state !is TorXOneApplication.AppInitState.Ready || app.getLocalIdentityId() == null)
            return Result.retry()
        return try {
            when (app.scheduledMessageService.run(id, generation)) {
                ScheduledRunResult.COMPLETE -> Result.success()
                ScheduledRunResult.RETRY -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
