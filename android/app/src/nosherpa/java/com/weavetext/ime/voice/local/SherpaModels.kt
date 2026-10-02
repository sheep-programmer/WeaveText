package com.weavetext.ime.voice.local

import com.weavetext.ime.models.ModelLocation
import com.weavetext.ime.models.ModelSpec
import java.io.File

/**
 * 轻量版适配层：不随包带 sherpa-onnx，改用语音包里下载的运行库（经 [NativeAsr] 在运行时载入）。
 * 轻量版的模型都是下载到私有目录的文件，不会在 APK assets 里。
 *
 * Lite adapters: no bundled sherpa-onnx; the runtime from the voice pack is loaded at run time through
 * [NativeAsr]. Lite models are always downloaded files, never APK assets.
 */
internal object SherpaModels {
    /** 载入运行库（只在第一次真正载入）。 Load the runtime (only the first call does work). */
    fun prepare(runtimeDir: File?) {
        if (!NativeAsr.nativeIsLoaded() && runtimeDir != null) com.weavetext.ime.models.AsrRuntime.makeReadOnly(runtimeDir)
        NativeAsrModels.load(runtimeDir)
    }

    private fun files(loc: ModelLocation): ModelLocation {
        check(loc.assets == null) { "轻量版不含内置模型" }
        return loc
    }

    fun streaming(spec: ModelSpec, loc: ModelLocation, threads: Int = 2, hotwords:HotwordConfig? = null): StreamingAsr =
        files(loc).let {
            if (spec.arch == "zipformer-transducer") NativeAsrModels.streamingTransducer(
                it.path(spec.files.first { f -> f.name.startsWith("encoder") }.name),
                it.path(spec.files.first { f -> f.name.startsWith("decoder") }.name),
                it.path(spec.files.first { f -> f.name.startsWith("joiner") }.name), it.path("tokens.txt"), threads, hotwords=hotwords,
            ) else NativeAsrModels.streaming(spec.arch, it.path("model.int8.onnx"), it.path("tokens.txt"), threads)
        }

    fun offline(spec: ModelSpec, loc: ModelLocation, threads: Int = 2): OfflineAsr =
        files(loc).let {
            if (spec.arch == "whisper") NativeAsrModels.offline("whisper", org.json.JSONArray(listOf(
                it.path(spec.files.first { f -> f.name.contains("encoder") }.name),
                it.path(spec.files.first { f -> f.name.contains("decoder") }.name),
            )).toString(), it.path(spec.files.first { f -> f.name.contains("tokens") }.name), threads)
            else NativeAsrModels.offline(spec.arch, it.path("model.int8.onnx"), it.path("tokens.txt"), threads)
        }

    fun punctuator(loc: ModelLocation): Punctuator = NativeAsrModels.punctuator(files(loc).path("model.int8.onnx"))

    fun detector(loc: ModelLocation): SpeechDetector = NativeAsrModels.detector(files(loc).path("silero_vad_v5.onnx"))
}
