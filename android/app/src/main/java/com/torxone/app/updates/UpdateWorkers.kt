package com.torxone.app.updates

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.work.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val runtime = UpdateRuntime.get(applicationContext)
            runtime.check(manual = false)
            Result.success() // Next daily check handles failure; rate-limit cooldown is persisted.
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.success() }
    }
}

class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val code = inputData.getLong("version", -1)
        val hash = inputData.getString("hash")
        val store = UpdateStore(applicationContext)
        val part = UpdateFiles.partial(applicationContext, code, id)
        val apk = UpdateFiles.apk(applicationContext, code)
        var completed = false
        var promoted = false
        try {
            val saved = store.snapshot()
            val release = saved.pending ?: throw UpdateFailure("Update details expired. Check for updates again.")
            if (saved.downloadId != id.toString()) throw UpdateFailure("Update job was superseded.")
            if (release.versionCode != code || release.sha256 != hash) throw UpdateFailure("Update job no longer matches the selected release.")
            UpdatePolicy.validate(release)
            part.delete()
            val available = StatFs(UpdateFiles.directory(applicationContext).absolutePath).availableBytes
            if (available < release.size + 32 * 1024 * 1024L) throw UpdateFailure("Not enough storage to download this update.")
            val notification = UpdateNotifications(applicationContext).download(id)
            val foreground = if (Build.VERSION.SDK_INT >= 29)
                ForegroundInfo(UpdateNotifications.DOWNLOAD_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(UpdateNotifications.DOWNLOAD_ID, notification)
            setForeground(foreground)
            setProgress(workDataOf("phase" to "download", "progress" to 0f))
            // Retry restarts from zero. It cannot append bytes from an unverified or changed asset.
            var lastProgress = -1
            UpdateHttp().read(release.apkUrl, release.size, timeoutMs = 8 * 60_000L) { input, length ->
                if (length > 0 && length != release.size) throw UpdateFailure("Update verification failed. Download size changed.")
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var received = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); received += n
                        val percent = (received * 100 / release.size).toInt()
                        if (percent != lastProgress) {
                            lastProgress = percent
                            setProgress(workDataOf("phase" to "download", "progress" to percent / 100f))
                        }
                    }
                    output.fd.sync()
                }
            }
            setProgress(workDataOf("phase" to "verify"))
            UpdateInstaller(applicationContext).verify(part, release)
            coroutineContext.ensureActive()
            if (store.snapshot().downloadId != id.toString()) throw UpdateFailure("Update job was superseded.")
            apk.delete()
            if (!part.renameTo(apk)) throw UpdateFailure("Couldn't save the verified update. Check available storage.")
            promoted = true
            UpdateFiles.cleanup(applicationContext, apk)
            completed = true
            try { UpdateNotifications(applicationContext).ready(release) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Verified file/UI survive notification failure. */ }
            Result.success(workDataOf("version" to code))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = e as? UpdateFailure
            val retryable = failure?.retryable == true || e is IOException
            if (retryable && runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf("message" to (failure?.userMessage
                ?: if (e is IOException) "Download interrupted or storage unavailable. Please try again."
                else "Couldn't download and verify the update. Please try again.")))
        } finally {
            part.delete()
            if (!completed && promoted) apk.delete()
        }
    }
}

object UpdateWork {
    const val PERIODIC = "torx-update-check-daily"
    const val DOWNLOAD = "torx-update-download"
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(PERIODIC); return }
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }
}
