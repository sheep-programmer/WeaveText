package com.weavetext.ime.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 采集增益：跟踪底噪，把说话声快速拉到目标电平，遇到大声立刻回落，绝不削波；增益在两次录音之间保留。
 * 手机麦克风常常很轻（远讲、低增益机型），流式模型对这种音量的英文几乎全错；基准里加了这一步后恢复正常。
 *
 * Capture gain: tracks the noise floor, lifts speech toward a target level quickly, backs off at once on loud
 * peaks, never clips, and keeps its gain between recordings. Phone microphones are often quiet, and streaming
 * models fail on quiet English; the offline benchmark recovers once this step is applied.
 */
internal class VoiceGain {
    private var gain = 1.0
    private var floor = -1.0

    /** Scales little-endian PCM16 in [pcm] in place. Call with one capture chunk at a time. */
    fun apply(pcm: ByteArray, size: Int = pcm.size) {
        val n = min(size, pcm.size) / 2
        if (n == 0) return
        var energy = 0.0
        var peak = 1e-9
        for (i in 0 until n) {
            val v = sample(pcm, i) / 32768.0
            energy += v * v
            peak = max(peak, abs(v))
        }
        val rms = sqrt(energy / n) + 1e-9
        // 数字静音不是底噪：不参与估计，也不放大。 Digital silence is not a noise floor: ignore it.
        if (rms < SILENCE) return
        floor = when {
            floor < 0 -> rms
            rms < floor -> floor * 0.9 + rms * 0.1
            else -> floor + (rms - floor) * 0.01
        }
        if (rms > max(3 * floor, 2e-4)) {
            val want = min(TARGET / rms, MAX_GAIN)
            gain += (want - gain) * if (want < gain) 0.5 else 0.25
        }
        val g = if (peak * gain > 0.9) min(max(gain, 1.0), 0.9 / peak) else max(gain, 1.0)
        if (g == 1.0) return
        for (i in 0 until n) {
            val v = (sample(pcm, i) * g).toInt().coerceIn(-32768, 32767)
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
    }

    private fun sample(pcm: ByteArray, i: Int): Int =
        ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()

    private companion object {
        const val SILENCE = 1e-5
        const val TARGET = 0.1
        const val MAX_GAIN = 40.0
    }
}
