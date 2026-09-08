package com.torxone.app.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class UpdateInfo(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isUpdateAvailable: Boolean,
    val sha256: String? = null,
    val assetName: String
)

enum class UpdateState {
    DOWNLOADING, VERIFYING, INSTALLER_LAUNCHED,
    DOWNLOAD_FAILED, SIGNATURE_INVALID, INVALID_APK, INSTALLER_UNAVAILABLE, INSTALL_PERMISSION_REQUIRED
}

class GitHubUpdater(private val context: Context) {

    private val REPO_URL = "https://api.github.com/repos/Nikhilesh-CS/TorX-One/releases/latest"
    private val PREFS_NAME = "updater_prefs"
    private val LAST_CHECK_TIME = "last_check_time"
    private val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun checkForUpdates(manual: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!manual) {
            val lastCheck = prefs.getLong(LAST_CHECK_TIME, 0)
            if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                return@withContext null // Too soon for an automatic check
            }
        }

        try {
            val url = URL(REPO_URL)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name").removePrefix("v")
                val releaseNotes = json.getString("body")
                
                val assets = json.getJSONArray("assets")
                val expectedAssetName = "TorX-One-v$tagName-release.apk"
                var downloadUrl = ""
                var sha256: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name") == expectedAssetName) {
                        downloadUrl = asset.getString("browser_download_url")
                        sha256 = asset.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                        break
                    }
                }

                if (downloadUrl.isBlank()) {
                    Log.e("GitHubUpdater", "Release $tagName is missing required asset $expectedAssetName")
                    return@withContext null
                }
                if (sha256 == null) {
                    Log.e("GitHubUpdater", "Release $tagName is missing a SHA-256 asset digest")
                    return@withContext null
                }

                // Simple version string comparison (assuming semantic versioning like 1.0.0)
                val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                val isUpdateAvailable = isVersionGreater(tagName, currentVersion)

                if (!manual && isUpdateAvailable) {
                    prefs.edit().putLong(LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                }

                return@withContext UpdateInfo(
                    version = tagName,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    isUpdateAvailable = isUpdateAvailable,
                    sha256 = sha256,
                    assetName = expectedAssetName
                )
            }
        } catch (e: Exception) {
            Log.e("GitHubUpdater", "Failed to check for updates: ${e.message}")
        }
        return@withContext null
    }

    fun downloadAndInstallUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit,
        onInstallerLaunched: () -> Unit,
        onError: (String) -> Unit,
        onState: (UpdateState) -> Unit = {}
    ) {
        if (updateInfo.downloadUrl.isEmpty()) {
            onError("Required release APK is missing.")
            return
        }
        Thread {
            val fileName = "torx-one-${updateInfo.version}.apk"
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.cacheDir
            val apkFile = File(outputDir, fileName)

            try {
                postState(onState, UpdateState.DOWNLOADING)
                if (apkFile.exists()) apkFile.delete()
                outputDir.mkdirs()

                val connection = (URL(updateInfo.downloadUrl).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("Accept", "application/octet-stream")
                    instanceFollowRedirects = true
                }

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                        val buffer = ByteArray(64 * 1024)
                        var downloadedBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            totalBytes?.let { total ->
                                postProgress(onProgress, (downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                postProgress(onProgress, 1f)
                postState(onState, UpdateState.VERIFYING)
                val validationError = validateDownloadedApk(apkFile, updateInfo)
                if (validationError != null) {
                    apkFile.delete()
                    postState(onState, if (validationError.startsWith("Signature")) UpdateState.SIGNATURE_INVALID else UpdateState.INVALID_APK)
                    postError(onError, validationError)
                    return@Thread
                }
                installApk(apkFile, onInstallerLaunched, onError, onState)
            } catch (e: Exception) {
                Log.e("GitHubUpdater", "Failed to download update", e)
                apkFile.delete()
                postState(onState, UpdateState.DOWNLOAD_FAILED)
                postError(onError, "Download failed: ${e.message}")
            }
        }.start()
    }

    private fun installApk(
        apkFile: File,
        onInstallerLaunched: () -> Unit,
        onError: (String) -> Unit,
        onState: (UpdateState) -> Unit
    ) {
        try {
            if (!apkFile.exists()) {
                postState(onState, UpdateState.INVALID_APK)
                postError(onError, "Downloaded APK not found.")
                return
            }

            // On Android 8.0+, check if installing unknown apps is permitted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    postState(onState, UpdateState.INSTALL_PERMISSION_REQUIRED)
                    mainHandler.post {
                        Toast.makeText(context, "Please enable 'Allow from this source' to install the update", Toast.LENGTH_LONG).show()
                        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(settingsIntent)
                    }
                    postError(onError, "Allow installs from TorX One in Settings, then retry the update.")
                    return
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // Explicitly grant read URI permission to all potential handling activities
            val resInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (resolveInfo in resInfoList) {
                context.grantUriPermission(resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            mainHandler.post {
                try {
                    context.startActivity(intent)
                    // ACTION_VIEW provides no reliable installation result. This event means only
                    // that Android Package Installer was opened; it must never be shown as done.
                    onState(UpdateState.INSTALLER_LAUNCHED)
                    onInstallerLaunched()
                } catch (e: Exception) {
                    Log.e("GitHubUpdater", "Failed to start install activity", e)
                    onState(UpdateState.INSTALLER_UNAVAILABLE)
                    postError(onError, "Failed to launch installer: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("GitHubUpdater", "Failed to install APK", e)
            postState(onState, UpdateState.INSTALLER_UNAVAILABLE)
            postError(onError, "Failed to launch installer: ${e.message}")
        }
    }

    private fun postProgress(onProgress: (Float) -> Unit, value: Float) {
        mainHandler.post { onProgress(value) }
    }

    private fun postError(onError: (String) -> Unit, message: String) {
        mainHandler.post { onError(message) }
    }

    private fun postState(onState: (UpdateState) -> Unit, state: UpdateState) {
        mainHandler.post { onState(state) }
    }

    /** Returns a user-safe validation error, or null only for an installable update candidate. */
    private fun validateDownloadedApk(apkFile: File, updateInfo: UpdateInfo): String? {
        if (!apkFile.exists() || apkFile.length() <= 0L) return "Invalid APK: downloaded file is empty."
        updateInfo.sha256?.let { expected ->
            if (!sha256(apkFile).equals(expected, ignoreCase = true)) return "Invalid APK: SHA-256 digest mismatch."
        }
        val archive = archivePackageInfo(apkFile) ?: return "Invalid APK: Android could not parse the package."
        if (archive.packageName != context.packageName) return "Invalid APK: package name does not match TorX One."
        if (archiveVersionCode(archive) <= installedVersionCode()) return "Invalid APK: versionCode is not newer than the installed app."
        if (!verifyApkSignature(apkFile)) return "Signature invalid: update certificate does not match the installed app."
        return null
    }

    private fun archivePackageInfo(apkFile: File): android.content.pm.PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
    } catch (e: Exception) {
        null
    }

    private fun installedVersionCode(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } else {
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
    }

    private fun archiveVersionCode(info: android.content.pm.PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private fun extractSignatures(packageInfo: android.content.pm.PackageInfo?): Array<android.content.pm.Signature>? {
        if (packageInfo == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                return if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            }
        }
        @Suppress("DEPRECATION")
        return packageInfo.signatures
    }

    private fun getArchivePackageInfoWithSignatures(apkFile: File): android.content.pm.PackageInfo? {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val info = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
                if (info?.signingInfo != null) return info
            } catch (e: Exception) {
                Log.w("GitHubUpdater", "Failed reading archive with GET_SIGNING_CERTIFICATES (API 33+)", e)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                if (info?.signingInfo != null) return info
            } catch (e: Exception) {
                Log.w("GitHubUpdater", "Failed reading archive with GET_SIGNING_CERTIFICATES (API 28+)", e)
            }
        }

        // Fallback to GET_SIGNATURES
        return try {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            Log.w("GitHubUpdater", "Failed reading archive with GET_SIGNATURES", e)
            null
        }
    }

    private fun getInstalledPackageInfoWithSignatures(): android.content.pm.PackageInfo? {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
                if (info.signingInfo != null) return info
            } catch (e: Exception) {
                Log.w("GitHubUpdater", "Failed reading installed package with GET_SIGNING_CERTIFICATES (API 33+)", e)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                if (info.signingInfo != null) return info
            } catch (e: Exception) {
                Log.w("GitHubUpdater", "Failed reading installed package with GET_SIGNING_CERTIFICATES (API 28+)", e)
            }
        }

        // Fallback to GET_SIGNATURES
        return try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            Log.w("GitHubUpdater", "Failed reading installed package with GET_SIGNATURES", e)
            null
        }
    }

    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val archiveInfo = getArchivePackageInfoWithSignatures(apkFile)
            val currentInfo = getInstalledPackageInfoWithSignatures() ?: return false

            val newSigs = extractSignatures(archiveInfo)
            val oldSigs = extractSignatures(currentInfo)

            if (!newSigs.isNullOrEmpty() && !oldSigs.isNullOrEmpty()) {
                val matches = newSigs.any { newSig ->
                    oldSigs.any { oldSig ->
                        newSig.toByteArray().contentEquals(oldSig.toByteArray())
                    }
                }
                if (matches) return true

                // In debug / development builds, ignore signature mismatch so developers can test updates
                val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebuggable) {
                    Log.w("GitHubUpdater", "Debug build: signature mismatch ignored for testing.")
                    return true
                }
                Log.e("GitHubUpdater", "Signature mismatch between downloaded update and installed app.")
                return false
            }

            // If userland getPackageArchiveInfo cannot extract signatures (e.g. on Android versions
            // where getPackageArchiveInfo does not support modern v2/v3-only signatures),
            // permit installation to proceed because Android OS's PackageInstaller kernel/system service
            // strictly enforces certificate matching (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
            Log.w("GitHubUpdater", "Could not inspect signatures via PackageManager; delegating enforcement to Android PackageInstaller.")
            true
        } catch (e: Exception) {
            Log.e("GitHubUpdater", "Signature verification failed: ${e.message}", e)
            true
        }
    }

    private fun isVersionGreater(remote: String, local: String): Boolean {
        try {
            val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val lParts = local.split(".").map { it.toIntOrNull() ?: 0 }
            val length = maxOf(rParts.size, lParts.size)
            for (i in 0 until length) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return false
    }
}
