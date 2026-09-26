package com.weavetext.ime.ui.keyboard

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * 键盘颜色令牌（docs/design/01 §2.1–2.2），由配色主题 JSON 解析得到（05 §4）。次要文字对键面 ≥ 4.5:1，提示/禁用 ≥ 3:1（WCAG），
 * 禁用键另以描边无填充的形状区分，不只靠变暗。
 * Keyboard colour tokens. Secondary text ≥ 4.5:1 on key faces, hints/disabled ≥ 3:1; disabled keys
 * are also drawn outlined (no fill) rather than dimmed only.
 */
data class KbPalette(
    val dark: Boolean,
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val keyFunc: Int,
    val keyFuncPressed: Int,
    val keyShadow: Int,
    val keyAccent: Int,
    val keyAccentPressed: Int,
    val onAccent: Int,
    val label: Int,
    val labelHint: Int,
    val labelSecondary: Int,
    val labelDisabled: Int,
    val candidateFirst: Int,
    val icon: Int,
    val divider: Int,
    val toolbarActive: Int,
    val accentSoft: Int,
    val popup: Int,
    val popupSelected: Int,
    val popupShadow: Int,
    val card: Int,
    val danger: Int,
    val voiceWave: Int,
    /** 底部弹层遮罩（01 §5 E4）。 Sheet scrim. */
    val scrim: Int,
    /** 按键阴影形式 [KeyShadow]。 Key shadow form. */
    val shadow: Int = KeyShadow.BAR,
    /** 按键描边色与宽度（dp，0 = 无）。 Key outline colour and width in dp (0 = none). */
    val stroke: Int = 0,
    val strokeWidth: Float = 0f,
    /** 字母键用 500 字重。 Letters in medium weight. */
    val letterMedium: Boolean = false,
    /** 首选候选用胶囊底（否则彩色字）。 First candidate on a pill instead of coloured text. */
    val candidatePill: Boolean = false,
    /** 首选胶囊底色。 First-candidate pill colour. */
    val candidatePillColor: Int = 0,
    /** 非纯色背景（渐变 / 图片）；null = 纯色 [background]。 Non-solid background; null = solid. */
    val backdrop: KbBackdrop? = null,
)

/** 按键阴影形式。 Key shadow forms. */
object KeyShadow {
    /** 底部 1dp 实色条。 Solid 1dp bar below the key. */
    const val BAR = 0
    /** 柔和投影。 Soft drop shadow. */
    const val SOFT = 1
    const val NONE = 2
}

/**
 * 渐变或图片背景。图片已在解析时解码、模糊并缩放，绘制时不再分配。
 * Gradient or image background; images are decoded, blurred and scaled once at resolve time.
 */
class KbBackdrop(
    val colors: IntArray,
    /** 渐变角度（度，0 = 从左到右，90 = 从上到下）。 Gradient angle in degrees. */
    val angle: Float = 90f,
    val image: android.graphics.Bitmap? = null,
    /** 压暗（0–1，叠加黑色）。 Dim amount (0–1, black overlay). */
    val dim: Float = 0f,
)

/**
 * 键盘尺寸（01 §4.3、§6、§7）。全部为 px，布局时一次算好。
 * Keyboard metrics (01 §4.3, §6, §7), precomputed in px.
 */
class KbMetrics(ctx: Context, level: Int, geo: KbGeometry = KbGeometry.DEFAULT, scale: Float = 1f) {
    val density: Float = ctx.resources.displayMetrics.density
    private val widthDp: Float
    val landscape: Boolean
    val small: Boolean

    val rowPitch: Float
    val keyHeight: Float
    val topBar: Float
    val padTop: Float
    val padBottom: Float
    val padH: Float
    val insetH: Float
    val insetV: Float
    /** 字号缩放 s×f。 Label scale. */
    val labelScale: Float
    /** 键内图标缩放 s（不随系统字号）。 Icon scale inside keys (ignores font scale). */
    val iconScale: Float
    /** 副标记（数字/符号提示、角标）缩放：系统字号最多计 1.15。 Secondary hints: font scale counted up to 1.15. */
    val hintScale: Float
    /** 候选栏字号缩放 f（完整跟随系统字号）。 Candidate scale, follows the system font scale fully. */
    val candScale: Float
    /** 顶栏高度与组合串行随 f 放大的比例。 Top-bar growth with font scale. */
    val topScale: Float
    /** 键区（4 行）高度。 Main area height. */
    val mainHeight: Float
    /** 键盘内容高度（不含导航栏）。 Keyboard height excluding nav inset. */
    val kbHeight: Float
    /** 键盘上方给气泡留的透明区。 Transparent area above the keyboard for bubbles. */
    val bubbleSpace: Float
    /** 普通键与大键（九键、数字键盘）的圆角 px。 Corner radius of normal and large keys. */
    val keyRadius: Float
    val keyRadiusLarge: Float

