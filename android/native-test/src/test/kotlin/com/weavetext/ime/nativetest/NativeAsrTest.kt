package com.weavetext.ime.nativetest

import com.weavetext.ime.voice.local.NativeAsr
import com.weavetext.ime.voice.local.NativeAsrModels
import com.weavetext.ime.voice.local.HotwordConfig
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

    /**
     * Same audio set, same timing and character error-rate calculation for the installed final models.
     * The transcripts are the ground truth published with Sherpa's Paraformer model card; this is a
     * reproducible slice of its Sichuan/Chuanyu test set, not a claim about overall accuracy.
     */
    @Test fun officialChineseModelBenchmarkReportsTextAndLatency() = benchmarkChineseModels(false)

    @Test fun officialChineseStreamingBaselineReportsTextAndLatency() = benchmarkChineseModels(true)

    private fun benchmarkChineseModels(streamingOnly: Boolean) {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val paraformerDir = root.resolve("sherpa-onnx-paraformer-zh-int8-2025-10-07")
        val senseDir = root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        val zipformerDir = File(models, "asr-final-small")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").isFile)
        assumeTrue(paraformerDir.resolve("model.int8.onnx").isFile && senseDir.resolve("model.int8.onnx").isFile)
        assumeTrue(zipformerDir.resolve("model.int8.onnx").isFile)
        NativeAsrModels.load(runtime)

        val truth = linkedMapOf(
            "1.wav" to "来哥哥再给你唱首歌好儿哎呦把伴奏给我放起来放就放嘛还要躲人家钩子",
            "2.wav" to "对不起只有二娃才能让我真正体会作为女人的快乐",
            "3.wav" to "我想去逛街欢迎进入直播间晚上好那我的名字是怎么说的呢",
            "4.wav" to "梦见的就是你不行啊有四川话根本唱不起来根本唱不起来呀",
            "5.wav" to "就临走那天挑了个飘了一下嗨呀弟弟灵魂儿就飞上九霄云就飘着一下魂都飞了对不对",
            "6.wav" to "是不是给人感觉后头是青花亮色的然后说话是很平和的眼神是不慌乱的不散的",
            "7.wav" to "他坐在椅子上挺直起腰杆脸上展现出灿烂的笑容",
            "8.wav" to "唤起路由无限的感慨使他更加痛恨官场的欺诈污浊",
            "9.wav" to "看面貌约五十左右却自称活了两百多岁在清顺治时出家当过和尚还有杜蝶为证",
            "10.wav" to "其言曰士大夫以其见闻之广反各有所偏自有负担杀者有负良骑者",
            "11.wav" to "据说有网友坐飞机的时候呢广播全程播报",
            "12.wav" to "将溃疡两周以上都应该及时就医据了解啊小云平时呢都喜欢吃比较烫的饭菜也喜欢吃麻辣烫火锅之类的高温食物",
            "13.wav" to "绝佳好位置好像我被看到了就问你敢不敢进来吧你一套带走猪脚亮",
            "14.wav" to "两岸猿声啼不住有家难回车里住",
            "15.wav" to "杨大人一律就退还会再要求以关注货币来补助这个差额天宝年间杨胜坚转任",
            "16.wav" to "做钱的速度还快这真的是一个经济爆发式增长的时代",
        )
        val audioDir = paraformerDir.resolve("test_wavs")
        val candidates: List<Pair<String, () -> com.weavetext.ime.voice.local.OfflineAsr>> = if (streamingOnly) {
            val dir = File(models, "asr-stream-small")
            assumeTrue(dir.resolve("model.int8.onnx").isFile)
            listOf("stream-small" to {
                val stream = NativeAsrModels.streaming("zipformer2-ctc", dir.resolve("model.int8.onnx").path, dir.resolve("tokens.txt").path)
                object : com.weavetext.ime.voice.local.OfflineAsr {
                    override fun decode(samples: FloatArray): String {
                        stream.startSession()
                        var offset = 0
                        while (offset < samples.size) {
                            val end = minOf(samples.size, offset + 640)
                            stream.accept(samples.copyOfRange(offset, end)); offset = end
                        }
                        stream.finish()
                        return stream.text()
                    }
                    override fun release() = stream.release()
                }
            })
        } else listOf(
            "paraformer" to { NativeAsrModels.offline("paraformer", paraformerDir.resolve("model.int8.onnx").path, paraformerDir.resolve("tokens.txt").path) },
            "sensevoice-zh" to { NativeAsrModels.offline("sense-voice", senseDir.resolve("model.int8.onnx").path, senseDir.resolve("tokens.txt").path) },
            "zipformer-ctc" to { NativeAsrModels.offline("zipformer-ctc", zipformerDir.resolve("model.int8.onnx").path, zipformerDir.resolve("tokens.txt").path) },
        )
        candidates.forEach { (model, create) ->
            val recognizer = create()
            try {
                if (model == "sensevoice-zh") recognizer.setLanguage("zh")
                var errors = 0
                var totalReference = 0
                var totalDistance = 0
                truth.forEach { (name, expected) ->
                    val audio = samples(audioDir.resolve(name))
                    val start = System.nanoTime()
                    val text = TwoPassRecognizer.clean(recognizer.decode(audio))
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val reference = normalizeForCer(expected)
                    val actual = normalizeForCer(text)
                    val distance = editDistance(reference, actual)
                    totalReference += reference.length
                    totalDistance += distance
                    if (distance != 0) errors++
                    println("benchmark model=$model audio=$name durationMs=${audio.size * 1000 / 16000} elapsedMs=$elapsedMs cer=${"%.3f".format(distance.toDouble() / reference.length.coerceAtLeast(1))} text=$text")
                    assertTrue("$model returned no text for $name", text.isNotEmpty())
                }
                println("benchmark summary model=$model files=${truth.size} filesWithErrors=$errors cer=${"%.3f".format(totalDistance.toDouble() / totalReference)}")
            } finally {
                recognizer.release()
            }
        }
    }

    private fun normalizeForCer(text: String): String = text.filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }

    private fun editDistance(expected: String, actual: String): Int {
        var previous = IntArray(actual.length + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(actual.length + 1)
            current[0] = i + 1
            for (j in actual.indices) {
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    previous[j] + if (expected[i] == actual[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous.last()
    }

    @Test fun whisperLanguageAndMixedAccuracyComparison() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val sense = root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        val mixed = root.resolve("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/test_wavs/4.wav")
        assumeTrue(File(runtime,"libsherpa-onnx-c-api.dylib").isFile)
        NativeAsrModels.load(runtime)
        for (size in listOf("base", "small")) {
            val dir = root.resolve("sherpa-onnx-whisper-$size")
            assumeTrue(dir.resolve("$size-encoder.int8.onnx").isFile)
            val paths = org.json.JSONArray(listOf("$dir/$size-encoder.int8.onnx", "$dir/$size-decoder.int8.onnx")).toString()
            val rec = NativeAsrModels.offline("whisper", paths, "$dir/$size-tokens.txt")
            try {
                for ((language,wav) in listOf("en" to sense.resolve("test_wavs/en.wav"),"zh" to sense.resolve("test_wavs/zh.wav"),"auto" to mixed)) {
                    rec.setLanguage(language)
                    val text=TwoPassRecognizer.clean(rec.decode(samples(wav)))
                    println("whisper $size $language: $text")
                    if (language=="en") assertTrue(text,text.lowercase().contains("boy") && text.lowercase().contains("gold"))
                    else assertTrue(text,text.any {it in '\u4e00'..'\u9fff'})
                    if (language=="auto") println("mixed English retained: ${text.lowercase().contains("on time")} / ${text.lowercase().contains("in time")}")
                }
            } finally {rec.release()}
        }
    }

    @Test fun bilingualShortUtterancesCompareGreedyAndBeamWithoutPrimingAudio() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val dir = root.resolve("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile")
        val fixture = dir.resolve("test_wavs/4.wav")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").isFile && fixture.isFile)
        NativeAsrModels.load(runtime)
        val audio = samples(fixture).copyOf(4 * 16000)
        for (beam in listOf(0, 4)) {
            val rec = NativeAsrModels.streamingTransducer("$dir/encoder-epoch-99-avg-1.int8.onnx", "$dir/decoder-epoch-99-avg-1.onnx", "$dir/joiner-epoch-99-avg-1.int8.onnx", "$dir/tokens.txt", beamPaths = beam)
            try {
                rec.startSession()
                for (i in audio.indices step 640) rec.accept(audio.copyOfRange(i,minOf(i+640,audio.size)))
                rec.finish()
                println("short beam=$beam: ${rec.text()}")
                assertTrue(rec.text(), rec.text().lowercase().contains("in time"))
            } finally { rec.release() }
        }
    }

    @Test fun personalChineseAndEnglishHotwordsDecodeUsingTheOriginalTokenizer() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val dir=root.resolve("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile")
        assumeTrue(File(runtime,"libsherpa-onnx-c-api.dylib").isFile && dir.resolve("encoder-epoch-99-avg-1.int8.onnx").isFile)
        val vocabulary=File(System.getProperty("weave.data")).parentFile.parentFile.resolve("android/app/src/main/assets/models/vocab-mixed-standard.txt")
        assumeTrue(vocabulary.isFile)
        NativeAsrModels.load(runtime)
        val hotwords=File.createTempFile("weave-voice-words",".txt").apply {writeText("准时\nON TIME\nIN TIME\n")}
        val stream=NativeAsrModels.streamingTransducer("$dir/encoder-epoch-99-avg-1.int8.onnx","$dir/decoder-epoch-99-avg-1.onnx","$dir/joiner-epoch-99-avg-1.int8.onnx","$dir/tokens.txt",hotwords=HotwordConfig(hotwords.path,"cjkchar+bpe",vocabulary.path))
        val final=mutableListOf<String>()
        val recognizer=TwoPassRecognizer(stream,null,null,object:TwoPassListener {
            override fun onPartial(text:String){}
            override fun onFinal(text:String){final+=text}
        })
        try {
            val pcm=samples(dir.resolve("test_wavs/4.wav"))
            repeat(2) { recording ->
                if(recording==1) hotwords.writeText("准时\nON TIME\nIN TIME\n今天\n")
                final.clear()
                stream.startSession()
                for(i in pcm.indices step 640)recognizer.feed(pcm.copyOfRange(i,minOf(i+640,pcm.size)))
                recognizer.finish()
                val text=final.joinToString(" ");println("personal hotwords recording $recording: $text")
                assertTrue(text,text.contains("准时"));assertTrue(text,text.lowercase().contains("in time"));assertTrue(text,text.lowercase().contains("on time"))
            }
        } finally {recognizer.release();hotwords.delete()}
    }

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
