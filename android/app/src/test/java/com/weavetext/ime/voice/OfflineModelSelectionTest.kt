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
    @Test fun languageModesRememberIndependentSelections() {
        val repo = FakeModels(listOf("asr-stream-mixed-medium", "asr-stream-small", "asr-sensevoice", "asr-whisper-base").associateWith { ModelState.Installed })
        val selection = OfflineModelSelection(ctx, repo)
        assertEquals(VoiceLanguage.MIXED, selection.mode)
        selection.select(listOf("asr-stream-mixed-medium", "asr-sensevoice"))
        // 英文档位必须保留双语模型：中英双语 Zipformer 是用户手上最常见的英文来源，排除它会让英文档位无模型可用。
        // English mode keeps bilingual models: excluding them left the mode with no engine at all.
        selection.mode = VoiceLanguage.ENGLISH
        assertEquals(setOf("asr-stream-mixed-medium", "asr-sensevoice", "asr-whisper-base"), selection.available().map { it.id }.toSet())
        selection.select(listOf("asr-whisper-base"))
        selection.mode = VoiceLanguage.CHINESE
        assertEquals(listOf("asr-sensevoice"), selection.ids())
        selection.select(listOf("asr-stream-small"))
        selection.mode = VoiceLanguage.MIXED
        assertEquals(listOf("asr-stream-mixed-medium", "asr-sensevoice"), selection.ids())
        selection.mode = VoiceLanguage.ENGLISH
        assertEquals(listOf("asr-whisper-base"), selection.ids())
    }

    /**
     * 只装了中英双语实时模型、切到英文档位：必须有可用引擎，且首选就是那个双语模型。
     * 旧实现把 zipformer-transducer 排除在英文档位之外，界面因此显示「未选择引擎／请先下载离线语音包」，
     * 用户即使下载了中英模型也无法用英文。 Only the bilingual streaming model installed, switched to English:
     * there must be a usable engine and it must be that model.
     */
    @Test fun englishModeUsesABilingualModelWhenThatIsWhatIsInstalled() {
        val repo = FakeModels(mapOf("asr-stream-mixed-medium" to ModelState.Installed))
        val selection = OfflineModelSelection(ctx, repo)
        selection.mode = VoiceLanguage.ENGLISH
        assertEquals(listOf("asr-stream-mixed-medium"), selection.ids())
        assertTrue("选中项应满足英文档位 / the selection must satisfy English mode", selection.primaryOk())
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
