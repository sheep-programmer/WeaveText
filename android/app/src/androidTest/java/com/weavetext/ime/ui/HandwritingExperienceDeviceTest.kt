package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.core.DataInstaller
import com.weavetext.ime.core.NativeEngine
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class HandwritingExperienceDeviceTest {
    @Test fun realInkStylesUndoRedoAndLineWritingUseThePackagedRecognizer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val name = "hand-experience-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name).apply { mkdirs() }
        val prefNames = mutableSetOf<String>()
        val owner = object : ContextWrapper(app) {
            // 偏好通过 applicationContext 解析，重跑也须隔离。 Preferences resolve through applicationContext; isolate reruns.
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode).also { prefNames += "$name-$key" }
        }
        val prefs = WeavePrefs.of(owner)
        assertTrue(prefs.edit().putString(WeavePrefs.KEYBOARDS, "hand,pinyin,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "hand").putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false)
            .putBoolean(WeavePrefs.HAND_LINE, false).commit())
        assertSame("Fixture preferences must stay in the isolated namespace", prefs,
            app.getSharedPreferences("$name-${WeavePrefs.FILE}", Context.MODE_PRIVATE))
        val engine = NativeEngine.createFromSpec(DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner))!!
        val worker = Executors.newSingleThreadExecutor()
        lateinit var kb: WeaveKeyboard
        lateinit var controller: InputController
        try {
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val context = object : ContextWrapper(activity) {
                        override fun getApplicationContext(): Context = owner
                        override fun getFilesDir() = owner.filesDir
                        override fun getCacheDir() = owner.cacheDir
                        override fun getSharedPreferences(key: String, mode: Int) = owner.getSharedPreferences(key, mode)
                    }
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    val editor = activity.findViewById<android.widget.EditText>(android.R.id.edit)
                    editor.showSoftInputOnFocus = false
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.setText("")
                    val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                    controller = InputController { editor.onCreateInputConnection(info) }
                    controller.onStartInput(info, false); controller.attachEngine(engine)
                    controller.handWorker = worker
                    controller.postMain = { activity.runOnUiThread(it) }
                    kb = WeaveKeyboard(context, controller, object : ImeWindowHost {
                        override fun hideKeyboard() {}
                        override val window get() = activity.window
                    })
                    assertSame("Keyboard must use the fixture's isolated preferences", prefs, kb.prefs)
                    activity.addContentView(kb.view, FrameLayout.LayoutParams(-1, -2).apply { gravity = android.view.Gravity.BOTTOM })
                    kb.onShown(); kb.flushRender()
                    val width = activity.resources.displayMetrics.widthPixels
                    kb.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
                    kb.view.layout(0, 0, width, kb.view.measuredHeight)
                }
                fun awaitRecognition() {
                    val until = SystemClock.uptimeMillis() + 10000
                    while (true) {
                        var busy = false
                        scenario.onActivity { busy = controller.state.handRecognizing; kb.flushRender() }
                        if (!busy) return
                        assertTrue("packaged handwriting recognition timed out", SystemClock.uptimeMillis() < until)
                        Thread.sleep(20)
                    }
                }
                fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) = scenario.onActivity {
                    val time = SystemClock.uptimeMillis()
                    for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, x1, y1), Triple(MotionEvent.ACTION_MOVE, x2, y2), Triple(MotionEvent.ACTION_UP, x2, y2))) {
                        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                        try { kb.keyboardView.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                fun tap(code: Int) = scenario.onActivity {
                    val key = kb.keyboardView.keyOf(code)!!
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(time, time, action, key.rect.centerX(), key.rect.centerY(), 0)
                        try { kb.keyboardView.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                var cx = 0f; var cy = 0f; var half = 0f
                scenario.onActivity { val r = kb.keyboardView.hand!!.rect; cx = r.centerX(); cy = r.centerY(); half = minOf(r.width(), r.height()) * 0.32f }
                stroke(cx - half, cy, cx + half, cy)
                stroke(cx, cy - half, cx, cy + half)
                awaitRecognition()
                lateinit var original: List<FloatArray>
                scenario.onActivity {
                    assertTrue(controller.state.candidates.take(5).any { it.text == "十" })
                    original = kb.keyboardView.hand!!.strokes.map { it.copyOf() }
                    HandInkPrefs.setStyle(prefs, HandInkStyle.HIGHLIGHTER)
                    HandInkPrefs.setWidthDp(prefs, 10f)
                    assertTrue(HandInkPrefs.setColor(prefs, "#DC2626"))
                    val pad = kb.keyboardView.hand!!
                    assertEquals(HandInkStyle.HIGHLIGHTER, pad.style)
                    assertEquals(10f, pad.widthDp, 0.01f)
                    assertEquals("#DC2626", pad.color)
                    pad.strokes.forEachIndexed { i, s -> assertArrayEquals(original[i], s, 0f) }
                }
                tap(KeyCode.DELETE); awaitRecognition()
                scenario.onActivity {
                    assertEquals(1, kb.keyboardView.hand!!.strokes.size)
                    val keyboard = kb.keyboardView
                    val modeId = keyboard.keys.indexOfFirst { it.code == KeyCode.HAND_MODE }
                    val provider = keyboard.accessibilityNodeProvider!!
                    val redo = provider.createAccessibilityNodeInfo(modeId)!!.actionList.firstOrNull {
                        it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK
                    }
                    assertNotNull("重做需要可用的无障碍动作 / Redo needs an accessible action", redo)
                    assertEquals("重做上一笔", redo!!.label.toString())
                    assertTrue(provider.performAction(modeId, redo.id, null))
                    assertFalse(provider.performAction(modeId, redo.id, null))
                }
                awaitRecognition()
                scenario.onActivity { kb.keyboardView.hand!!.strokes.forEachIndexed { i, s -> assertArrayEquals(original[i], s, 0f) } }
                scenario.onActivity {
                    assertFalse("Redo must leave single-character mode selected", prefs.getBoolean(WeavePrefs.HAND_LINE, false))
                    assertFalse(kb.keyboardView.handLineMode)
                }
                tap(KeyCode.HAND_MODE)
                scenario.onActivity {
                    val keyboard = kb.keyboardView
                    val mode = keyboard.keyOf(KeyCode.HAND_MODE)!!
                    assertTrue("Mode tap must enable line writing: key=${mode.rect}, pad=${keyboard.hand!!.rect}, " +
                        "label=${mode.label}, viewLine=${keyboard.handLineMode}, strokes=${keyboard.hand!!.strokes.size}",
                        prefs.getBoolean(WeavePrefs.HAND_LINE, false))
                    assertTrue("Line-writing mode must reach the view", keyboard.handLineMode)
                }
                tap(KeyCode.HAND_CLEAR)
                var left = 0f; var right = 0f
                scenario.onActivity { val r = kb.keyboardView.hand!!.rect; left = r.left + half * 1.2f; right = r.right - half * 1.2f }
                // 连写要能真正攒住多个字：写字之间长时间停顿也不能把上一个字上屏、清空画布，
                // 否则第二个字永远进不了同一组。 Continuous writing must really collect several characters: a long pause
                // between them may not commit the first and clear the canvas, or the second can never join the group.
                stroke(left - half, cy, left + half, cy); stroke(left, cy - half, left, cy + half)
                awaitRecognition()
                Thread.sleep(WeavePrefs.HAND_PAUSE_MS[WeavePrefs.HAND_PAUSE_DEFAULT] + 600)
                scenario.onActivity {
                    assertEquals("连写不因停笔自动上屏", "", controller.state.preedit)
                    assertTrue("上一个字仍留在画布里", kb.keyboardView.hand!!.strokes.isNotEmpty())
                }
                stroke(right - half, cy, right + half, cy); stroke(right, cy - half, right, cy + half)
                awaitRecognition()
                scenario.onActivity { activity ->
                    assertEquals("两个字都留在同一块画布上", 4, kb.keyboardView.hand!!.strokes.size)
                    val index = controller.state.candidates.indexOfFirst { it.text == "十十" }
                    assertTrue(controller.state.candidates.map { it.text }.toString(), index >= 0)
                    val bitmap = Bitmap.createBitmap(kb.view.width, kb.view.height, Bitmap.Config.ARGB_8888)
                    kb.view.draw(Canvas(bitmap))
                    File(app.cacheDir, "handwriting-experience-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    controller.onCandidate(index)
                    assertEquals("十十", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                }
                // 同一区域按停顿叠写：真实包内识别，不用横向留白。 Same-region overlay uses temporal boundaries, not spatial gaps.
                tap(KeyCode.HAND_MODE)
                scenario.onActivity { activity ->
                    assertEquals("叠写", kb.keyboardView.keyOf(KeyCode.HAND_MODE)!!.label)
                    activity.findViewById<android.widget.EditText>(android.R.id.edit).setText("")
                }
                stroke(cx - half, cy, cx + half, cy); stroke(cx, cy - half, cx, cy + half)
                awaitRecognition()
                Thread.sleep(WeavePrefs.handPauseMs(prefs) + 100)
                stroke(cx - half, cy, cx + half, cy); stroke(cx, cy - half, cx, cy + half)
                awaitRecognition()
                scenario.onActivity { activity ->
                    val pad = kb.keyboardView.hand!!
                    assertArrayEquals(intArrayOf(2, 4), pad.groupEnds)
                    assertArrayEquals(pad.strokes[0], pad.strokes[2], 0f)
                    assertArrayEquals(pad.strokes[1], pad.strokes[3], 0f)
                    assertEquals("", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    assertTrue(controller.state.candidates.map { it.text }.toString(), controller.state.candidates.any { it.text == "十十" })
                }
                tap(KeyCode.DELETE); awaitRecognition()
                scenario.onActivity {
                    assertArrayEquals(intArrayOf(2, 3), kb.keyboardView.hand!!.groupEnds)
                    val keyboard = kb.keyboardView
                    val modeId = keyboard.keys.indexOfFirst { it.code == KeyCode.HAND_MODE }
                    assertTrue(keyboard.accessibilityNodeProvider!!.performAction(modeId, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                }
                awaitRecognition()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.cacheDir, "handwriting-overlay-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                scenario.onActivity { activity ->
                    assertArrayEquals(intArrayOf(2, 4), kb.keyboardView.hand!!.groupEnds)
                    val index = controller.state.candidates.indexOfFirst { it.text == "十十" }
                    assertTrue(controller.state.candidates.map { it.text }.toString(), index >= 0)
                    kb.onCandidate(index)
                    assertEquals("十十", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    assertTrue(kb.keyboardView.hand!!.strokes.isEmpty())
                }
                tap(KeyCode.HAND_MODE)
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.EditText>(android.R.id.edit).setText("")
                    prefs.edit().putBoolean(WeavePrefs.HAND_AUTO_COMMIT, true)
                        .putInt(WeavePrefs.HAND_PAUSE, 2).commit()
                    assertFalse(kb.keyboardView.handLineMode)
                }
                stroke(cx - half, cy, cx + half, cy); stroke(cx, cy - half, cx, cy + half)
                Thread.sleep(900)
                tap(KeyCode.DELETE)
                Thread.sleep(350)
                scenario.onActivity { activity ->
                    assertEquals("旧定时器不能在退笔后提前上屏", "", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    assertEquals(1, kb.keyboardView.hand!!.strokes.size)
                    assertTrue(kb.keyboardView.hand!!.canRedo)
                    val key = kb.keyboardView.keyOf(KeyCode.HAND_MODE)!!
                    val time = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, key.rect.centerX(), key.rect.centerY(), 0)
                    try { kb.keyboardView.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                Thread.sleep(500)
                scenario.onActivity { activity ->
                    val key = kb.keyboardView.keyOf(KeyCode.HAND_MODE)!!
                    val time = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(time - 500, time, MotionEvent.ACTION_UP, key.rect.centerX(), key.rect.centerY(), 0)
                    try { kb.keyboardView.dispatchTouchEvent(event) } finally { event.recycle() }
                    assertEquals("", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    assertEquals(2, kb.keyboardView.hand!!.strokes.size)
                }
                awaitRecognition()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.cacheDir, "handwriting-redo-pause-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val until = SystemClock.uptimeMillis() + 5000
                var committed = false
                while (!committed && SystemClock.uptimeMillis() < until) {
                    scenario.onActivity { activity ->
                        committed = activity.findViewById<android.widget.EditText>(android.R.id.edit).text.isNotEmpty()
                    }
                    if (!committed) Thread.sleep(20)
                }
                scenario.onActivity { activity ->
                    assertTrue("重做后应在新的停笔等待结束时上屏", committed)
                    assertEquals("十", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    assertTrue(kb.keyboardView.hand!!.strokes.isEmpty())
                    controller.onFinishInput(); kb.dispose()
                }
            }
        } finally {
            worker.shutdownNow(); engine.close(); root.deleteRecursively(); prefNames.forEach(app::deleteSharedPreferences)
        }
    }
}
