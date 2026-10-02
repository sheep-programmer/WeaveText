package com.weavetext.ime.link

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.core.DataInstaller
import com.weavetext.ime.core.NativeEngine
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.InputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Drives the real keyboard controller and the dictionaries stored inside the installed APK. */
@RunWith(AndroidJUnit4::class)
class LearningDeviceTest {
    @Test fun actualCandidateSelectionsPersistAndEnglishCompletionAddsNoSpace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val user = File(ctx.cacheDir, "learning-device-${UUID.randomUUID()}")
        val spec = DataInstaller.sourceSpec(ctx)
        val engine = NativeEngine.createFromSpec(spec, user.path, DataInstaller.cacheKb(ctx))!!
        try {
            engine.importUserWords("是\tshi\t100000\n时\tshi\t90000\n")
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val editor = activity.findViewById<android.widget.EditText>(android.R.id.edit)
                    val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                    val connection = editor.onCreateInputConnection(info)
                    val controller = InputController { connection }
                    controller.onStartInput(info,false); controller.attachEngine(engine)
                    controller.setOption("candidates.prediction",false)
                    repeat(5) {
                        "shi".forEach { controller.onChar(it.code) }
                        val index = controller.loadCandidates(0,800).indexOfFirst { it.text == "嗜" }
                        assertTrue(index >= 0); controller.onCandidate(index)
                    }
                    assertEquals("嗜嗜嗜嗜嗜",editor.text.toString())
                    "shi".forEach { controller.onChar(it.code) }
                    assertEquals("嗜",controller.state.candidates.first().text)
                    controller.reset()
                    controller.toggleChinese()
                    "hel".forEach { controller.onChar(it.code) }
                    val index=controller.state.candidates.indexOfFirst { it.text=="hello" }
                    assertTrue(controller.state.candidates.map { it.text }.toString(),index>0)
                    controller.onCandidate(index)
                    assertEquals("嗜嗜嗜嗜嗜hello",editor.text.toString())
                    controller.onChar(','.code)
                    assertEquals("嗜嗜嗜嗜嗜hello,",editor.text.toString())
                    controller.onSpace()
                    assertEquals("嗜嗜嗜嗜嗜hello, ",editor.text.toString())
                    controller.onFinishInput()
                }
            }
            // Read another native handle after actual controller commits, using the same owned directory.
            NativeEngine.createFromSpec(spec,user.path,DataInstaller.cacheKb(ctx))!!.use { other ->
                "shi".forEach { other.inputChar(it.code) }
                assertEquals("嗜",other.snapshot().candidates.first().text)
            }
        } finally { engine.close(); user.deleteRecursively() }
    }
}
