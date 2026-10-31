package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Direct slides use the packaged recognizer without replacing the visible QWERTY/T9 keys. */
@RunWith(AndroidJUnit4::class)
class DirectHandwritingDeviceTest {
    @Test fun qwertyAndT9SlidesRecognizeInkAndRestoreTheirSchema() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "direct-hand-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name).apply { mkdirs() }
        val prefNames = mutableSetOf<String>()
        val owner = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) =
                app.getSharedPreferences("$name-$key", mode).also { prefNames += "$name-$key" }
        }
        assertTrue(WeavePrefs.of(owner).edit()
            .putString(WeavePrefs.KEYBOARDS, "pinyin,t9,hand,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "pinyin")
            .putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit())
        val engine = requireNotNull(NativeEngine.createFromSpec(
            DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner)))
        lateinit var kb: WeaveKeyboard
        lateinit var controller: InputController
        try {
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    val editor = activity.findViewById<EditText>(android.R.id.edit)
                    editor.showSoftInputOnFocus = false
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.setText("")
                    val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                    controller = InputController { editor.onCreateInputConnection(info) }
                    controller.onStartInput(info, false)
                    controller.attachEngine(engine)
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
                    activity.addContentView(kb.view, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
                    kb.onShown(); kb.flushRender()
                }
                fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) = scenario.onActivity {
                    val start = SystemClock.uptimeMillis()
                    for ((action, x, y) in listOf(
                        Triple(MotionEvent.ACTION_DOWN, x1, y1),
                        Triple(MotionEvent.ACTION_MOVE, x2, y2),
                        Triple(MotionEvent.ACTION_UP, x2, y2),
                    )) {
                        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                        try { assertTrue(kb.keyboardView.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                    kb.flushRender()
                }
                fun drawCross(code: Int) {
                    var x = 0f; var y = 0f; var unit = 0f
                    scenario.onActivity {
                        val key = requireNotNull(kb.keyboardView.keyOf(code))
                        x = key.rect.centerX(); y = key.rect.centerY(); unit = kb.metrics.density
                    }
                    stroke(x, y, x + 48f * unit, y)
                    stroke(x + 24f * unit, y - 24f * unit, x + 24f * unit, y + 24f * unit)
                }
                fun writeCross(code: Int, returnSchema: String) {
                    drawCross(code)
                    scenario.onActivity {
                        assertEquals("hand", controller.state.schema)
                        assertNotNull(kb.keyboardView.hand)
                        assertNotNull("the original character key stays in place", kb.keyboardView.keyOf(code))
                        val index = controller.state.candidates.indexOfFirst { it.text == "十" }
                        assertTrue(controller.state.candidates.map { it.text }.toString(), index >= 0)
                        kb.onCandidate(index)
                        kb.flushRender()
                        assertEquals(returnSchema, controller.state.schema)
                        assertNull(kb.keyboardView.hand)
                    }
                }
                writeCross('q'.code, "pinyin")
                scenario.onActivity { kb.chooseKeyboard("t9"); kb.flushRender() }
                writeCross('2'.code, "t9")
                scenario.onActivity { activity ->
                    assertEquals("十十", activity.findViewById<EditText>(android.R.id.edit).text.toString())
                    assertNotNull(kb.keyboardView.side)
                    activity.findViewById<EditText>(android.R.id.edit).setText("")
                }
                // 覆盖层还没确认时点一下空格：先把手写的字上屏，再照常输入空格。
                // Tapping Space over the live overlay: the handwritten char goes out, then the space.
                drawCross('2'.code)
                var sx = 0f; var sy = 0f
                scenario.onActivity {
                    assertEquals("hand", controller.state.schema)
                    assertNotNull(kb.keyboardView.hand)
                    val space = requireNotNull(kb.keyboardView.keyOf(KeyCode.SPACE))
                    sx = space.rect.centerX(); sy = space.rect.centerY()
                }
                stroke(sx, sy, sx, sy)
                scenario.onActivity { activity ->
                    assertEquals("十 ", activity.findViewById<EditText>(android.R.id.edit).text.toString())
                    assertEquals("t9", controller.state.schema)
                    assertNull(kb.keyboardView.hand)
                    activity.findViewById<EditText>(android.R.id.edit).setText("")
                }
                // 九键侧栏在覆盖层里也能点：先上屏墨迹，再插入那一列符号。
                // The T9 side column stays tappable in the overlay: commit the ink, then insert its symbol.
                drawCross('2'.code)
                var item = ""
                scenario.onActivity {
                    assertEquals("hand", controller.state.schema)
                    val side = requireNotNull(kb.keyboardView.side)
                    item = side.items.first()
                    sx = side.cell.centerX(); sy = side.cell.top + side.itemHeight / 2
                }
                stroke(sx, sy, sx, sy)
                scenario.onActivity { activity ->
                    assertEquals("十$item", activity.findViewById<EditText>(android.R.id.edit).text.toString())
                    assertEquals("t9", controller.state.schema)
                    assertNull(kb.keyboardView.hand)
                    controller.onFinishInput(); kb.dispose()
                }
            }
        } finally {
            engine.close(); root.deleteRecursively(); prefNames.forEach(app::deleteSharedPreferences)
        }
    }
}
