package com.weavetext.ime.voice

/**
 * 插件宿主的 JNI 入口（Rust `weave-plugin`）。 JNI entry of the Rust plugin host.
 */
internal object NativePluginHost {
    init {
        System.loadLibrary("weave")
    }

    @JvmStatic external fun nativeCreate(pluginsDir: String, configDir: String): Long
    /** 重新扫描，返回插件 JSON 数组。 Rescan; returns a JSON array of plugins. */
    @JvmStatic external fun nativeScan(h: Long): String?
    @JvmStatic external fun nativeIcon(h: Long, id: String): ByteArray?
    /** 返回 `{"plugin":{…}}` 或 `{"error":"…"}`。 */
    @JvmStatic external fun nativeInstall(h: Long, path: String): String?
    /** 只读取包信息不安装，返回格式同 nativeInstall。 Preview without installing. */
    @JvmStatic external fun nativeInspect(path: String): String?
    /** 成功返回 null，否则返回错误信息。 Null on success, else the error. */
    @JvmStatic external fun nativeUninstall(h: Long, id: String): String?
    @JvmStatic external fun nativeGetConfig(h: Long, id: String, key: String): String?
    /** value 为 null 表示删除。 A null value removes the key. */
    @JvmStatic external fun nativeSetConfig(h: Long, id: String, key: String, value: String?)
    /** 可能阻塞数秒，不要在主线程调用。 May block for seconds; not on the main thread. */
    @JvmStatic external fun nativeIsConfigured(h: Long, id: String): Boolean
    /** 返回会话句柄；失败返回 0（且已回调 onError/onEnd）。 Session handle, 0 on failure. */
    @JvmStatic external fun nativeStartSpeech(h: Long, id: String, cb: NativeSpeechCallback): Long
    /** 16kHz / 16bit / 单声道 / 小端 PCM。 16 kHz mono s16le PCM. */
    @JvmStatic external fun nativeFeed(session: Long, pcm: ByteArray, len: Int)
    @JvmStatic external fun nativeStop(session: Long)
    @JvmStatic external fun nativeCancel(session: Long)
    /** 释放会取消会话：只能在 onEnd 之后调用。 Releasing cancels; call only after onEnd. */
    @JvmStatic external fun nativeRelease(session: Long)
}

/** native 回调（在插件线程上调用）。 Native callbacks, invoked on plugin threads. */
interface NativeSpeechCallback {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onReplace(old: String, new: String)
    fun onError(message: String)
    fun onEnd()
    fun onLog(level: Int, message: String)
}
