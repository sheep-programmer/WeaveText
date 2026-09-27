package com.weavetext.ime.link

/**
 * 互联内核的传输接口（JSON 命令进、JSON 事件出），测试里可换成假实现。
 * Transport to the WeaveLink core (JSON commands in, JSON events out); fakeable in tests.
 */
interface LinkBackend {
    /** 启动；配置 JSON 见 core/weave-link。 Start with a JSON config; false on failure. */
    fun start(config: String): Boolean
    /** 至多阻塞 [timeoutMs] 取一个事件；停止后返回 null。 Next event, blocking up to timeoutMs; null once stopped. */
    fun poll(timeoutMs: Int): String?
    fun call(command: String): String
    fun stop()
}

/** Rust 内核（libweave）。 The Rust core in libweave. */
class NativeLink : LinkBackend {
    @Volatile private var handle = 0L

    override fun start(config: String): Boolean {
        if (handle != 0L) return true
        handle = runCatching { nativeStart(config) }.getOrDefault(0L)
        return handle != 0L
    }

    override fun poll(timeoutMs: Int): String? = handle.takeIf { it != 0L }?.let { nativePoll(it, timeoutMs) }

    override fun call(command: String): String = handle.takeIf { it != 0L }?.let { nativeCall(it, command) } ?: "{}"

    override fun stop() {
        val h = handle
        if (h == 0L) return
        nativeStop(h)
    }

    /** 轮询线程退出后释放。 Free after the polling thread has exited. */
    fun destroy() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    private companion object {
        init {
            System.loadLibrary("weave")
        }

        @JvmStatic external fun nativeStart(config: String): Long
        @JvmStatic external fun nativePoll(h: Long, timeoutMs: Int): String?
        @JvmStatic external fun nativeCall(h: Long, command: String): String?
        @JvmStatic external fun nativeStop(h: Long)
        @JvmStatic external fun nativeDestroy(h: Long)
    }
}
