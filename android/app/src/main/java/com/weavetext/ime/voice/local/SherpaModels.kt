package com.weavetext.ime.voice.local

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.weavetext.ime.models.ModelLocation
import com.weavetext.ime.models.ModelSpec

/** sherpa-onnx 适配层（Android 版 Kotlin API）。 Adapters over sherpa-onnx's Android Kotlin API. */
internal object SherpaModels {
    private const val THREADS = 2
    private val FEATURES = FeatureConfig(sampleRate = 16000, featureDim = 80)

    fun streaming(spec: ModelSpec, loc: ModelLocation): StreamingAsr {
        require(spec.arch == "zipformer2-ctc") { "unsupported streaming arch ${spec.arch}" }
        val config = OnlineRecognizerConfig(
            featConfig = FEATURES,
            modelConfig = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = loc.path("model.int8.onnx")),
                tokens = loc.path("tokens.txt"),
                numThreads = THREADS,
                debug = false,
            ),
            // 句尾判定：说过话后静音 0.8 秒即断句；一直没说话 2.4 秒；单句最长 20 秒。
            // Endpoints: 0.8 s silence after speech, 2.4 s with no speech, 20 s max.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.8f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
        )
        val rec = OnlineRecognizer(loc.assets, config)
        return object : StreamingAsr {
            private var stream: OnlineStream = rec.createStream()
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

    fun offline(spec: ModelSpec, loc: ModelLocation): OfflineAsr {
        val model = loc.path("model.int8.onnx")
        val modelConfig = when (spec.arch) {
            "zipformer-ctc" -> OfflineModelConfig(zipformerCtc = OfflineZipformerCtcModelConfig(model = model), tokens = loc.path("tokens.txt"), numThreads = THREADS, debug = false)
            "sense-voice" -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = model, language = "auto", useInverseTextNormalization = true),
                tokens = loc.path("tokens.txt"),
                numThreads = THREADS,
                debug = false,
            )
            "paraformer" -> OfflineModelConfig(paraformer = OfflineParaformerModelConfig(model = model), tokens = loc.path("tokens.txt"), numThreads = THREADS, debug = false)
            else -> throw IllegalArgumentException("unsupported offline arch ${spec.arch}")
        }
        val rec = OfflineRecognizer(loc.assets, OfflineRecognizerConfig(featConfig = FEATURES, modelConfig = modelConfig))
        return object : OfflineAsr {
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
}
