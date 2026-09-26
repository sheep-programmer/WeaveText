package com.weavetext.ime.ui.keyboard

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 按键音合成（docs/design/06 §3）：全部在代码里按公式生成，不带任何音频文件。
 * 每种风格对字母键 / 删除 / 空格 / 回车各有一个变体，时长都在 80 ms 以内。
 * Key-sound synthesis: every sound is generated from formulas at startup, no audio files.
 * Each style has letter / delete / space / enter variants, all shorter than 80 ms.
 */
object KeySoundSynth {
    const val RATE = 44_100
    const val MAX_MS = 80

    enum class Variant { LETTER, DELETE, SPACE, ENTER }

    /** 自有风格（不含「关」与「跟随系统」）。 Our own styles (excluding off / system). */
    val STYLES = listOf("crisp", "bubble", "wood", "typewriter", "drop")

    /** 各风格时长（ms）。 Duration per style in ms. */
    fun durationMs(style: String, v: Variant): Int = when (style) {
        "crisp" -> if (v == Variant.SPACE) 40 else 32
        "bubble" -> if (v == Variant.SPACE) 60 else 48
        "wood" -> if (v == Variant.SPACE) 70 else 56
        "typewriter" -> if (v == Variant.ENTER) 78 else 45
        "drop" -> if (v == Variant.SPACE) 66 else 54
        else -> throw IllegalArgumentException("unknown style $style")
    }

    fun sampleCount(style: String, v: Variant) = durationMs(style, v) * RATE / 1000

    /** 生成 16 bit 单声道 PCM。 Render 16-bit mono PCM. */
    fun render(style: String, v: Variant): ShortArray {
        val n = sampleCount(style, v)
        val out = DoubleArray(n)
        val noise = Noise(style.hashCode() * 31 + v.ordinal)
        when (style) {
            "crisp" -> crisp(out, v, noise)
            "bubble" -> bubble(out, v)
            "wood" -> wood(out, v, noise)
            "typewriter" -> typewriter(out, v, noise)
            "drop" -> drop(out, v)
        }
        return finish(out)
    }

    // ------------------------------------------------------------ styles

    /** 清脆：高通噪声瞬态 + 短促高频正弦。 Crisp: high-passed noise transient + short high sine. */
    private fun crisp(o: DoubleArray, v: Variant, noise: Noise) {
        val f = when (v) { Variant.LETTER -> 3400.0; Variant.DELETE -> 2700.0; Variant.SPACE -> 1900.0; Variant.ENTER -> 2300.0 }
        var prev = 0.0
        for (i in o.indices) {
            val t = i.toDouble() / RATE
            val w = noise.next()
            val hp = w - prev; prev = w
            o[i] = 0.55 * hp * exp(-t / 0.0025) + 0.6 * sin(2 * PI * f * t) * exp(-t / 0.006)
            if (v == Variant.SPACE) o[i] += 0.35 * sin(2 * PI * 420.0 * t) * exp(-t / 0.012)
        }
        if (v == Variant.ENTER) echo(o, 0.012, 0.55)
    }

    /** 气泡：带上滑音高的正弦，像气泡破裂。 Bubble: sine with a rising pitch sweep, like a popping bubble. */
    private fun bubble(o: DoubleArray, v: Variant) {
        val (f0, f1) = when (v) {
            Variant.LETTER -> 520.0 to 1350.0; Variant.DELETE -> 420.0 to 900.0
            Variant.SPACE -> 300.0 to 720.0; Variant.ENTER -> 600.0 to 1700.0
        }
        val sweep = 0.022
        var phase = 0.0
        for (i in o.indices) {
            val t = i.toDouble() / RATE
            val k = (t / sweep).coerceAtMost(1.0)
            val f = f0 + (f1 - f0) * (1 - (1 - k) * (1 - k))
            phase += 2 * PI * f / RATE
            o[i] = sin(phase) * attack(t, 0.002) * exp(-t / 0.014)
        }
    }

    /** 木质：木块的非谐波模态（阻尼正弦叠加）+ 很短的敲击噪声。 Wood: inharmonic damped modes plus a tiny strike. */
    private fun wood(o: DoubleArray, v: Variant, noise: Noise) {
        val f = when (v) { Variant.LETTER -> 820.0; Variant.DELETE -> 640.0; Variant.SPACE -> 430.0; Variant.ENTER -> 560.0 }
        val modes = doubleArrayOf(1.0, 2.57, 4.21)
        val gains = doubleArrayOf(1.0, 0.45, 0.2)
        val taus = doubleArrayOf(0.016, 0.009, 0.005)
        for (i in o.indices) {
            val t = i.toDouble() / RATE
            var s = 0.0
            for (m in modes.indices) s += gains[m] * sin(2 * PI * f * modes[m] * t) * exp(-t / taus[m])
            o[i] = s + 0.3 * noise.next() * exp(-t / 0.0015)
        }
        if (v == Variant.ENTER) echo(o, 0.018, 0.6)
    }

