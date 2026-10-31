package com.weavetext.ime.captions

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/** 完整版：用 sherpa-onnx 的说话人嵌入提取器。Full build: sherpa-onnx speaker embedding extractor. */
object SpeakerRuntime {
    /** 模型未安装时返回 null。Null when the model is not installed. */
    fun embedder(ctx: Context, model: File): ((FloatArray) -> FloatArray)? {
        if (!model.isFile) return null
        val extractor = SpeakerEmbeddingExtractor(ctx.assets, SpeakerEmbeddingExtractorConfig(model.path, 2, false, "cpu"))
        return { samples ->
            val stream = extractor.createStream()
            stream.acceptWaveform(samples, 16_000)
            stream.inputFinished()
            extractor.compute(stream)
        }
    }
}
