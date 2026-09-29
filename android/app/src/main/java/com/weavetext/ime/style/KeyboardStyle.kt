package com.weavetext.ime.style

import com.weavetext.ime.ui.keyboard.KbGeometry
import com.weavetext.ime.ui.keyboard.KbMetrics
import com.weavetext.ime.ui.keyboard.KbPalette
import com.weavetext.ime.ui.keyboard.KeyShadow

/**
 * 解析完成的键盘风格：布局 × 主题（亮或暗）× 微调。渲染器、面板、候选栏、工具栏、气泡只从这里读取尺寸与颜色。
 * A resolved keyboard style (layout × theme variant × tweaks). All keyboard views read sizes and colours from here.
 */
class KeyboardStyle(
    val layout: LayoutStyle,
    val theme: ThemeStyle,
    val dark: Boolean,
    val overrides: StyleOverrides,
    val palette: KbPalette,
    val metrics: KbMetrics,
) {
    /** 字母键副标签位置（微调可关闭）。 Letter hint position (the tweak can turn it off). */
    val hint: String get() = if (overrides.hints ?: layout.qwerty.showHints) layout.qwerty.hint else "none"

    companion object {
        /** 把微调叠加到布局几何上。 Applies the tweaks to the layout geometry. */
        fun geometry(l: LayoutStyle, o: StyleOverrides): KbGeometry {
            val g = l.geometry
            if (o.radius == null && o.gap == null && o.textScale == null) return g
            val gap = o.gap ?: 1f
            return KbGeometry(
                gapH = g.gapH * gap,
                gapV = (g.gapV * gap).coerceAtLeast(4f),
                padH = g.padH,
                rowScale = g.rowScale,
                radius = o.radius ?: g.radius,
                radiusLarge = o.radius?.let { it + 2f } ?: g.radiusLarge,
                textScale = o.textScale ?: g.textScale,
            )
        }

        /**
         * 激活态底色：强调色按 [mix] 混入背景；强调色字在上面不到 3:1 时减淡底色（最淡到 0.04）。
         * Active-state fill: the accent mixed into the background by [mix]; lightened (down to 0.04) while the accent
         * text on it stays under 3:1.
         */
        private fun softFor(bg: Int, accent: Int, mix: Float): Int {
            var t = mix
            var soft = StyleParser.mix(bg, accent, t)
            while (Contrast.ratio(accent, soft) < 3.0 && t > 0.05f) { t -= 0.02f; soft = StyleParser.mix(bg, accent, t) }
            return soft
        }

        /** 把微调叠加到配色上（强调色、阴影、按键不透明度）。 Applies colour tweaks. */
        fun palette(p: KbPalette, o: StyleOverrides): KbPalette {
            var out = p
            o.accent?.let { a ->
                val on = Contrast.onColor(a)
                // 首选候选与激活态文字需要落在背景上可读：必要时向文字色靠拢。 Keep accent text readable on the background.
                var text = a
                var t = 0f
                while (Contrast.ratio(text, p.background) < 4.5 && t < 1f) { t += 0.1f; text = StyleParser.mix(a, p.label, t) }
                out = out.copy(
                    keyAccent = a, keyAccentPressed = StyleParser.mix(a, 0xFF000000.toInt(), 0.15f), onAccent = on,
                    candidateFirst = text, popupSelected = a, voiceWave = text,
                    accentSoft = softFor(p.background, a, if (p.dark) 0.22f else 0.16f),
                )
            }
            when (o.shadow) {
                false -> out = out.copy(shadow = KeyShadow.NONE)
                true -> if (out.shadow == KeyShadow.NONE) out = out.copy(shadow = KeyShadow.BAR)
                null -> {}
            }
            o.keyOpacity?.let { a ->
                fun f(c: Int) = (((c ushr 24) * a).toInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
                out = out.copy(key = f(out.key), keyFunc = f(out.keyFunc))
            }
            return out
        }
    }
}
