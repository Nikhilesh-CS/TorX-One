package com.torxone.app.updates

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class UpdatePolicyTest {
    private val page = "https://github.com/Nikhilesh-CS/TorX-One/releases/tag/v0.1.1-build4"
    private val apk = "https://github.com/Nikhilesh-CS/TorX-One/releases/download/v0.1.1-build4/torxone-v0.1.1-build4.apk"
    private val metadata = """{"schemaVersion":1,"channel":"stable","versionCode":4,"versionName":"0.1.1",
        "minimumSupportedVersionCode":1,"minimumAndroidSdk":26,"critical":false,
        "apkAsset":"torxone-v0.1.1-build4.apk","sha256":"${"a".repeat(64)}","releaseNotes":"Fixes"}"""
    private fun release(text: String = metadata) = UpdatePolicy.metadata(text, apk, page, 100)
    private fun reject(block: () -> Unit) { try { block(); fail("Must reject invalid update") } catch (_: IllegalArgumentException) {} }
    @Test fun numericVersionsNeverDowngrade() {
        assertTrue(UpdatePolicy.newer(1, 2)); assertFalse(UpdatePolicy.newer(2, 2)); assertFalse(UpdatePolicy.newer(3, 2))
        assertTrue(UpdatePolicy.newer(9, 10))
    }
    @Test fun validMetadataRoundTripsWithCacheValidation() {
        val r = release(); assertEquals(4, r.versionCode.toInt())
        assertEquals(r, UpdatePolicy.decodeRelease(UpdatePolicy.json.encodeToString(r)))
    }
    @Test fun rejectsInvalidVersionsSchemaAndHash() {
        reject { release(metadata.replace("\"versionCode\":4", "\"versionCode\":0")) }
        reject { release(metadata.replace("\"versionCode\":4", "\"versionCode\":\"4\"")) }
        reject { release(metadata.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        reject { release(metadata.replace("\"minimumAndroidSdk\":26", "\"minimumAndroidSdk\":4294967322")) }
        reject { release(metadata.replace("a".repeat(64), "bad")) }
        reject { release(metadata.replace("\"versionName\":\"0.1.1\",", "")) }
    }
    @Test fun rejectsUntrustedHostsCredentialsAndHttp() {
        listOf("https://evil.example/malware.apk", "https://github.com.evil.example/x",
            "http://github.com/Nikhilesh-CS/TorX-One/releases/download/v1/a.apk",
            "https://user@github.com/Nikhilesh-CS/TorX-One/releases/download/v1/a.apk",
            "https://github.com/other/project/releases/download/v1/a.apk",
            "https://evil.githubusercontent.com/x", "https://api.github.com/repos/other/project/releases/latest")
            .forEach { assertFalse(it, UpdatePolicy.transportUrl(it)) }
        assertTrue(UpdatePolicy.transportUrl("https://release-assets.githubusercontent.com/github-production-release-asset/x?sig=example"))
    }
    @Test fun rejectsCrossReleaseOrRenamedAssets() {
        reject { UpdatePolicy.metadata(metadata, apk.replace("download/v0.1.1-build4", "download/v0.1.0-build3"), page, 100) }
        reject { UpdatePolicy.metadata(metadata, apk.replace("torxone-v0.1.1-build4.apk", "other.apk"), page, 100) }
    }
    @Test fun hashRejectsModifiedBytes() {
        val original = byteArrayOf(1, 2, 3)
        val expected = UpdatePolicy.sha256(original)
        assertTrue(UpdatePolicy.hashMatches(expected, UpdatePolicy.sha256(original)))
        assertFalse(UpdatePolicy.hashMatches(expected, UpdatePolicy.sha256(byteArrayOf(1, 2, 4))))
    }
    @Test fun rejectsWrongPackageWrongSignerAndDowngrade() {
        val r = release(); val signer = setOf("certificate")
        fun verify(pkg: String, code: Long, installed: Long, cert: Set<String>) =
            UpdatePolicy.verifyIdentity(pkg, "com.torxone.app", code, r.versionName, installed, cert, signer, r)
        verify("com.torxone.app", 4, 3, signer)
        listOf<() -> Unit>({ verify("evil.app", 4, 3, signer) }, { verify("com.torxone.app", 4, 4, signer) },
            { verify("com.torxone.app", 4, 3, setOf("different")) }).forEach {
            try { it(); fail("Must reject") } catch (_: UpdateFailure) {}
        }
    }
    @Test fun cacheCooldownAndClockChangesAreBounded() {
        assertFalse(UpdatePolicy.due(100, 50, 0))
        assertTrue(UpdatePolicy.due(UpdatePolicy.CHECK_INTERVAL + 50, 50, 0))
        assertFalse(UpdatePolicy.due(100, 0, 101))
        assertTrue(UpdatePolicy.due(50, 100, 0))
    }
}
