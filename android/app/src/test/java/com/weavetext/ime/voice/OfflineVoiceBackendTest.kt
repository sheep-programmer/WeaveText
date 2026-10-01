package com.weavetext.ime.voice

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineVoiceBackendTest {
    @Test fun oldSystemOrCloudPreferenceCannotBypassOfflineDownload() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val prefs = app.getSharedPreferences("weave_voice", 0)
        for (old in listOf("weave.system", "org.example.cloud")) {
            prefs.edit().putString("active", old).putStringSet("also", setOf(old)).commit()
            val (engines, rec) = VoiceBackend.create(app)
            assertTrue(engines.list().isEmpty())
            assertFalse(rec.hasEngine())
            var downloadRequired = false
            assertFalse(rec.start(object : VoiceListener {
                override fun onPartial(text: String) { error("must not recognize") }
                override fun onFinal(text: String) { error("must not recognize") }
                override fun onError(message: String) { downloadRequired = message.contains("下载") }
                override fun onEnd() {}
            }))
            assertTrue(downloadRequired)
            assertFalse(rec.isRunning)
        }
    }
}
