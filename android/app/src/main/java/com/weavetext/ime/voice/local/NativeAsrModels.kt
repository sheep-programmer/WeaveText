package com.weavetext.ime.voice.local

import java.io.File

/**
 * 在 [NativeAsr]（按需下载的运行库）之上实现的识别器，参数与离线语音版的 sherpa-onnx 适配层一致，
 * 所以两个版本的识别效果相同。只用文件路径，不依赖 Android，桌面测试直接用它。
 *
 * Recognizers over [NativeAsr] (the downloaded runtime) with the same settings as the offline-voice build's
 * sherpa-onnx adapters, so both builds recognise identically. File paths only, no Android: desktop tests use it.
 */
internal object NativeAsrModels {
    private const val THREADS = 2

    /**
     * 载入 [dir] 下的运行库（只载一次）；失败抛出带中文说明的异常。
     * Load the runtime from [dir] once; throws with a readable Chinese message on failure.
     */
    fun load(dir: File?) {
        if (NativeAsr.nativeIsLoaded()) return
        if (dir == null || !dir.isDirectory) throw IllegalStateException("还没有下载识别运行库，请先下载语音包")
        NativeAsr.nativeLoad(dir.path)?.let { throw IllegalStateException(friendly(it)) }
    }

    /** 流式识别器；端点规则与离线语音版相同。 Streaming recognizer with the voice build's endpoint rules. */
    fun streaming(arch: String, model: String, tokens: String): StreamingAsr {
        // 句尾判定：说过话后静音 1.6 秒分句；无说话 4 秒；单句最长 30 秒。
        // Endpoints: 1.6 s silence after speech, 4 s with no speech, 30 s max.
        val h = NativeAsr.nativeOnlineCreate(arch, model, tokens, THREADS, 4f, 1.6f, 30f)
        if (h == 0L) throw IllegalStateException(friendly(NativeAsr.nativeLastError()))
        return object : StreamingAsr {
            private var handle = h
            override fun accept(samples: FloatArray) = NativeAsr.nativeOnlineAccept(handle, samples, samples.size)
            override fun text(): String = NativeAsr.nativeOnlineText(handle).orEmpty()
            override fun isEndpoint(): Boolean = NativeAsr.nativeOnlineIsEndpoint(handle)
            override fun reset() = NativeAsr.nativeOnlineReset(handle)
            override fun finish() = NativeAsr.nativeOnlineFinish(handle)
            override fun release() {
                NativeAsr.nativeOnlineDestroy(handle)
                handle = 0
            }
        }
    }

    /** 非流式识别器（终稿）。 Offline recognizer for the final pass. */
    fun offline(arch: String, model: String, tokens: String): OfflineAsr {
        val h = NativeAsr.nativeOfflineCreate(arch, model, tokens, THREADS)
        if (h == 0L) throw IllegalStateException(friendly(NativeAsr.nativeLastError()))
        return object : OfflineAsr {
            private var handle = h
            override fun decode(samples: FloatArray): String = NativeAsr.nativeOfflineDecode(handle, samples, samples.size).orEmpty()
            override fun release() {
                NativeAsr.nativeOfflineDestroy(handle)
                handle = 0
            }
        }
    }

    fun punctuator(model: String): Punctuator {
        val h = NativeAsr.nativePunctCreate(model, 1)
        if (h == 0L) throw IllegalStateException(friendly(NativeAsr.nativeLastError()))
        return object : Punctuator {
            private var handle = h
            override fun punctuate(text: String): String = NativeAsr.nativePunctuate(handle, text) ?: text
            override fun release() {
                NativeAsr.nativePunctDestroy(handle)
                handle = 0
            }
        }
    }

    fun detector(model: String): SpeechDetector {
        val h = NativeAsr.nativeVadCreate(model)
        check(h != 0L) { friendly(NativeAsr.nativeLastError()) }
        return object : SpeechDetector {
            private var handle = h
            override var heard = false
                private set
            override var endpoint = false
                private set
            override fun accept(samples: FloatArray) {
                val flags = NativeAsr.nativeVadAccept(handle, samples, samples.size)
                heard = heard || flags != 0
                endpoint = flags and 2 != 0
            }
            override fun reset() { NativeAsr.nativeVadReset(handle); heard = false; endpoint = false }
            override fun release() { NativeAsr.nativeVadDestroy(handle); handle = 0 }
        }
    }

    /**
     * 把原生层的英文错误换成用户看得懂的说明。 Turn the native layer's English errors into readable text.
     */
    fun friendly(raw: String?): String {
        val m = raw.orEmpty()
        return when {
            m.contains("required") -> "识别运行库版本不对，请在「语音包」里删除「识别运行库」后重新下载"
            m.startsWith("load onnxruntime") || m.startsWith("load sherpa-onnx") || m.startsWith("missing ") ->
                "识别运行库无法载入，可能已损坏，请在「语音包」里删除后重新下载（$m）"
            m.contains("not loaded") -> "识别运行库还没有载入"
            m.startsWith("unsupported") -> "不支持这种模型（$m）"
            m.contains("recognizer") || m.contains("stream") || m.contains("punctuation") ->
                "模型无法打开，可能下载不完整，请在「语音包」里删除后重新下载"
            m.isEmpty() -> "离线识别出错"
            else -> "离线识别出错：$m"
        }
    }
}
