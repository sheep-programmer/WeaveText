package com.weavetext.ime.voice.local

/**
 * 按需下载的端侧识别运行时（sherpa-onnx C 接口 + onnxruntime），由 Rust 内核在运行时载入
 * （见 core/weave-ffi/src/asr.rs）。轻量版用它做本地语音识别；离线语音版仍用随包的 sherpa-onnx。
 * 所有句柄都不是线程安全的：同一句柄同一时刻只在一个线程上使用。
 *
 * The downloadable on-device runtime (sherpa-onnx C API + onnxruntime), loaded at run time by the Rust
 * core (core/weave-ffi/src/asr.rs). The lite build uses it for local speech; the offline-voice build keeps
 * the bundled sherpa-onnx. Handles are not thread-safe: use each from one thread at a time.
 */
object NativeAsr {
    init {
        System.loadLibrary("weave")
    }

    /** 绑定对应的运行时版本（下载与校验都按这个版本）。 Runtime version the bindings are written for. */
    const val RUNTIME_VERSION = "1.13.8"

    /** 载入 [dir] 下的 libonnxruntime.so 与 libsherpa-onnx-c-api.so；成功返回 null，否则返回原因。 */
    @JvmStatic external fun nativeLoad(dir: String): String?
    @JvmStatic external fun nativeIsLoaded(): Boolean
    /** 最近一次创建失败的原因。 Reason of the last failed create. */
    @JvmStatic external fun nativeLastError(): String?

    /** 流式识别器（目前支持 arch = "zipformer2-ctc"）；失败返回 0。 Streaming recognizer; 0 on failure. */
    @JvmStatic external fun nativeOnlineCreate(
        arch: String, model: String, tokens: String, threads: Int,
        noSpeechSec: Float, afterSpeechSec: Float, maxUtteranceSec: Float,
    ): Long
    /** 送入 16 kHz 单声道样本并解码。 Feed 16 kHz mono samples and decode. */
    @JvmStatic external fun nativeOnlineAccept(h: Long, samples: FloatArray, n: Int)
    @JvmStatic external fun nativeOnlineText(h: Long): String?
    @JvmStatic external fun nativeOnlineIsEndpoint(h: Long): Boolean
    @JvmStatic external fun nativeOnlineReset(h: Long)
    /** 补静音并解码完剩余部分。 Pad silence and flush. */
    @JvmStatic external fun nativeOnlineFinish(h: Long)
    @JvmStatic external fun nativeOnlineDestroy(h: Long)

    /** 非流式识别器：arch = "zipformer-ctc" / "sense-voice" / "paraformer"；失败返回 0。 */
    @JvmStatic external fun nativeOfflineCreate(arch: String, model: String, tokens: String, threads: Int): Long
    @JvmStatic external fun nativeOfflineDecode(h: Long, samples: FloatArray, n: Int): String?
    @JvmStatic external fun nativeOfflineDestroy(h: Long)

    /** 智能标点（ct-transformer）；失败返回 0。 Punctuation; 0 on failure. */
    @JvmStatic external fun nativePunctCreate(model: String, threads: Int): Long
    @JvmStatic external fun nativePunctuate(h: Long, text: String): String?
    @JvmStatic external fun nativePunctDestroy(h: Long)
}
