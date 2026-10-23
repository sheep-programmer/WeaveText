package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
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
import com.weavetext.ime.core.DataInstaller
import com.weavetext.ime.core.NativeEngine
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 真实窗口按键、选词、退格后的学习证据。 Learning evidence after real-window typing, selection and deletion. */
@RunWith(AndroidJUnit4::class)
class LearningExperienceDeviceTest {
    @Test fun rejectedPairDoesNotTeachANewPhrase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        // 进程级服务必须绑定持久应用上下文，不能保留稍后删除的测试目录。
        // Process services must retain the durable app context, not deleted fixture storage.
        com.weavetext.ime.core.CloudWords.get(app)
        val name = "learning-experience-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name).apply { mkdirs() }
        val prefNames = mutableSetOf<String>()
        val owner = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode).also { prefNames += "$name-$key" }
        }
        assertTrue(WeavePrefs.of(owner).edit().putString(WeavePrefs.KEYBOARDS, "pinyin,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "pinyin").putBoolean(WeavePrefs.PREDICTION, false).commit())
        val engine = NativeEngine.createFromSpec(DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner))!!
        lateinit var kb: WeaveKeyboard
        lateinit var controller: InputController
        var keyboardReady = false
        try {
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        val editor = activity.findViewById<EditText>(android.R.id.edit)
                        editor.showSoftInputOnFocus = false
                        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
                        editor.setText("")
                        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                        controller = InputController { editor.onCreateInputConnection(info) }
                        controller.onStartInput(info, false); controller.attachEngine(engine)
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
                    fun tap(code: Int) {
                        scenario.onActivity {
                            val key = kb.keyboardView.keyOf(code)!!
                            val time = SystemClock.uptimeMillis()
                            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                                val event = MotionEvent.obtain(time, time, action, key.rect.centerX(), key.rect.centerY(), 0)
                                try { kb.keyboardView.dispatchTouchEvent(event) } finally { event.recycle() }
                            }
                            kb.flushRender()
                        }
                    }
                    repeat(3) { round ->
                        scenario.onActivity { activity ->
                            controller.onFinishInput()
                            activity.findViewById<EditText>(android.R.id.edit).setText("")
                            controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
                            kb.onShown(); kb.flushRender()
                        }
                        for ((keys, text) in listOf("riqi" to "日期", "shijian" to "时间")) {
                            keys.forEach { tap(it.code) }
                            scenario.onActivity {
                                val index = controller.state.candidates.indexOfFirst { it.text == text }
                                assertTrue(controller.state.candidates.map { it.text }.toString(), index >= 0)
                                kb.onCandidate(index); kb.flushRender()
                            }
                        }
                        scenario.onActivity { activity ->
                            assertEquals("日期时间", activity.findViewById<EditText>(android.R.id.edit).text.toString())
                        }
                        if (round == 0) tap(KeyCode.DELETE)
                        scenario.onActivity { activity ->
                            assertEquals(if (round == 0) "日期时" else "日期时间", activity.findViewById<EditText>(android.R.id.edit).text.toString())
                            assertEquals("round $round", round == 2, engine.userWords().any { it.text == "日期时间" })
                        }
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(app.cacheDir, "learning-experience-device.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                } finally {
                    if (keyboardReady) scenario.onActivity {
                        try { controller.onFinishInput() } finally { kb.dispose() }
                    }
                }
            }
        } finally {
            engine.close(); root.deleteRecursively(); prefNames.forEach(app::deleteSharedPreferences)
        }
    }
}
