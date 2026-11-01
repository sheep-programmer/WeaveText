package com.weavetext.ime.translate

import com.weavetext.ime.voice.VoiceUpgrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineTranslationPluginDownloadTest {
    private val sha = "a".repeat(64)

    /** 与 GitHub 发布接口一致的最小 JSON：资产名、摘要、大小。 Minimal release JSON with name, digest and size. */
    private fun release(tag: String, asset: String) = """
        {"tag_name":"$tag","assets":[
          {"name":"WeaveText-other.apk","digest":"sha256:${"b".repeat(64)}","size":1},
          {"name":"$asset","digest":"sha256:$sha","size":21002304}
        ]}
    """.trimIndent()

    @Test fun assetNameAndVersionMatchThePluginBuild() {
        // 插件的 versionName 必须与 OfflineTranslationPluginDownload.PLUGIN_VERSION 一致，否则下载的文件名对不上。
        val gradle = listOf(File("../translation-google/build.gradle.kts"), File("../../translation-google/build.gradle.kts"))
            .first { it.exists() }.readText()
        val versionName = Regex("""versionName\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
        assertEquals(versionName, OfflineTranslationPluginDownload.PLUGIN_VERSION)
        assertEquals("WeaveText-Google-Offline-Translation-$versionName.apk", OfflineTranslationPluginDownload.ASSET)
    }

    @Test fun digestAndSizeComeFromTheSameVersionRelease() {
        val asset = OfflineTranslationPluginDownload.ASSET
        val found = VoiceUpgrade.assetInfo(emptyList(), AtomicBoolean(false), releaseTag = "v0.1.0-beta.29", asset = asset,
            read = { release("v0.1.0-beta.29", asset) })
        assertNotNull(found)
        assertEquals(sha, found!!.first)
        assertEquals(21002304L, found.second)
    }

    @Test fun aReleaseWithADifferentTagIsNotUsed() {
        val asset = OfflineTranslationPluginDownload.ASSET
        val found = VoiceUpgrade.assetInfo(emptyList(), AtomicBoolean(false), releaseTag = "v0.1.0-beta.30", asset = asset,
            read = { release("v0.1.0-beta.29", asset) })
        assertNull("不能用别的版本的插件", found)
    }
}
