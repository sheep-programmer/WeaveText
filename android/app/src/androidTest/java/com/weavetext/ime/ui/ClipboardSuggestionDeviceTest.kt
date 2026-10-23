package com.weavetext.ime.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.settings.WeavePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 真正的输入法窗口与跨页复制、剪切。 Real IME window with copy and cut across editor pages. */
@RunWith(AndroidJUnit4::class)
class ClipboardSuggestionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun copyAndCutOfferExactTextInANewFieldWithHistoryDisabled() {
        val app = instrumentation.targetContext
        val ime = ComponentName(app.packageName, "${app.packageName}.ime.WeaveImeService")
        val imeId = ime.flattenToShortString()
        val previousIme = shell("settings get secure default_input_method")
        val wasEnabled = shell("settings get secure enabled_input_methods").split(':')
            .any { ComponentName.unflattenFromString(it.substringBefore(';')) == ime }
        val previousHardwareSetting = shell("settings get secure show_ime_with_hard_keyboard")
        val prefs = WeavePrefs.of(app)
        val hadHistoryPreference = prefs.contains(WeavePrefs.CLIPBOARD_RECORD)
        val previousHistoryPreference = prefs.getBoolean(WeavePrefs.CLIPBOARD_RECORD, false)
        val serviceInfo = automation.serviceInfo
        val previousFlags = serviceInfo.flags
        try {
            automation.serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            assertTrue("Disable history without clearing existing entries",
                prefs.edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, false).commit())
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable ${quote(imeId)}")
            shell("ime set ${quote(imeId)}")
            assertEquals("Weave must be the selected system IME", ime,
                ComponentName.unflattenFromString(shell("settings get secure default_input_method")))

            // 原创样本保留空格、换行与标点，区分复制和剪切的旧建议。
            // Original fixtures preserve whitespace and punctuation, and distinguish stale suggestions.
            val samples = listOf(
                android.R.id.copy to "  河边的灯亮了。\n把这句话带到下一页，再慢慢读一遍。  ",
                android.R.id.cut to "  阳台留了一把椅子，旁边是薄荷。\n明天带上书和一杯温水。🌿  ",
            )
            for ((action, text) in samples) {
                ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                    lateinit var source: EditText
                    lateinit var target: EditText
                    lateinit var nextPage: Button
                    scenario.onActivity { activity ->
                        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                        source = editor(activity, "clipboard_source").apply { setText(text) }
                        nextPage = Button(activity).apply {
                            this.text = "下一页 / Next page"
                            contentDescription = "clipboard_next_page"
                            setOnClickListener {
                                activity.getSystemService(InputMethodManager::class.java)
                                    .hideSoftInputFromWindow(source.windowToken, 0)
                                source.clearFocus()
                                target = editor(activity, "clipboard_target").apply {
                                    showSoftInputOnFocus = false
                                }
                                val page = LinearLayout(activity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    isFocusableInTouchMode = true
                                    addView(TextView(activity).apply { this.text = "新页面 / New page" })
                                    addView(target)
                                }
                                activity.setContentView(page)
                                page.requestFocus()
                            }
                        }
                        activity.setContentView(LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(source)
                            addView(nextPage)
                        })
                    }
                    showIme(scenario, source)
                    scenario.onActivity {
                        assertFalse("History must remain disabled", WeavePrefs.clipboardRecord(prefs))
                        source.setSelection(0, source.length())
                        assertTrue("EditText must handle its real copy/cut action",
                            source.onTextContextMenuItem(action))
                        assertEquals("Cut empties the source; copy preserves it",
                            if (action == android.R.id.cut) "" else text, source.text.toString())
                    }
                    val nextBounds = Rect()
                    scenario.onActivity {
                        assertTrue("Page navigation must be visible", nextPage.getLocalVisibleRect(nextBounds))
                        val origin = IntArray(2).also(nextPage::getLocationOnScreen)
                        nextBounds.offset(origin[0], origin[1])
                    }
                    tap(nextBounds)
                    await("source page removed and IME hidden before targeting a new field") {
                        var pageChanged = false
                        scenario.onActivity {
                            pageChanged = !source.isAttachedToWindow && !source.isShown &&
                                target.isShown && !target.hasFocus() && target.text.isEmpty()
                        }
                        pageChanged && automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    }
                    showIme(scenario, target)

