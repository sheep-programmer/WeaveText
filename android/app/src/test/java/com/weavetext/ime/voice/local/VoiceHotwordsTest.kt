package com.weavetext.ime.voice.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 个人热词的分词方式必须与声学模型一致：X-ASR 用 BPE 词表，没有词表的模型退回字级。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceHotwordsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = ctx.getSharedPreferences(LocalAsrChoice.PREFS, Context.MODE_PRIVATE)

    private fun spec(id: String, arch: String) = ModelSpec(
        id = id, kind = ModelKind.ASR_STREAMING, arch = arch, name = id, description = "",
        license = "Apache-2.0", builtin = false, archives = emptyList(), files = emptyList(),
        installedSize = 0, bench = null, hfRepo = null,
    )

    @Before fun reset() {
        prefs.edit().clear().putBoolean(VoiceHotwords.ENABLED, true).commit()
        File(ctx.filesDir, "voice-vocabulary").deleteRecursively()
    }

    private fun location() = com.weavetext.ime.models.ModelLocation(assets = null, dir = ctx.filesDir.path)

    /** X-ASR 的 BPE 词表随模型准备，英文热词保持原样大小写。 */
    @Test fun xAsrHotwordsUseItsBpeVocabulary() {
        prefs.edit().putString(VoiceHotwords.TEXT, "in time\n准时").commit()
        val config = VoiceHotwords.prepare(ctx, spec("asr-x-asr-960", "zipformer-transducer"), location())!!
        assertEquals("cjkchar+bpe", config.unit)
        assertTrue(config.vocabulary, File(config.vocabulary).isFile)
        assertTrue(File(config.vocabulary).readText(), File(config.vocabulary).readText().lineSequence().any { it.startsWith("▁in") })
        assertEquals(listOf("in time", "准时"), File(config.file).readLines())
    }

    /** 没有配套词表的模型（例如插件里的实时模型）继续用字级热词，非中文词条被丢弃。 */
    @Test fun modelsWithoutAVocabularyFallBackToCharacterHotwords() {
        prefs.edit().putString(VoiceHotwords.TEXT, "织文输入法\nhello").commit()
        val config = VoiceHotwords.prepare(ctx, spec("plugin-stream", "zipformer-transducer"), location())!!
        assertEquals("cjkchar", config.unit)
        assertEquals("", config.vocabulary)
        assertEquals(listOf("织文输入法"), File(config.file).readLines())
    }

    /** 热词开关关闭或不是 transducer 架构时不生成配置。 */
    @Test fun disabledOrUnsupportedModelsProduceNothing() {
        prefs.edit().putString(VoiceHotwords.TEXT, "准时").commit()
        assertNull(VoiceHotwords.prepare(ctx, spec("asr-stream-small", "zipformer2-ctc"), null))
        prefs.edit().putBoolean(VoiceHotwords.ENABLED, false).commit()
        assertNull(VoiceHotwords.prepare(ctx, spec("asr-x-asr-960", "zipformer-transducer"), null))
    }
}
