package com.weavetext.ime.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsActivity
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.settings.settingsTestInstantTransitions
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

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = findAll(predicate).firstOrNull()

    private fun findAll(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (predicate(node)) out += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        for (window in automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) {
            walk(window.root)
        }
        walk(automation.rootInActiveWindow)
        return out
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Expected settings control did not appear")
    }

    private fun text(label: String) = awaitNode { it.text?.contains(label) == true }

    /** 超时把整棵无障碍树写进 logcat，含可用/可点击/可见状态。 Dump the a11y tree to logcat on timeout. */
    private fun dumpTree() {
        val texts = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val label = n.text ?: n.contentDescription
            if (label != null) {
                texts.append(label).append("{e=").append(n.isEnabled)
                    .append(",c=").append(n.isClickable).append(",v=").append(n.isVisibleToUser).append("}|")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        for (w in automation.windows) {
            texts.append("#win").append(w.type).append(if (w.isFocused) "F" else "").append(if (w.isActive) "A" else "").append(':')
            walk(w.root)
        }
        android.util.Log.e("VoiceSettingsDump", "timeout tree: $texts")
    }

    /** 点击所有匹配的节点；返回是否有点击被接受。 Click every match; true if any accepted the click. */
    private fun clickAll(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var clicked = false
        for (node in findAll(predicate)) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clicked = true
        }
        return clicked
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
        SettingsActivity.testHook = { activity ->
            SettingsDeps(activity, prefs = prefs, engines = { engines }, status = { ImeStatus(true, true, true) }) to nav
        }
        settingsTestInstantTransitions = true
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
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
                // 弹窗关闭可能吞掉第一次返回点击；以导航栈为准重试。每轮只点一个「返回」，
                // 避免新旧两屏的返回钮同时生效造成连续出栈。
                // The dialog teardown may swallow the first back click; retry by the nav stack. Click only one
                // back button per round so stale and fresh screens cannot both pop.
                val backDeadline = SystemClock.uptimeMillis() + 30_000
                while (nav.current != Route.Voice) {
                    find { it.contentDescription?.toString() == "返回" }?.let { node ->
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    val settled = SystemClock.uptimeMillis() + 2_000
                    while (nav.current != Route.Voice && SystemClock.uptimeMillis() < settled) SystemClock.sleep(100)
                    check(nav.current == Route.Voice || SystemClock.uptimeMillis() < backDeadline) {
                        "Detail screen did not close"
                    }
                }
                // The configuration entry remains present because its card remains expanded.
                text("配置与详情")
                // 返回后上一屏的过期节点可能仍命中文本；点遍所有「设为当前引擎」，以引擎状态为准。
                // Stale nodes from the detail screen can still match; click every candidate and judge by engine state.
                val activateDeadline = SystemClock.uptimeMillis() + 30_000
                while (engines.activeId != plugin.id && SystemClock.uptimeMillis() < activateDeadline) {
                    clickAll { it.text?.toString() == "设为当前引擎" && it.isEnabled }
                    SystemClock.sleep(200)
                }
                if (engines.activeId != plugin.id) dumpTree()
                assertEquals(plugin.id, engines.activeId)
                text("当前引擎")
            }
        } finally {
            SettingsActivity.testHook = null
            settingsTestInstantTransitions = false
            prefs.edit().clear().commit()
            saved.flags = flags
            automation.serviceInfo = saved
        }
    }
}
