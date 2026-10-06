package com.weavetext.ime.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsActivity
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.voice.ConfigField
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.VoicePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Actual Android windows and touch dispatch, with an isolated in-memory speech service. */
@RunWith(AndroidJUnit4::class)
class VoiceSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val plugin = VoicePlugin("org.weavetext.ui.test", "配置测试引擎", "用于检查插件配置与列表返回。", "1.0", null,
        listOf(ConfigField("api_key", "访问密钥", "password", required = true, helpText = "在测试服务中获取密钥")))

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        for (window in automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) {
            walk(window.root)?.let { return it }
        }
        return walk(automation.rootInActiveWindow)
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Expected settings control did not appear")
    }

    private fun text(label: String) = awaitNode { it.text?.contains(label) == true }

    private fun awaitDetailClosed() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (find { it.text?.toString() == "通用" } != null) {
            check(SystemClock.uptimeMillis() < deadline) { "Detail screen did not finish its exit animation" }
            SystemClock.sleep(100)
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("Control is not clickable")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    @Test fun tappingConfigurationHelpOpensEditorAndSavingRefreshesTheExpandedList() {
        val app = instrumentation.targetContext
        val prefsName = "voice-ui-test-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(prefsName, 0)
        val values = ConcurrentHashMap<String, String>()
        val local = VoicePlugin("weave.local", "离线语音", "", "", null, emptyList())
        val engines = object : VoiceEngines {
            @Volatile override var activeId: String? = local.id
            override fun list() = listOf(local, plugin)
            override fun getConfig(id: String, key: String) = values["$id/$key"]
            override fun setConfig(id: String, key: String, value: String) { values["$id/$key"] = value }
            override fun install(xipkPath: String) = Result.failure<VoicePlugin>(UnsupportedOperationException())
            override fun uninstall(id: String) = Result.failure<Unit>(UnsupportedOperationException())
        }
        val nav = Navigator(listOf(Route.Home, Route.Voice))
        val saved = automation.serviceInfo
        val flags = saved.flags
        automation.serviceInfo = saved.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val deps = SettingsDeps(activity, prefs = prefs, engines = { engines }, status = { ImeStatus(true, true, true) })
                    activity.setContent { SettingsApp(deps, nav) }
                }
                click(text(plugin.name))
                click(text("配置与详情"))
                val help = text("在测试服务中获取密钥")
                val bounds = Rect().also(help::getBoundsInScreen)
                assertTrue(!bounds.isEmpty)
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                val editor = awaitNode { it.isEditable }
                val value = "device-test-configured"
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
                click(text("保存"))
                assertEquals(value, engines.getConfig(plugin.id, "api_key"))
                click(awaitNode { it.contentDescription?.toString() == "返回" })
                awaitDetailClosed()
                // The configuration entry remains present because its card remains expanded.
                text("配置与详情")
                val activate = awaitNode { it.text?.toString() == "设为当前引擎" && it.isEnabled }
                click(activate)
                assertEquals(plugin.id, engines.activeId)
                text("当前引擎")
            }
        } finally {
            prefs.edit().clear().commit()
            saved.flags = flags
            automation.serviceInfo = saved
        }
    }
}
