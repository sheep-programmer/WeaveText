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

    @Test fun bilingualStreamingModelsKeepActualWordsAcrossLanguages() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val names = listOf("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile", "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile")
        val fixture = root.resolve(names[0]).resolve("test_wavs/4.wav")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").exists() && names.all { root.resolve(it).resolve("encoder-epoch-99-avg-1.int8.onnx").isFile } && fixture.isFile)
        NativeAsrModels.load(runtime)
        val audio = samples(fixture)
        for (name in names) {
            val dir = root.resolve(name)
            val stream = NativeAsrModels.streamingTransducer("$dir/encoder-epoch-99-avg-1.int8.onnx", "$dir/decoder-epoch-99-avg-1.onnx", "$dir/joiner-epoch-99-avg-1.int8.onnx", "$dir/tokens.txt")
            val finals = mutableListOf<String>()
            val rec = TwoPassRecognizer(stream, null, null, object : TwoPassListener {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) { finals += text }
            })
            try {
                for (i in audio.indices step 640) rec.feed(audio.copyOfRange(i, minOf(i + 640, audio.size)))
                rec.finish()
                val text = finals.joinToString(" ")
                println("bilingual $name: $text")
                assertTrue(text, text.contains("准时"))
                assertTrue(text, text.lowercase().contains("on time") && text.lowercase().contains("in time"))
            } finally { rec.release() }
        }
    }

    @Test fun mixedModelsRecognizeChineseAndEnglishInOneRecording() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val wenet = root.resolve("sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10")
        val sense = root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").exists() && wenet.resolve("model.int8.onnx").isFile && sense.resolve("model.int8.onnx").isFile)
        NativeAsrModels.load(runtime)
        val audio = samples(sense.resolve("test_wavs/zh.wav")) + FloatArray(3200) + samples(sense.resolve("test_wavs/en.wav"))
        for ((arch, dir) in listOf("sense-voice" to sense, "wenet-ctc" to wenet)) {
            val model = NativeAsrModels.offline(arch, "$dir/model.int8.onnx", "$dir/tokens.txt")
            try {
                val text = TwoPassRecognizer.clean(model.decode(audio))
                println("mixed $arch: $text")
                assertTrue("Chinese words survive: $text", text.any { it in '\u4e00'..'\u9fff' })
                assertTrue("English words survive: $text", Regex("[A-Za-z]{3,}").containsMatchIn(text))
            } finally { model.release() }
        }
    }

    @Test fun additionalChineseModelsDecodeRealSpeech() {
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").exists())
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val candidates = listOf(
            "dolphin" to "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02",
            "dolphin" to "sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02",
            "telespeech-ctc" to "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04",
        )
        assumeTrue("Optional downloaded model fixtures", candidates.all { root.resolve(it.second).resolve("model.int8.onnx").isFile } && wav.isFile)
        NativeAsrModels.load(runtime)
        val audio = samples(wav)
        for ((arch, name) in candidates) {
            val dir = root.resolve(name)
            val rec = NativeAsrModels.offline(arch, "$dir/model.int8.onnx", "$dir/tokens.txt")
            try {
                val text = TwoPassRecognizer.clean(rec.decode(audio))
                println("$name: $text")
                assertTrue(text, text.contains("大家") && text.contains("研究"))
            } finally { rec.release() }
        }
    }

    @Test fun neuralDetectorKeepsQuietSpeechAndLongPauses() {
        val vadFile = File(System.getProperty("weave.cache"), "silero_vad_v5.onnx")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").exists() && vadFile.isFile && wav.isFile)
        NativeAsrModels.load(runtime)
        val detector = NativeAsrModels.detector(vadFile.path)
        val dir = File(models, "asr-final-small")
        val offline = NativeAsrModels.offline("zipformer-ctc", "$dir/model.int8.onnx", "$dir/tokens.txt")
        val finals = mutableListOf<String>()
        val rec = TwoPassRecognizer(null, offline, null, object : TwoPassListener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { finals += text }
        }, speechDetector = detector)
        fun feed(audio: FloatArray) { for (i in audio.indices step 640) rec.feed(audio.copyOfRange(i, minOf(i + 640, audio.size))) }
        try {
            feed(FloatArray(16_000 * 4))
            assertTrue("Silence must not hallucinate words", finals.isEmpty())
            val audio = samples(wav)
            feed(audio.map { it * 0.1f }.toFloatArray())
            feed(FloatArray(16_000 * 8))
            val first = finals.joinToString("")
            assertTrue("Quiet first sentence: $first", first.contains("研究"))
            feed(audio)
            rec.finish()
            val all = finals.joinToString("")
            println("quiet speech, 8s pause, continued speech: $all")
            assertTrue(all, all.length > first.length && all.substring(first.length).contains("研究"))
        } finally { rec.release() }
    }

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
            // 只有终稿模型：按音量断句、整句识别（前后各 1 秒静音）。 Final model alone: level endpoints, whole sentence.
            val alone = mutableListOf<String>()
            val one = TwoPassRecognizer(null, it, null, object : TwoPassListener {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) { alone += text }
            })
            (FloatArray(16000) + audio + FloatArray(16000)).toList().chunked(640).forEach { c -> one.feed(c.toFloatArray()) }
            one.finish()
            println("native offline-only: $alone")
            assertTrue(alone.toString(), alone.joinToString("").startsWith("对我做了介绍"))
            it.release()
        }
        // 错误的模型路径：返回可读的错误而不是崩溃。 Bad model path: readable error, no crash.
        val err = runCatching { NativeAsrModels.streaming("zipformer2-ctc", "/nonexistent.onnx", "$dir/tokens.txt") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty(), err is IllegalStateException)
        assertEquals(true, NativeAsr.nativeIsLoaded())
    }
}
