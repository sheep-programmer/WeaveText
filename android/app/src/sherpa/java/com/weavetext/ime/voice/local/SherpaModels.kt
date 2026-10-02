package com.weavetext.ime.voice.local

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.weavetext.ime.models.ModelLocation
import com.weavetext.ime.models.ModelSpec
import java.io.File

/** sherpa-onnx 适配层（Android 版 Kotlin API）。 Adapters over sherpa-onnx's Android Kotlin API. */
internal object SherpaModels {
    private const val THREADS = 2
    private val FEATURES = FeatureConfig(sampleRate = 16000, featureDim = 80)

    /** 运行库随包，无需载入。 The runtime is bundled; nothing to load. */
    @Suppress("UNUSED_PARAMETER")
    fun prepare(runtimeDir: File?) {}

    fun streaming(spec: ModelSpec, loc: ModelLocation, threads: Int = THREADS, hotwords:HotwordConfig? = null): StreamingAsr {
        require(spec.arch in setOf("zipformer2-ctc", "zipformer-transducer")) { "unsupported streaming arch ${spec.arch}" }
        val model = if (spec.arch == "zipformer-transducer") OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = loc.path(spec.files.first { it.name.startsWith("encoder") }.name),
                decoder = loc.path(spec.files.first { it.name.startsWith("decoder") }.name),
                joiner = loc.path(spec.files.first { it.name.startsWith("joiner") }.name),
            ), tokens = loc.path("tokens.txt"), numThreads = threads, debug = false, modelingUnit=hotwords?.unit.orEmpty(), bpeVocab=hotwords?.vocabulary.orEmpty(),
        ) else OnlineModelConfig(zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = loc.path("model.int8.onnx")), tokens = loc.path("tokens.txt"), numThreads = threads, debug = false)
        val config = OnlineRecognizerConfig(
            featConfig = FEATURES,
            modelConfig = model,
            // 句尾判定：说过话后静音 1.6 秒分句；无说话 4 秒；单句最长 30 秒。
            // Endpoints: 1.6 s silence after speech, 4 s with no speech, 30 s max.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 4f, 0f),
                rule2 = EndpointRule(true, 1.6f, 0f),
                rule3 = EndpointRule(false, 0f, 30f),
            ),
            enableEndpoint = true,
            decodingMethod = if (spec.arch == "zipformer-transducer") "modified_beam_search" else "greedy_search",
            maxActivePaths = 4,
            hotwordsScore=2f,
        )
        val rec = OnlineRecognizer(loc.assets, config)
        fun newStream()=rec.createStream(hotwords?.file?.let {File(it).readLines().joinToString("/")}.orEmpty())
        return object : StreamingAsr {
            private var stream: OnlineStream = newStream()
            override fun startSession() {
                val next = newStream()
                stream.release()
                stream = next
            }
            override fun accept(samples: FloatArray) {
                stream.acceptWaveform(samples, 16000)
                while (rec.isReady(stream)) rec.decode(stream)
            }
            override fun text(): String = rec.getResult(stream).text
            override fun isEndpoint(): Boolean = rec.isEndpoint(stream)
            override fun reset() = rec.reset(stream)
            override fun finish() {
                stream.acceptWaveform(FloatArray(8000), 16000)
                while (rec.isReady(stream)) rec.decode(stream)
            }
            override fun release() {
                stream.release()
                rec.release()
            }
        }
    }

    fun offline(spec: ModelSpec, loc: ModelLocation, threads: Int = THREADS): OfflineAsr {
        val model = loc.path("model.int8.onnx")
        val modelConfig = when (spec.arch) {
            "zipformer-ctc" -> OfflineModelConfig(zipformerCtc = OfflineZipformerCtcModelConfig(model = model), tokens = loc.path("tokens.txt"), numThreads = threads, debug = false)
            "whisper" -> OfflineModelConfig(whisper = OfflineWhisperModelConfig(
                encoder = loc.path(spec.files.first { it.name.contains("encoder") }.name),
                decoder = loc.path(spec.files.first { it.name.contains("decoder") }.name),
                language = "", task = "transcribe", tailPaddings = 1000,
            ), tokens = loc.path(spec.files.first { it.name.contains("tokens") }.name), numThreads = threads, debug = false)
            "sense-voice" -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = model, language = "auto", useInverseTextNormalization = true),
                tokens = loc.path("tokens.txt"),
                numThreads = threads,
                debug = false,
            )
            "paraformer" -> OfflineModelConfig(paraformer = OfflineParaformerModelConfig(model = model), tokens = loc.path("tokens.txt"), numThreads = threads, debug = false)
            "dolphin" -> OfflineModelConfig(dolphin = OfflineDolphinModelConfig(model = model), tokens = loc.path("tokens.txt"), numThreads = threads, debug = false)
            "telespeech-ctc" -> OfflineModelConfig(teleSpeech = model, tokens = loc.path("tokens.txt"), numThreads = threads, debug = false)
            "wenet-ctc" -> OfflineModelConfig(wenetCtc = OfflineWenetCtcModelConfig(model = model), tokens = loc.path("tokens.txt"), numThreads = threads, debug = false)
            else -> throw IllegalArgumentException("unsupported offline arch ${spec.arch}")
        }
        val config = OfflineRecognizerConfig(featConfig = FEATURES, modelConfig = modelConfig)
        val rec = OfflineRecognizer(loc.assets, config)
        return object : OfflineAsr {
            override val livePreview = spec.arch != "whisper"
            override fun setLanguage(language: String) {
                if (spec.arch == "sense-voice") config.modelConfig.senseVoice.language = language
                else if (spec.arch == "whisper") config.modelConfig.whisper.language = if (language == "auto") "" else language
                else return
                rec.setConfig(config)
            }
            override fun decode(samples: FloatArray): String {
                val s = rec.createStream()
                try {
                    s.acceptWaveform(samples, 16000)
                    rec.decode(s)
                    return rec.getResult(s).text
                } finally {
                    s.release()
                }
            }
            override fun release() = rec.release()
        }
    }

    fun punctuator(loc: ModelLocation): Punctuator {
        val p = OfflinePunctuation(
            loc.assets,
            OfflinePunctuationConfig(OfflinePunctuationModelConfig(ctTransformer = loc.path("model.int8.onnx"), numThreads = 1, debug = false)),
        )
        return object : Punctuator {
            override fun punctuate(text: String): String = p.addPunctuation(text)
            override fun release() = p.release()
        }
    }

    fun detector(loc: ModelLocation): SpeechDetector {
        val vad = Vad(loc.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = loc.path("silero_vad_v5.onnx"), threshold = 0.35f,
            minSpeechDuration = 0.1f, minSilenceDuration = 1.6f, maxSpeechDuration = 30f,
        )))
        return object : SpeechDetector {
            override var heard = false
                private set
            override val endpoint get() = !vad.empty()
            override fun accept(samples: FloatArray) { vad.acceptWaveform(samples); heard = heard || vad.isSpeechDetected() || endpoint }
            override fun reset() { vad.reset(); vad.clear(); heard = false }
            override fun release() = vad.release()
        }
    }
}
