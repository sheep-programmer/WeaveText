package com.weavetext.ime.translate

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.settings.TranslationSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Host behavior when the optional APK is absent: no SDK, model download, or fake translation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleTranslationServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Test fun missingPluginReturnsActionableFailureRatherThanDownloadingModels() {
        val prefs = app.getSharedPreferences("missing-translation-plugin", 0)
        prefs.edit().clear().commit()
        TranslationSettings.setProtocol(prefs, TranslationProtocol.GOOGLE_DEVICE)
        val results = LinkedBlockingQueue<TranslationResult>()
        GoogleTranslationService(app, prefs).translate(TranslationRequest("zh", "en", "你好")) { results.add(it) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (results.isEmpty() && System.nanoTime() < deadline) { Thread.yield(); ShadowLooper.idleMainLooper() }
        val failure = results.poll() as? TranslationResult.Failure
        assertNotNull(failure)
        assertTrue(failure!!.error.message.contains("插件"))
        assertEquals(OfflineTranslationPlugin.State.MISSING, OfflineTranslationPlugin.status(app).state)
    }
    @Test fun cancelledMissingPluginRequestCannotPublishAResult() {
        val prefs = app.getSharedPreferences("cancelled-translation-plugin", 0)
        prefs.edit().clear().commit()
        TranslationSettings.setProtocol(prefs, TranslationProtocol.GOOGLE_DEVICE)
        val results = LinkedBlockingQueue<TranslationResult>()
        val call = GoogleTranslationService(app, prefs).translate(TranslationRequest("zh", "en", "你好")) { results.add(it) }
        call.cancel()
        ShadowLooper.idleMainLooper()
        assertTrue(results.isEmpty())
    }
}
