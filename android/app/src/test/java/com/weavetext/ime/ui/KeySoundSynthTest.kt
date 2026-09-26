package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.KeySoundSynth
import com.weavetext.ime.ui.keyboard.KeySoundSynth.Variant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** 合成的按键音：长度、响度、变体区别、WAV 封装。 Synthesized key sounds. */
class KeySoundSynthTest {
    private fun rms(a: ShortArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size) / 32768.0

    @Test fun everyStyleAndVariantIsShortAndAudible() {
        for (style in KeySoundSynth.STYLES) for (v in Variant.entries) {
            val pcm = KeySoundSynth.render(style, v)
            val ms = KeySoundSynth.durationMs(style, v)
            assertTrue("$style/$v too long: $ms ms", ms < KeySoundSynth.MAX_MS)
            assertEquals("$style/$v length", ms * KeySoundSynth.RATE / 1000, pcm.size)
            assertTrue("$style/$v too quiet: ${rms(pcm)}", rms(pcm) > 0.02)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$style/$v peak $peak", peak in 20_000..32_767)
            // 末尾淡出，没有爆音。 Faded tail, no click.
            assertTrue("$style/$v tail", abs(pcm.last().toInt()) < 200)
        }
    }

    @Test fun variantsAndStylesDiffer() {
        for (style in KeySoundSynth.STYLES) {
            val renders = Variant.entries.map { KeySoundSynth.render(style, it).toList() }
            assertEquals("$style variants must be distinct", renders.size, renders.toSet().size)
        }
        val letters = KeySoundSynth.STYLES.map { KeySoundSynth.render(it, Variant.LETTER).toList() }
        assertEquals(letters.size, letters.toSet().size)
    }

    @Test fun deterministic() {
        assertArrayEquals(KeySoundSynth.render("typewriter", Variant.ENTER), KeySoundSynth.render("typewriter", Variant.ENTER))
    }

    @Test fun wavHeader() {
        val pcm = KeySoundSynth.render("crisp", Variant.LETTER)
        val wav = KeySoundSynth.wav(pcm)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(KeySoundSynth.RATE, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(pcm[10], b.getShort(44 + 20))
        assertFalse(pcm.all { it.toInt() == 0 })
    }
}
