package com.torxone.app.updates

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Serializable
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val minimumSupportedVersionCode: Long,
    val minimumAndroidSdk: Int,
    val critical: Boolean,
    val apkAsset: String,
    val sha256: String,
    val releaseNotes: String,
    val apkUrl: String,
    val releaseUrl: String,
    val size: Long
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Verifying : UpdateState
    data class ReadyToInstall(val release: UpdateRelease) : UpdateState
    data class Error(val userMessage: String) : UpdateState
}

class UpdateFailure(val userMessage: String, val retryable: Boolean = false,
                    val retryAt: Long = 0) : Exception(userMessage)

object UpdatePolicy {
    const val REPOSITORY = "Nikhilesh-CS/TorX-One"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val SOURCE = "https://github.com/$REPOSITORY"
    const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    const val MAX_APK_BYTES = 512 * 1024 * 1024L
    val json = Json { ignoreUnknownKeys = true }

    fun newer(installed: Long, remote: Long) = remote > installed
    fun due(now: Long, lastAttempt: Long, retryAt: Long): Boolean =
        now >= retryAt && (lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= CHECK_INTERVAL)

    fun releaseUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host == "github.com" && u.rawUserInfo == null &&
            u.port == -1 && u.rawQuery == null && u.rawFragment == null &&
            u.rawPath.startsWith("/$REPOSITORY/releases/tag/") &&
            u.rawPath.removePrefix("/$REPOSITORY/releases/tag/").isNotBlank()
    }.getOrDefault(false)

    fun assetUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host == "github.com" && u.rawUserInfo == null &&
            u.port == -1 && u.rawQuery == null && u.rawFragment == null &&
            u.rawPath.startsWith("/$REPOSITORY/releases/download/") &&
            u.rawPath.split('/').none { it == "." || it == ".." }
    }.getOrDefault(false)

    /** No suffix matching: only GitHub's exact release-asset infrastructure is accepted. */
    fun transportUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        if (u.scheme != "https" || u.rawUserInfo != null || u.port != -1 || u.rawFragment != null) false
        else when (u.host) {
            "api.github.com" -> value == API
            "github.com" -> assetUrl(value)
            "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            "github-releases.githubusercontent.com" -> u.rawPath.startsWith("/")
            else -> false
        }
    }.getOrDefault(false)

    fun validate(r: UpdateRelease): UpdateRelease {
        require(r.versionCode in 1..Int.MAX_VALUE.toLong())
        require(r.minimumSupportedVersionCode in 1..r.versionCode)
        require(r.minimumAndroidSdk in 1..1000)
        require(r.versionName.isNotBlank() && r.versionName.length <= 80 && r.versionName.none { it.isISOControl() })
        require(r.apkAsset.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk")))
        require(r.sha256.matches(Regex("[0-9a-f]{64}")))
        require(r.releaseNotes.length <= 4000)
        require(r.size in 1..MAX_APK_BYTES)
        require(assetUrl(r.apkUrl) && releaseUrl(r.releaseUrl))
        val asset = URI(r.apkUrl)
        val page = URI(r.releaseUrl)
        require(asset.rawPath.substringAfter("/releases/download/").substringBeforeLast('/') ==
            page.rawPath.substringAfter("/releases/tag/"))
        require(asset.path.substringAfterLast('/') == r.apkAsset)
        return r
    }

    fun decodeRelease(value: String) = validate(json.decodeFromString<UpdateRelease>(value))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    fun hashMatches(expected: String, actual: String): Boolean =
        expected.matches(Regex("[0-9a-f]{64}")) && actual.matches(Regex("[0-9a-f]{64}")) &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))

    fun verifyIdentity(packageName: String, expectedPackage: String, code: Long, name: String?,
                       installedCode: Long, actualSigners: Set<String>, installedSigners: Set<String>, release: UpdateRelease) {
        if (packageName != expectedPackage || code != release.versionCode || name != release.versionName ||
            !newer(installedCode, code)) throw UpdateFailure("Update verification failed. Wrong package or version.")
        if (installedSigners.isEmpty() || actualSigners != installedSigners)
            throw UpdateFailure("Update verification failed. Signing certificate does not match this installation.")
    }

    fun metadata(text: String, apkUrl: String, releaseUrl: String, size: Long): UpdateRelease {
        val o = json.parseToJsonElement(text).jsonObject
        fun long(key: String): Long = requireNotNull(o[key]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull) { "Missing integer $key" }
        fun string(key: String): String = o[key]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing string $key")
        require(o["schemaVersion"]?.jsonPrimitive?.takeIf { !it.isString }?.intOrNull == 1)
        require(o["channel"]?.jsonPrimitive?.takeIf { it.isString }?.content == "stable")
        val sdk = long("minimumAndroidSdk").also { require(it in 1..1000) }.toInt()
        if (o.containsKey("critical")) require(o["critical"]?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull != null)
        return validate(UpdateRelease(long("versionCode"), string("versionName"),
            long("minimumSupportedVersionCode"), sdk,
            o["critical"]?.jsonPrimitive?.booleanOrNull ?: false,
            string("apkAsset"), string("sha256"), string("releaseNotes"), apkUrl, releaseUrl, size))
    }
}
