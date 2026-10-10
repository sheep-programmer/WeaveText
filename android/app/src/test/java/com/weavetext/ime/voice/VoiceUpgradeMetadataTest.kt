package com.weavetext.ime.voice

import com.weavetext.ime.models.Mirror
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceUpgradeMetadataTest {
    private val sha = "abcdef12".repeat(8)
    private val tag = "v0.1.0-beta.21"
    private val asset = "WeaveText-0.1.0-beta.21-arm64-voice.apk"
    private fun release(name: String = asset, digest: String = sha, releaseTag: String = tag) =
        """{"tag_name":"$releaseTag","assets":[{"name":"$name","digest":"sha256:$digest","size":12345}]}"""

    @Test fun exactReleaseAssetProvidesTheDigestAndSize() {
        val requests = mutableListOf<String>()
        val info = VoiceUpgrade.assetInfo(emptyList(), AtomicBoolean(), tag, asset) {
            requests += it; release()
        }
        assertEquals(sha to 12345L, info)
        assertEquals(1, requests.size)
    }

    @Test fun wrongReleaseOrAssetCannotSupplyTheDigest() {
        for (json in listOf(release(releaseTag = "v0.1.0"), release(name = "$asset.other"))) {
            val info = VoiceUpgrade.assetInfo(emptyList(), AtomicBoolean(), tag, asset) {
                if (it.contains("api.github.com")) json else "$sha *$asset\n"
            }
            assertEquals(sha to 0L, info)
        }
    }

    @Test fun invalidDigestFallsBackToAnExactChecksumFilename() {
        val info = VoiceUpgrade.assetInfo(emptyList(), AtomicBoolean(), tag, asset) {
            if (it.contains("api.github.com")) release(digest = "z".repeat(64))
            else "${"1".repeat(64)} $asset.backup\n$sha $asset\n"
        }
        assertEquals(sha to 0L, info)
    }

    @Test fun htmlAndUnavailableApiContinueToConfiguredSources() {
        val mirror = Mirror("fallback", "备用", "https://mirror.example/{url}")
        val info = VoiceUpgrade.assetInfo(listOf(mirror), AtomicBoolean(), tag, asset) {
            if (it.startsWith("https://mirror.example/")) release() else "<html>Unavailable</html>"
        }
        assertEquals(sha to 12345L, info)
    }

    @Test fun cancellationIsNotSwallowedAsAnUnavailableSource() {
        val cancel = AtomicBoolean()
        var requests = 0
        try {
            VoiceUpgrade.assetInfo(listOf(Mirror("fallback", "备用", "https://mirror.example/{url}")), cancel, tag, asset) {
                requests++; cancel.set(true); throw IOException("cancelled")
            }
            fail("Cancellation must abort metadata discovery")
        } catch (_: IOException) { }
        assertEquals(1, requests)
    }
}