                    val label = "粘贴最近复制：${text.replace('\n', ' ')}"
                    var chip: Rect? = null
                    await("actual IME clipboard chip for action=$action") {
                        chip = imeBounds(label)
                        chip != null
                    }
                    scenario.onActivity {
                        assertTrue("The new target must be empty before tapping the chip", target.text.isEmpty())
                        assertTrue("The target must own the input connection",
                            it.getSystemService(InputMethodManager::class.java).isActive(target))
                    }
                    // 全屏截图包含新页面及真实键盘上的建议。 Capture the new page and real IME suggestion.
                    screenshot()
                    // 截图后重新读取位置，避免窗口动画改变触点。 Refresh bounds after capture/animation.
                    chip = imeBounds(label)
                    assertTrue("The current clipboard chip must still be visible", chip != null)
                    tap(requireNotNull(chip))
                    await("exact clipboard text committed to the new target for action=$action") {
                        var pasted = false
                        scenario.onActivity { pasted = target.text.toString() == text }
                        pasted
                    }
                    scenario.onActivity {
                        assertEquals(text, target.text.toString())
                        assertEquals(if (action == android.R.id.cut) "" else text, source.text.toString())
                        assertFalse("The original editor must stay off the new page", source.isAttachedToWindow)
                        assertFalse("Pasting must work with history disabled", WeavePrefs.clipboardRecord(prefs))
                    }
                }
            }
        } finally {
            try {
                if (previousIme.isNotBlank() && previousIme != "null") {
                    shell("ime set ${quote(previousIme)}")
                }
                if (!wasEnabled) shell("ime disable ${quote(imeId)}")
                if (previousIme.isBlank() || previousIme == "null") {
                    shell("settings delete secure default_input_method")
                }
                if (previousHardwareSetting == "null") {
                    shell("settings delete secure show_ime_with_hard_keyboard")
                } else {
                    shell("settings put secure show_ime_with_hard_keyboard ${quote(previousHardwareSetting)}")
                }
            } finally {
                try {
                    val restore = prefs.edit()
                    if (hadHistoryPreference) restore.putBoolean(WeavePrefs.CLIPBOARD_RECORD, previousHistoryPreference)
                    else restore.remove(WeavePrefs.CLIPBOARD_RECORD)
                    assertTrue("Restore the user's history preference, including an unset value", restore.commit())
                } finally {
                    serviceInfo.flags = previousFlags
                    automation.serviceInfo = serviceInfo
                }
            }
        }
    }

    private fun editor(activity: SmokeActivity, label: String) = EditText(activity).apply {
        id = View.generateViewId()
        contentDescription = label
        hint = label
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
    }

    private fun showIme(scenario: ActivityScenario<SmokeActivity>, editor: EditText) {
        await("real Weave IME serving ${editor.contentDescription}") {
            var served = false
            scenario.onActivity { activity ->
                editor.showSoftInputOnFocus = true
                editor.requestFocus()
                val manager = activity.getSystemService(InputMethodManager::class.java)
                manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                served = editor.hasFocus() && editor.hasWindowFocus() && manager.isActive(editor)
            }
            served && imeBounds("空格", prefix = true) != null
        }
    }

    private fun imeBounds(label: String, prefix: Boolean = false): Rect? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == instrumentation.targetContext.packageName) {
                    findBounds(root, label, prefix)?.let { return it }
                }
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
        return null
    }

    private fun findBounds(node: AccessibilityNodeInfo, label: String, prefix: Boolean): Rect? {
        val description = node.contentDescription?.toString()
        if (node.isVisibleToUser && (description == label || (prefix && description?.startsWith(label) == true))) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty) return bounds
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findBounds(child, label, prefix)?.let { return it }
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return null
    }

    private fun tap(bounds: Rect) {
        assertFalse("Tap bounds must be nonempty", bounds.isEmpty)
        val downTime = SystemClock.uptimeMillis()
        fun emit(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Screen touch injection failed", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        emit(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(40)
        emit(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            if (ready()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for $label; accessibility window types=${automation.windows.map { it.type }}")
    }

    private fun screenshot() {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Full-screen screenshot unavailable" }
        try {
            assertTrue("Screenshot must include screen pixels", bitmap.width > 0 && bitmap.height > 0)
            File(instrumentation.targetContext.cacheDir, "clipboard-suggestion-device.png").outputStream().use {
                assertTrue("Save full UI screenshot", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
