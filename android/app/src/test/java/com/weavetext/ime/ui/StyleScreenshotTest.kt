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
import org.robolectric.shadows.ShadowLooper
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

    /** 「玻璃」的长按候选半透明：垫的是键盘背景，不透出下面的按键。 Glass long-press popup, translucent over the backdrop. */
    @Test fun glassPopup() {
        for (dark in listOf(false, true)) {
            val (k, _) = keyboard(dark) { clear(); putString(WeavePrefs.STYLE_THEME, "glass") }
            val kv = k.keyboardView
            val ov = k.overlay!!
            val r = android.graphics.RectF(kv.keyOf('e'.code)!!.rect)
            ov.map(kv, r, r)
            ov.showAlternatives(r, listOf("e", "é", "è", "ê", "ë", "ē"), 0)
            idle()
            k.view.captureRoboImage(File(dir, "theme_glass_popup_${if (dark) "dark" else "light"}.png").path)
            kb?.dispose()
            kb = null
        }
    }

    /**
     * 渐变背景上候选栏右端的渐隐与展开区直接透出背景：逐像素等于只画背景的结果。
     * On a gradient the candidate bar's right-edge fade and expand area show the backdrop itself, pixel for pixel.
     */
    @Test fun candidateFadeShowsBackdrop() {
        val (k, c) = keyboard(false) { clear(); putString(WeavePrefs.STYLE_THEME, "glass") }
        c.previewState(composing())
        idle()
        val board = k.board
        val full = android.graphics.Bitmap.createBitmap(board.width, board.height, android.graphics.Bitmap.Config.ARGB_8888)
        board.draw(android.graphics.Canvas(full))
        val bgOnly = android.graphics.Bitmap.createBitmap(board.width, board.height, android.graphics.Bitmap.Config.ARGB_8888)
        com.weavetext.ime.ui.keyboard.BackdropDrawable(k.palette.backdrop!!).apply { setBounds(0, 0, board.width, board.height) }
            .draw(android.graphics.Canvas(bgOnly))
        val bar = k.topBar
        val d = app.resources.displayMetrics.density
        val y = bar.top + bar.height - 3
        var worst = 0
        for (x in (bar.right - (60 * d).toInt()) until bar.right) {
            val a = full.getPixel(x, y); val b = bgOnly.getPixel(x, y)
            for (sh in intArrayOf(16, 8, 0)) worst = maxOf(worst, kotlin.math.abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)))
        }
        org.junit.Assert.assertTrue("fade differs from backdrop by $worst", worst <= 2)
        kb?.dispose()
        kb = null
    }

    /** 左侧分类布局：向上一划翻到下一页，页码随之变化。 Side-category layout: a swipe up turns one page. */
    @Test fun sideSymbolsPage() {
        val (k, _) = keyboard(false) { clear(); putString(WeavePrefs.STYLE_LAYOUT, "classic") }
        k.showPanel("symbol")
        val panel = k.panelNamed("symbol") as com.weavetext.ime.ui.keyboard.SymbolPanel
        panel.selectEmoji()
        idle()
        val body = (panel.view as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        val grid = body.getChildAt(1) as com.weavetext.ime.ui.keyboard.ScrollGridView
        org.junit.Assert.assertTrue(grid.pageHeight() > 0f)
        val t = android.os.SystemClock.uptimeMillis()
        val x = grid.width / 2f
        val y0 = grid.height * 0.8f
        fun ev(dt: Long, action: Int, y: Float) = android.view.MotionEvent.obtain(t, t + dt, action, x, y, 0).also { grid.dispatchTouchEvent(it); it.recycle() }
        ev(0, android.view.MotionEvent.ACTION_DOWN, y0)
        for (i in 1..5) ev(i * 100L, android.view.MotionEvent.ACTION_MOVE, y0 - grid.height * 0.1f * i)
        ev(600, android.view.MotionEvent.ACTION_UP, y0 - grid.height * 0.5f)
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        grid.computeScroll()
        org.junit.Assert.assertEquals(1, grid.page)
        snap("classic_symbols_emoji_page2_light")
    }
}
