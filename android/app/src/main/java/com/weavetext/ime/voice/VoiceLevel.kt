package com.weavetext.ime.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** A logarithmic meter: quiet speech remains visible; digital silence stays flat. */
internal object VoiceLevel {
    fun fromPcm16(pcm: ByteArray, size: Int = pcm.size): Float {
        val n = minOf(size, pcm.size) / 2
        if (n == 0) return 0f
        var energy = 0.0
        for (i in 0 until n) {
            val v = ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            energy += v * v
        }
        val rms = sqrt(energy / n) / 32768.0
        if (rms <= 1.0 / 32768.0) return 0f
        return ((20 * log10(rms) + 90) / 90).coerceIn(0.0, 1.0).toFloat()
    }
}
