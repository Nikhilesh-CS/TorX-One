package com.torxone.app.updates

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

/** Platform HTTP, system TLS, no cookies, tokens, device identifiers or message transport. */
class UpdateHttp {
    suspend fun <T> read(url: String, limit: Long, timeoutMs: Long = 60_000,
                         consume: suspend (InputStream, Long) -> T): T = withContext(Dispatchers.IO) {
        require(UpdatePolicy.transportUrl(url))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var next = url
        for (redirect in 0..5) {
            coroutineContext.ensureActive()
            if (System.nanoTime() >= deadline) throw UpdateFailure("Update request timed out. Try again.", true)
            if (!UpdatePolicy.transportUrl(next)) throw UpdateFailure("Update link could not be verified.")
            val c = URL(next).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "TorX-One-Updater")
            c.setRequestProperty("Accept", if (next == UpdatePolicy.API) "application/vnd.github+json" else "application/octet-stream")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (next == UpdatePolicy.API) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            try {
                when (val code = c.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw UpdateFailure("Invalid update redirect.")
                        next = URL(URL(next), location).toString()
                        continue
                    }
                    403, 429 -> {
                        val retry = c.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400)
                        val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                        throw UpdateFailure("Could not check for updates right now. Please try later.", true,
                            reset?.coerceIn(System.currentTimeMillis() + 60_000, System.currentTimeMillis() + 86_400_000)
                                ?: (System.currentTimeMillis() + (retry ?: 3600) * 1000))
                    }
                    404 -> throw UpdateFailure("No published stable update is available at this address. Try again later.")
                    200 -> Unit
                    else -> throw UpdateFailure("Update server is unavailable. Try again later.", code >= 500)
                }
                val length = c.contentLengthLong
                if (length > limit) throw UpdateFailure("Update response exceeds the allowed size.")
                val input = object : java.io.FilterInputStream(c.inputStream) {
                    var total = 0L
                    override fun read(): Int = throw UnsupportedOperationException("Use bounded bulk reads")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (System.nanoTime() >= deadline) throw UpdateFailure("Update request timed out. Try again.", true)
                        val n = super.read(b, off, len)
                        if (n > 0) { total += n; if (total > limit) throw UpdateFailure("Update response exceeds the allowed size.") }
                        return n
                    }
                }
                return@withContext input.use { consume(it, length) }
            } finally { c.disconnect() }
        }
        throw UpdateFailure("Too many update redirects.")
    }

    suspend fun text(url: String, limit: Long): String = read(url, limit) { input, _ ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            bytes.write(buffer, 0, n)
        }
        bytes.toString("UTF-8")
    }
}

fun interface ReleaseSource { suspend fun latest(): UpdateRelease }

class GitHubReleaseClient(private val http: UpdateHttp = UpdateHttp()) : ReleaseSource {
    override suspend fun latest(): UpdateRelease = try {
        kotlinx.coroutines.withTimeout(45_000) { fetchLatest() }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        throw UpdateFailure("Update request timed out. Please try again.", true)
    }

    private suspend fun fetchLatest(): UpdateRelease {
        try {
            val o = UpdatePolicy.json.parseToJsonElement(http.text(UpdatePolicy.API, 256 * 1024)).jsonObject
            require(o["draft"]?.jsonPrimitive?.booleanOrNull == false)
            require(o["prerelease"]?.jsonPrimitive?.booleanOrNull == false)
            val page = o["html_url"]!!.jsonPrimitive.content
            require(UpdatePolicy.releaseUrl(page))
            val assets = o["assets"]!!.jsonArray.map { it.jsonObject }
            val metadata = assets.single { it["name"]?.jsonPrimitive?.content == "update.json" }
            val metadataUrl = metadata["browser_download_url"]!!.jsonPrimitive.content
            require(UpdatePolicy.assetUrl(metadataUrl))
            val text = http.text(metadataUrl, 16 * 1024)
            val name = UpdatePolicy.json.parseToJsonElement(text).jsonObject["apkAsset"]!!.jsonPrimitive.content
            val apk = assets.single { it["name"]?.jsonPrimitive?.content == name }
            val release = UpdatePolicy.metadata(text, apk["browser_download_url"]!!.jsonPrimitive.content,
                page, apk["size"]!!.jsonPrimitive.long)
            // Metadata must come from the same release as the selected APK.
            require(java.net.URI(metadataUrl).rawPath.substringBeforeLast('/') ==
                java.net.URI(release.apkUrl).rawPath.substringBeforeLast('/'))
            return release
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: UpdateFailure) { throw e }
        catch (e: java.io.IOException) { throw UpdateFailure("Can't check for updates. Check your connection and try again.", true) }
        catch (_: Exception) { throw UpdateFailure("This release's update metadata is missing or invalid. Try again later.") }
    }
}
