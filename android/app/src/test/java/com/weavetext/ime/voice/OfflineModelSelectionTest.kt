package com.weavetext.ime.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.voice.local.LocalAsrChoice
import com.weavetext.ime.voice.local.OfflineModelSelection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineModelSelectionTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { ctx.getSharedPreferences(LocalAsrChoice.PREFS, 0).edit().clear().commit() }
    @Test fun picksMixedModelAndAllowsUpToThreeInstalledRecognizers() {
        val installed = listOf("asr-stream-small", "asr-sensevoice", "asr-wenet-mixed", "asr-stream-large", "punc-ct", "vad-silero")
        val repo = FakeModels(installed.associateWith { ModelState.Installed })
        val selection = OfflineModelSelection(ctx, repo)
        assertEquals(listOf("asr-sensevoice"), selection.ids())
        assertTrue(selection.toggle("asr-stream-small"))
        assertTrue(selection.toggle("asr-wenet-mixed"))
        assertFalse(selection.toggle("asr-stream-large"))
        assertFalse(selection.toggle("punc-ct"))
        assertFalse(selection.toggle("vad-silero"))
        assertEquals(listOf("asr-sensevoice", "asr-stream-small", "asr-wenet-mixed"), OfflineModelSelection(ctx, repo).ids())
        repo.emit("asr-sensevoice", ModelState.NotInstalled)
        assertEquals(listOf("asr-stream-small", "asr-wenet-mixed"), selection.ids())
        assertTrue(selection.toggle("asr-stream-small"))
        assertFalse(selection.toggle("asr-wenet-mixed"))
    }
}
