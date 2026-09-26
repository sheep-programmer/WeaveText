package com.weavetext.ime.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.ime.EnterAction
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.style.StyleRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 键盘风格截图：每套布局风格 × 亮/暗 × {26 键输入中、九键、符号面板、空闲工具栏}，每套配色主题 × 26 键（亮/暗）。
 * 输出 src/test/snapshots/style_*.png 与 theme_*.png。
 * Style snapshots: every layout × light/dark × {QWERTY composing, 9-key, symbols, idle toolbar}; every theme × QWERTY.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class StyleScreenshotTest : KeyboardSnapshotSupport() {
    private val layouts get() = StyleRepository.get(app).layoutIds

    private fun each(layout: String) {
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            val style: android.content.SharedPreferences.Editor.() -> Unit = { clear(); putString(WeavePrefs.STYLE_LAYOUT, layout) }

            val (_, c) = keyboard(dark, style)
            c.previewState(composing())
            snap("${layout}_composing_$mode")
            kb?.dispose()

            val (_, c2) = keyboard(dark) { style(); putString(WeavePrefs.KEYBOARDS, "t9,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9") }
            c2.previewState(composing("t9", preedit = "64", pinyin = listOf("ni", "mi", "oh", "o", "n")))
            snap("${layout}_t9_$mode")
            kb?.dispose()

            val (k3, _) = keyboard(dark, style)
            k3.showPanel("symbol")
            snap("${layout}_symbols_$mode")
            kb?.dispose()

            val (_, c4) = keyboard(dark, style)
            c4.previewState(ImeState(engineReady = true, enterAction = if (dark) EnterAction.SEND else EnterAction.NEWLINE))
            snap("${layout}_idle_$mode")
            kb?.dispose()
            kb = null
        }
    }

    override fun snap(name: String) {
        idle()
        kb!!.view.captureRoboImage(File(dir, "style_$name.png").path)
    }

    @Test fun fresh() = each("fresh")
    @Test fun classic() = each("classic")
    @Test fun bright() = each("bright")
    @Test fun plain() = each("plain")
    @Test fun round() = each("round")
    @Test fun refined() = each("refined")
    @Test fun numrow() = each("numrow")

    @Test fun allLayoutsCovered() {
        org.junit.Assert.assertEquals(listOf("fresh", "classic", "bright", "plain", "round", "refined", "numrow"), layouts)
    }

    /** 每套配色主题配默认布局的 26 键（输入中）。 Every theme on the default layout, composing. */
    @Test fun themes() {
        val cands = listOf("你好", "拟好", "你", "尼", "泥", "呢", "倪").map { Candidate(it, "", false) }
        for (id in StyleRepository.get(app).themeIds) for (dark in listOf(false, true)) {
            val (_, c) = keyboard(dark) { clear(); putString(WeavePrefs.STYLE_THEME, id) }
            c.previewState(composing(cands = cands))
            idle()
            kb!!.view.captureRoboImage(File(dir, "theme_${id}_${if (dark) "dark" else "light"}.png").path)
            kb?.dispose()
            kb = null
        }
    }

    /** 英文三格建议条与浮动组合串等布局专有形态。 Layout-specific forms: English strip. */
    @Test fun englishStrip() {
        val (_, c) = keyboard(false) { putString(WeavePrefs.STYLE_LAYOUT, "round") }
        c.previewState(ImeState(chinese = false, engineReady = true, candidates = listOf("hello", "help", "held").map { Candidate(it, "", false) }, totalCandidates = 3))
        snap("round_english_light")
    }
}
