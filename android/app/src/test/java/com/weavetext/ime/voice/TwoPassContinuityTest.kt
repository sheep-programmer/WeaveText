package com.weavetext.ime.voice

import com.weavetext.ime.voice.local.StreamingAsr
import com.weavetext.ime.voice.local.OfflineAsr
import com.weavetext.ime.voice.local.TwoPassListener
import com.weavetext.ime.voice.local.TwoPassRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoPassContinuityTest {
    @Test fun silentStreamingEndpointDoesNotAskOfflineModelToHallucinate() {
        var accepted = 0
        var decodes = 0
        val stream = object : StreamingAsr {
            override fun accept(samples: FloatArray) { accepted += samples.size }
            override fun text() = ""
            override fun isEndpoint() = accepted >= 16_000 * 4
            override fun reset() { accepted = 0 }
            override fun finish() {}
            override fun release() {}
        }
        val off = object : OfflineAsr {
            override fun decode(samples: FloatArray): String { decodes++; return "谢谢观看" }
            override fun release() {}
        }
        val finals = mutableListOf<String>()
        val rec = TwoPassRecognizer(stream, off, null, object : TwoPassListener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { finals += text }
        })
        repeat(250) { rec.feed(FloatArray(640)) }
        rec.finish()
        assertEquals(0, decodes)
        assertEquals(emptyList<String>(), finals)
    }
    @Test fun longSpeechFlushesTailBeforeEveryForcedChunk() {
        var segment = 0
        var flushed = false
        val stream = object : StreamingAsr {
            override fun accept(samples: FloatArray) { flushed = false }
            override fun text() = if (flushed) listOf("前半句", "后半句", "末尾")[segment] else "未定"
            override fun isEndpoint() = false
            override fun finish() { flushed = true }
            override fun reset() { segment++; flushed = false }
            override fun release() {}
        }
        val finals = mutableListOf<String>()
        val rec = TwoPassRecognizer(stream, null, null, object : TwoPassListener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { finals += text }
        }, maxUtteranceSeconds = 2)
        repeat(125) { rec.feed(FloatArray(640) { 0.1f }) } // 5 seconds, two forced chunks plus tail
        rec.finish()
        assertEquals(listOf("前半句", "后半句", "末尾"), finals)
    }
}
