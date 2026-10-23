package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
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
import com.weavetext.ime.ime.ClipHistory
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.link.LinkManager
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 真实窗口里的快捷时间、拆字与计算。 Quick date/time, decomposition and calculation in a real window. */
@RunWith(AndroidJUnit4::class)
class QuickInputDeviceTest {
    @Test fun currentValuesAndCalculationsCanBeSelectedAndCommitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        CloudWords.get(app); LinkManager.get(app)
        val name = "quick-input-${UUID.randomUUID()}"
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
        var ready = false
        try {
            assertTrue(WeavePrefs.of(owner).edit().putString(WeavePrefs.KEYBOARDS, "pinyin,english")
                .putString(WeavePrefs.ACTIVE_KEYBOARD, "pinyin")
                .putBoolean(WeavePrefs.PREDICTION, false).putBoolean(WeavePrefs.CLOUD_WORDS, false).commit())
            val native = requireNotNull(NativeEngine.createFromSpec(
                DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner)))
            engine = native
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        editor = activity.findViewById(android.R.id.edit)
                        editor.showSoftInputOnFocus = false
                        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
                        editor.setText(""); editor.requestFocus()
                        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                        controller = InputController { editor.onCreateInputConnection(info) }
                        controller.onStartInput(info, false); controller.attachEngine(native)
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
                        ready = true
                        activity.addContentView(kb.view, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
                        kb.onShown(); kb.flushRender()
                    }
                    instrumentation.waitForIdleSync()
                    fun touch(view: android.view.View, x: Float, y: Float) {
                        val time = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(time, time, action, x, y, 0)
                            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                        }
                        kb.flushRender()
                    }
                    fun tap(code: Int) = scenario.onActivity {
                        val key = requireNotNull(kb.keyboardView.keyOf(code))
                        touch(kb.keyboardView, key.rect.centerX(), key.rect.centerY())
                    }
                    fun reset() = scenario.onActivity {
                        controller.onFinishInput(); editor.setText("")
                        controller.onStartInput(EditorInfo().apply {
                            inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0
                        }, false)
                        kb.onShown(); kb.flushRender()
                    }
                    fun pick(index: Int) = scenario.onActivity {
                        val node = requireNotNull(kb.topBar.accessibilityNodeProvider.createAccessibilityNodeInfo(100 + index))
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val origin = IntArray(2).also(kb.topBar::getLocationOnScreen)
                        @Suppress("DEPRECATION")
                        node.recycle()
                        touch(kb.topBar, bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1])
                    }
                    "shijian".forEach { tap(it.code) }
                    var value = ""
                    scenario.onActivity {
                        assertEquals("时间", controller.state.candidates[0].text)
                        value = controller.state.candidates[1].text
                        val now = LocalTime.now()
                        val format = DateTimeFormatter.ofPattern("HH:mm")
                        assertTrue(value, value in listOf(now.minusMinutes(1).format(format), now.format(format)))
                    }
                    pick(1)
                    scenario.onActivity { assertEquals(value, editor.text.toString()) }
                    reset()
                    val before = LocalDate.now()
                    "riqi".forEach { tap(it.code) }
                    scenario.onActivity {
                        assertEquals("日期", controller.state.candidates[0].text)
                        value = controller.state.candidates[1].text
                        val format = DateTimeFormatter.ofPattern("yyyy年M月d日")
                        assertTrue(value, value in listOf(before.format(format), LocalDate.now().format(format)))
                    }
                    instrumentation.waitForIdleSync()
                    pick(1)
                    scenario.onActivity { assertEquals(value, editor.text.toString()) }
                    reset()
                    "uhs".forEach { tap(it.code) }
                    tap(KeyCode.SPACE)
                    scenario.onActivity { assertEquals("十", editor.text.toString()) }
                    reset()
                    tap('v'.code)
                    // 数字／符号面板的生产回调。 Production callback used by the number/symbol panels.
                    scenario.onActivity { "（128＋32）×4=".forEach { controller.onText(it.toString()) }; kb.flushRender() }
                    scenario.onActivity {
                        assertTrue(controller.state.composing)
                        assertEquals("640", controller.state.candidates.first().text)
                        assertEquals("", editor.text.toString())
                    }
                    tap(KeyCode.SPACE)
                    scenario.onActivity { assertEquals("640", editor.text.toString()) }
                    reset()
                    scenario.onActivity {
                        "128*4=".forEach { controller.onText(it.toString()) }; kb.flushRender()
                        assertEquals("128*4=", editor.text.toString())
                        val result = requireNotNull(kb.topBar.accessibilityNodeProvider.createAccessibilityNodeInfo(100))
                        assertEquals("512", result.contentDescription.toString())
                        @Suppress("DEPRECATION")
                        result.recycle()
                    }
                    pick(0)
                    scenario.onActivity { assertEquals("128*4=512", editor.text.toString()) }
                } finally {
                    if (ready) scenario.onActivity { try { controller.onFinishInput() } finally { kb.dispose() } }
                }
            }
        } finally {
            try { engine?.close() } finally {
                try { ClipHistory.awaitIo() } finally { root.deleteRecursively(); prefNames.forEach(app::deleteSharedPreferences) }
            }
        }
    }
}
