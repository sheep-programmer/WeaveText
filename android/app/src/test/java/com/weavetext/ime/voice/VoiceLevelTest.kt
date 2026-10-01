package com.weavetext.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VoiceLevelTest {
    private fun tone(amplitude: Int) = ByteArray(1280).also { pcm ->
        repeat(640) { i ->
            val v = (amplitude * sin(i * 0.12)).toInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
    }

    @Test fun silenceStaysFlatAndQuietSpeechIsVisible() {
        assertEquals(0f, VoiceLevel.fromPcm16(ByteArray(1280)), 0f)
        assertEquals(0f, VoiceLevel.fromPcm16(ByteArray(0)), 0f)
        val quiet = VoiceLevel.fromPcm16(tone(128))
        val normal = VoiceLevel.fromPcm16(tone(2048))
        val loud = VoiceLevel.fromPcm16(tone(24000))
        assertTrue("quiet speech must move the waveform", quiet > 0.15f)
        assertTrue(normal > quiet && loud > normal)
        assertTrue("normal speech must not saturate the waveform", loud < 1f)
        assertTrue("loud speech must retain visible volume changes",
            VoiceLevel.fromPcm16(tone(8000)) < VoiceLevel.fromPcm16(tone(16000)))
        assertTrue("low-gain microphones must show received audio", VoiceLevel.fromPcm16(tone(8)) > 0.1f)
    }
}
