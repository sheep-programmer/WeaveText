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
        assertEquals(listOf("asr-sensevoice", "asr-wenet-mixed", "asr-stream-small"), OfflineModelSelection(ctx, repo).ids())
        repo.emit("asr-sensevoice", ModelState.NotInstalled)
        assertEquals(listOf("asr-wenet-mixed", "asr-stream-small"), selection.ids())
        assertTrue(selection.toggle("asr-stream-small"))
        assertFalse(selection.toggle("asr-wenet-mixed"))
    }
    @Test fun languageModesRememberIndependentSelections() {
        val repo = FakeModels(listOf("asr-stream-mixed-medium", "asr-stream-small", "asr-sensevoice", "asr-whisper-base").associateWith { ModelState.Installed })
        val selection = OfflineModelSelection(ctx, repo)
        assertEquals(VoiceLanguage.MIXED, selection.mode)
        selection.select(listOf("asr-stream-mixed-medium", "asr-sensevoice"))
        // 英文档位必须能真正指定 en，不能把双语自动识别当成英文专用识别。
        selection.mode = VoiceLanguage.ENGLISH
        assertEquals(setOf("asr-sensevoice", "asr-whisper-base"), selection.available().map { it.id }.toSet())
        selection.select(listOf("asr-whisper-base"))
        selection.mode = VoiceLanguage.CHINESE
        assertEquals(listOf("asr-sensevoice"), selection.ids())
        selection.select(listOf("asr-stream-small"))
        selection.mode = VoiceLanguage.MIXED
        assertEquals(listOf("asr-stream-mixed-medium", "asr-sensevoice"), selection.ids())
        selection.mode = VoiceLanguage.ENGLISH
        assertEquals(listOf("asr-whisper-base"), selection.ids())
    }

    /** 只有不支持指定语言的双语实时模型时，英文档位必须准备语言可控模型，不能继续出中文。 */
    @Test fun englishModeDoesNotTreatAutoBilingualStreamingAsForcedEnglish() {
        val repo = FakeModels(mapOf("asr-stream-mixed-medium" to ModelState.Installed))
        val selection = OfflineModelSelection(ctx, repo)
        selection.mode = VoiceLanguage.ENGLISH
        assertTrue(selection.ids().isEmpty())
        assertFalse(selection.primaryOk())
        repo.emit("asr-sensevoice", ModelState.Installed)
        assertEquals(listOf("asr-sensevoice"), selection.ids())
        assertTrue(selection.primaryOk())
    }

    @Test fun aSingleMixedStreamingModelUsesAnInstalledFinalCompanion() {
        val repo = FakeModels(listOf("asr-stream-mixed-high", "asr-sensevoice", "asr-stream-small").associateWith { ModelState.Installed })
        val selection = OfflineModelSelection(ctx, repo)
        selection.select(listOf("asr-stream-mixed-high"))
        assertEquals("asr-sensevoice", selection.finalCompanion("asr-stream-mixed-high"))
        selection.select(listOf("asr-stream-mixed-high", "asr-sensevoice"))
        assertNull(selection.finalCompanion("asr-stream-mixed-high"))
        selection.mode = VoiceLanguage.CHINESE
        selection.select(listOf("asr-stream-small"))
        assertNull(selection.finalCompanion("asr-stream-small"))
    }

    @Test fun mixedModeDoesNotReviveALegacyChineseOnlyFinalChoice() {
        val repo = FakeModels(listOf("asr-final-small", "asr-sensevoice").associateWith { ModelState.Installed })
        ctx.getSharedPreferences(LocalAsrChoice.PREFS, 0).edit().putString(LocalAsrChoice.KEY_FINAL, "asr-final-small").commit()
        assertEquals(listOf("asr-sensevoice"), OfflineModelSelection(ctx, repo).ids())
    }

    /** 中文专用模型不能被当成「已满足混说／英文档位」，否则会一直只出中文。 Chinese-only models must not satisfy mixed/English. */
    @Test fun chineseOnlyModelDoesNotSatisfyMixedMode() {
        val repo = FakeModels(mapOf("asr-stream-small" to ModelState.Installed))
        val selection = OfflineModelSelection(ctx, repo)
        assertFalse(selection.primaryOk())
        selection.select(listOf("asr-stream-small"))
        assertFalse("中文专用模型不算满足中英混合 / Chinese-only does not satisfy mixed", selection.primaryOk())
    }

    /** 混说档位下只剩中文专用模型、同时装了双语模型时，自动改用双语模型而不是继续只出中文。 */
    @Test fun mixedModeSkipsAPinnedChineseOnlyModelWhenABilingualOneIsInstalled() {
        val repo = FakeModels(mapOf("asr-stream-small" to ModelState.Installed, "asr-stream-mixed-medium" to ModelState.Installed))
        val selection = OfflineModelSelection(ctx, repo)
        selection.select(listOf("asr-stream-small"))
        assertEquals(listOf("asr-stream-mixed-medium"), selection.ids())
        assertTrue(selection.primaryOk())
    }

}
