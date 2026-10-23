package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.translate.*
import com.weavetext.ime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationPanelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun button(view: View, title: String): Button? {
        if (view is Button && (view.text.toString() == title || (title == "翻译" && (view.text == "完成" || view.text.endsWith(" 翻译"))))) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), title)?.let { return it }
        return null
    }
    private fun action(view: View, title: String) {
        button(view, "更多")!!.performClick()
        val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
        val item = (0 until popup.menu.size()).map(popup.menu::getItem).first { it.title == title }
        if (item.isEnabled) org.robolectric.Shadows.shadowOf(popup).onMenuItemClickListener.onMenuItemClick(item)
        popup.dismiss()
    }
    private fun texts(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        is android.widget.TextView -> listOf(view.text.toString())
        else -> emptyList()
    }

    private fun withKeyboard(block: (WeaveKeyboard, InputController, FakeInputConnection) -> Unit) {
        WeavePrefs.of(app).edit().clear()
            .putBoolean(com.weavetext.ime.settings.TranslationSettings.GOOGLE_WEB_MIGRATION, true)
            .putString(com.weavetext.ime.settings.TranslationSettings.PROTOCOL, TranslationProtocol.GENERIC.id).commit()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        val ic = object : FakeInputConnection(frame) {
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) =
                android.view.inputmethod.ExtractedText().apply {
                    text = editable.toString()
                    selectionStart = android.text.Selection.getSelectionStart(editable)
                    selectionEnd = android.text.Selection.getSelectionEnd(editable)
                }
        }
        val controller = InputController { ic }
        controller.attachEngine(com.weavetext.ime.testing.FakeEngine())
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0
        }, false)
        val kb = WeaveKeyboard(activity, controller, object : ImeWindowHost {
            override fun hideKeyboard() {}
            override val window: android.view.Window? get() = null
        })
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(frame); kb.onShown()
        try { block(kb, controller, ic) } finally { kb.dispose() }
    }

    // Hiding the key area or overlaying it makes continued typing impossible.
    @Test fun translationStaysAboveVisibleKeysAndRestoresKeyboardHeightOnClose() = withKeyboard { kb, _, _ ->
        fun layout() {
            kb.view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
            kb.view.layout(0, 0, kb.view.measuredWidth, kb.view.measuredHeight)
        }
        layout()
        val originalHeight = kb.view.height
        kb.showPanel("translation"); layout()
        val panel = kb.panel!!
        assertEquals(View.VISIBLE, kb.keyboardView.visibility)
        assertEquals(View.GONE, kb.full.visibility)
        val sourceLocation = IntArray(2); val keysLocation = IntArray(2)
        panel.view.getLocationOnScreen(sourceLocation); kb.keyboardView.getLocationOnScreen(keysLocation)
        assertTrue("translation must end before the key area", sourceLocation[1] + panel.view.height <= keysLocation[1])
        assertTrue("inline content must get its own height", kb.view.height > originalHeight)
        kb.closePanel(); layout()
        assertEquals(originalHeight, kb.view.height)
    }

    @Test fun languagePickersStayVisibleAboveTheDraft() = withKeyboard { kb, controller, _ ->
        kb.showPanel("translation")
        controller.onText("hello"); kb.flushRender()
        ShadowLooper.idleMainLooper()
        kb.view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
        kb.view.layout(0, 0, kb.view.measuredWidth, kb.view.measuredHeight)
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val views = descendants(kb.panel!!.view)
        val source = views.first { it.contentDescription == "翻译原文" }
        val position = IntArray(2).also(source::getLocationOnScreen)
        for (picker in views.filterIsInstance<android.widget.Spinner>()) {
            val visible = android.graphics.Rect()
            assertTrue("language picker must be visible without scrolling: size=${picker.width}x${picker.height} shown=${picker.isShown} parent=${(picker.parent as View).width}x${(picker.parent as View).height} root=${kb.view.width}x${kb.view.height}", picker.getGlobalVisibleRect(visible))
            assertTrue("languages precede the draft", visible.bottom <= position[1] || visible.right <= position[0])
        }
    }

    @Test fun translationHasNoExtraActionRows() = withKeyboard { kb, _, _ ->
        kb.showPanel("translation"); ShadowLooper.idleMainLooper()
        for (label in listOf("替换选区", "插入", "复制", "清空", "读取选区", "粘贴文字", "网页翻译")) {
            assertNull("$label belongs in the menu, not in the translation layout", button(kb.panel!!.view, label))
        }
        button(kb.panel!!.view, "更多")!!.performClick()
        val menu = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu().menu
        assertTrue((0 until menu.size()).any { menu.getItem(it).title == "插入" })
    }

    // Capturing a translation target on show used to discard the active candidate flow.
    @Test fun openingTranslationPreservesPreeditAndTracksNormalTyping() = withKeyboard { kb, controller, ic ->
        controller.onChar('n'.code)
        val preedit = controller.state.preedit
        kb.showPanel("translation")
        assertEquals(preedit, controller.state.preedit)
        assertTrue(controller.state.composing)
        assertTrue(texts(kb.panel!!.view).contains(preedit))
        controller.onChar('i'.code); kb.flushRender()
        assertTrue(texts(kb.panel!!.view).contains(controller.state.preedit))
        controller.onCandidate(0); kb.flushRender()
        assertEquals("", ic.text)
        assertTrue(texts(kb.panel!!.view).contains("【ni】"))
        kb.closePanel()
        controller.onText("正文")
        assertEquals("正文", ic.text)
    }

    // Translating a stale preview would send the wrong text and bind replacement to an empty selection.
    @Test fun freshlyTypedTextCanBeTranslatedAndReplacedWithoutSelectingIt() = withKeyboard { kb, controller, ic ->
        val requests = mutableListOf<String>()
        val panel = TranslationPanel(kb, serviceProvider = { object : TranslationService {
            override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
                requests += request.text; callback(TranslationResult.Success("hello")); return TranslationCall {}
            }
        } })
        (kb.view as ViewGroup).addView(panel.view)
        panel.onShow()
        controller.onText("你好"); panel.onState(controller.state)
        button(panel.view, "翻译")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(listOf("你好"), requests)
        assertTrue(texts(panel.view).contains("hello"))
        assertEquals("", ic.text)
        action(panel.view, "插入")
        assertEquals("hello", ic.text)
    }

    @Test fun hardwareEditingCannotEscapeTranslationDraft() = withKeyboard { kb, controller, ic ->
        ic.commitText("宿主", 1)
        controller.onSelectionUpdate(2, 2, -1, -1)
        kb.showPanel("translation")
        controller.onText("abc")
        val hardware = com.weavetext.ime.ime.HardwareKeys(controller)
        fun press(code: Int, meta: Int = 0) {
            assertTrue("draft must consume key $code", hardware.onKeyDown(code,
                android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta)))
        }
        press(android.view.KeyEvent.KEYCODE_ENTER)
        press(android.view.KeyEvent.KEYCODE_MOVE_HOME)
        press(android.view.KeyEvent.KEYCODE_FORWARD_DEL)
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        press(android.view.KeyEvent.KEYCODE_MOVE_END)
        press(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON)
        press(android.view.KeyEvent.KEYCODE_X, android.view.KeyEvent.META_CTRL_ON)
        press(android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.META_CTRL_ON)
        kb.flushRender()
        assertEquals("宿主", ic.text)
    }

    @Test fun editingClearingChangingLanguageAndClosingDiscardLateResults() = withKeyboard { kb, controller, ic ->
        var finish: ((TranslationResult) -> Unit)? = null
        val panel = TranslationPanel(kb, serviceProvider = { object : TranslationService {
            override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
                finish = callback; return TranslationCall {}
            }
        } })
        (kb.view as ViewGroup).addView(panel.view)
        panel.onShow(); ShadowLooper.idleMainLooper()
        controller.onText("原文"); panel.onState(controller.state)
        button(panel.view, "翻译")!!.performClick()
        controller.onText("改动"); panel.onState(controller.state)
        finish!!(TranslationResult.Success("过期一")); ShadowLooper.idleMainLooper()
        assertFalse(texts(panel.view).contains("过期一"))
        button(panel.view, "翻译")!!.performClick()
        action(panel.view, "清空")
        finish!!(TranslationResult.Success("过期二")); ShadowLooper.idleMainLooper()
        assertFalse(texts(panel.view).contains("过期二"))
        controller.onText("新的"); panel.onState(controller.state)
        button(panel.view, "翻译")!!.performClick()
        fun spinner(v: View): android.widget.Spinner? {
            if (v is android.widget.Spinner) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) spinner(v.getChildAt(i))?.let { return it }
            return null
        }
        spinner(panel.view)!!.setSelection(1); ShadowLooper.idleMainLooper()
        finish!!(TranslationResult.Success("过期三")); ShadowLooper.idleMainLooper()
        assertFalse(texts(panel.view).contains("过期三"))
        button(panel.view, "翻译")!!.performClick(); panel.onHide()
        finish!!(TranslationResult.Success("过期四")); ShadowLooper.idleMainLooper()
        assertFalse(texts(panel.view).contains("过期四"))
        assertEquals("", ic.text)
    }

    @Test fun changingFieldsDiscardsDraftAndCannotReceiveLateTranslation() = withKeyboard { kb, controller, ic ->
        var finish: ((TranslationResult) -> Unit)? = null
        val panel = TranslationPanel(kb, serviceProvider = { object : TranslationService {
            override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
                finish = callback; return TranslationCall {}
            }
        } })
        (kb.view as ViewGroup).addView(panel.view)
        panel.onShow(); ShadowLooper.idleMainLooper()
        controller.onText("旧草稿"); panel.onState(controller.state)
        button(panel.view, "翻译")!!.performClick()
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        finish!!(TranslationResult.Success("迟到")); ShadowLooper.idleMainLooper()
        action(panel.view, "插入")
        assertEquals("", ic.text)
        assertFalse(texts(panel.view).contains("迟到"))
        panel.onHide()
    }

    @Test fun draftBackspaceAndSpaceKeepTheHostIntact() = withKeyboard { kb, controller, ic ->
        kb.showPanel("translation")
        controller.onText("ab")
        controller.onBackspace()
        controller.onSpace(periodShortcut = false); kb.flushRender()
        assertTrue(texts(kb.panel!!.view).contains("a "))
        assertEquals("", ic.text)
    }

    @Test @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun inlineTranslationFitsFloatingLandscapeWithoutClippingTheKeys() = withKeyboard { kb, _, _ ->
        kb.toggleFloating(); ShadowLooper.idleMainLooper()
        kb.showPanel("translation"); ShadowLooper.idleMainLooper()
        kb.view.measure(View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        kb.view.layout(0, 0, 2200, 900)
        val card = kb.board.parent as View
        assertTrue("floating card fits short viewport", card.height <= 900)
        val keys = IntArray(2); val strip = IntArray(2); val board = IntArray(2)
        kb.keyboardView.getLocationOnScreen(keys); kb.panel!!.view.getLocationOnScreen(strip)
        kb.board.getLocationOnScreen(board)
        assertTrue(strip[1] + kb.panel!!.view.height <= keys[1])
        assertTrue("bottom key row stays in board", keys[1] + kb.keyboardView.height <= board[1] + kb.board.height)
        assertTrue("translate remains visible", button(kb.panel!!.view, "翻译")!!.getGlobalVisibleRect(android.graphics.Rect()))
        assertNull("no extra insert control in short windows", button(kb.panel!!.view, "插入"))
        fun source(view: View): View? {
            if (view.contentDescription == "翻译原文") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) source(view.getChildAt(i))?.let { return it }
            return null
        }
        val visible = android.graphics.Rect()
        val draft = source(kb.panel!!.view)!!
        assertTrue("draft remains visible in a short window", draft.getGlobalVisibleRect(visible))
        assertTrue("draft needs a readable line", visible.height() >= kb.metrics.dp(24f))
    }

    @Test fun localTranslationDraftRejectsRichContentWithoutWritingToHost() = withKeyboard { kb, controller, ic ->
        var commits = 0
        val richHost = object : FakeInputConnection(kb.view) {
            override fun commitContent(content: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                commits++; return true
            }
        }
        val richController = InputController { richHost }
        richController.attachEngine(com.weavetext.ime.testing.FakeEngine())
        richController.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0
            androidx.core.view.inputmethod.EditorInfoCompat.setContentMimeTypes(this, arrayOf("image/*"))
        }, false)
        val local = android.view.inputmethod.BaseInputConnection(kb.view, true)
        assertNotNull(richController.beginTranslationDraft(local))
        assertFalse(richController.onContent(android.net.Uri.parse("content://test/image"), "image/png", "image"))
        assertEquals(0, commits)
        richController.endTranslationDraft(local)
        assertTrue(richController.onContent(android.net.Uri.parse("content://test/image"), "image/png", "image"))
        assertEquals(1, commits)
    }

    @Test fun lateVoiceResultCannotEscapeAClosedTranslationDraft() = withKeyboard { kb, controller, ic ->
        kb.showPanel("translation")
        controller.voiceBegin(); controller.voicePartial("草稿")
        kb.closePanel()
        controller.voiceFinal("迟到")
        assertEquals("", ic.text)
    }

    @Test fun selectedTextTranslatesAndRequiresExplicitWriteback() {
        WeavePrefs.of(app).edit().clear()
            .putBoolean(com.weavetext.ime.settings.TranslationSettings.GOOGLE_WEB_MIGRATION, true)
            .putString(com.weavetext.ime.settings.TranslationSettings.PROTOCOL, com.weavetext.ime.translate.TranslationProtocol.GOOGLE_DEVICE.id)
            .putString(com.weavetext.ime.settings.TranslationSettings.SOURCE_LANGUAGE, "en")
            .putString(com.weavetext.ime.settings.TranslationSettings.TARGET_LANGUAGE, "zh").commit()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        val ic = FakeInputConnection(frame)
        ic.commitText("hello", 1); ic.setSelection(0, 5)
        val controller = InputController { ic }
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 5 }, false)
        val kb = WeaveKeyboard(activity, controller, object : ImeWindowHost {
            override fun hideKeyboard() {}
            override val window: android.view.Window? get() = null
        })
        try {
            frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
            activity.setContentView(frame); kb.onShown()
            var request: TranslationRequest? = null
            val panel = TranslationPanel(kb, serviceProvider = { object : TranslationService {
                override fun translate(r: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
                    request = r; callback(TranslationResult.Success("你好")); return TranslationCall {}
                }
            } })
            frame.addView(panel.view, FrameLayout.LayoutParams(-1, 900))
            panel.applyTheme(); panel.onShow(); ShadowLooper.idleMainLooper()
            button(panel.view, "翻译")!!.performClick(); ShadowLooper.idleMainLooper()
            assertEquals("hello", request!!.text)
            assertEquals("en", request!!.sourceLanguage)
            assertEquals("zh", request!!.targetLanguage)
            assertEquals("hello", ic.text)
            val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")
            panel.view.captureRoboImage(File(dir, "keyboard_translation_light.png").path)
            action(panel.view, "复制")
            assertEquals("你好", (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
            action(panel.view, "替换选区")
            assertEquals("你好", ic.text)
        } finally { kb.dispose() }
    }
}
