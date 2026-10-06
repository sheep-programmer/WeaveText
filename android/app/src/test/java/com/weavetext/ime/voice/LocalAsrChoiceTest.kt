package com.weavetext.ime.voice

import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.voice.local.EnergyEndpoint
import com.weavetext.ime.voice.local.LocalAsrChoice
import com.weavetext.ime.voice.local.OfflineAsr
import com.weavetext.ime.voice.local.Punctuator
import com.weavetext.ime.voice.local.TwoPassListener
import com.weavetext.ime.voice.local.TwoPassRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

/**
 * 本地识别的模型选择（新下载的直接用上、一键切换）与只有终稿模型时的整句识别。
 * Local model choice (new downloads adopted, one-tap switch) and whole-sentence recognition with a final model only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAsrChoiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun newlyDownloadedModelsAreUsedAndCanBeSwitched() {
        val repo = FakeModels(mapOf("asr-stream-small" to ModelState.Installed, "asr-final-small" to ModelState.Installed))
        val c = LocalAsrChoice(ctx, repo)
        assertEquals("asr-stream-small", c.streamId())
        assertEquals("asr-final-small", c.finalId())
        // 下载完 SenseVoice：直接成为终稿模型。 SenseVoice finishes downloading: it becomes the final model.
        repo.emit("asr-sensevoice", ModelState.Installed)
        assertEquals("asr-sensevoice", c.finalId())
        repo.emit("asr-stream-large", ModelState.Installed)
        assertEquals("asr-stream-large", c.streamId())
        // 在语音包页面点「使用」切回去。 Tap 使用 on the voice-pack page to switch back.
        c.use(repo.catalog.find("asr-final-small")!!)
        assertEquals("asr-final-small", c.finalId())
        assertTrue(c.inUse(repo.catalog.find("asr-final-small")!!))
        assertFalse(c.inUse(repo.catalog.find("asr-sensevoice")!!))
        c.setFinal(null)
        assertNull(c.finalId())
        // 删掉正在用的模型：退回到还在的那个。 Deleting the model in use falls back to one that is still there.
        repo.emit("asr-stream-large", ModelState.NotInstalled)
        assertEquals("asr-stream-small", c.streamId())
    }

    private class FakeOffline(val text: String) : OfflineAsr {
        val lengths = mutableListOf<Int>()
        override fun decode(samples: FloatArray): String { lengths += samples.size; return text }
        override fun release() {}
    }

    private class FakePunctuator(private val result: String) : Punctuator {
        var calls = 0
        override fun punctuate(text: String): String { calls++; return result }
        override fun release() {}
    }

    private fun tone(seconds: Double, amp: Float) = FloatArray((16000 * seconds).toInt()) { i -> amp * sin(i * 0.12).toFloat() }
    private fun quiet(seconds: Double) = FloatArray((16000 * seconds).toInt()) { i -> if (i % 7 == 0) 0.001f else -0.0005f }

    private fun feedChunks(r: TwoPassRecognizer, s: FloatArray) {
        var i = 0
        while (i < s.size) { val n = minOf(640, s.size - i); r.feed(s.copyOfRange(i, i + n)); i += n }
    }

    @Test fun finalModelAloneRecognisesEachSentence() {
        val off = FakeOffline("你好世界")
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val r = TwoPassRecognizer(null, off, null, object : TwoPassListener {
            override fun onPartial(text: String) { partials += text }
            override fun onFinal(text: String) { finals += text }
        })
        feedChunks(r, quiet(2.0))
        assertTrue("开头的静音不识别 / leading silence is not decoded", finals.isEmpty() && off.lengths.isEmpty())
        feedChunks(r, tone(2.0, 0.2f))
        assertEquals("说话中出实时文字 / live text while speaking", listOf("你好世界"), partials)
        feedChunks(r, quiet(1.8))
        assertEquals(listOf("你好世界"), finals)
        // 终稿只含 0.3 秒句首 + 说话 + 断句前的静音，不含开头 2 秒。 The final skips the 2 s of leading silence.
        assertTrue(off.lengths.last() < 16000 * 4.2)
        // 只有噪声就结束：不上屏。 Only noise, then stop: nothing is committed.
        feedChunks(r, quiet(1.0))
        r.finish()
        assertEquals(1, finals.size)
    }

    @Test fun punctuationCompletesTextWithAnInternalComma() {
        val punc = FakePunctuator("你好，世界，大家好。")
        val finals = mutableListOf<String>()
        val r = TwoPassRecognizer(null, FakeOffline("你好，世界大家好"), punc, object : TwoPassListener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { finals += text }
        })
        feedChunks(r, quiet(0.5))
        feedChunks(r, tone(0.2, 0.2f))
        r.finish()
        assertEquals("含内部逗号但没有句末标点时仍调用标点模型 / punctuation should complete an unterminated sentence", 1, punc.calls)
        assertEquals(listOf("你好，世界，大家好"), finals)
    }

    @Test fun energyEndpointAdaptsToTheNoiseFloor() {
        val e = EnergyEndpoint(16000)
        // 嘈杂底噪上的正常说话也能分出来。 Speech over a noisy floor is still told apart.
        e.accept(tone(1.0, 0.03f))
        assertFalse(e.heard)
        e.accept(tone(0.5, 0.3f))
        assertTrue(e.heard)
        assertFalse(e.endpoint)
        e.accept(tone(1.8, 0.03f))
        assertTrue(e.endpoint)
    }
}
