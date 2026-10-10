package com.weavetext.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.weavetext.ime.models.Mirror
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpgradeTest {
    @Test fun ordersReleasesAndPrereleasesSemantically() {
        assertTrue(AppUpgrade.compareVersions("v0.1.0-beta.17", "0.1.0-beta.16") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0-beta.17", "0.1.0-beta.1") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0-beta.10", "0.1.0-beta.9") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0", "0.1.0-beta.18") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0-rc.1", "0.1.0-beta.18") > 0)
        assertTrue(AppUpgrade.compareVersions("0.2.0", "0.1.9") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.10", "0.2.0") < 0)
        assertEquals(0, AppUpgrade.compareVersions("v0.1.0-beta.18", "0.1.0-beta.18"))
        assertTrue(AppUpgrade.compareVersions("0.1.0-beta.17", "0.1.0-beta.18") < 0)
    }

    @Test fun metadataFallbackSkipsHtmlAndPackTagsAndUsesSemanticVersionOrder() {
        val seen = mutableListOf<String>()
        val mirrors = listOf(Mirror("html", "html", "https://html.invalid/{url}"), Mirror("working", "working", "https://working.invalid/{url}"))
        val release = AppUpgrade.latest(mirrors, AtomicBoolean(false)) { url ->
            seen += url
            when {
                url.startsWith("https://api.github.com") -> throw IOException("HTTP 403")
                url.startsWith("https://html.invalid") -> "<html>mirror landing page</html>"
                else -> """[{"tag_name":"dictpacks-v99"},{"tag_name":"asr-runtime-v1.13.8"},{"tag_name":"v0.1.0-beta.9"},{"tag_name":"v0.1.0-beta.20"},{"tag_name":"v9.0.0","draft":true},{"tag_name":"v0.1.0-beta.10"}]"""
            }
        }
        assertEquals(3, seen.size)
        assertEquals("v0.1.0-beta.20", release!!.getString("tag_name"))
    }

    @Test fun cancellationStopsMetadataFallbackRatherThanSwallowingTheCancelledRead() {
        val token = AtomicBoolean(false)
        var requests = 0
        val error = runCatching {
            AppUpgrade.latest(listOf(Mirror("mirror", "mirror", "https://mirror.invalid/{url}")), token) {
                requests++
                token.set(true)
                throw IOException("socket closed")
            }
        }.exceptionOrNull()
        assertEquals(1, requests)
        assertTrue(error is IOException && error.message == "cancelled")
    }

    @Test fun digestBelongsToTheSelectedAssetAndIsNotReusedWhenNextReleaseHasNoDigest() {
        fun release(version: String, digest: String?) = JSONObject().put("tag_name", "v$version").put("assets", org.json.JSONArray().put(
            JSONObject().put("name", "WeaveText-$version-arm64.apk").put("size", 44_452_264).also { if (digest != null) it.put("digest", digest) },
        ))
        val previous = AppUpgrade.availableFrom(release("0.1.0-beta.20", "sha256:" + "A".repeat(64)))
        val next = AppUpgrade.availableFrom(release("0.1.0-beta.21", null))
        assertEquals("a".repeat(64), previous.sha256)
        assertNull(next.sha256)
        assertEquals(44_452_264L, next.size)
        assertNull(AppUpgrade.availableFrom(release("0.1.0-beta.22", "sha256:" + "z".repeat(64))).sha256)
        assertNull(AppUpgrade.availableFrom(release("0.1.0-beta.23", "sha512:" + "a".repeat(64))).sha256)
    }

    @Test fun checksumRequiresHexDigestAndExactFilename() {
        val asset = "WeaveText-0.1.0-beta.21-arm64.apk"
        assertNull(AppUpgrade.checksum("${"a".repeat(64)}  other-$asset", asset))
        assertNull(AppUpgrade.checksum("${"z".repeat(64)}  $asset", asset))
        assertEquals("b".repeat(64), AppUpgrade.checksum("${"B".repeat(64)}  *$asset", asset))
    }

    @Test fun signerMismatchActuallyDeletesTheVerifiedDownload() {
        val dir = Files.createTempDirectory("weave-update-test").toFile()
        try {
            val apk = File(dir, "update.apk").apply { writeText("wrong signer") }
            val error = runCatching { AppUpgrade.requireMatchingSigner(apk) { false } }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(apk.exists())
            apk.writeText("correct signer")
            AppUpgrade.requireMatchingSigner(apk) { true }
            assertTrue(apk.exists())
        } finally { dir.deleteRecursively() }
    }
}
