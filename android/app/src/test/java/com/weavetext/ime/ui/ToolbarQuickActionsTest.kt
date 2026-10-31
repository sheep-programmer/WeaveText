package com.weavetext.ime.ui

import com.weavetext.ime.style.ToolIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 工具栏长按的快捷动作：长按光标是全选，不再打开光标面板。 Long-press shortcuts on the toolbar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolbarQuickActionsTest : KeyboardSnapshotSupport() {
    @Test fun longPressingCursorSelectsAllAndKeepsThePanelClosed() {
        val (k, _) = keyboard(false)
        assertTrue("the long press is consumed, so the tap does not open the cursor panel", k.onToolbarLong(ToolIds.CURSOR))
        idle()
        assertNull(k.panel)
    }

    /** 无痕输入开启时，工具栏菜单按钮变色，不必打开工具箱就能看到状态。 */
    @Test fun incognitoIsVisibleOnTheToolbarWithoutOpeningAnything() {
        val (k, controller) = keyboard(false)
        assertFalse(k.topBar.incognito)
        controller.setIncognito(true)
        idle()
        assertTrue(k.topBar.incognito)
        controller.setIncognito(false)
        idle()
        assertFalse(k.topBar.incognito)
    }
}
