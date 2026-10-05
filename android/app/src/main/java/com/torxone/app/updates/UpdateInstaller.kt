package com.torxone.app.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Separate manifest component prevents merging with the existing media provider. */
class UpdateFileProvider : FileProvider()

object UpdateFiles {
    fun directory(context: Context) = File(context.filesDir, "update-apks").apply { mkdirs() }
    fun apk(context: Context, code: Long) = File(directory(context), "update-$code.apk")
    fun partial(context: Context, code: Long, jobId: java.util.UUID) = File(directory(context), "update-$code-$jobId.part")
    fun cleanup(context: Context, keep: File? = null) {
        directory(context).listFiles()?.filter { it != keep && it.name.matches(Regex("update-[0-9]+(?:-[0-9a-f-]{36})?\\.(apk|part)")) }
            ?.forEach { it.delete() }
    }
}

class UpdateInstaller(private val context: Context) {
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return certificates?.map { UpdatePolicy.sha256(it.toByteArray()) }?.toSet().orEmpty()
    }
    @Suppress("DEPRECATION")
    suspend fun verify(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        UpdatePolicy.validate(release)
        if (!file.isFile || file.length() != release.size) throw UpdateFailure("Update verification failed. Please download it again.")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer); if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        if (!UpdatePolicy.hashMatches(release.sha256, hash.digest().joinToString("") { "%02x".format(it) }))
            throw UpdateFailure("Update verification failed. The downloaded update could not be verified.")
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateFailure("Update verification failed. Invalid APK.")
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        UpdatePolicy.verifyIdentity(archive.packageName, context.packageName, archiveCode,
            archive.versionName, installedCode, signers(archive), signers(installed), release)
        if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT)
            throw UpdateFailure("This update is not compatible with your Android version.")
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))

    /** Called only after a fresh verify and an explicit user tap. Android owns final confirmation. */
    fun installIntent(file: File): Intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        data = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_RETURN_RESULT, true)
        clipData = android.content.ClipData.newRawUri("Verified TorX One update", data)
    }
}
