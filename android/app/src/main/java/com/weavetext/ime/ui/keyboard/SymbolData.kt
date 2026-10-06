package com.weavetext.ime.ui.keyboard

/**
 * 符号面板数据（02 §8）。表情按 Unicode 分组顺序排列。
 * Symbol panel data; emoji follow Unicode group order.
 */
object SymbolData {
    class Category(val name: String, val items: List<String>, val columns: Int = 6, val emoji: Boolean = false, val kaomoji: Boolean = false)

    private fun chars(s: String) = s.split(' ').filter { it.isNotEmpty() }

    val DEFAULT_COMMON = chars("， 。 ？ ！ 、 ： ； “ ” ‘ ’ （ ） 《 》 【 】 … — · ～ ￥ ＠ ＃ ％ ＆ ＊ ＋ ＝ ／")

    val CHINESE = chars(
        "， 。 ？ ！ 、 ： ； “ ” ‘ ’ （ ） 《 》 〈 〉 【 】 〔 〕 「 」 『 』 … — · ～ ￥ ※ § № ° ℃ ‰ ＠ ＃ ％ ＆ ＊ ＋ － ＝ ／ ＼ ｜ ＿",
    )
    val ENGLISH = chars(
        ", . ? ! : ; ' \" ( ) [ ] { } < > @ # $ % ^ & * - _ + = / \\ | ~ ` € £ ¥ © ® ™",
    )
    val MATH = chars(
        "+ − × ÷ = ≠ ≈ ≡ < > ≤ ≥ ± ∞ √ ∛ ∑ ∏ ∫ ∮ ∂ ∆ ∇ π ° ′ ″ % ‰ ∈ ∉ ⊂ ⊃ ⊆ ⊇ ∪ ∩ ∧ ∨ ¬ ∀ ∃ ∵ ∴ ∝ ⊥ ∠ ½ ⅓ ¼ ¾ ² ³ ⁿ",
    )
    val ORDINAL = chars(
        "① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ ⒈ ⒉ ⒊ ⒋ ⒌ ⒍ ⒎ ⒏ ⒐ ⒑ " +
            "㈠ ㈡ ㈢ ㈣ ㈤ ㈥ ㈦ ㈧ ㈨ ㈩ 一 二 三 四 五 六 七 八 九 十 壹 贰 叁 肆 伍 陆 柒 捌 玖 拾 佰 仟 " +
            "Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ Ⅺ Ⅻ ⅰ ⅱ ⅲ ⅳ ⅴ ⅵ ⅶ ⅷ ⅸ ⅹ",
    )
    val ARROWS = chars(
        "← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇑ ⇒ ⇓ ⇔ ⇕ ➔ ➜ ➤ ↩ ↪ ⤴ ⤵ ↺ ↻ ⇄ ⇅ ▲ ▼ ◀ ▶ △ ▽ ◁ ▷ ★ ☆ ● ○ ◆ ◇ ■ □ ✓ ✗ ♠ ♥ ♣ ♦ ♪ ♫",
    )
    val EMOJI = chars(
        // 笑脸 / smileys
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 " +
            "🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 " +
            "😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 " +
            "🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊 💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️ 🧡 " +
            "💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 💬 💭 💤 " +
            // 手势与人 / people
            "👋 🤚 ✋ 🖖 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🙏 ✍️ 💪 👀 " +
            "🧠 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙅 🙆 💁 🙋 🙇 🤦 🤷 " +
            // 动物 / animals
            "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 " +
            "🐍 🐙 🦀 🐠 🐬 🐳 🐘 🦒 🐑 🐇 🌸 🌹 🌻 🌼 🌷 🌱 🌲 🌴 🍀 🍁 " +
            // 食物 / food
            "🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥝 🍅 🥑 🥦 🌽 🥕 🍞 🧀 🍳 🍔 🍟 🍕 🌭 🍜 🍝 🍣 🍱 🥟 " +
            "🍚 🍙 🍦 🍰 🎂 🍫 🍬 🍭 🍩 🍪 ☕ 🍵 🧋 🍺 🍻 🥂 🍷 " +
            // 活动 / activities
            "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🏸 🥊 🎯 🎮 🎲 🎨 🎤 🎧 🎵 🎶 🎹 🎸 🏆 🥇 🎉 🎊 🎁 🎈 🎄 🧧 🏮 " +
            // 旅行 / travel
            "🚗 🚕 🚌 🚓 🚑 🚒 🚲 🛵 🚄 🚇 ✈️ 🚀 🚢 ⛵ 🏠 🏢 🏥 🏫 ⛰️ 🏖️ 🌋 🌈 ☀️ 🌤️ ⛅ 🌧️ ⛈️ ❄️ ☃️ 🌙 ⭐ 🌟 🔥 💧 🌊 " +
            // 物品 / objects
            "⌚ 📱 💻 ⌨️ 🖨️ 📷 📺 ⏰ 🔋 🔌 💡 🔦 💰 💳 💎 🔧 🔨 🔑 🔒 🚪 🛏️ 🛁 🧸 📦 ✉️ 📅 📌 📎 ✂️ 📝 📖 🔍 " +
            // 符号 / symbols
            "✅ ❌ ⭕ ❗ ❓ ⚠️ 🚫 ♻️ 🆗 🆕 🆒 🔴 🟠 🟡 🟢 🔵 🟣",
    )
    val KAOMOJI = listOf(
        "(＾▽＾)", "(๑•̀ㅂ•́)و✧", "╮(╯▽╰)╭", "(｡•́︿•̀｡)", "ヽ(✿ﾟ▽ﾟ)ノ", "¯\\_(ツ)_/¯",
        "(╯°□°）╯︵ ┻━┻", "(＞﹏＜)", "(ง •̀_•́)ง", "(´・ω・`)", "(=^･ω･^=)", "(*´▽`*)",
        "(ÒωÓױ)", "(⊙o⊙)", "(^_−)☆", "o(*￣▽￣*)ブ", "(っ´▽`)っ", "(T▽T)",
        "(￣▽￣)~*", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "(；′⌒`)", "_(:3」∠)_", "(・∀・)", "(◍•ᴗ•◍)",
    )