    init {
        val dm = ctx.resources.displayMetrics
        val cfg = ctx.resources.configuration
        widthDp = dm.widthPixels / density
        val heightDp = dm.heightPixels / density
        landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        small = widthDp < 360f
        val base = if (landscape) (heightDp * 0.105f).coerceIn(38f, 48f) else (heightDp * 0.0615f).coerceIn(46f, 62f)
        val factor = LEVEL_FACTORS[level.coerceIn(0, 4)]
        val gapV = if (small) geo.gapV - 2f else geo.gapV
        // [scale]：悬浮卡片的缩放（允许比常规档更矮）。 Floating-card scale; may go below the docked minimum.
        val pitchDp = (base * factor * geo.rowScale).coerceIn(40f, 68f).let { if (scale == 1f) it else (it * scale).coerceIn(32f, 76f) }
            .roundToInt().toFloat()
        rowPitch = pitchDp * density
        keyHeight = (pitchDp - gapV) * density
        // 系统字号完整生效（上限 2.0，与系统最大档一致）；字母键另受键高约束，见 [letter]。
        // Full system font scale (up to 2.0); letters are additionally capped by key height.
        val f = cfg.fontScale.coerceIn(1f, 2f)
        topScale = 1f + 0.55f * (f - 1f)
        topBar = (if (landscape) 40f else 48f) * topScale * density
        padTop = 2f * density
        padBottom = 4f * density
        padH = geo.padH * density
        insetH = (if (small) geo.gapH / 2f - 0.5f else geo.gapH / 2f) * density
        insetV = (gapV / 2f) * density
        val s = ((pitchDp - gapV) / 46f).coerceIn(0.85f, 1.2f)
        labelScale = s * f * geo.textScale
        iconScale = s
        hintScale = s * f.coerceAtMost(1.15f) * geo.textScale
        candScale = f
        mainHeight = 4 * rowPitch
        kbHeight = topBar + padTop + mainHeight + padBottom
        bubbleSpace = 72f * density
        keyRadius = geo.radius * density
        keyRadiusLarge = geo.radiusLarge * density
    }

    fun dp(v: Float): Float = v * density
    /** 键内副标记 px。 Key hint size in px. */
    fun hint(dpSize: Float): Float = dpSize * density * hintScale
    /** 键内图标 px。 Key icon size in px. */
    fun icon(dpSize: Float): Float = dpSize * density * iconScale
    /** 键内文字 px。 Key label size in px. */
    fun label(dpSize: Float): Float = dpSize * density * labelScale
    /** 字母键主字：不超过键高的 0.62，保证不裁切。 Letter size, capped at 0.62 × key height. */
    fun letter(dpSize: Float): Float = minOf(label(dpSize) * (if (small) 0.92f else 1f), keyHeight * 0.62f)

    companion object {
        val LEVEL_FACTORS = floatArrayOf(0.88f, 0.94f, 1.00f, 1.06f, 1.12f)
    }
}

/**
 * 布局风格给出的几何参数（dp），与键高档位、系统字号一起决定 [KbMetrics]。
 * Layout-style geometry in dp; combined with the height level and font scale into [KbMetrics].
 */
class KbGeometry(
    /** 左右相邻键的间距。 Horizontal gap between keys. */
    val gapH: Float = 6f,
    /** 上下相邻键的间距（行距 − 键高）。 Vertical gap between rows. */
    val gapV: Float = 10f,
    /** 键区左右留白。 Side padding of the key area. */
    val padH: Float = 3f,
    /** 行高比例。 Row pitch scale. */
    val rowScale: Float = 1f,
    val radius: Float = 6f,
    val radiusLarge: Float = 8f,
    /** 键内文字缩放（用户微调）。 Key text scale (user tweak). */
    val textScale: Float = 1f,
) {
    companion object { val DEFAULT = KbGeometry() }
}
