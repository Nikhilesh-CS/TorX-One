package com.torxone.app.updates

import android.content.Context
import android.os.Build
import androidx.work.*
import com.torxone.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** App-context singleton; jobs/cache own lifetime, never an Activity or message service. */
class UpdateRuntime private constructor(context: Context) {
    companion object {
        @Volatile private var instance: UpdateRuntime? = null
        fun get(context: Context): UpdateRuntime = instance ?: synchronized(this) {
            instance ?: UpdateRuntime(context.applicationContext).also { instance = it }
        }
        fun start(context: Context) {
            try { get(context).initialize() }
            catch (_: Exception) { android.util.Log.w("TorXUpdates", "Update scheduling unavailable; messaging startup continues") }
        }
        fun open(context: Context) {
            try {
                val intent = android.content.Intent(context, UpdateActivity::class.java)
                if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "App updates are temporarily unavailable.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private val context = context.applicationContext
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        mutable.value = UpdateState.Error("App updates are temporarily unavailable. Please try again.")
        android.util.Log.w("TorXUpdates", "Update background task failed; messaging continues")
    })
    private val initialized = AtomicBoolean()
    private val commandMutex = Mutex()
    val store = UpdateStore(context)
    private val repository = UpdateRepository(GitHubReleaseClient(), store, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)
    private val wm = WorkManager.getInstance(context)
    private val downloads = UpdateDownloadManager(context, store)
    val state = mutable.asStateFlow()
    val preferences = store.snapshots.catch { mutable.value = UpdateState.Error("Update preferences could not be read.")
        emit(UpdateSnapshot(automatic = false)) }.stateIn(scope, SharingStarted.Eagerly, UpdateSnapshot())
    @Volatile private var workState: UpdateState? = null

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            preferences.map { it.automatic }.distinctUntilChanged().collect {
                runCatching { UpdateWork.schedule(context, it) }
                    .onFailure { mutable.value = UpdateState.Error("Background update checks could not be scheduled.") }
            }
        }
        scope.launch { repository.state.collect { if (workState == null) mutable.value = it } }
        scope.launch {
            combine(wm.getWorkInfosForUniqueWorkFlow(UpdateWork.DOWNLOAD), store.snapshots) { infos, saved -> infos to saved }
                .catch { mutable.value = UpdateState.Error("Update download status could not be restored.") }
                .collect { (infos, saved) ->
                    val release = saved.pending
                    if (release == null) { workState = null; return@collect }
                    if (!UpdatePolicy.newer(BuildConfig.VERSION_CODE.toLong(), release.versionCode)) {
                        UpdateFiles.cleanup(context); store.pending(null); workState = null; return@collect
                    }
                    val work = infos.firstOrNull { it.id.toString() == saved.downloadId }
                    workState = when {
                        work == null -> if (UpdateFiles.apk(context, release.versionCode).isFile) restoreReady(release)
                            else UpdateState.Error("The update job was interrupted. Check for updates and download again.")
                        work.state == WorkInfo.State.ENQUEUED || work.state == WorkInfo.State.BLOCKED -> UpdateState.Downloading(-1f)
                        work.state == WorkInfo.State.RUNNING -> if (work.progress.getString("phase") == "verify") UpdateState.Verifying
                            else UpdateState.Downloading(work.progress.getFloat("progress", 0f))
                        work.state == WorkInfo.State.SUCCEEDED -> restoreReady(release)
                        work.state == WorkInfo.State.FAILED -> UpdateState.Error(work.outputData.getString("message") ?: "Update download failed. Try again.")
                        else -> UpdateState.Error("Update download cancelled.")
                    }
                    mutable.value = workState ?: repository.state.value
                }
        }
        scope.launch { check(false) }
    }

    private suspend fun restoreReady(release: UpdateRelease): UpdateState = try {
        UpdateInstaller(context).verify(UpdateFiles.apk(context, release.versionCode), release)
        UpdateState.ReadyToInstall(release)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        UpdateFiles.apk(context, release.versionCode).delete()
        UpdateState.Error("Saved update could not be verified. Download it again.")
    }

    suspend fun check(manual: Boolean) {
        if (!commandMutex.tryLock()) return
        try {
            if (workState is UpdateState.Downloading || workState == UpdateState.Verifying) return
            if (workState is UpdateState.ReadyToInstall) return
            if (manual) { store.pending(null); workState = null }
            val release = repository.check(manual) ?: return
            if (manual) return
            val saved = store.snapshot()
            if (saved.automatic && saved.notified < release.versionCode &&
                !(saved.dismissed == release.versionCode && System.currentTimeMillis() < saved.remindAt)) {
                if (UpdateNotifications(context).available(release)) store.notified(release.versionCode)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (manual) mutable.value = UpdateState.Error("Can't check for updates. Please try again.") }
        finally { commandMutex.unlock() }
    }
    fun manualCheck() { scope.launch { check(true) } }
    fun automatic(value: Boolean) { scope.launch {
        try { store.automatic(value); UpdateWork.schedule(context, value) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = UpdateState.Error("Couldn't save update preferences.") }
    } }
    fun later(release: UpdateRelease) { scope.launch {
        try { store.dismiss(release.versionCode); UpdateNotifications(context).dismiss() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = UpdateState.Error("Couldn't save the reminder preference.") }
    } }
    fun download(release: UpdateRelease) { scope.launch {
        if (!commandMutex.tryLock()) return@launch
        try {
            workState = UpdateState.Downloading(-1f); mutable.value = workState!!
            downloads.enqueue(release)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { workState = null; mutable.value = UpdateState.Error("Couldn't schedule the update download. Try again.") }
        finally { commandMutex.unlock() }
    } }
    fun cancelDownload() {
        try { downloads.cancel() }
        catch (_: Exception) { mutable.value = UpdateState.Error("Couldn't cancel the download. Please try again.") }
    }
    fun rejectDownloaded(release: UpdateRelease, message: String) { scope.launch {
        UpdateFiles.apk(context, release.versionCode).delete()
        workState = null
        mutable.value = UpdateState.Error(message)
        try { store.pending(null) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = UpdateState.Error("Could not clear the invalid update. Please try again.") }
    } }
}
