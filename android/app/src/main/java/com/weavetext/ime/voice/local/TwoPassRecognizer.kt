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
 * 兼顾响应速度与准确率。只有非流式模型时，用音量判断句尾，说话中每隔一段重新识别一次作为实时文字。
 *
 * Two-pass recognition: the streaming model shows text while speaking; at each endpoint the offline
 * model re-decodes that utterance for the final text, then punctuation is restored. With only an offline model,
 * endpoints come from the audio level and the utterance so far is re-decoded now and then as live text.
 *
 * 非线程安全，由调用方在单个解码线程上使用。 Not thread-safe; drive it from one decoding thread.
 */
class TwoPassRecognizer(
    private val streaming: StreamingAsr?,
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
    private val vad = if (streaming == null) EnergyEndpoint(sampleRate) else null
    /** 只有非流式模型时，上次出实时文字时的样本数。 Offline-only: sample count at the last live decode. */
    private var decodedAt = 0

    init {
        require(streaming != null || offline != null) { "no recognizer" }
    }

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
        val endpoint = if (streaming != null) {
            streaming.accept(samples)
            partial(clean(streaming.text()))
            streaming.isEndpoint()
        } else {
            val v = vad!!
            v.accept(samples)
            // 没说话的开头不留着：只保留最后 0.3 秒作为句首。 Drop leading silence but keep 0.3 s of lead-in.
            if (!v.heard && len > sampleRate * 3 / 10) drop(len - sampleRate * 3 / 10)
            // 每 1.5 秒重新识别一次；句子越长间隔越大（约为已录长度的四分之一），免得整句反复解码拖慢识别。
            // Re-decode every 1.5 s, spacing out as the utterance grows (about a quarter of its length) so repeated
            // whole-utterance decodes don't fall behind.
            if (v.heard && len - decodedAt >= maxOf(sampleRate * 3 / 2, len / 4)) {
                decodedAt = len
                partial(clean(runCatching { offline!!.decode(buf.copyOf(len)) }.getOrDefault("")))
            }
            v.endpoint
        }
        if (endpoint || len >= sampleRate * maxUtteranceSeconds) finalizeUtterance()
    }

    private fun partial(t: String) {
        if (t != lastPartial) {
            lastPartial = t
            if (t.isNotEmpty()) listener.onPartial(t)
        }
    }

    /** 录音结束：处理最后一句。 Recording ended: finish the last utterance. */
    fun finish() {
        streaming?.finish()
        finalizeUtterance()
    }

    private fun finalizeUtterance() {
        val quick = streaming?.let { clean(it.text()) } ?: lastPartial
        val heard = vad?.heard ?: true
        val samples = buf.copyOf(len)
        len = 0
        decodedAt = 0
        lastPartial = ""
        streaming?.reset()
        vad?.reset()
        // 太短且首遍没识别出东西、或一直没出声：视为噪声。 Too short with nothing heard, or silent: noise.
        if (quick.isEmpty() && (samples.size < sampleRate / 2 || !heard)) return
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

    private fun drop(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    private fun append(s: FloatArray) {
        if (len + s.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + s.size))
        System.arraycopy(s, 0, buf, len, s.size)
        len += s.size
    }

    fun release() {
        streaming?.release()
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

/**
 * 按音量判断句尾（只有非流式模型时用）：底噪自适应，说过话（响度超过底噪约 3 倍、持续 0.2 秒）之后静音 0.8 秒即断句。
 * Level-based endpointing for offline-only recognition: the noise floor adapts; after speech (about 3× the floor
 * for 0.2 s), 0.8 s of quiet ends the utterance.
 */
internal class EnergyEndpoint(private val sampleRate: Int) {
    private val frame = sampleRate / 50
    private var acc = 0.0
    private var inFrame = 0
    private var floor = -1.0
    private var loudFrames = 0
    private var quietFrames = 0
    var heard = false
        private set
    var endpoint = false
        private set

    fun accept(samples: FloatArray) {
        for (v in samples) {
            acc += v * v
            if (++inFrame == frame) {
                onFrame(kotlin.math.sqrt(acc / frame))
                acc = 0.0
                inFrame = 0
            }
        }
    }

    private fun onFrame(rms: Double) {
        floor = when {
            floor < 0 -> rms
            rms < floor -> floor * 0.9 + rms * 0.1
            else -> floor + (rms - floor) * 0.002
        }
        val loud = rms > maxOf(0.006, floor * 3)
        if (loud) { loudFrames++; quietFrames = 0 } else { quietFrames++; if (!heard) loudFrames = 0 }
        if (loudFrames >= 10) heard = true
        if (heard && quietFrames >= 40) endpoint = true
    }

    fun reset() {
        loudFrames = 0
        quietFrames = 0
        heard = false
        endpoint = false
    }
}