    /** 打字机：金属敲击（高频模态）+ 机械噪声；回车带一声短铃。 Typewriter: metallic strike and noise; enter adds a short bell. */
    private fun typewriter(o: DoubleArray, v: Variant, noise: Noise) {
        val base = when (v) { Variant.LETTER -> 1900.0; Variant.DELETE -> 1500.0; Variant.SPACE -> 900.0; Variant.ENTER -> 1700.0 }
        var lp = 0.0
        for (i in o.indices) {
            val t = i.toDouble() / RATE
            lp += 0.35 * (noise.next() - lp)
            var s = 0.8 * lp * exp(-t / 0.004)
            s += 0.5 * sin(2 * PI * base * t) * exp(-t / 0.005)
            s += 0.3 * sin(2 * PI * base * 1.93 * t) * exp(-t / 0.004)
            s += 0.2 * sin(2 * PI * base * 2.71 * t) * exp(-t / 0.003)
            // 机械回弹：8 ms 后第二下轻响。 Mechanical return: a softer second tick after 8 ms.
            val t2 = t - 0.008
            if (t2 > 0) s += 0.35 * lp * exp(-t2 / 0.003)
            if (v == Variant.ENTER) {
                val tb = t - 0.02
                if (tb > 0) s += 0.45 * sin(2 * PI * 2093.0 * tb) * attack(tb, 0.001) * exp(-tb / 0.03)
            }
            o[i] = s
        }
    }

    /** 水滴：指数上滑的正弦，起音柔和。 Drop: exponentially rising sine with a soft onset. */
    private fun drop(o: DoubleArray, v: Variant) {
        val (f0, f1) = when (v) {
            Variant.LETTER -> 700.0 to 1900.0; Variant.DELETE -> 560.0 to 1300.0
            Variant.SPACE -> 420.0 to 1000.0; Variant.ENTER -> 800.0 to 2300.0
        }
        var phase = 0.0
        for (i in o.indices) {
            val t = i.toDouble() / RATE
            val f = f1 - (f1 - f0) * exp(-t / 0.008)
            phase += 2 * PI * f / RATE
            o[i] = sin(phase) * attack(t, 0.004) * exp(-t / 0.013)
        }
        if (v == Variant.ENTER) echo(o, 0.02, 0.5)
    }

    // ------------------------------------------------------------ helpers

    private fun attack(t: Double, len: Double) = (t / len).coerceAtMost(1.0)

    /** 叠加一个延迟回声（回车键的「两下」）。 Add a delayed copy (the enter key's double hit). */
    private fun echo(o: DoubleArray, delay: Double, gain: Double) {
        val d = (delay * RATE).roundToInt()
        for (i in o.indices.reversed()) if (i >= d) o[i] += gain * o[i - d]
    }

    /** 归一化到 0.8 满幅，末尾 3 ms 淡出防爆音。 Normalize to 0.8 FS; 3 ms fade-out avoids clicks. */
    private fun finish(o: DoubleArray): ShortArray {
        val fade = (0.003 * RATE).toInt().coerceAtMost(o.size)
        for (k in 0 until fade) o[o.size - 1 - k] *= k.toDouble() / fade
        val peak = o.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val g = 0.8 * Short.MAX_VALUE / peak
        return ShortArray(o.size) { (o[it] * g).roundToInt().coerceIn(-32767, 32767).toShort() }
    }

    /** 固定种子的白噪声（每次生成结果一致）。 Seeded white noise, deterministic. */
    private class Noise(seed: Int) {
        private var s = seed.toLong() and 0xffffffffL or 1L
        fun next(): Double {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33).toDouble() / (1L shl 31).toDouble()) * 2 - 1
        }
    }

    /** 封装为 WAV（PCM 16 bit 单声道）。 Wrap as a 16-bit mono PCM WAV file. */
    fun wav(pcm: ShortArray, rate: Int = RATE): ByteArray {
        val data = pcm.size * 2
        val o = ByteArrayOutputStream(44 + data)
        fun i32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
        fun i16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
        o.write("RIFF".toByteArray()); i32(36 + data); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(data)
        for (s in pcm) i16(s.toInt())
        return o.toByteArray()
    }
}
