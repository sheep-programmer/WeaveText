package com.weavetext.ime.nativetest

import com.weavetext.ime.voice.local.NativeAsr
import com.weavetext.ime.voice.local.NativeAsrModels
import com.weavetext.ime.voice.local.TwoPassListener
import com.weavetext.ime.voice.local.TwoPassRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 轻量版的识别链路端到端：由 Rust 在运行时载入桌面版运行库（与手机上下载的同一版本），用内置实时模型经两遍识别流程
 * 解码官方测试音频。只在 macOS arm64 上有对应运行库，其它主机跳过。
 *
 * End to end for the lite build's recognition path: Rust loads the desktop build of the same runtime version
 * at run time and the built-in streaming model decodes the upstream test wav through the two-pass flow.
 * Only macOS arm64 has a matching runtime; other hosts skip.
 */
class NativeAsrTest {
    private val runtime = File(System.getProperty("weave.asrRuntime"))
    private val models = File(System.getProperty("weave.models"))
    private val wav = File(System.getProperty("weave.testWavs"), "0.wav")

    private fun samples(f: File): FloatArray {
        val b = f.readBytes()
        var off = 12
        while (!(b[off] == 'd'.code.toByte() && b[off + 1] == 'a'.code.toByte() && b[off + 2] == 't'.code.toByte())) {
            off += 8 + ByteBuffer.wrap(b, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val n = ByteBuffer.wrap(b, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int / 2
        val bb = ByteBuffer.wrap(b, off + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.short / 32768f }
    }

    @Test
    fun missingRuntimeGivesReadableError() {
        if (NativeAsr.nativeIsLoaded()) return
        val err = runCatching { NativeAsrModels.load(File("/nonexistent")) }.exceptionOrNull()
        assertTrue(err?.message.orEmpty(), err?.message.orEmpty().contains("语音包"))
    }

    @Test
    fun decodesTestWavThroughDownloadedRuntime() {
        assumeTrue("macOS arm64 runtime", File(runtime, "libsherpa-onnx-c-api.dylib").exists())
        val dir = File(models, "asr-stream-small")
        assumeTrue(wav.isFile && File(dir, "model.int8.onnx").isFile)
        NativeAsrModels.load(runtime)
        assertTrue(NativeAsr.nativeIsLoaded())

        val streaming = NativeAsrModels.streaming("zipformer2-ctc", "$dir/model.int8.onnx", "$dir/tokens.txt")
        val fDir = File(models, "asr-final-small")
        val offline = if (File(fDir, "model.int8.onnx").isFile) NativeAsrModels.offline("zipformer-ctc", "$fDir/model.int8.onnx", "$fDir/tokens.txt") else null
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val r = TwoPassRecognizer(streaming, null, null, object : TwoPassListener {
            override fun onPartial(text: String) { partials += text }
            override fun onFinal(text: String) { finals += text }
        })
        val audio = samples(wav)
        audio.toList().chunked(640).forEach { r.feed(it.toFloatArray()) }
        r.finish()
        val text = finals.joinToString("")
        println("native streaming: partials=${partials.size} finals=$finals")
        assertTrue(partials.isNotEmpty())
        assertTrue(text, text.startsWith("对我做了介绍"))
        r.release()

        // 终稿模型同样可用（有则测）。 The offline recognizer works too, when present.
        offline?.let {
            val t = TwoPassRecognizer.clean(it.decode(audio))
            println("native offline: $t")
            assertTrue(t, t.startsWith("对我做了介绍"))
            it.release()
        }
        // 错误的模型路径：返回可读的错误而不是崩溃。 Bad model path: readable error, no crash.
        val err = runCatching { NativeAsrModels.streaming("zipformer2-ctc", "/nonexistent.onnx", "$dir/tokens.txt") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty(), err is IllegalStateException)
        assertEquals(true, NativeAsr.nativeIsLoaded())
    }
}
