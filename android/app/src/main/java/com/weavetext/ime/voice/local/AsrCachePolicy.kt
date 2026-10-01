package com.weavetext.ime.voice.local

import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

/** UI_HIDDEN is a visibility event, not memory pressure. */
internal object AsrCachePolicy {
    const val IDLE_MINUTES = 20L
    fun releaseForTrim(level: Int) = level == 10 || level == 15 || level >= 60
    fun threads(models: Int, processors: Int = Runtime.getRuntime().availableProcessors()) =
        (processors / models.coerceAtLeast(1)).coerceIn(1, 2)
}

/** Loading weights serially avoids three simultaneous allocation/IO spikes. Decode stays independent. */
internal object AsrLoadQueue {
    private val queue = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weave-asr-load").apply { isDaemon = true }
    }
    fun <T> load(block: () -> T): T = try {
        queue.submit<T> { block() }.get()
    } catch (failure: ExecutionException) {
        throw (failure.cause ?: failure)
    }
}
