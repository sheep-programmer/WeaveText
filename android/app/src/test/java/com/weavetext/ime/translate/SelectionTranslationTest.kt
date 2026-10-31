package com.weavetext.ime.translate

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.settings.TranslationSettings
import com.weavetext.ime.settings.WeavePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 系统「文字选择」菜单的翻译入口：读取选中文字、按设置构造请求、把译文写回宿主。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectionTranslationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun readsTheSelectedTextAndWhetherTheHostAllowsReplacement() {
        val editable = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "你好").putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val incoming = SelectionTranslation.incoming(editable)
        assertEquals("你好", incoming.text)
        assertFalse(incoming.readOnly)
        val readOnly = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "hello").putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        assertTrue(SelectionTranslation.incoming(readOnly).readOnly)
        assertEquals("", SelectionTranslation.incoming(Intent()).text)
    }

    @Test fun requestUsesTheSavedLanguagesAndDefaultsToEnglishTarget() {
        val defaults = SelectionTranslation.request(prefs, "你好")
        assertEquals("auto", defaults.sourceLanguage)
        assertEquals("en", defaults.targetLanguage)
        prefs.edit().putString(TranslationSettings.SOURCE_LANGUAGE, "zh")
            .putString(TranslationSettings.TARGET_LANGUAGE, "ja").commit()
        val saved = SelectionTranslation.request(prefs, "你好")
        assertEquals("zh", saved.sourceLanguage)
        assertEquals("ja", saved.targetLanguage)
        assertEquals(null, saved.validationError())
    }

    @Test fun replacementReturnsTheTranslationAsTheProcessedText() {
        val result = SelectionTranslation.replacementResult("hello")
        assertEquals("hello", result.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
    }
}
