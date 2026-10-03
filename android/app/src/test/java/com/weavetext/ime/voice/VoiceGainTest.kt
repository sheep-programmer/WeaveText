package com.weavetext.ime.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceGainTest {
    private fun tone(amp: Double, n: Int = 640, phase: Int = 0): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (amp * 32767 * sin(2 * PI * 300 * (i + phase) / 16000)).toInt()
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun rms(b: ByteArray): Double {
        var e = 0.0
        for (i in 0 until b.size / 2) {
            val v = ((b[2 * i].toInt() and 255) or (b[2 * i + 1].toInt() shl 8)).toShort() / 32768.0
            e += v * v
        }
        return sqrt(e / (b.size / 2))
    }

    @Test fun `quiet speech is lifted after the noise floor is learned`() {
        val g = VoiceGain()
        repeat(10) { g.apply(tone(0.0003)) }                    // room tone
        var last = tone(0.02)
        repeat(40) { last = tone(0.02, phase = it * 640); g.apply(last) }
        assertTrue("rms=${rms(last)}", rms(last) > 0.05)
    }

    @Test fun `loud audio never clips and is not boosted`() {
        val g = VoiceGain()
        repeat(10) { g.apply(tone(0.001)) }
        val loud = tone(0.95)
        val before = loud.copyOf()
        g.apply(loud)
        for (i in 0 until loud.size / 2) {
            val v = ((loud[2 * i].toInt() and 255) or (loud[2 * i + 1].toInt() shl 8)).toShort().toInt()
            assertTrue(abs(v) <= 32767)
        }
        assertTrue(rms(loud) <= rms(before) + 1e-6)
    }

    @Test fun `steady silence and pure noise stay untouched`() {
        val g = VoiceGain()
        val silence = ByteArray(1280)
        g.apply(silence)
        assertArrayEquals(ByteArray(1280), silence)
        val hiss = tone(0.001)
        val copy = hiss.copyOf()
        repeat(30) { g.apply(hiss) }
        assertArrayEquals(copy, hiss)
    }

    @Test fun `short and empty chunks are handled`() {
        val g = VoiceGain()
        g.apply(ByteArray(0))
        g.apply(ByteArray(3), 3)
    }
}
