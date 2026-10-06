package com.weavetext.ime.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.debug.SmokeActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Real IME windows: navigation mode, rotation and repeated hide/show without toolbar intervention. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    private fun space(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        if (node.contentDescription?.startsWith("空格") == true) {
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) return bounds
        }
        for (i in 0 until node.childCount) space(node.getChild(i))?.let { return it }
        return null
    }

    private fun navigation(): Rect? {
        val windows = shell("dumpsys window windows")
        val part = Regex("Window #[0-9]+ Window\\{[^\\n]*NavigationBar[^\\n]*\\}:([\\s\\S]*?)(?=  Window #|$)")
            .find(windows)?.groupValues?.get(1) ?: return null
        val match = Regex("mFrame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(part) ?: return null
        val values = match.groupValues.drop(1).map(String::toInt)
        return Rect(values[0], values[1], values[2], values[3]).takeUnless { it.isEmpty }
    }

    @Test fun navigationChangesNeverCoverTheSpaceKey() {
        val savedInfo = automation.serviceInfo
        val savedFlags = savedInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val settings = listOf("system accelerometer_rotation", "system user_rotation", "secure show_ime_with_hard_keyboard")
            .associateWith { shell("settings get $it") }
        val overlays = listOf("com.android.internal.systemui.navbar.threebutton", "com.android.internal.systemui.navbar.gestural")
        val original = shell("cmd overlay list").lineSequence().filter { it.startsWith("[x]") && ".navbar." in it }
            .map { it.substringAfter("] ") }.toList()
        try {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable com.weavetext.ime/.ime.WeaveImeService")
            shell("ime set com.weavetext.ime/.ime.WeaveImeService")
            for (overlay in overlays) {
                shell("cmd overlay enable-exclusive --category $overlay")
                for (rotation in listOf(0, 1)) {
                    shell("settings put system user_rotation $rotation")
                    repeat(2) { cycle ->
                        ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                            scenario.onActivity { activity ->
                                val edit = activity.findViewById<android.widget.EditText>(android.R.id.edit)
                                edit.requestFocus()
                                activity.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                            }
                            val deadline = System.currentTimeMillis() + 15_000
                            var key: Rect? = null
                            var nav: Rect? = null
                            while (System.currentTimeMillis() < deadline) {
                                scenario.onActivity { activity ->
                                    val edit = activity.findViewById<android.widget.EditText>(android.R.id.edit)
                                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                                }
                                key = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root?.let(::space)
                                nav = navigation()
                                if (key != null && nav != null && !Rect.intersects(key, nav)) break
                                Thread.sleep(200)
                            }
                            check(key != null && nav != null) { "Missing IME key or navigation window: $overlay rotation=$rotation key=$key nav=$nav windows=${automation.windows.map { it.type }}" }
                            assertFalse("$overlay rotation=$rotation cycle=$cycle space=$key navigation=$nav", Rect.intersects(key, nav))
                        }
                    }
                }
            }
        } finally {
            for (overlay in overlays) shell("cmd overlay disable $overlay")
            for (overlay in original) shell("cmd overlay enable-exclusive --category $overlay")
            for ((key, value) in settings) {
                if (value == "null") shell("settings delete $key") else shell("settings put $key $value")
            }
            savedInfo.flags = savedFlags
            automation.serviceInfo = savedInfo
        }
    }
}
