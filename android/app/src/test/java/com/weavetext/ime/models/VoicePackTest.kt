package com.weavetext.ime.models

import com.weavetext.ime.settings.Route
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.voice.local.NativeAsrModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轻量版的本地识别开关与「语音包」下载流程（假模型仓库，不联网）。
 * Lite gating of local recognition and the voice-pack download flow, over a fake repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePackTest {
    private val stream = "asr-stream-small"
    private fun progress(done: Long, total: Long, bps: Long = 0, mirror: String = "ghfast.top") =
        ModelState.Downloading(Progress(done, total, bps, mirror))

    // ------------------------------------------------------------ catalog per build

    @Test fun voiceBuildHidesRuntimeAndKeepsBuiltins() {
        val c = FakeModels.CATALOG
        assertNull(c.find(AsrRuntime.ID))
        assertTrue(c.find(stream)!!.builtin)
        assertTrue(c.find(stream)!!.description.contains("内置"))
    }

    @Test fun liteListsRuntimeWithFallbackSourcesAndNoBuiltins() {
        val c = FakeModels.LITE
        val rt = c.find(AsrRuntime.ID)!!
        assertEquals(ModelKind.ASR_RUNTIME, rt.kind)
        assertEquals(2, rt.archives.size)
        assertTrue(rt.archives[0].url.contains("sheep-programmer/WeaveText"))
        assertTrue(rt.archives[1].url.contains("k2-fsa/sherpa-onnx"))
        assertEquals(listOf("arm64-v8a/libonnxruntime.so", "arm64-v8a/libsherpa-onnx-c-api.so"), rt.fileNames())
        assertTrue(c.models.none { it.builtin })
        assertFalse(c.find(stream)!!.description.contains("内置"))
        // 旧的单个 archive 字段仍能读。 The legacy single `archive` still parses.
        assertEquals(1, c.find(stream)!!.archives.size)
        assertNull(FakeModels.LITE_X86.find(AsrRuntime.ID))
    }

    // ------------------------------------------------------------ gating

    @Test fun liteNeedsRuntimeAndStreamingModel() {
        val repo = FakeModels(catalog = FakeModels.LITE)
        assertFalse(AsrRuntime.ready(repo, bundled = false))
        assertFalse(AsrRuntime.engineReady(repo, bundled = false))
        repo.emit(stream, ModelState.Installed)
        assertFalse("模型在、运行库不在 / model without runtime", AsrRuntime.engineReady(repo, bundled = false))
        repo.emit(AsrRuntime.ID, ModelState.Installed)
        assertTrue(AsrRuntime.engineReady(repo, bundled = false))
        repo.emit(stream, ModelState.NotInstalled)
        assertTrue(AsrRuntime.ready(repo, bundled = false))
        assertFalse("运行库在、没有实时模型 / runtime without a streaming model", AsrRuntime.engineReady(repo, bundled = false))
    }

    @Test fun bundledRuntimeIsAlwaysReady() {
        val repo = FakeModels()
        assertTrue(AsrRuntime.ready(repo, bundled = true))
        assertTrue(AsrRuntime.engineReady(repo, bundled = true))
    }

    @Test fun modelsDeepLinkFollowsRuntime() {
        assertEquals(listOf(Route.Voice, Route.Models), Route.fromPath("/models", runtimeReady = true))
        assertEquals(listOf(Route.Voice, Route.VoiceUpgrade), Route.fromPath("/models", runtimeReady = false))
    }

    // ------------------------------------------------------------ pack flow

    @Test fun packDownloadsBothPartsWithCombinedProgressThenSelects() {
        val repo = FakeModels(catalog = FakeModels.LITE)
        val pack = VoicePack(repo)
        assertTrue(pack.supported)
        val rt = repo.catalog.find(AsrRuntime.ID)!!
        val model = repo.catalog.find(stream)!!
        val size = rt.archiveSize + model.archiveSize
        assertEquals(VoicePack.State.Idle(size), pack.state())
        assertTrue("约 30 MB / about 30 MB", size in 29_000_000L..32_000_000L)

        var selected = 0
        pack.start(allowMetered = false) { selected++ }
        assertEquals(listOf(AsrRuntime.ID to false, stream to false), repo.downloads)

        repo.emit(AsrRuntime.ID, progress(4_000_000, rt.archiveSize, 1_000_000))
        repo.emit(stream, progress(1_000_000, model.archiveSize, 500_000, "gh-proxy.com"))
        val s = pack.state() as VoicePack.State.Downloading
        assertEquals(5_000_000L, s.done)
        assertEquals(size, s.total)
        assertEquals(1_500_000L, s.bytesPerSecond)

        repo.emit(AsrRuntime.ID, ModelState.Installed)
        repo.emit(stream, ModelState.Extracting)
        assertEquals(VoicePack.State.Installing, pack.state())
        assertEquals(0, selected)
        repo.emit(stream, ModelState.Installed)
        assertEquals(VoicePack.State.Ready, pack.state())
        assertEquals(1, selected)
        // 之后的变化不再触发。 Later changes don't fire again.
        repo.emit(stream, ModelState.Installed)
        assertEquals(1, selected)
    }

    @Test fun packOnlyFetchesMissingPartsAndHonoursMetered() {
        val repo = FakeModels(mapOf(AsrRuntime.ID to ModelState.Installed), catalog = FakeModels.LITE)
        val pack = VoicePack(repo)
        assertEquals(VoicePack.State.Idle(repo.catalog.find(stream)!!.archiveSize), pack.state())
        pack.start(allowMetered = true)
        assertEquals(listOf(stream to true), repo.downloads)
    }

    @Test fun packFailureIsReadableAndCancelStopsBoth() {
        val repo = FakeModels(catalog = FakeModels.LITE)
        val pack = VoicePack(repo)
        var selected = 0
        pack.start(allowMetered = false) { selected++ }
        repo.emit(AsrRuntime.ID, ModelState.Failed("ARCHIVE: sherpa-onnx-runtime.tar.bz2: all mirrors failed:"))
        // 另一半还在下载：仍显示进度。 The other part is still going: still downloading.
        assertTrue(pack.state() is VoicePack.State.Downloading)
        repo.emit(stream, ModelState.Failed("当前为移动网络，已按设置暂停下载"))
        val f = pack.state() as VoicePack.State.Failed
        assertTrue(f.message, f.message.startsWith("下载失败") || f.message.startsWith("当前为移动网络"))
        assertEquals("下载失败，请检查网络后重试（可在「离线模型」里换下载源）", VoicePack.friendly("HF: HTTP 404"))
        assertEquals("下载的文件校验失败，请重试", VoicePack.friendly("model.int8.onnx: sha256 mismatch"))
        assertEquals("存储空间不足，需要约 60 MB", VoicePack.friendly("存储空间不足，需要约 60 MB"))

        // 重试后取消：两样都取消，回到未下载。 Retry then cancel: both cancelled, back to idle.
        pack.start(allowMetered = false) { selected++ }
        pack.cancel()
        assertEquals(listOf(AsrRuntime.ID, stream), repo.cancels)
        assertTrue(pack.state() is VoicePack.State.Idle)
        assertEquals(0, selected)
    }

    @Test fun packUnsupportedWithoutRuntimeEntry() {
        assertFalse(VoicePack(FakeModels()).supported)
        assertFalse(VoicePack(FakeModels(catalog = FakeModels.LITE_X86)).supported)
    }

    @Test fun nativeErrorsBecomeReadable() {
        assertTrue(NativeAsrModels.friendly("sherpa-onnx 1.12.0 found, 1.13.8 required").contains("版本不对"))
        assertTrue(NativeAsrModels.friendly("load onnxruntime: dlopen failed").contains("无法载入"))
        assertTrue(NativeAsrModels.friendly("failed to create streaming recognizer").contains("模型无法打开"))
        assertEquals("离线识别出错", NativeAsrModels.friendly(null))
    }
}
