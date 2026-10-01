package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 剪贴板隐私：默认不记录、私密输入框不显示、设置里清空。 Clipboard privacy behaviour. */
@RunWith(RobolectricTestRunner::class)
class ClipboardPrivacyTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        WeavePrefs.of(app).edit().clear().commit()
        // 上一个测试的剪贴板写入可能还在后台排队。 A previous test's clip writes may still be queued.
        com.weavetext.ime.ime.ClipHistory.awaitIo()
        com.weavetext.ime.ime.ClipHistory.resetShared()
        File(app.filesDir, "clipboard").deleteRecursively()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller = InputController { null }
        kb = WeaveKeyboard(activity, controller, host)
    }

    @After fun tearDown() { kb.dispose() }

    private fun copy(text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("t", text))
        ShadowLooper.idleMainLooper()
    }

    private fun field(inputType: Int, imeOptions: Int = 0) {
        controller.onStartInput(EditorInfo().also { it.inputType = inputType; it.imeOptions = imeOptions }, false)
        kb.onShown()
    }

    @Test fun recordingIsOffUntilAsked() {
        val p = WeavePrefs.of(app)
        assertFalse(WeavePrefs.clipboardRecord(p))
        assertFalse(WeavePrefs.clipboardAsked(p))
        field(InputType.TYPE_CLASS_TEXT)
        copy("你好")
        assertEquals(0, kb.clipboard.history.size)
        // 最近复制 chip 仍然出现（只是当前剪贴板）。 The chip still shows the current clip.
        assertEquals("你好", kb.topBar.clipChip)
    }

    @Test fun recordsWhenEnabled() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        field(InputType.TYPE_CLASS_TEXT)
        copy("记下我")
        assertEquals(listOf("记下我"), kb.clipboard.history.list(System.currentTimeMillis()).map { it.text })
    }

    @Test fun passwordFieldNeitherRecordsNorShowsChip() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(controller.isSensitiveField)
        copy("secret")
        assertEquals(0, kb.clipboard.history.size)
        assertNull(kb.topBar.clipChip)
    }

    @Test fun noPersonalizedLearningFieldIsPrivate() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        field(InputType.TYPE_CLASS_TEXT)
        copy("先复制")
        assertEquals("先复制", kb.topBar.clipChip)
        // 切到无痕输入框：chip 立即消失。 Switching to an incognito field hides the chip.
        field(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(controller.isSensitiveField)
        assertNull(kb.topBar.clipChip)
        copy("无痕")
        assertEquals(listOf("先复制"), kb.clipboard.history.list(System.currentTimeMillis()).map { it.text })
    }

    @Test fun clearSignalFromSettingsEmptiesHistory() {
        val p = WeavePrefs.of(app)
        p.edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        field(InputType.TYPE_CLASS_TEXT)
        copy("a"); copy("b")
        assertEquals(2, kb.clipboard.history.size)
        p.edit().putLong(WeavePrefs.CLIPBOARD_CLEARED, 1L).commit()
        assertEquals(0, kb.clipboard.history.size)
    }
}
