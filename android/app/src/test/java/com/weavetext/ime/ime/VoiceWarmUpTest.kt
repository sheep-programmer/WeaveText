package com.weavetext.ime.ime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeRecognizer
import com.weavetext.ime.ui.keyboard.VoiceSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 语音会话的预热转发到识别器。 VoiceSession forwards warm-up to the recognizer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceWarmUpTest {
    @Test fun warmUpForwards() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val rec = FakeRecognizer()
        val session = VoiceSession(app, InputController { null }, recognizerProvider = { rec })
        session.warmUp()
        assertEquals(1, rec.warmUps)
    }
}
