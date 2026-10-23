package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.core.CloudWords
import com.weavetext.ime.core.DataInstaller
import com.weavetext.ime.core.NativeEngine
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 真实窗口中的光标横滑与候选抬手坐标。 Cursor slides and final candidate coordinates in a real window. */
@RunWith(AndroidJUnit4::class)
class TypingGestureDeviceTest {
    @Test fun cursorExcursionAndCandidateReleaseKeepTheirGestureMeaning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        // 进程级服务使用持久上下文，测试数据单独存放。 Process services retain durable storage; fixture data is isolated.
        CloudWords.get(app)
        com.weavetext.ime.link.LinkManager.get(app)
        val name = "typing-gesture-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name).apply { mkdirs() }
        val prefNames = mutableSetOf<String>()
        val owner = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) =
                app.getSharedPreferences("$name-$key", mode).also { prefNames += "$name-$key" }
        }
        var engine: NativeEngine? = null
        lateinit var kb: WeaveKeyboard
        lateinit var controller: InputController
        lateinit var editor: EditText
        var keyboardReady = false
        try {
            assertTrue(WeavePrefs.of(owner).edit()
                .putString(WeavePrefs.KEYBOARDS, "pinyin,english")
                .putString(WeavePrefs.ACTIVE_KEYBOARD, "pinyin")
                .putString(WeavePrefs.TOOLBAR_ITEMS, "keyboard,voice,emoji,clipboard,hide")
                .putBoolean(WeavePrefs.PREDICTION, false)
                .putBoolean(WeavePrefs.CLOUD_WORDS, false).commit())
            val native = requireNotNull(NativeEngine.createFromSpec(
                DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner)))
            engine = native
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        editor = activity.findViewById(android.R.id.edit)
                        editor.showSoftInputOnFocus = false
                        activity.getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(editor.windowToken, 0)
                        editor.setText("hello")
                        editor.requestFocus()
                        editor.setSelection(5)
                        val info = EditorInfo().apply {
                            inputType = InputType.TYPE_CLASS_TEXT
                            initialSelStart = 5; initialSelEnd = 5
                        }
                        controller = InputController { editor.onCreateInputConnection(info) }
                        controller.onStartInput(info, false)
                        controller.attachEngine(native)
                        val context = object : ContextWrapper(activity) {
                            override fun getApplicationContext(): Context = owner
                            override fun getFilesDir() = owner.filesDir
                            override fun getCacheDir() = owner.cacheDir
                            override fun getSharedPreferences(key: String, mode: Int) = owner.getSharedPreferences(key, mode)
                        }
                        kb = WeaveKeyboard(context, controller, object : ImeWindowHost {
                            override fun hideKeyboard() {}
                            override val window get() = activity.window
                        })
                        keyboardReady = true
                        activity.addContentView(kb.view, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
                        kb.onShown(); kb.flushRender()
                    }
                    instrumentation.waitForIdleSync()
                    var downTime = 0L
                    fun touch(view: View, action: Int, x: Float, y: Float) {
                        scenario.onActivity {
                            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                            kb.flushRender()
                        }
                    }
                    fun touchPair(view: View, x: Float, y: Float, endX: Float = x) {
                        scenario.onActivity {
                            val start = SystemClock.uptimeMillis()
                            // 同一次主线程调用回放短手势，避免调度延迟触发长按。
                            // Replay a short gesture in one main-thread call so scheduling cannot turn it into a long press.
                            for ((action, at, time) in listOf(
                                Triple(MotionEvent.ACTION_DOWN, x, start),
                                Triple(MotionEvent.ACTION_UP, endX, start + 100),
                            )) {
                                val event = MotionEvent.obtain(start, time, action, at, y, 0)
                                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                            }
                            kb.flushRender()
                        }
                    }
                    fun tap(code: Int) {
                        var x = 0f; var y = 0f
                        scenario.onActivity {
                            val key = requireNotNull(kb.keyboardView.keyOf(code))
                            x = key.rect.centerX(); y = key.rect.centerY()
                        }
                        touchPair(kb.keyboardView, x, y)
                    }
                    var x = 0f; var y = 0f; var unit = 0f
                    scenario.onActivity {
                        assertTrue(kb.keyboardView.isShown && kb.keyboardView.width > 0)
                        val space = requireNotNull(kb.keyboardView.keyOf(KeyCode.SPACE))
                        x = space.rect.centerX(); y = space.rect.centerY(); unit = kb.metrics.density
                    }
                    touch(kb.keyboardView, MotionEvent.ACTION_DOWN, x, y)
                    touch(kb.keyboardView, MotionEvent.ACTION_MOVE, x - 42f * unit, y)
                    scenario.onActivity { assertEquals(3, editor.selectionEnd) }
                    SystemClock.sleep(100)
                    touch(kb.keyboardView, MotionEvent.ACTION_MOVE, x - 18f * unit, y)
                    scenario.onActivity { assertEquals(5, editor.selectionEnd) }
                    touch(kb.keyboardView, MotionEvent.ACTION_UP, x - 18f * unit, y)
                    scenario.onActivity {
                        assertEquals("hello", editor.text.toString())
                        assertEquals(5, editor.selectionEnd)
                    }
                    tap(KeyCode.SPACE)
                    scenario.onActivity { assertEquals("hello ", editor.text.toString()) }
                    tap('n'.code); tap('i'.code)
                    var candidate = ""
                    scenario.onActivity {
                        assertTrue(controller.state.composing)
                        candidate = controller.state.candidates.first().text
                        val node = requireNotNull(kb.topBar.accessibilityNodeProvider.createAccessibilityNodeInfo(100))
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val origin = IntArray(2).also(kb.topBar::getLocationOnScreen)
                        x = bounds.exactCenterX() - origin[0]
                        y = bounds.exactCenterY() - origin[1]
                        @Suppress("DEPRECATION")
                        node.recycle()
                    }
                    // 快速横滑只有 DOWN 和 UP，也不能选词。 A fast horizontal DOWN/UP slide must not select a word.
                    touchPair(kb.topBar, x, y, x + 80f * unit)
                    scenario.onActivity {
                        assertEquals("hello ", editor.text.toString())
                        assertEquals("ni", controller.state.preedit)
                    }
                    touchPair(kb.topBar, x, y, x + 3f * unit)
                    scenario.onActivity {
                        assertEquals("hello $candidate", editor.text.toString())
                        assertFalse(controller.state.composing)
                    }
                    scenario.onActivity {
                        editor.setText("")
                        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        val info = EditorInfo().apply {
                            inputType = editor.inputType
                            initialSelStart = 0; initialSelEnd = 0
                        }
                        controller.onStartInput(info, false)
                        controller.toggleChinese()
                        kb.onShown(); kb.flushRender()
                        assertFalse(controller.state.chinese)
                    }
                    tap('a'.code)
                    scenario.onActivity {
                        assertEquals("A", controller.state.preedit)
                        val key = requireNotNull(kb.keyboardView.keyOf(KeyCode.DELETE))
                        x = key.rect.centerX(); y = key.rect.centerY()
                    }
                    touch(kb.keyboardView, MotionEvent.ACTION_DOWN, x, y)
                    SystemClock.sleep(430)
                    touch(kb.keyboardView, MotionEvent.ACTION_UP, x, y)
                    scenario.onActivity { assertFalse(controller.state.composing) }
                    tap('b'.code); tap(KeyCode.SPACE)
                    scenario.onActivity { assertEquals("B ", editor.text.toString()) }
                    SystemClock.sleep(250)
                    instrumentation.waitForIdleSync()
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(app.cacheDir, "typing-gesture-device.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                    fun tapToolbar(id: Int) {
                        scenario.onActivity {
                            val node = requireNotNull(kb.topBar.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
                            val bounds = Rect().also(node::getBoundsInScreen)
                            val origin = IntArray(2).also(kb.topBar::getLocationOnScreen)
                            x = bounds.exactCenterX() - origin[0]
                            y = bounds.exactCenterY() - origin[1]
                        }
                        touchPair(kb.topBar, x, y)
                    }
                    val emoji = com.weavetext.ime.style.ToolIds.EMOJI
                    tapToolbar(emoji)
                    scenario.onActivity {
                        assertTrue(kb.panel is com.weavetext.ime.ui.keyboard.SymbolPanel)
                        assertEquals(emoji, kb.topBar.activeTool)
                        assertEquals("已打开", kb.topBar.accessibilityNodeProvider
                            .createAccessibilityNodeInfo(emoji)?.stateDescription)
                    }
                    SystemClock.sleep(300)
                    instrumentation.waitForIdleSync()
                    val emojiBitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(app.cacheDir, "emoji-toolbar-selected-device.png").outputStream().use {
                            assertTrue(emojiBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { emojiBitmap.recycle() }
                    tapToolbar(emoji)
                    scenario.onActivity { assertEquals(-1, kb.topBar.activeTool); assertEquals(null, kb.panel) }
                    tapToolbar(emoji)
                    tapToolbar(com.weavetext.ime.style.ToolIds.CLIPBOARD)
                    scenario.onActivity {
                        assertEquals(com.weavetext.ime.style.ToolIds.CLIPBOARD, kb.topBar.activeTool)
                        assertEquals(null, kb.topBar.accessibilityNodeProvider
                            .createAccessibilityNodeInfo(emoji)?.stateDescription)
                    }
                } finally {
                    if (keyboardReady) scenario.onActivity {
                        try { controller.onFinishInput() } finally { kb.dispose() }
                    }
                }
            }
        } finally {
            try { engine?.close() } finally {
                try { com.weavetext.ime.ime.ClipHistory.awaitIo() } finally {
                    root.deleteRecursively()
                    prefNames.forEach(app::deleteSharedPreferences)
                }
            }
        }
    }
}
