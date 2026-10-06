package com.weavetext.ime.ime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeRecognizer
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.voice.VoiceAutoDownload
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 打开语音面板会检查补装策略并预热识别器，不需要先点击录音。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceWarmUpTest {
    @Test fun warmUpForwards() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val rec = FakeRecognizer()
        val session = VoiceSession(app, InputController { null }, recognizerProvider = { rec })
        val previous = VoiceAutoDownload.ensureOverride
        var preparations = 0
        try {
            VoiceAutoDownload.ensureOverride = { preparations++; false }
            session.warmUp()
            assertEquals(1, preparations)
            assertEquals(1, rec.warmUps)
        } finally { VoiceAutoDownload.ensureOverride = previous }
    }
}
