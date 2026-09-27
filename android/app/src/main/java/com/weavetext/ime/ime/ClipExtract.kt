package com.weavetext.ime.ime

/**
 * 从复制的短信 / 通知里取出验证码：优先取「验证码、校验码、动态码、code」附近的 4–8 位数字（或字母数字）；
 * 没有关键词时不猜，避免把金额、电话当成验证码。
 * Pull a one-time code out of a copied message: the 4–8 digit (or alphanumeric) token near 验证码 / 校验码 / 动态码 /
 * code. Without such a keyword nothing is guessed, so amounts and phone numbers are never mistaken for codes.
 */
object ClipExtract {
    private val KEYWORDS = Regex("验证码|校验码|动态码|确认码|认证码|激活码|verification code|security code|one-time|otp|\\bcode\\b", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9A-Za-z])")
    private val ALNUM = Regex("(?<![0-9A-Za-z])([0-9A-Za-z]{4,8})(?![0-9A-Za-z])")

    /** 验证码，找不到返回 null。 The code, or null. */
    fun code(text: String): String? {
        if (text.length > 500) return null
        val kw = KEYWORDS.find(text) ?: return null
        // 取离关键词最近的候选；纯数字优先。 The candidate nearest the keyword; digits first.
        fun nearest(r: Regex, ok: (String) -> Boolean) = r.findAll(text).map { it.groups[1]!! }.filter { ok(it.value) }
            .minByOrNull { g -> minOf(kotlin.math.abs(g.range.first - kw.range.last), kotlin.math.abs(kw.range.first - g.range.last)) }?.value
        return nearest(DIGITS) { true }
            ?: nearest(ALNUM) { v -> v.any(Char::isDigit) && v.any(Char::isLetter) }
    }
}
