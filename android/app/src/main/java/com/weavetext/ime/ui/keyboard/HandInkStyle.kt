package com.weavetext.ime.ui.keyboard

import android.content.SharedPreferences
import java.util.Locale

/** Display-only tools. Neither the tool nor its colour/pressure is sent to recognition. */
enum class HandInkStyle(val id: String, val label: String, val alpha: Int) {
    BALLPOINT("ballpoint", "圆珠笔", 255),
    BRUSH("brush", "毛笔", 255),
    PENCIL("pencil", "铅笔", 190),
    HIGHLIGHTER("highlighter", "荧光笔", 105);

    companion object {
        fun fromId(id: String?): HandInkStyle = entries.firstOrNull { it.id == id } ?: BRUSH
    }
}

data class HandInkAppearance(
    val style: HandInkStyle = HandInkStyle.BRUSH,
    val widthDp: Float = HandInkPrefs.DEFAULT_WIDTH_DP,
    val color: String = HandInkPrefs.THEME_COLOR,
)

data class HandInkColor(val value: String, val label: String)

/** Uses the supplied prefs (normally WeavePrefs.of(context)); owns only these three keys. */
object HandInkPrefs {
    const val STYLE = "hand_ink_style"
    const val WIDTH_DP = "hand_ink_width_dp"
    const val COLOR = "hand_ink_color"
    const val THEME_COLOR = "theme"
    const val DEFAULT_WIDTH_DP = 6f
    const val MIN_WIDTH_DP = 2f
    const val MAX_WIDTH_DP = 12f

    val colors = listOf(
        HandInkColor(THEME_COLOR, "跟随主题"),
        HandInkColor("#202124", "墨黑"),
        HandInkColor("#2563EB", "蓝色"),
        HandInkColor("#DC2626", "红色"),
        HandInkColor("#15803D", "绿色"),
        HandInkColor("#7C3AED", "紫色"),
        HandInkColor("#EA580C", "橙色"),
    )
    private val rgbPattern = Regex("#[0-9a-fA-F]{6}")

    fun normalizeWidth(widthDp: Float): Float =
        if (widthDp.isFinite()) widthDp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP) else DEFAULT_WIDTH_DP

    /** Accepts only theme or #RRGGBB, with surrounding whitespace trimmed. No alpha/shorthand. */
    fun normalizeColor(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed == THEME_COLOR -> THEME_COLOR
            rgbPattern.matches(trimmed) -> trimmed.uppercase(Locale.ROOT)
            else -> null
        }
    }

    /** null means theme/invalid; a custom colour is always opaque before applying tool opacity. */
    fun parseColor(value: String): Int? {
        val normalized = normalizeColor(value) ?: return null
        if (normalized == THEME_COLOR) return null
        return (0xFF000000L or normalized.substring(1).toLong(16)).toInt()
    }

    fun resolveColor(value: String, themeColor: Int): Int = parseColor(value) ?: themeColor
    fun colorLabel(value: String): String = colors.firstOrNull { it.value == value }?.label ?: value

    fun read(prefs: SharedPreferences): HandInkAppearance {
        val style = try { prefs.getString(STYLE, null) } catch (_: ClassCastException) { null }
        val width = try { prefs.getFloat(WIDTH_DP, DEFAULT_WIDTH_DP) } catch (_: ClassCastException) { DEFAULT_WIDTH_DP }
        val color = try { prefs.getString(COLOR, THEME_COLOR) } catch (_: ClassCastException) { THEME_COLOR }
        return HandInkAppearance(HandInkStyle.fromId(style), normalizeWidth(width),
            normalizeColor(color ?: THEME_COLOR) ?: THEME_COLOR)
    }

    fun setStyle(prefs: SharedPreferences, style: HandInkStyle) {
        prefs.edit().putString(STYLE, style.id).apply()
    }

    fun setWidthDp(prefs: SharedPreferences, widthDp: Float) {
        prefs.edit().putFloat(WIDTH_DP, normalizeWidth(widthDp)).apply()
    }

    /** Returns false without writing if the input is invalid. */
    fun setColor(prefs: SharedPreferences, value: String): Boolean {
        val normalized = normalizeColor(value) ?: return false
        prefs.edit().putString(COLOR, normalized).apply()
        return true
    }
}
