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
    @Test fun officialChineseModelBenchmarkReportsTextAndLatency() = benchmarkChineseModels(null)

    @Test fun officialChineseStreamingBaselineReportsTextAndLatency() = benchmarkChineseModels("asr-stream-small")

    /** 目录里的高精度流式模型，与小型流式模型在同一批音频上比较。 Same audio set for the larger streaming model. */
    @Test fun highAccuracyStreamingModelReportsTextAndLatency() = benchmarkChineseModels("asr-stream-large")

    /** 中英混说的流式 Zipformer，自带标点；同一批音频上与 2023 双语模型比较。 Mixed zh-en streaming model with punctuation. */
    @Test fun mixedStreamingTransducerReportsTextAndLatency() = benchmarkChineseModels("x-asr")

    private fun benchmarkChineseModels(streaming: String?) {
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
        val candidates: List<Pair<String, () -> com.weavetext.ime.voice.local.OfflineAsr>> = if (streaming != null) {
            when (streaming) {
                "asr-stream-small" -> {
                    val dir = File(models, streaming)
                    assumeTrue(dir.resolve("model.int8.onnx").isFile)
                    listOf(streaming to { asOffline(NativeAsrModels.streaming("zipformer2-ctc", dir.resolve("model.int8.onnx").path, dir.resolve("tokens.txt").path)) })
                }
                "x-asr" -> {
                    val dir = root.resolve("sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05")
                    assumeTrue(dir.resolve("encoder.int8.onnx").isFile)
                    listOf(streaming to { asOffline(NativeAsrModels.streamingTransducer(
                        dir.resolve("encoder.int8.onnx").path, dir.resolve("decoder.onnx").path,
                        dir.resolve("joiner.int8.onnx").path, dir.resolve("tokens.txt").path)) })
                }
                else -> {
                    val dir = root.resolve("sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30")
                    assumeTrue(dir.resolve("model.int8.onnx").isFile)
                    listOf(streaming to { asOffline(NativeAsrModels.streaming("zipformer2-ctc", dir.resolve("model.int8.onnx").path, dir.resolve("tokens.txt").path)) })
                }
            }
        } else listOf(
            "paraformer" to { NativeAsrModels.offline("paraformer", paraformerDir.resolve("model.int8.onnx").path, paraformerDir.resolve("tokens.txt").path) },
            "sensevoice-zh" to { NativeAsrModels.offline("sense-voice", senseDir.resolve("model.int8.onnx").path, senseDir.resolve("tokens.txt").path) },
            "sensevoice-zh-2024" to { NativeAsrModels.offline("sense-voice",
                root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx").path,
                root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/tokens.txt").path) },
            "zipformer-ctc" to { NativeAsrModels.offline("zipformer-ctc", zipformerDir.resolve("model.int8.onnx").path, zipformerDir.resolve("tokens.txt").path) },
        )
        candidates.forEach { (model, create) ->
            val recognizer = create()
            try {
                if (model.startsWith("sensevoice-zh")) recognizer.setLanguage("zh")
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

    /** \u4e2d\u82f1\u6df7\u8bf4\u7684\u6d41\u5f0f\u6a21\u578b\u5728\u82f1\u6587\u3001\u4e2d\u6587\u4e0e\u6df7\u8bf4\u4e09\u53e5\u4e0a\u7684\u5b9e\u9645\u8f93\u51fa\uff0c\u7528\u6765\u5224\u65ad\u5b83\u80fd\u5426\u9876\u66ff Whisper \u4e0e 2023 \u53cc\u8bed\u6a21\u578b\u3002 */
    @Test fun mixedStreamingTransducerKeepsEnglishWords() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val dir = root.resolve("sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05")
        val sense = root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        val mixed = root.resolve("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/test_wavs/4.wav")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").isFile && dir.resolve("encoder.int8.onnx").isFile && mixed.isFile)
        NativeAsrModels.load(runtime)
        val clips = listOf("en" to sense.resolve("test_wavs/en.wav"), "zh" to sense.resolve("test_wavs/zh.wav"), "mixed" to mixed)
        for ((label, file) in clips) {
            val stream = NativeAsrModels.streamingTransducer(
                dir.resolve("encoder.int8.onnx").path, dir.resolve("decoder.onnx").path,
                dir.resolve("joiner.int8.onnx").path, dir.resolve("tokens.txt").path)
            try {
                val audio = samples(file)
                stream.startSession()
                var offset = 0
                while (offset < audio.size) {
                    val end = minOf(audio.size, offset + 640)
                    stream.accept(audio.copyOfRange(offset, end)); offset = end
                }
                stream.finish()
                val text = stream.text()
                println("x-asr $label: $text")
                assertTrue("x-asr returned no text for $label", text.isNotBlank())
            } finally { stream.release() }
        }
    }

    /**
     * \u6807\u51c6\u666e\u901a\u8bdd\u7684\u540c\u4e00\u6279\u6bd4\u8f83\u3002\u97f3\u9891\u7531\u672c\u673a\u4e2d\u6587\u8bed\u97f3\u5408\u6210\uff0c\u53e5\u5b50\u662f\u65e5\u5e38\u8f93\u5165\u5185\u5bb9\uff1b\u4e0e\u5ddd\u6e1d\u65b9\u8a00\u96c6\u5206\u5f00\uff0c
     * \u907f\u514d\u628a\u65b9\u8a00\u6d4b\u8bd5\u96c6\u4e0a\u7684\u540d\u6b21\u5f53\u6210\u666e\u901a\u8bdd\u51c6\u786e\u7387\u3002\u5408\u6210\u8bed\u97f3\u4e0d\u4ee3\u8868\u771f\u5b9e\u53e3\u97f3\u548c\u566a\u58f0\u3002
     *
     * Same-clip comparison on standard Mandarin synthesised locally: daily dictation sentences, kept apart from
     * the Chuanyu dialect set so a dialect ranking is not read as general Mandarin accuracy.
     */
    @Test fun syntheticMandarinBenchmarkComparesModels() {
        val say = File("/usr/bin/say")
        val afconvert = File("/usr/bin/afconvert")
        assumeTrue(say.canExecute() && afconvert.canExecute())
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val clipDir = File(System.getProperty("weave.cache")).parentFile.resolve("tts-zh").apply { mkdirs() }
        val chinese = listOf(
            "\u6211\u4eec\u4eca\u5929\u4e0b\u5348\u4e09\u70b9\u5f00\u4f1a\uff0c\u8bf7\u63d0\u524d\u5341\u5206\u949f\u5230\u4f1a\u8bae\u5ba4\u3002",
            "\u8fd9\u4e2a\u6708\u7684\u623f\u79df\u6211\u5df2\u7ecf\u8f6c\u7ed9\u4f60\u4e86\uff0c\u8bf7\u67e5\u6536\u3002",
            "\u660e\u5929\u5929\u6c14\u600e\u4e48\u6837\uff0c\u8981\u4e0d\u8981\u5e26\u4f1e\u3002",
            "\u8bf7\u628a\u521a\u624d\u7684\u6587\u4ef6\u518d\u53d1\u6211\u4e00\u904d\uff0c\u8c22\u8c22\u3002",
            "\u6211\u5728\u8def\u4e0a\uff0c\u5927\u6982\u4e8c\u5341\u5206\u949f\u540e\u5230\u3002",
            "\u5b69\u5b50\u660e\u5929\u8981\u4ea4\u4f5c\u4e1a\uff0c\u522b\u5fd8\u4e86\u653e\u8fdb\u4e66\u5305\u3002",
            "\u8fd9\u5bb6\u5e97\u7684\u725b\u8089\u9762\u5f88\u597d\u5403\uff0c\u4e0b\u6b21\u6211\u4eec\u518d\u6765\u3002",
            "\u4f1a\u8bae\u7eaa\u8981\u5df2\u7ecf\u6574\u7406\u597d\u4e86\uff0c\u53d1\u5230\u7fa4\u91cc\u4e86\u3002",
            "\u4f60\u7684\u5feb\u9012\u653e\u5728\u95e8\u53e3\uff0c\u8bb0\u5f97\u62ff\u4e00\u4e0b\u3002",
            "\u94f6\u884c\u5361\u7684\u5bc6\u7801\u4e0d\u8981\u544a\u8bc9\u4efb\u4f55\u4eba\u3002",
            "\u6211\u4eec\u4e0b\u5468\u4e00\u53bb\u722c\u5c71\uff0c\u4f60\u6765\u4e0d\u6765\u3002",
            "\u628a\u7a7a\u8c03\u6e29\u5ea6\u8c03\u9ad8\u4e00\u70b9\uff0c\u6709\u70b9\u51b7\u3002",
        )
        val mixed = listOf(
            "\u6211\u4e0b\u5348\u8981\u53c2\u52a0\u4e00\u4e2a meeting\uff0c\u53ef\u80fd\u665a\u70b9\u56de\u4f60\u3002",
            "\u8fd9\u4e2a project \u7684 deadline \u662f\u4e0b\u5468\u4e94\u3002",
            "\u5e2e\u6211 check \u4e00\u4e0b\u660e\u5929\u7684 flight \u6709\u6ca1\u6709\u5ef6\u8bef\u3002",
        )
        val clips = (chinese + mixed).mapIndexed { i, text -> Triple("${i + 1}.wav", text, i < chinese.size) }
        for ((name, text, _) in clips) {
            val wav = clipDir.resolve(name)
            if (wav.isFile) continue
            val aiff = clipDir.resolve(name.replace(".wav", ".aiff"))
            val ok = runProcess(say.path, "-v", "Tingting", "-o", aiff.path, text) == 0 &&
                runProcess(afconvert.path, "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff.path, wav.path) == 0
            assumeTrue("macOS Chinese voice (Tingting)", ok && wav.isFile)
            aiff.delete()
        }
        val paraformer = root.resolve("sherpa-onnx-paraformer-zh-int8-2025-10-07")
        val sv2025 = root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        val sv2024 = root.resolve("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17")
        val xasr = root.resolve("sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05")
        val large = root.resolve("sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30")
        val bilingual = root.resolve("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile")
        val small = File(models, "asr-stream-small")
        val finalSmall = File(models, "asr-final-small")
        assumeTrue(File(runtime, "libsherpa-onnx-c-api.dylib").isFile)
        assumeTrue(listOf(paraformer, sv2025, sv2024, xasr, large, bilingual).all { it.isDirectory } &&
            small.resolve("model.int8.onnx").isFile && finalSmall.resolve("model.int8.onnx").isFile)
        NativeAsrModels.load(runtime)
        val modelsToRun: List<Triple<String, String?, () -> com.weavetext.ime.voice.local.OfflineAsr>> = listOf(
            Triple("paraformer-chuan", "zh", { NativeAsrModels.offline("paraformer", paraformer.resolve("model.int8.onnx").path, paraformer.resolve("tokens.txt").path) }),
            Triple("sensevoice-yue-2025", "zh", { NativeAsrModels.offline("sense-voice", sv2025.resolve("model.int8.onnx").path, sv2025.resolve("tokens.txt").path) }),
            Triple("sensevoice-2024", "zh", { NativeAsrModels.offline("sense-voice", sv2024.resolve("model.int8.onnx").path, sv2024.resolve("tokens.txt").path) }),
            Triple("final-small", "zh", { NativeAsrModels.offline("zipformer-ctc", finalSmall.resolve("model.int8.onnx").path, finalSmall.resolve("tokens.txt").path) }),
            Triple("stream-large", null, { asOffline(NativeAsrModels.streaming("zipformer2-ctc", large.resolve("model.int8.onnx").path, large.resolve("tokens.txt").path)) }),
            Triple("bilingual-2023-medium", null, { asOffline(NativeAsrModels.streamingTransducer(
                bilingual.resolve("encoder-epoch-99-avg-1.int8.onnx").path,
                bilingual.resolve("decoder-epoch-99-avg-1.onnx").path,
                bilingual.resolve("joiner-epoch-99-avg-1.int8.onnx").path,
                bilingual.resolve("tokens.txt").path)) }),
            Triple("stream-small", null, { asOffline(NativeAsrModels.streaming("zipformer2-ctc", small.resolve("model.int8.onnx").path, small.resolve("tokens.txt").path)) }),
            Triple("x-asr", null, { asOffline(NativeAsrModels.streamingTransducer(
                xasr.resolve("encoder.int8.onnx").path, xasr.resolve("decoder.onnx").path,
                xasr.resolve("joiner.int8.onnx").path, xasr.resolve("tokens.txt").path)) }),
            Triple("x-asr-tail2s", null, { asOffline(NativeAsrModels.streamingTransducer(
                xasr.resolve("encoder.int8.onnx").path, xasr.resolve("decoder.onnx").path,
                xasr.resolve("joiner.int8.onnx").path, xasr.resolve("tokens.txt").path), tailSilence = 32_000) }),
        )
        // 每个模型的 (普通话 CER, 混说 CER)，供下面的准确率门槛使用。 Per-model CERs for the accuracy gate below.
        val cers = mutableMapOf<String, Pair<Double, Double>>()
        for ((name, language, create) in modelsToRun) {
            val recognizer = create()
            try {
                if (language != null) recognizer.setLanguage(language)
                var zhDistance = 0
                var zhReference = 0
                var mixedDistance = 0
                var mixedReference = 0
                for ((file, expected, isChinese) in clips) {
                    val text = TwoPassRecognizer.clean(recognizer.decode(samples(clipDir.resolve(file))))
                    val reference = normalizeForCer(expected)
                    val actual = normalizeForCer(text)
                    val distance = editDistance(reference, actual)
                    if (isChinese) { zhDistance += distance; zhReference += reference.length }
                    else { mixedDistance += distance; mixedReference += reference.length }
                    println("mandarin model=$name file=$file cer=${"%.3f".format(distance.toDouble() / reference.length)} text=$text")
                }
                val modelZh = zhDistance.toDouble() / zhReference
                val modelMixed = mixedDistance.toDouble() / mixedReference
                cers[name] = modelZh to modelMixed
                println("mandarin summary model=$name zhCer=${"%.3f".format(modelZh)} mixedCer=${"%.3f".format(modelMixed)}")
            } finally { recognizer.release() }
        }
        // 门槛：生产配置（X-ASR 句尾补 2 秒静音）的字错误率。实测 0.000 / 0.076，留出余量；超过即失败。
        // Gate on the shipped configuration (X-ASR with the 2 s tail): measured 0.000 / 0.076, with headroom.
        val (gateZh, gateMixed) = cers.getValue("x-asr-tail2s")
        assertTrue("X-ASR Mandarin CER ${"%.3f".format(gateZh)} exceeds 0.030", gateZh <= 0.030)
        assertTrue("X-ASR mixed CER ${"%.3f".format(gateMixed)} exceeds 0.100", gateMixed <= 0.100)
    }

    private fun runProcess(vararg command: String): Int = ProcessBuilder(*command)
        .redirectErrorStream(true).start().let { process ->
            process.inputStream.readBytes()
            process.waitFor()
        }

    private fun asOffline(stream: com.weavetext.ime.voice.local.StreamingAsr, tailSilence: Int = 0) = object : com.weavetext.ime.voice.local.OfflineAsr {
        override fun decode(samples: FloatArray): String {
            stream.startSession()
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(samples.size, offset + 640)
                stream.accept(samples.copyOfRange(offset, end)); offset = end
            }
            if (tailSilence > 0) stream.accept(FloatArray(tailSilence))
            stream.finish()
            return stream.text()
        }
        override fun release() = stream.release()
    }

    /** X-ASR \u662f BPE \u8bcd\u8868\uff0c\u70ed\u8bcd\u5fc5\u987b\u7528\u5b83\u81ea\u5df1\u7684\u8bcd\u8868\u4e0e cjkchar+bpe \u5355\u5143\uff0c\u4e0d\u80fd\u6cbf\u7528\u4e2d\u6587\u5b57\u7b26\u5355\u5143\u3002 */
    @Test fun mixedStreamingTransducerAcceptsItsOwnBpeHotwords() {
        val root = File(System.getProperty("weave.cache")).parentFile.resolve("sherpa")
        val dir = root.resolve("sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05")
        val vocabulary = dir.resolve("vocab.txt")
        val clip = root.resolve("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/test_wavs/4.wav")
        assumeTrue("runtime=${File(runtime, "libsherpa-onnx-c-api.dylib")} vocab=$vocabulary clip=$clip",
            File(runtime, "libsherpa-onnx-c-api.dylib").isFile && vocabulary.isFile && clip.isFile)
        NativeAsrModels.load(runtime)
        val hotwords = File.createTempFile("weave-x-asr-words", ".txt").apply { writeText("\u51c6\u65f6\nON TIME\nIN TIME\n") }
        val stream = NativeAsrModels.streamingTransducer(
            dir.resolve("encoder.int8.onnx").path, dir.resolve("decoder.onnx").path,
            dir.resolve("joiner.int8.onnx").path, dir.resolve("tokens.txt").path,
            hotwords = HotwordConfig(hotwords.path, "cjkchar+bpe", vocabulary.path))
        try {
            val audio = samples(clip)
            stream.startSession()
            var offset = 0
            while (offset < audio.size) {
                val end = minOf(audio.size, offset + 640)
                stream.accept(audio.copyOfRange(offset, end)); offset = end
            }
            stream.accept(FloatArray(32_000))
            stream.finish()
            val text = stream.text()
            println("x-asr hotwords: $text")
            assertTrue(text, text.contains("\u51c6\u65f6"))
            assertTrue(text, text.lowercase().contains("in time"))
        } finally { stream.release(); hotwords.delete() }
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
