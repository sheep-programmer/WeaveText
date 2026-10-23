package com.weavetext.ime.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weavetext.ime.debug.SettingsTestActivity
import com.weavetext.ime.ime.InputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 原生编辑器里的清空恢复，包含选区与光标冲突。 Clear recovery in a real Android editor. */
@RunWith(AndroidJUnit4::class)
class ClearRecoveryDeviceTest {
    @Test fun recoveryRestoresASelectionButRejectsACursorMoveAndRestart() {
        ActivityScenario.launch(SettingsTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    showSoftInputOnFocus = false
                    setText("你好🌿，明天见")
                    setSelection(4, 2)
                }
                activity.setContentView(field)
                field.requestFocus()
                val info = EditorInfo()
                val connection = field.onCreateInputConnection(info)
                val controller = InputController { connection }
                controller.onStartInput(info, false)
                val selected = controller.clearBeforeCursor()!!
                assertEquals("🌿", selected)
                assertEquals("你好，明天见", field.text.toString())
                controller.onSelectionUpdate(2, 2, -1, -1)
                controller.restoreCleared(selected)
                assertEquals("你好🌿，明天见", field.text.toString())
                assertEquals(4, field.selectionStart)
                assertEquals(2, field.selectionEnd)
                controller.restoreCleared(selected)
                assertEquals("你好🌿，明天见", field.text.toString())

                field.setSelection(2)
                val beforeMove = controller.clearBeforeCursor()!!
                assertEquals("🌿，明天见", field.text.toString())
                field.setSelection(field.length())
                controller.onSelectionUpdate(field.length(), field.length(), -1, -1)
                controller.restoreCleared(beforeMove)
                assertEquals("🌿，明天见", field.text.toString())

                val beforeRestart = controller.clearBeforeCursor()!!
                controller.onStartInput(info, true)
                controller.restoreCleared(beforeRestart)
                assertEquals("", field.text.toString())
                controller.onFinishInput()
            }
        }
    }
}
