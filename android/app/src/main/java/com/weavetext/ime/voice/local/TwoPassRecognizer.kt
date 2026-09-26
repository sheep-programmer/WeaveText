package com.weavetext.ime.voice.local

/** 流式识别器（首遍）。 Streaming recognizer (first pass). */
interface StreamingAsr {
    /** 16 kHz 单声道浮点样本。 16 kHz mono float samples. */
    fun accept(samples: FloatArray)
    /** 当前句的实时文本。 Live text of the current utterance. */
    fun text(): String
    /** 是否检测到一句话结束。 Whether an endpoint was detected. */
    fun isEndpoint(): Boolean
    /** 开始新的一句。 Start a new utterance. */
    fun reset()
    /** 输入结束：补静音并解码剩余部分。 Input finished: pad and flush. */
    fun finish()
    fun release()
}

/** 非流式识别器（终稿二遍）。 Offline recognizer (second pass). */
interface OfflineAsr {
    fun decode(samples: FloatArray): String
    fun release()
}

/** 标点恢复。 Punctuation restoration. */
interface Punctuator {
    fun punctuate(text: String): String
    fun release()
}

/** 识别事件。 Recognition events. */
interface TwoPassListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
}

/**
 * 两遍识别：流式模型边说边出字；检测到句尾时，用非流式模型对这一句重新识别作为终稿，再补标点。
 * 与商业输入法的「实时 + 终稿」一致，兼顾响应速度与准确率。
 *
 * Two-pass recognition: the streaming model shows text while speaking; at each endpoint the offline
 * model re-decodes that utterance for the final text, then punctuation is restored.
 *
 * 非线程安全，由调用方在单个解码线程上使用。 Not thread-safe; drive it from one decoding thread.
 */
class TwoPassRecognizer(
    private val streaming: StreamingAsr,
    private val offline: OfflineAsr?,
    private val punctuator: Punctuator?,
    private val listener: TwoPassListener,
    private val sampleRate: Int = 16_000,
    /** 单句最长秒数，超过强制断句。 Force an endpoint after this many seconds. */
    private val maxUtteranceSeconds: Int = 25,
) {
    private var buf = FloatArray(sampleRate * 4)
    private var len = 0
    private var lastPartial = ""

    /** 送入 16 bit 小端 PCM。 Feed 16-bit little-endian PCM. */
    fun feedPcm16(pcm: ByteArray, size: Int = pcm.size) {
        val n = size / 2
        val f = FloatArray(n)
        for (i in 0 until n) {
            val v = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            f[i] = v.toShort() / 32768f
        }
        feed(f)
    }

    fun feed(samples: FloatArray) {
        append(samples)
        streaming.accept(samples)
        val t = clean(streaming.text())
        if (t != lastPartial) {
            lastPartial = t
            if (t.isNotEmpty()) listener.onPartial(t)
        }
        if (streaming.isEndpoint() || len >= sampleRate * maxUtteranceSeconds) finalizeUtterance()
    }

    /** 录音结束：处理最后一句。 Recording ended: finish the last utterance. */
    fun finish() {
        streaming.finish()
        finalizeUtterance()
    }

    private fun finalizeUtterance() {
        val quick = clean(streaming.text())
        val samples = buf.copyOf(len)
        len = 0
        lastPartial = ""
        streaming.reset()
        // 太短且首遍没识别出东西：视为噪声。 Too short and nothing heard: treat as noise.
        if (quick.isEmpty() && samples.size < sampleRate / 2) return
        var text = quick
        val second = offline
        if (second != null && samples.size >= sampleRate / 4) {
            val t = clean(runCatching { second.decode(samples) }.getOrDefault(""))
            if (t.isNotEmpty()) text = t
        }
        if (text.isEmpty()) return
        val punct = punctuator
        if (punct != null && !hasPunctuation(text)) {
            val before = text
            text = runCatching { punct.punctuate(before) }.getOrDefault(before)
        }
        text = stripTrailingPeriod(text)
        if (text.isNotEmpty()) listener.onFinal(text)
    }

    private fun append(s: FloatArray) {
        if (len + s.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + s.size))
        System.arraycopy(s, 0, buf, len, s.size)
        len += s.size
    }

    fun release() {
        streaming.release()
        offline?.release()
        punctuator?.release()
    }

    companion object {
        private val CJK_SPACE = Regex("(?<=[\\u3000-\\u9fff\\uff00-\\uffef])\\s+|\\s+(?=[\\u3000-\\u9fff\\uff00-\\uffef])")

        /** 去掉中文之间的空格与首尾空白。 Drop spaces next to CJK and trim. */
        fun clean(s: String): String = s.replace(CJK_SPACE, "").trim()

        private fun hasPunctuation(s: String) = s.any { it in "，。？！、；：,.?!" }

        /** 语音输入多是往输入框里填半句，句末句号通常多余。 A trailing full stop is usually unwanted. */
        fun stripTrailingPeriod(s: String): String = s.trimEnd('。', '.')
    }
}
