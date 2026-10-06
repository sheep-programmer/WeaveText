package com.weavetext.ime.ui

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
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode).also { prefNames += "$name-$key" }
        }
        val prefs = WeavePrefs.of(owner)
        prefs.edit().putString(WeavePrefs.KEYBOARDS, "hand,pinyin,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "hand").putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit()
        val engine = NativeEngine.createFromSpec(DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner))!!
        val worker = Executors.newSingleThreadExecutor()
        lateinit var kb: WeaveKeyboard
        lateinit var controller: InputController
        try {
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val context = object : ContextWrapper(activity) {
                        override fun getFilesDir() = owner.filesDir
                        override fun getCacheDir() = owner.cacheDir
                        override fun getSharedPreferences(key: String, mode: Int) = owner.getSharedPreferences(key, mode)
                    }
                    val editor = activity.findViewById<android.widget.EditText>(android.R.id.edit)
                    editor.showSoftInputOnFocus = false; editor.setText("")
                    val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                    controller = InputController { editor.onCreateInputConnection(info) }
                    controller.onStartInput(info, false); controller.attachEngine(engine)
                    controller.handWorker = worker
                    controller.postMain = { activity.runOnUiThread(it) }
                    kb = WeaveKeyboard(context, controller, object : ImeWindowHost {
                        override fun hideKeyboard() {}
                        override val window get() = activity.window
                    })
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
                    assertTrue(kb.keyboardView.redoStroke())
                    controller.replaceHandInk(kb.keyboardView.hand!!.strokes)
                }
                awaitRecognition()
                scenario.onActivity { kb.keyboardView.hand!!.strokes.forEachIndexed { i, s -> assertArrayEquals(original[i], s, 0f) } }
                tap(KeyCode.HAND_MODE); tap(KeyCode.HAND_CLEAR)
                scenario.onActivity { assertTrue(prefs.getBoolean(WeavePrefs.HAND_LINE, false)) }
                var left = 0f; var right = 0f
                scenario.onActivity { val r = kb.keyboardView.hand!!.rect; left = r.left + half * 1.2f; right = r.right - half * 1.2f }
                stroke(left - half, cy, left + half, cy); stroke(left, cy - half, left, cy + half)
                stroke(right - half, cy, right + half, cy); stroke(right, cy - half, right, cy + half)
                awaitRecognition()
                scenario.onActivity { activity ->
                    val index = controller.state.candidates.indexOfFirst { it.text == "十十" }
                    assertTrue(controller.state.candidates.map { it.text }.toString(), index >= 0)
                    val bitmap = Bitmap.createBitmap(kb.view.width, kb.view.height, Bitmap.Config.ARGB_8888)
                    kb.view.draw(Canvas(bitmap))
                    File(app.cacheDir, "handwriting-experience-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    controller.onCandidate(index)
                    assertEquals("十十", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    controller.onFinishInput(); kb.dispose()
                }
            }
        } finally {
            worker.shutdownNow(); engine.close(); root.deleteRecursively(); prefNames.forEach(app::deleteSharedPreferences)
        }
    }
}
