package com.weavetext.ime.ui

import android.text.InputType
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
