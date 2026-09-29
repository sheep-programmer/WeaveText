package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.ime.EnterAction
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ClipboardPanel
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.SymbolPanel
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 自绘键盘的 TalkBack 虚拟节点。 Virtual accessibility nodes of the custom keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class KeyboardAccessibilityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        WeavePrefs.of(app).edit().clear().commit()
        // 上一个测试的剪贴板写入可能还在后台排队。 A previous test's clip writes may still be queued.
        com.weavetext.ime.ime.ClipHistory.awaitIo()
        File(app.filesDir, "clipboard").deleteRecursively()
        val am = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        am.setEnabled(true)
        am.setTouchExplorationEnabled(true)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller = InputController { null }
        kb = WeaveKeyboard(activity, controller, host)
        val frame = FrameLayout(activity)
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        kb.onShown()
        ShadowLooper.idleMainLooper()
    }

    @After fun tearDown() { kb.dispose() }

    private class Node(val id: Int, val info: AccessibilityNodeInfo)

    private fun nodes(v: View): List<Node> {
        val p: AccessibilityNodeProvider = v.accessibilityNodeProvider!!
        val hostInfo = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        return (0 until hostInfo.childCount).map { i ->
            // 隐藏 API：子节点 id 的高 32 位是虚拟 id。 Hidden API: the virtual id is the high 32 bits.
            val raw = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.java).invoke(hostInfo, i) as Long
            val id = (raw shr 32).toInt()
            Node(id, p.createAccessibilityNodeInfo(id)!!)
        }
    }

    private fun labels(v: View) = nodes(v).map { it.info.contentDescription?.toString() }

    private fun click(v: View, label: String) {
        val n = nodes(v).first { it.info.contentDescription == label }
        assertTrue(v.accessibilityNodeProvider!!.performAction(n.id, AccessibilityNodeInfo.ACTION_CLICK, null))
        ShadowLooper.idleMainLooper()
    }

    private fun hover(v: View, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        v.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    @Test fun everyKeyHasAChineseLabel() {
        val kv = kb.keyboardView
        val l = labels(kv)
        assertEquals(kv.keys.size, l.size)
        assertTrue(l.all { !it.isNullOrBlank() })
        for (want in listOf("删除", "空格", "中英切换，当前中文", "符号", "数字", "换行", "逗号", "句号", "大写", "q", "m")) {
            assertTrue("missing $want in $l", want in l)
        }
        // 触控区域与按键格子对齐。 Bounds follow the key cells.
        val del = nodes(kv).first { it.info.contentDescription == "删除" }
        val r = android.graphics.Rect()
        @Suppress("DEPRECATION") del.info.getBoundsInParent(r)
        val cell = kv.keyOf(KeyCode.DELETE)!!.cell
        assertEquals(cell.left.toInt(), r.left, 1)
        assertEquals(cell.bottom.toInt(), r.bottom, 1)
    }

    @Test fun englishLettersAndEnterAction() {
        controller.previewState(ImeState(chinese = false, engineReady = true, enterAction = EnterAction.SEND))
        ShadowLooper.idleMainLooper()
        val l = labels(kb.keyboardView)
        assertTrue("中英切换，当前英文" in l)
        assertTrue("发送" in l)
        click(kb.keyboardView, "大写")
        val shift = nodes(kb.keyboardView).first { it.info.contentDescription == "大写" }
        assertEquals("下一个字母大写", shift.info.stateDescription)
        assertTrue("Q" in labels(kb.keyboardView))
    }

    @Test fun t9KeysReadDigitAndLetters() {
        WeavePrefs.of(app).edit().putString(WeavePrefs.KEYBOARDS, "t9,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9").commit()
        controller.previewState(ImeState(schema = "t9", engineReady = true))
        ShadowLooper.idleMainLooper()
        val l = labels(kb.keyboardView)
        assertTrue("2，ABC" in l)
        assertTrue("1，标点" in l)
        // 左侧标点列也可读。 The side punctuation column is exposed too.
        assertTrue("逗号" in l)
    }

    @Test fun clickOpensPanels() {
        click(kb.keyboardView, "符号")
        assertTrue(kb.panel is SymbolPanel)
        kb.closePanel()
        click(kb.topBar, "剪贴板")
        assertTrue(kb.panel is ClipboardPanel)
    }

    @Test fun toolbarLabels() {
        assertEquals(listOf("工具箱", "切换键盘", "语音输入", "光标与编辑", "剪贴板", "收起键盘"), labels(kb.topBar))
    }

    @Test fun liftDuringTouchExplorationActivatesKey() {
        val kv = kb.keyboardView
        val c = kv.keyOf(KeyCode.SYMBOL)!!.cell
        hover(kv, MotionEvent.ACTION_HOVER_ENTER, c.centerX(), c.centerY())
        hover(kv, MotionEvent.ACTION_HOVER_MOVE, c.centerX() + 2, c.centerY())
        assertNull(kb.panel)
        hover(kv, MotionEvent.ACTION_HOVER_EXIT, c.centerX() + 2, c.centerY())
        ShadowLooper.idleMainLooper()
        assertTrue(kb.panel is SymbolPanel)
    }

    @Test fun slidingOutDoesNotActivate() {
        val kv = kb.keyboardView
        val c = kv.keyOf(KeyCode.SYMBOL)!!.cell
        hover(kv, MotionEvent.ACTION_HOVER_ENTER, c.centerX(), c.centerY())
        hover(kv, MotionEvent.ACTION_HOVER_EXIT, c.centerX(), kv.height + 50f)
        ShadowLooper.idleMainLooper()
        assertNull(kb.panel)
    }

    @Test fun candidatesAreReadableAndScrollable() {
        val cands = listOf("你好", "拟好", "你", "尼", "泥", "呢", "倪", "妮", "你号", "逆", "腻", "你们", "你的").map { Candidate(it, "", false) }
        controller.previewState(ImeState(preedit = "ni'hao", candidates = cands, totalCandidates = 13, composing = true, engineReady = true))
        ShadowLooper.idleMainLooper()
        val l = labels(kb.topBar)
        assertEquals("输入码 ni'hao", l.first())
        assertTrue("你好" in l)
        assertTrue("展开更多候选" in l)
        val p = kb.topBar.accessibilityNodeProvider!!
        val hostInfo = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        assertTrue(hostInfo.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD))
        assertTrue(p.performAction(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertTrue("你的" in labels(kb.topBar))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
