package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.settings.TranslationSettings
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.translate.TranslationCall
import com.weavetext.ime.translate.TranslationProtocol
import com.weavetext.ime.translate.TranslationRequest
import com.weavetext.ime.translate.TranslationResult
import com.weavetext.ime.translate.TranslationService
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.TranslationPanel
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import java.io.File
import java.util.UUID
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weavetext.ime.debug.SettingsTestActivity
import com.weavetext.ime.ime.InputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses actual Android EditText InputConnections instead of the JVM editor substitute. */
@RunWith(AndroidJUnit4::class)
class TranslationEditorDeviceTest {
    @Test fun selectedTextCanBeTranslatedAndLateWriteCannotTouchAnotherEditor() {
        ActivityScenario.launch(SettingsTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT; setText("hello world"); setSelection(0, 5) }
                val second = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT; setText("second"); setSelection(6) }
                activity.setContentView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(first); addView(second) })
                val firstInfo = EditorInfo()
                var active = first.onCreateInputConnection(firstInfo)
                val controller = InputController { active }
                controller.onStartInput(firstInfo, false)
                val initial = controller.captureTranslationTarget()!!
                assertEquals("hello", initial.selectedText)
                assertTrue(controller.writeTranslation(initial, "你好", true))
                assertEquals("你好 world", first.text.toString())
                first.setSelection(0, 2)
                controller.onSelectionUpdate(0, 2, -1, -1)
                val stale = controller.captureTranslationTarget()!!
                val secondInfo = EditorInfo()
                active = second.onCreateInputConnection(secondInfo)
                controller.onStartInput(secondInfo, false)
                assertFalse(controller.writeTranslation(stale, "错误", true))
                assertEquals("second", second.text.toString())
                val insertion = controller.captureTranslationTarget()!!
                assertTrue(controller.writeTranslation(insertion, "译文", false))
                assertEquals("second译文", second.text.toString())
            }
        }
    }
    @Test fun inlineDraftUsesRealKeysAndWritesOnlyAfterConfirmation() = verifyInlineDraft(false)

    @Test fun floatingInlineDraftUsesRealKeysAndWritesOnlyAfterConfirmation() = verifyInlineDraft(true)

    private fun verifyInlineDraft(floating: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val namespace = "translation-inline-${UUID.randomUUID()}"
        val preferenceNames = mutableSetOf<String>()
        var keyboard: WeaveKeyboard? = null
        var controller: InputController? = null
        lateinit var field: EditText
        val requests = mutableListOf<TranslationRequest>()
        var complete: ((TranslationResult) -> Unit)? = null
        try {
            ActivityScenario.launch(SettingsTestActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        val context = object : ContextWrapper(activity) {
                            override fun getApplicationContext(): Context = this
                            override fun getSharedPreferences(name: String, mode: Int) =
                                app.getSharedPreferences("$namespace-$name", mode).also { preferenceNames += "$namespace-$name" }
                        }
                        WeavePrefs.of(context).edit()
                            .putBoolean(TranslationSettings.GOOGLE_WEB_MIGRATION, true)
                            .putString(TranslationSettings.PROTOCOL, TranslationProtocol.GENERIC.id)
                            .putBoolean(WeavePrefs.FLOATING, floating)
                            .putBoolean(WeavePrefs.KEY_PREVIEW, false)
                            .putBoolean(WeavePrefs.CLIPBOARD_RECORD, false).commit()
                        field = EditText(activity).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                            showSoftInputOnFocus = false
                            setText("message:"); setSelection(text.length)
                        }
                        val info = EditorInfo()
                        val connection = requireNotNull(field.onCreateInputConnection(info))
                        val input = InputController { connection }.also { controller = it; it.onStartInput(info, false) }
                        val kb = WeaveKeyboard(context, input, object : ImeWindowHost {
                            override fun hideKeyboard() {}
                            override val window get() = activity.window
                        }).also { keyboard = it }
                        activity.setContentView(FrameLayout(activity).apply {
                            addView(field, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                            addView(kb.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                        })
                        field.requestFocus(); kb.onShown(); kb.flushRender()
                        // Inject only the service boundary; mounting and input use the production panel path.
                        val original = requireNotNull(kb.panelNamed("translation"))
                        val host = original.view.parent as ViewGroup
                        host.removeView(original.view)
                        val panel = TranslationPanel(kb, serviceProvider = { object : TranslationService {
                            override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
                                requests += request; complete = callback
                                return TranslationCall {}
                            }
                        } })
                        @Suppress("UNCHECKED_CAST")
                        val cache = WeaveKeyboard::class.java.getDeclaredField("panels").apply { isAccessible = true }
                            .get(kb) as MutableMap<String, com.weavetext.ime.ui.keyboard.KbPanel>
                        cache["translation"] = panel
                        host.addView(panel.view, original.view.layoutParams)
                        panel.applyTheme(); kb.showPanel("translation"); kb.flushRender()
                    }
                    instrumentation.waitForIdleSync()
                    val kb = requireNotNull(keyboard)
                    fun walk(view: View): Sequence<View> = sequence {
                        yield(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(walk(view.getChildAt(i)))
                    }
                    fun click(title: String) = scenario.onActivity {
                        val button = walk(requireNotNull(kb.panel).view).filterIsInstance<Button>().first {
                            it.text.toString() == title || (title == "翻译" && it.text.toString() == "完成")
                        }
                        assertTrue(button.performClick()); kb.flushRender()
                    }
                    fun source() = walk(requireNotNull(kb.panel).view).filterIsInstance<TextView>()
                        .first { it.contentDescription == "翻译原文" }.text.toString()
                    fun screenshot(name: String) {
                        val drawn = java.util.concurrent.CountDownLatch(1)
                        scenario.onActivity {
                            kb.view.postOnAnimation { kb.view.postOnAnimation { drawn.countDown() } }
                        }
                        assertTrue("wait for the rendered frame", drawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        instrumentation.waitForIdleSync()
                        // Software-rendered emulator buffers may be presented after the UI frame callbacks.
                        SystemClock.sleep(500)
                        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try { File(app.cacheDir, if (floating) "floating-$name" else name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                        finally { bitmap.recycle() }
                    }
                    for (character in "hello") scenario.onActivity {
                        val key = requireNotNull(kb.keyboardView.keyOf(character.code))
                        val time = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(time, time + 50, action, key.rect.centerX(), key.rect.centerY(), 0)
                            try { assertTrue(kb.keyboardView.dispatchTouchEvent(event)) } finally { event.recycle() }
                        }
                        kb.flushRender()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertEquals("message:", field.text.toString())
                        assertEquals("hello", source())
                        assertTrue(requests.isEmpty())
                        assertTrue(kb.keyboardView.isShown)
                        val panelPosition = IntArray(2).also(requireNotNull(kb.panel).view::getLocationOnScreen)
                        val keysPosition = IntArray(2).also(kb.keyboardView::getLocationOnScreen)
                        assertTrue(panelPosition[1] + requireNotNull(kb.panel).view.height <= keysPosition[1])
                    }
                    screenshot("translation-inline-draft.png")
                    click("翻译")
                    scenario.onActivity {
                        assertEquals("hello", requests.single().text)
                        assertEquals("message:", field.text.toString())
                        requireNotNull(complete)(TranslationResult.Success("你好"))
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertEquals("message:", field.text.toString())
                        assertTrue(walk(requireNotNull(kb.panel).view).filterIsInstance<TextView>().any { it.text.toString() == "你好" })
                    }
                    screenshot("translation-inline-result.png")
                    scenario.onActivity {
                        val labels = walk(requireNotNull(kb.panel).view).filterIsInstance<Button>().map { it.text.toString() }.toList()
                        assertFalse(labels.contains("替换选区"))
                        assertFalse(labels.contains("插入"))
                        assertFalse(labels.contains("读取选区"))
                    }
                    click("更多")
                    instrumentation.waitForIdleSync()
                    val item = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                        .findAccessibilityNodeInfosByText("插入").first { it.text?.toString() == "插入" }
                    var target = item
                    while (!target.isClickable) target = requireNotNull(target.parent)
                    assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertEquals("message:你好", field.text.toString()) }
                } finally {
                    scenario.onActivity { keyboard?.dispose(); controller?.onFinishInput() }
                }
            }
        } finally { preferenceNames.forEach(app::deleteSharedPreferences) }
    }

    @Test fun actualPasswordEditorCannotBecomeTranslationSource() {
        ActivityScenario.launch(SettingsTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText("secret"); setSelection(0, 6) }
                activity.setContentView(field)
                val info = EditorInfo()
                val connection = field.onCreateInputConnection(info)
                val controller = InputController { connection }
                controller.onStartInput(info, false)
                assertTrue(controller.isSensitiveField)
                assertNull(controller.captureTranslationTarget())
            }
        }
    }
}
