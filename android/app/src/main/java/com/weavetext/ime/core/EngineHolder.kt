package com.weavetext.ime.core

import android.content.Context
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 进程内唯一的内核实例，输入法服务与设置页共用（同一进程）。内核内部有锁，跨线程调用安全，
 * 但按键路径只应在主线程调用。
 * Process-wide engine shared by the IME service and settings (same process). The native side is
 * mutex-guarded; the key path should still stay on the main thread.
 */
object EngineHolder {
    private const val TAG = "WeaveEngine"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-engine-init") }
    @Volatile private var engine: NativeEngine? = null
    private var loading: CountDownLatch? = null

    /** 系统内存紧张时清空内核的解压缓存。 Drop the engine's decode caches under memory pressure. */
    fun trim() {
        engine?.trim()
    }

    /** 已就绪则直接返回。 Returns the engine if already loaded. */
    fun peek(): NativeEngine? = engine

    /** 后台加载，完成后在加载线程回调。 Load in the background; callback runs on the loader thread. */
    fun load(ctx: Context, done: (NativeEngine?) -> Unit) {
        engine?.let { done(it); return }
        val app = ctx.applicationContext
        io.execute { done(getBlocking(app)) }
    }

    /** 阻塞加载（不要在主线程调用）。 Blocking load; never call on the main thread. */
    fun getBlocking(ctx: Context): NativeEngine? {
        engine?.let { return it }
        val latch: CountDownLatch
        val first: Boolean
        synchronized(this) {
            engine?.let { return it }
            first = loading == null
            if (first) loading = CountDownLatch(1)
            latch = loading!!
        }
        if (!first) {
            latch.await()
            return engine
        }
        try {
            val spec = DataInstaller.sourceSpec(ctx)
            engine = NativeEngine.createFromSpec(spec, DataInstaller.userDir(ctx).absolutePath, DataInstaller.cacheKb(ctx))
            if (engine == null) Log.e(TAG, "engine create failed")
            engine?.let { CloudWords.get(ctx).attach(it) }
            // 启用了手写键盘：在加载线程上先载入手写模型，第一笔不用等（没启用就不占这份内存）。
            // Handwriting keyboard enabled: load its models on the loader thread so the first stroke doesn't wait
            // (skipped otherwise, saving the memory).
            val prefs = ctx.getSharedPreferences(com.weavetext.ime.settings.WeavePrefs.FILE, Context.MODE_PRIVATE)
            if ("hand" in com.weavetext.ime.settings.WeavePrefs.keyboards(prefs)) engine?.handRecognize(emptyList())
        } catch (t: Throwable) {
            Log.e(TAG, "engine load failed", t)
        } finally {
            latch.countDown()
            synchronized(this) { if (engine == null) loading = null }
        }
        return engine
    }
}
