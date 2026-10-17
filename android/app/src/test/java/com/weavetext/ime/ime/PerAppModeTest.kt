package com.weavetext.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 按应用记住中/英：在一个应用里显式切过的模式，回到那个应用时还原；没切过的走字段默认；不能打中文的输入框不参与。
 * Per-app Chinese/English memory: an explicit switch is restored when returning to that app, apps never switched in
 * keep the field default, and fields that cannot take Chinese take no part.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PerAppModeTest {
    /** 记账的假记忆。 A memory that just keeps a map. */
    private class Memory : AppModeMemory {
        val modes = HashMap<String, Boolean>()
        override fun mode(pkg: String?): Boolean? = modes[pkg]
        override fun remember(pkg: String?, chinese: Boolean) { if (pkg != null) modes[pkg] = chinese }
    }

    private val ic = FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext()))
    private val controller = InputController { ic }
    private val engine = FakeEngine()
    private val memory = Memory()

    private fun info(pkg: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
        packageName = pkg
        inputType = type
        initialSelStart = -1
        initialSelEnd = -1
    }

    private fun start(pkg: String, type: Int = InputType.TYPE_CLASS_TEXT) {
        controller.attachEngine(engine)
        controller.onStartInput(info(pkg, type), false)
    }

    @Test fun theModeSwitchedInOneAppComesBackWhenReturning() {
        controller.appModeMemory = memory
        start("com.a")
        assertTrue("默认中文", controller.state.chinese)
        controller.toggleChinese()
        assertFalse(controller.state.chinese)
        assertEquals("切过一次就记下", false, memory.modes["com.a"])
        // 别的应用照旧中文；回到 a 还原英文。 Another app stays Chinese; coming back to a restores English.
        start("com.b")
        assertTrue(controller.state.chinese)
        start("com.a")
        assertFalse("回到 a 还是英文", controller.state.chinese)
        // b 里也切成英文，两个应用各记各的。 b can be English too: each app keeps its own choice.
        start("com.b")
        controller.toggleChinese()
        assertFalse("b 里切成英文", controller.state.chinese)
        start("com.a")
        assertFalse("a 仍然是英文", controller.state.chinese)
        start("com.b")
        assertFalse("b 也记着英文", controller.state.chinese)
        start("com.c")
        assertTrue("没切过的 c 照旧中文", controller.state.chinese)
    }

    @Test fun fieldsThatCannotTakeChineseNeverUseTheMemory() {
        controller.appModeMemory = memory
        // a 里记的是中文；a 的密码框不该用它（密码框按拉丁处理）。 A remembered Chinese never reaches a password field.
        start("com.a")
        assertTrue(controller.state.chinese)
        controller.attachEngine(engine)
        controller.onStartInput(info("com.a", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false)
        assertFalse("密码框按英文处理", controller.state.chinese)
        // 数字框同理。 Number fields as well.
        controller.onStartInput(info("com.a", InputType.TYPE_CLASS_NUMBER), false)
        assertFalse(controller.state.chinese)
    }

    @Test fun withoutTheMemoryTheFieldDefaultWins() {
        // 不注入记忆（服务里没打开或设置里关掉）：与从前一样，每个输入框按类型给模式。
        assertNull(controller.appModeMemory)
        start("com.a")
        controller.toggleChinese()
        assertFalse(controller.state.chinese)
        start("com.b")
        assertTrue("换应用照旧默认中文", controller.state.chinese)
        start("com.a")
        assertTrue("没有记忆就不还原英文", controller.state.chinese)
    }

    @Test fun theSameFieldRestartKeepsTheCurrentMode() {
        controller.appModeMemory = memory
        start("com.a")
        controller.toggleChinese()
        assertFalse(controller.state.chinese)
        // 同一个输入框重新开始（聊天发送后清空）：保留用户当前选择，而不是回头去读记忆。
        // A restart inside the same field keeps the current choice instead of re-reading the memory.
        controller.onStartInput(info("com.a"), true)
        assertFalse(controller.state.chinese)
    }
}