    /** 支持肤色修饰的表情。 Emoji supporting skin-tone modifiers. */
    val SKIN_TONE_BASE: Set<String> = chars("👋 🤚 ✋ 🖖 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🙏 ✍️ 💪 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙅 🙆 💁 🙋 🙇 🤦 🤷").toSet()
    val SKIN_TONES = listOf("", "🏻", "🏼", "🏽", "🏾", "🏿")

    /** 把肤色加到表情上（去掉 VS16）。 Apply a skin tone (drops VS16). */
    fun withTone(base: String, tone: Int, supported: Set<String> = SKIN_TONE_BASE): String {
        if (tone !in 1..5 || base !in supported) return base
        val first = String(Character.toChars(base.codePointAt(0)))
        // Drop only the selector after the modified person/hand; retain ZWJ/gender selectors.
        return first + SKIN_TONES[tone] + base.substring(first.length).removePrefix("️")
    }

    /** 成对符号：左 → 右。 Paired symbols: open → close. */
    val PAIRS = mapOf(
        "“" to "”", "‘" to "’", "（" to "）", "《" to "》", "〈" to "〉", "【" to "】", "〔" to "〕",
        "「" to "」", "『" to "』", "(" to ")", "[" to "]", "{" to "}", "<" to ">",
    )

    const val TAB_COMMON = 0
    const val TAB_EMOJI = 6
    const val TAB_KAOMOJI = 7

    fun categories(recent: List<String>, catalog: ExpressionCatalog? = null): List<Category> = listOf(
        Category("常用", (recent + DEFAULT_COMMON).distinct().filter{catalog?.displayable(it) != false}.take(24 + DEFAULT_COMMON.size)),
        Category("中文", CHINESE),
        Category("英文", ENGLISH),
        Category("数学", MATH),
        Category("序号", ORDINAL),
        Category("箭头", ARROWS),
        Category("表情", catalog?.supportedEmoji?.map{it.text} ?: EMOJI, 8, emoji = true),
        Category("颜文字", catalog?.kaomoji?.map{it.text} ?: KAOMOJI, 2, kaomoji = true),
    ) + (catalog?.supportedEmoji?.groupBy{it.group}?.map {(group,items)->Category(group,items.map{it.text},8,emoji = true)} ?: emptyList()) +
        (catalog?.kaomoji?.groupBy{it.group}?.map {(group,items)->Category("颜·$group",items.map{it.text},2,kaomoji = true)} ?: emptyList())
}
