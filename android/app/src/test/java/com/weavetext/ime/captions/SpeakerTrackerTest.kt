package com.weavetext.ime.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTrackerTest {
    @Test fun similarVoicesStayWithOneSpeaker() {
        val t = SpeakerTracker()
        assertEquals(1, t.assign(floatArrayOf(1f, 0f, 0f)))
        assertEquals(1, t.assign(floatArrayOf(0.9f, 0.1f, 0f)))
        assertEquals(1, t.speakers)
    }

    @Test fun a_different_voice_opens_a_new_speaker() {
        val t = SpeakerTracker()
        assertEquals(1, t.assign(floatArrayOf(1f, 0f, 0f)))
        assertEquals(2, t.assign(floatArrayOf(0f, 1f, 0f)))
        // 之后再来第一个人的声音，回到说话人 1。 The first voice comes back to speaker 1.
        assertEquals(1, t.assign(floatArrayOf(0.95f, 0.05f, 0f)))
        assertEquals(2, t.speakers)
    }

    @Test fun cosineOfParallelAndOrthogonalVectors() {
        assertEquals(1f, SpeakerTracker.cosine(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f)), 1e-6f)
        assertEquals(0f, SpeakerTracker.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-6f)
    }
}
