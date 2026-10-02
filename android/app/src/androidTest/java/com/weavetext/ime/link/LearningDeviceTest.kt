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
    @Test fun selectionReconversionAndRealDictionaryFeaturesPreserveSurroundingText() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ctx=instrumentation.targetContext
        val user=File(ctx.cacheDir,"features-device-${UUID.randomUUID()}")
        val spec=DataInstaller.sourceSpec(ctx)
        NativeEngine.createFromSpec(spec,user.path,DataInstaller.cacheKb(ctx))!!.use {engine ->
            ActivityScenario.launch(SmokeActivity::class.java).use {scenario -> scenario.onActivity {activity ->
                val editor=activity.findViewById<android.widget.EditText>(android.R.id.edit)
                editor.setText("甲时乙时丙");editor.setSelection(1,2)
                val info=EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT;initialSelStart=1;initialSelEnd=2}
                val connection=editor.onCreateInputConnection(info)
                val controller=InputController {connection}
                controller.onStartInput(info,false);controller.attachEngine(engine)
                controller.setOption("candidates.prediction",false)
                assertTrue(controller.reselect())
                val i=controller.loadCandidates(0,200).indexOfFirst {it.text=="是"}
                assertTrue(i>=0);controller.onCandidate(i)
                assertEquals("甲是乙时丙",editor.text.toString())
                // Same selected text at a different location must cancel, never replace the new range.
                editor.setText("甲时乙时丙");editor.setSelection(1,2);controller.onSelectionUpdate(1,2,-1,-1)
                assertTrue(controller.reselect())
                val stale=controller.loadCandidates(0,200).indexOfFirst {it.text=="是"}
                editor.setSelection(3,4);controller.onSelectionUpdate(3,4,-1,-1);controller.onCandidate(stale)
                assertEquals("甲时乙时丙",editor.text.toString())
                controller.reset();editor.setText("");editor.setSelection(0);controller.onSelectionUpdate(0,0,-1,-1)
                "ken".forEach {controller.onChar(it.code)}
                assertEquals("ken",controller.state.preedit);assertEquals("肯",controller.state.candidates.first().text)
                val pin=controller.loadCandidates(0,200).indexOfFirst {it.text=="啃"};assertTrue(pin>=0)
                controller.candidatePolicy(pin,"啃","pin")
                assertEquals("啃",controller.state.candidates.first().text)
                controller.reset()
                "jintianreviewzhegePR".forEach {controller.onChar(it.code)}
                assertEquals("今天review这个PR",controller.state.candidates.first().text)
                controller.onCandidate(0);assertEquals("今天review这个PR",editor.text.toString())
                controller.toggleChinese()
                "recieve".forEach {controller.onChar(it.code)}
                assertEquals("recieve",controller.state.candidates.first().text)
                val correction=controller.loadCandidates(0,100).indexOfFirst {it.text=="receive"}
                assertTrue(correction>0);controller.onCandidate(correction)
                assertEquals("今天review这个PRreceive",editor.text.toString())
                controller.onFinishInput()
            }}
        }
        NativeEngine.createFromSpec(spec,user.path,DataInstaller.cacheKb(ctx))!!.use {other ->
            "ken".forEach {other.inputChar(it.code)}
            assertEquals("啃",other.snapshot().candidates.first().text)
        }
        user.deleteRecursively()
    }

}
