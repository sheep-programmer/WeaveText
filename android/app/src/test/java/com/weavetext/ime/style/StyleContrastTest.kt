package com.weavetext.ime.style

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 所有内置主题（亮 / 暗）的文字对比度自动断言：文字 ≥ 4.5:1，副文字与图标 ≥ 3:1（05 §6）。
 * Contrast of every built-in theme, light and dark: text ≥ 4.5:1, secondary text and icons ≥ 3:1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StyleContrastTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun allBuiltInThemesMeetContrast() {
        val store = com.weavetext.ime.extensions.ExtensionStore(app)
        store.items.filter { it.kind == "theme" && it.source == "bundled" }.forEach(store::install)
        val repo = StyleRepository.get(app)
        val failures = ArrayList<String>()
        for (id in repo.themeIds) {
            val t = repo.theme(id)
            for (dark in listOf(false, true)) {
                val bg = t.background(dark)
                val stops = if (bg.type == "gradient") bg.colors else intArrayOf(t.palette(dark).background)
                for (c in Contrast.checks(t.palette(dark), stops)) {
                    if (!c.ok) failures += "$id/${if (dark) "dark" else "light"}: $c"
                }
            }
        }
        assertTrue("对比度不足 / contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * 「玻璃」的长按候选等气泡半透明（叠在键盘背景上显示），文字对叠合后的底色在每个渐变色上都 ≥ 4.5:1。
     * Glass popups are translucent (shown over the backdrop); text on the composited plate is ≥ 4.5:1 at every stop.
     */
    @Test fun glassPopupIsTranslucentAndReadable() {
        val store = com.weavetext.ime.extensions.ExtensionStore(app)
        store.install(store.items.first { it.key == "theme:glass" })
        val t = StyleRepository.get(app).theme("glass")
        for (dark in listOf(false, true)) {
            val p = t.palette(dark)
            assertTrue("glass popup should be translucent", (p.popup ushr 24) in 1..254)
            for (bg in t.background(dark).colors) {
                val plate = Contrast.over(p.popup, bg)
                val r = Contrast.ratio(p.label, plate)
                assertTrue("glass/${if (dark) "dark" else "light"} label/popup %.2f".format(r), r >= 4.5)
                val sel = Contrast.ratio(p.onAccent, Contrast.over(p.popupSelected, plate))
                assertTrue("glass/${if (dark) "dark" else "light"} onAccent/popupSelected %.2f".format(sel), sel >= 4.5)
            }
        }
    }

    @Test fun ratioMatchesWcagReference() {
        assertTrue(Math.abs(Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) - 21.0) < 0.01)
        assertTrue(Math.abs(Contrast.ratio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()) - 4.54) < 0.02)
        // 半透明前景先叠到背景上。 Translucent foregrounds are composited first.
        assertTrue(Contrast.ratio(0x00000000, 0xFFFFFFFF.toInt()) < 1.01)
    }
}
