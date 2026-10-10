package com.weavetext.ime.ui

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.HandwritingAreaMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Full viewport captures show the half/full area's extent and reserved bars; candidates are preview data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class HandwritingAreaScreenshotTest : KeyboardSnapshotSupport() {
    @Test fun standardScreenLight() = capture(HandwritingAreaMode.KEYBOARD)
    @Test fun halfScreenLight() = capture(HandwritingAreaMode.HALF)
    @Test fun fullScreenLight() = capture(HandwritingAreaMode.FULL)

    private fun capture(area: HandwritingAreaMode) {
        val (keyboard, controller) = keyboard(false) {
            putString(WeavePrefs.KEYBOARDS, "hand,pinyin,english")
            putString(WeavePrefs.ACTIVE_KEYBOARD, "hand")
            putString(WeavePrefs.HAND_AREA_MODE, area.key)
            putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false)
        }
        val windowInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 72, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 120))
            .setInsets(WindowInsets.Type.captionBar(), Insets.of(0, 0, 0, 160))
            .setVisible(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(), true).build()
        controller.previewState(composing("hand", preedit = "", cands = "中申巾由甲电".map { Candidate(it.toString(), "", false) }))
        idle()
        keyboard.flushRender()
        // The fixture's modeled bars must arrive after Activity's queued empty-inset traversal.
        keyboard.view.dispatchApplyWindowInsets(windowInsets)
        val frame = keyboard.view.parent as FrameLayout
        val bounds = activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        repeat(3) {
            frame.forceLayout()
            keyboard.view.forceLayout()
            frame.measure(View.MeasureSpec.makeMeasureSpec(bounds.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bounds.height(), View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, bounds.width(), bounds.height())
        }
        val pad = keyboard.keyboardView.hand!!
        val w = pad.rect.width()
        val h = pad.rect.height()
        val size = minOf(w, h, keyboard.metrics.dp(160f))
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        // Seed actual HandPad outlines without submitting recognition or starting an idle-commit timer.
        pad.begin(left + size * 0.2f, top + size * 0.25f, 0)
        pad.add(left + size * 0.8f, top + size * 0.25f, 20)
        pad.add(left + size * 0.8f, top + size * 0.75f, 40)
        pad.add(left + size * 0.2f, top + size * 0.75f, 60)
        pad.add(left + size * 0.2f, top + size * 0.25f, 80)
        pad.end(100)
        pad.begin(left + size * 0.5f, top + size * 0.05f, 120)
        pad.add(left + size * 0.5f, top + size * 0.95f, 140)
        pad.end(160)
        keyboard.keyboardView.inkLayer?.invalidate()
        assertTrue("the keyboard must keep clear of the status bar", keyboard.view.top >= 72)
        assertTrue("snapshot uses the measured viewport", keyboard.view.height <= bounds.height() - 72)
        dir.mkdirs()
        val name = if (area == HandwritingAreaMode.KEYBOARD) "standard" else area.key
        frame.captureRoboImage(File(dir, "keyboard_hand_${name}_light.png").path)
    }
}
