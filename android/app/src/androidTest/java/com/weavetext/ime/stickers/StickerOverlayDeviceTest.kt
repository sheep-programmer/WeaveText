package com.weavetext.ime.stickers

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.debug.SmokeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Real overlay windows and touch gestures, including inset refreshes and the minimum-size confirmation. */
@RunWith(AndroidJUnit4::class)
class StickerOverlayDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun find(node: AccessibilityNodeInfo?, description: String? = null, text: String? = null): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((description != null && node.contentDescription?.toString() == description) ||
            (text != null && node.text?.toString() == text)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), description, text)?.let { return it }
        return null
    }
    private fun node(description: String? = null, text: String? = null): AccessibilityNodeInfo? =
        automation.windows.firstNotNullOfOrNull { find(it.root, description, text) }
    private fun awaitNode(description: String? = null, text: String? = null): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000
        do {
            node(description, text)?.let { return it }
            Thread.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing sticker node: description=$description text=$text")
    }
    private fun bounds(): Rect {
        // Accessibility window bounds can include transient surface animation transforms.
        // WindowManager's layout frame is the rectangle whose size the resize gesture actually changes.
        val window = shell("dumpsys window windows").split(Regex("(?=  Window #)"))
            .firstOrNull { "package=com.weavetext.ime" in it && "ty=APPLICATION_OVERLAY" in it }
            ?: error("Missing expanded sticker window")
        val frame = Regex("(?:frame=|mFrame=)\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(window)
            ?: error("Missing sticker layout frame")
        val values = frame.groupValues.drop(1).map(String::toInt)
        return Rect(values[0], values[1], values[2], values[3])
    }
    private fun awaitSize(width: Int, height: Int): Rect {
        awaitNode(description = "从左上角调整表情收纳窗大小")
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: Rect? = null
        var stable = 0
        var current: Rect
        do {
            current = bounds()
            if (abs(current.width() - width) <= 2 && abs(current.height() - height) <= 2 && current == previous) stable++ else stable = 0
            // Wait for the system's window-move animation as well as the new dimensions.
            if (stable >= 2) return current
            previous = Rect(current)
            Thread.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Sticker size expected ${width}x$height, got $current")
    }
    private fun drag(description: String, dx: Int, dy: Int) {
        awaitNode(description = description)
        val frame = bounds()
        // Accessibility can cache child coordinates across a WindowManager move. Use the current layout frame.
        val x = if(description.contains("左"))frame.left+dp(13) else frame.right-dp(13)
        val y = if(description.contains("上"))frame.top+dp(13) else frame.bottom-dp(13)
        android.util.Log.i("StickerOverlayTest", "$description touch=$x,$y before=$frame delta=$dx,$dy")
        // Use the platform's complete finger events, as in manual device verification.
        shell("input swipe $x $y ${x + dx} ${y + dy} 600")
        instrumentation.waitForIdleSync()
        android.util.Log.i("StickerOverlayTest", "$description after=${bounds()}")
    }

    @Test fun allCornersResizeAndRememberDimensionsAndSmallWindowCanCancelDeletion() {
        val info = automation.serviceInfo
        val flags = info.flags
        automation.serviceInfo = info.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val saved = context.getSharedPreferences("sticker_overlay", Context.MODE_PRIVATE)
        val oldWidth = saved.getFloat("width_dp", 330f); val oldHeight = saved.getFloat("height_dp", 360f)
        val oldPermission = shell("appops get com.weavetext.ime SYSTEM_ALERT_WINDOW")
        val intent = Intent(context, StickerOverlayService::class.java)
        val repository = StickerRepository.get(context)
        val (item, imported) = instrumentation.context.assets.open("stickers/animated.gif").use {
            repository.store.import(it, "缩放确认测试")
        }
        try {
            context.stopService(intent)
            shell("appops set com.weavetext.ime SYSTEM_ALERT_WINDOW allow")
            saved.edit().putFloat("width_dp", 300f).putFloat("height_dp", 300f).commit()
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    val edit = activity.findViewById<android.widget.EditText>(android.R.id.edit)
                    activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(edit.windowToken, 0)
                    edit.clearFocus()
                }
                Thread.sleep(500)
                instrumentation.runOnMainSync { StickerOverlayService.avoidKeyboard(null); StickerOverlayService.start(context) }
                assertTrue(awaitNode(description = "表情收纳袋，拖动移动，点击展开").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitSize(dp(300), dp(300))
                for ((label, horizontal, vertical) in listOf(
                    Triple("左上角", -1, -1), Triple("右上角", 1, -1), Triple("左下角", -1, 1), Triple("右下角", 1, 1)
                )) {
                    val before = bounds()
                    drag("从${label}调整表情收纳窗大小", -horizontal * dp(18), -vertical * dp(16))
                    val smaller = awaitSize(before.width() - dp(18), before.height() - dp(16))
                    assertTrue("$label horizontal anchor: before=$before smaller=$smaller", abs((if (horizontal < 0) before.right else before.left) - (if (horizontal < 0) smaller.right else smaller.left)) <= 1)
                    assertTrue("$label vertical anchor: before=$before smaller=$smaller", abs((if (vertical < 0) before.bottom else before.top) - (if (vertical < 0) smaller.bottom else smaller.top)) <= 1)
                    drag("从${label}调整表情收纳窗大小", horizontal * dp(18), vertical * dp(16))
                    awaitSize(before.width(), before.height())
                }
                val preferred = bounds()
                assertTrue(awaitNode(description = "收起").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(awaitNode(description = "表情收纳袋，拖动移动，点击展开").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitSize(preferred.width(), preferred.height())
                instrumentation.runOnMainSync { StickerOverlayService.avoidKeyboard(dp(280)) }
                Thread.sleep(300)
                val clipped=bounds()
                assertTrue(clipped.height() < preferred.height())
                shell("input tap ${clipped.right-dp(13)} ${clipped.top+dp(13)}")
                // Neither a tap nor a horizontal resize may replace the preferred height with the IME-clipped height.
                drag("从右上角调整表情收纳窗大小",-dp(18),1)
                awaitSize(preferred.width()-dp(18),clipped.height())
                instrumentation.runOnMainSync { StickerOverlayService.avoidKeyboard(null) }
                awaitSize(preferred.width()-dp(18),preferred.height())
                drag("从右上角调整表情收纳窗大小",dp(18),0)
                awaitSize(preferred.width(), preferred.height())
                context.stopService(intent)
                Thread.sleep(200)
                instrumentation.runOnMainSync { StickerOverlayService.start(context) }
                assertTrue(awaitNode(description = "表情收纳袋，拖动移动，点击展开").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitSize(preferred.width(), preferred.height())
                drag("从右下角调整表情收纳窗大小", -dp(500), -dp(500))
                val minimum = awaitSize(dp(240), dp(190))
                assertTrue(awaitNode(description = item.name + "，动图").performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                assertTrue(awaitNode(description = "删除").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val cancel = awaitNode(text = "取消")
                val confirm = awaitNode(text = "删除")
                for (button in listOf(cancel, confirm)) {
                    val rect = Rect().also { button.getBoundsInScreen(it) }
                    assertTrue("Confirmation action outside minimum window: $rect window=$minimum", minimum.contains(rect))
                    assertTrue(button.isEnabled && rect.height() >= dp(40))
                }
                assertNotNull(repository.store.get(item.id))
                automation.takeScreenshot()?.let { screenshot ->
                    context.openFileOutput("sticker-small-delete-confirmation.png", Context.MODE_PRIVATE).use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }; screenshot.recycle()
                }
                assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertNotNull(repository.store.get(item.id))
                assertTrue(repository.store.file(item).isFile)
            }
        } finally {
            context.stopService(intent)
            instrumentation.runOnMainSync { StickerOverlayService.avoidKeyboard(null) }
            saved.edit().putFloat("width_dp", oldWidth).putFloat("height_dp", oldHeight).commit()
            if (imported) repository.store.delete(setOf(item.id))
            val mode = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default)").find(oldPermission)?.groupValues?.get(1) ?: "default"
            shell("appops set com.weavetext.ime SYSTEM_ALERT_WINDOW $mode")
            info.flags = flags; automation.serviceInfo = info
        }
    }
}
