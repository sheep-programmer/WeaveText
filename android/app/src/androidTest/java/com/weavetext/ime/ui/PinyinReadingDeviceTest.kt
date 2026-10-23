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
import java.util.UUID

/** 学习只提升匹配读音的候选。 Learning promotes candidates only for a matching reading. */
@RunWith(AndroidJUnit4::class)
class PinyinReadingDeviceTest {
    @Test fun learnedShijianDoesNotReplaceShijiaFromKeyCenterTouches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        CloudWords.get(app)
        LinkManager.get(app)
        val name = "pinyin-reading-${UUID.randomUUID()}"
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
                        editor.setText("")
                        editor.requestFocus()
                        val info = EditorInfo().apply {
                            inputType = InputType.TYPE_CLASS_TEXT
                            initialSelStart = 0; initialSelEnd = 0
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
                    fun touchPair(view: View, x: Float, y: Float) {
                        val time = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(time, time, action, x, y, 0)
                                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                        }
                        kb.flushRender()
                    }
                    fun tap(code: Int) {
                        scenario.onActivity {
                            assertTrue(kb.keyboardView.isShown && kb.keyboardView.width > 0)
                            val key = requireNotNull(kb.keyboardView.keyOf(code))
                            touchPair(kb.keyboardView, key.rect.centerX(), key.rect.centerY())
                        }
                    }
                    fun resetInput() {
                        scenario.onActivity {
                            controller.onFinishInput()
                            editor.setText("")
                            controller.onStartInput(EditorInfo().apply {
                                inputType = InputType.TYPE_CLASS_TEXT
                                initialSelStart = 0; initialSelEnd = 0
                            }, false)
                            kb.onShown(); kb.flushRender()
                        }
                    }
                    "shijian".forEach { tap(it.code) }
                    scenario.onActivity {
                        val index = controller.state.candidates.indexOfFirst { it.text == "时间" }
                        assertTrue(controller.state.candidates.toString(), index >= 0)
                        val node = requireNotNull(kb.topBar.accessibilityNodeProvider.createAccessibilityNodeInfo(100 + index))
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val origin = IntArray(2).also(kb.topBar::getLocationOnScreen)
                        @Suppress("DEPRECATION")
                        node.recycle()
                        touchPair(kb.topBar, bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1])
                        assertEquals("时间", editor.text.toString())
                        assertFalse(controller.state.composing)
                    }
                    // 清空输入上下文，保留同一内核的学习。 Reset input context while retaining the engine's learning.
                    resetInput()
                    "shijia".forEach { tap(it.code) }
                    instrumentation.waitForIdleSync()
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(app.cacheDir, "pinyin-reading-device.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                    scenario.onActivity {
                        assertTrue(controller.state.composing)
                        assertTrue(controller.state.candidates.isNotEmpty())
                        assertNotEquals("shijia must not inherit the learned shijian candidate",
                            "时间", controller.state.candidates.first().text)
                    }
                    tap(KeyCode.SPACE)
                    scenario.onActivity {
                        assertFalse(controller.state.composing)
                        assertTrue(editor.text.isNotEmpty())
                        assertNotEquals("shijia must not commit 时间", "时间", editor.text.toString())
                    }
                    resetInput()
                    "shijian".forEach { tap(it.code) }
                    scenario.onActivity {
                        assertTrue(controller.state.composing)
                        assertEquals("时间", controller.state.candidates.first().text)
                        controller.feature(org.json.JSONObject().put("op", "policy").put("index", 0)
                            .put("text", "时间").put("mode", "pin"))
                    }
                    resetInput()
                    "shijia".forEach { tap(it.code) }
                    scenario.onActivity {
                        assertNotEquals("a manual pin must respect the complete shijia reading",
                            "时间", controller.state.candidates.first().text)
                    }
                    tap(KeyCode.SPACE)
                    scenario.onActivity { assertNotEquals("时间", editor.text.toString()) }
                    resetInput()
                    "shijian".forEach { tap(it.code) }
                    scenario.onActivity { assertEquals("时间", controller.state.candidates.first().text) }
                } finally {
                    if (keyboardReady) scenario.onActivity {
                        try { controller.onFinishInput() } finally { kb.dispose() }
                    }
                }
            }
        } finally {
            try { engine?.close() } finally {
                try { ClipHistory.awaitIo() } finally {
                    root.deleteRecursively()
                    prefNames.forEach(app::deleteSharedPreferences)
                }
            }
        }
    }
}
