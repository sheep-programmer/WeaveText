package com.weavetext.ime.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部设置的键名与默认值（docs/design/03 §10）。设置 App 写入，IME 监听变化即时生效。
 * All setting keys and defaults (03 §10). The settings app writes; the IME listens and applies live.
 */
object WeavePrefs {
    const val FILE = "weave_settings"

    // 输入方案 / Input schemes
    /** 启用的键盘（逗号分隔、有序）：pinyin, shuangpin, t9, t14, hand, wubi86, english。 */
    const val KEYBOARDS = "keyboards"
    const val KEYBOARDS_DEFAULT = "pinyin,english"
    /** 当前中文键盘（上述键之一，english 除外）。 Current Chinese keyboard. */
    const val ACTIVE_KEYBOARD = "active_keyboard"
    const val SHUANGPIN_SCHEME = "shuangpin_scheme"
    const val SHUANGPIN_SCHEME_DEFAULT = "xiaohe"
    const val SHUANGPIN_HINTS = "shuangpin_hints"
    /** 模糊音（字符串集合，元素为内核选项名去掉 "fuzzy." 前缀）。 Fuzzy pairs. */
    const val FUZZY = "fuzzy"
    const val WUBI_ROOT_HINTS = "wubi_root_hints"
    const val WUBI_PINYIN_MIX = "wubi_pinyin_mix"

    // 外观与手感 / Look & feel
    /** "system" / "light" / "dark" */
    const val THEME = "theme"
    /** 键盘高度档位 0–4（[com.weavetext.ime.ui.keyboard.KbMetrics.LEVEL_FACTORS]）。 Height level 0–4. */
    const val HEIGHT_LEVEL = "height_level"
    /** 默认「较高」：1080×2400 一类的长屏上标准档偏矮（docs/design/06 §2）。 Default: the second-tallest level. */
    const val HEIGHT_LEVEL_DEFAULT = 3
    /** 0 关，1 跟随系统（旧版选项，仍然生效），2 轻，3 中，4 强。 0 off, 1 system (legacy), 2 light, 3 medium, 4 strong. */
    const val VIBRATION = "vibration"
    /** 默认关闭，用户在设置里打开。 Off by default. */
    const val VIBRATION_DEFAULT = 0
    /** 旧版按键音音量档 0–4（仅用于兼容：没有 [SOUND_STYLE] 时换算）。 Legacy sound level 0–4. */
    const val SOUND = "sound"
    /** 按键音风格："off" / "system" / [com.weavetext.ime.ui.keyboard.KeySoundSynth.STYLES]。 Key-sound style. */
    const val SOUND_STYLE = "sound_style"
    const val SOUND_OFF = "off"
    const val SOUND_SYSTEM = "system"
    /** 按键音音量 0–100。 Key-sound volume 0–100. */
    const val SOUND_VOLUME = "sound_volume"
    const val SOUND_VOLUME_DEFAULT = 50
    const val KEY_PREVIEW = "key_preview"
    /** 未设置 = 尚未询问（默认关闭，首次打开剪贴板面板时询问）。 Unset = not asked yet (off). */
    const val CLIPBOARD_RECORD = "clipboard_record"
    /** 设置里「清空剪贴板历史」写入时间戳，键盘收到后清空内存中的历史。 Clear-history signal. */
    const val CLIPBOARD_CLEARED = "clipboard_cleared"

    /** 成对输入括号与引号（默认开）。 Insert closing brackets and quotes automatically (on by default). */
    const val AUTO_PAIR = "auto_pair"
    /** 上屏后推荐下一个词（默认开）。 Suggest the next word after a commit (on by default). */
    const val PREDICTION = "prediction"
    /** 拼音自动纠错（默认开）：字母颠倒、漏打、多打时改正，并在拼音上标红。 Pinyin auto-correction, on by default. */
    const val AUTOCORRECT = "autocorrect"
    /** 手写停笔判字时间档位 0–2（[HAND_PAUSE_MS]）。 How long a pause ends a handwritten character (0–2). */
    const val HAND_PAUSE = "hand_pause"
    /** 默认中档：写得慢的人（长辈）用慢档。 Default: the middle level; slow writers pick the slow one. */
    const val HAND_PAUSE_DEFAULT = 1
    /** 各档的停笔时间（毫秒）：快 / 中 / 慢。 Pause per level in ms: fast / medium / slow. */
    val HAND_PAUSE_MS = longArrayOf(550L, 800L, 1200L)
    /** 云端热词（默认关闭）：只下载公开热词库，不上传任何内容。 Cloud hot words, off by default; download only. */
    const val CLOUD_WORDS = "cloud_words"

    // 织文互联 / WeaveLink
    /** 默认关闭：用户在「互联」里打开后才启动局域网服务。 Off by default; the LAN service starts only after opting in. */
    const val LINK_ENABLED = "link_enabled"
    /** 已连接时同步剪贴板（默认开）。 Sync the clipboard while connected (on by default). */
    const val LINK_CLIP_SYNC = "link_clip_sync"
    /** 本机在对方看到的名字（默认机型名）。 This device's name as others see it (defaults to the model). */
    const val LINK_NAME = "link_name"

    // 键盘风格 / Keyboard style (docs/design/05)
    /** 布局风格 id（内置或 "pack:<id>"）。 Layout style id (built-in or "pack:<id>"). */
    const val STYLE_LAYOUT = "style_layout"
    /** 配色主题 id；"auto" = 跟随布局默认。 Theme id; "auto" follows the layout's default. */
    const val STYLE_THEME = "style_theme"
    /** 用户微调 JSON。 User tweaks as JSON. */
    const val STYLE_OVERRIDES = "style_overrides"
    /** 背景图或风格包变化时写入时间戳，通知键盘重新解析。 Bumped when images or packs change. */
    const val STYLE_STAMP = "style_stamp"

    // 键盘内开关 / In-keyboard toggles
    const val TRADITIONAL = "traditional"
    /** 0 关，1 靠左，2 靠右。 0 off, 1 left, 2 right. */
    const val ONE_HAND = "one_hand"
    /** 宽屏（键区宽于 600dp）时 26 键分成左右两半（06 §8）。 Split QWERTY on wide screens. */
    const val SPLIT_WIDE = "split_wide"
    /** 悬浮键盘（06 §5）。 Floating keyboard. */
    const val FLOATING = "floating"
    /** 悬浮卡片位置 "fx,fy"（0–1 的比例），横竖屏各记一份。 Card position as fractions, per orientation. */
    const val FLOAT_POS_PORT = "float_pos_port"
    const val FLOAT_POS_LAND = "float_pos_land"
    /** 悬浮卡片缩放（0.7–1.3），横竖屏各记一份。 Card scale, per orientation. */
    const val FLOAT_SIZE_PORT = "float_size_port"
    const val FLOAT_SIZE_LAND = "float_size_land"
    /** "tap" / "hold" */
    const val VOICE_MODE = "voice_mode"
    const val SYMBOL_LOCK = "symbol_lock"
    const val SYMBOL_RECENT = "symbol_recent"
    const val EMOJI_SKIN = "emoji_skin"

    const val ONBOARDING_DONE = "onboarding_done"
    const val MIC_SKIPPED = "mic_skipped"

    val FUZZY_PAIRS = listOf(
        "z_zh" to "z=zh", "c_ch" to "c=ch", "s_sh" to "s=sh", "n_l" to "n=l", "f_h" to "f=h", "r_l" to "r=l",
        "an_ang" to "an=ang", "en_eng" to "en=eng", "in_ing" to "in=ing", "ian_iang" to "ian=iang", "uan_uang" to "uan=uang",
    )

    val SHUANGPIN_SCHEMES = listOf(
        "xiaohe" to "小鹤", "ziranma" to "自然码", "microsoft" to "微软", "sogou" to "搜狗",
    )

    val KEYBOARD_NAMES = mapOf(
        "pinyin" to "全拼 26 键", "shuangpin" to "双拼", "t9" to "九键拼音", "t14" to "14 键拼音", "hand" to "手写", "wubi86" to "五笔 86", "english" to "英文 26 键",
    )

    fun of(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun keyboards(p: SharedPreferences): List<String> =
        (p.getString(KEYBOARDS, KEYBOARDS_DEFAULT) ?: KEYBOARDS_DEFAULT).split(',').filter { it in KEYBOARD_NAMES }
            .ifEmpty { listOf("pinyin") }

    fun activeKeyboard(p: SharedPreferences): String {
        val enabled = keyboards(p).filter { it != "english" }
        val cur = p.getString(ACTIVE_KEYBOARD, null)
        return if (cur != null && cur in enabled) cur else enabled.firstOrNull() ?: "pinyin"
    }

    fun shuangpinScheme(p: SharedPreferences) = p.getString(SHUANGPIN_SCHEME, SHUANGPIN_SCHEME_DEFAULT) ?: SHUANGPIN_SCHEME_DEFAULT

    /** 键盘键 → 内核方案名。 Keyboard key → engine schema key. */
    fun engineSchema(p: SharedPreferences, keyboard: String): String = when (keyboard) {
        "shuangpin" -> "shuangpin:" + shuangpinScheme(p)
        "english" -> "english"
        else -> keyboard
    }

    fun theme(p: SharedPreferences) = p.getString(THEME, "system") ?: "system"
    fun heightLevel(p: SharedPreferences) = p.getInt(HEIGHT_LEVEL, HEIGHT_LEVEL_DEFAULT)
    fun styleLayout(p: SharedPreferences) = p.getString(STYLE_LAYOUT, null) ?: "fresh"
    fun styleTheme(p: SharedPreferences) = p.getString(STYLE_THEME, null) ?: "auto"
    fun vibration(p: SharedPreferences) = p.getInt(VIBRATION, VIBRATION_DEFAULT)
    private val LEGACY_VOLUMES = intArrayOf(0, 15, 30, 50, 80)

    /** 默认关闭；旧版开过按键音（未选过风格）的用户保持「跟随系统」。 Off by default; legacy users keep the system click. */
    fun soundStyle(p: SharedPreferences): String =
        p.getString(SOUND_STYLE, null) ?: if (p.getInt(SOUND, 0) > 0) SOUND_SYSTEM else SOUND_OFF

    fun soundVolume(p: SharedPreferences): Int {
        if (p.contains(SOUND_VOLUME)) return p.getInt(SOUND_VOLUME, SOUND_VOLUME_DEFAULT).coerceIn(0, 100)
        val legacy = p.getInt(SOUND, 0)
        return if (legacy > 0) LEGACY_VOLUMES[legacy.coerceIn(1, 4)] else SOUND_VOLUME_DEFAULT
    }
    fun keyPreview(p: SharedPreferences) = p.getBoolean(KEY_PREVIEW, true)
    fun clipboardRecord(p: SharedPreferences) = p.getBoolean(CLIPBOARD_RECORD, false)
    fun clipboardAsked(p: SharedPreferences) = p.contains(CLIPBOARD_RECORD)
    fun shuangpinHints(p: SharedPreferences) = p.getBoolean(SHUANGPIN_HINTS, true)
    fun wubiRootHints(p: SharedPreferences) = p.getBoolean(WUBI_ROOT_HINTS, false)
    fun wubiPinyinMix(p: SharedPreferences) = p.getBoolean(WUBI_PINYIN_MIX, true)
    /** 默认开启的常用模糊音（用户可增删）。 Common fuzzy pairs on by default; the user can add or remove. */
    val FUZZY_DEFAULT = setOf("z_zh", "c_ch", "s_sh", "n_l", "an_ang", "en_eng", "in_ing")
    fun fuzzy(p: SharedPreferences): Set<String> = p.getStringSet(FUZZY, FUZZY_DEFAULT) ?: FUZZY_DEFAULT
    fun traditional(p: SharedPreferences) = p.getBoolean(TRADITIONAL, false)
    fun oneHand(p: SharedPreferences) = p.getInt(ONE_HAND, 0)
    fun floating(p: SharedPreferences) = p.getBoolean(FLOATING, false)
    fun splitWide(p: SharedPreferences) = p.getBoolean(SPLIT_WIDE, true)
    fun voiceMode(p: SharedPreferences) = p.getString(VOICE_MODE, "tap") ?: "tap"
    fun prediction(p: SharedPreferences) = p.getBoolean(PREDICTION, true)
    fun autocorrect(p: SharedPreferences) = p.getBoolean(AUTOCORRECT, true)
    fun handPause(p: SharedPreferences) = p.getInt(HAND_PAUSE, HAND_PAUSE_DEFAULT).coerceIn(0, HAND_PAUSE_MS.size - 1)
    fun handPauseMs(p: SharedPreferences) = HAND_PAUSE_MS[handPause(p)]
    fun autoPair(p: SharedPreferences) = p.getBoolean(AUTO_PAIR, true)
    fun cloudWords(p: SharedPreferences) = p.getBoolean(CLOUD_WORDS, false)
    fun linkEnabled(p: SharedPreferences) = p.getBoolean(LINK_ENABLED, false)
    fun linkClipSync(p: SharedPreferences) = p.getBoolean(LINK_CLIP_SYNC, true)
    fun linkName(p: SharedPreferences) = p.getString(LINK_NAME, null)?.takeIf { it.isNotBlank() } ?: com.weavetext.ime.link.LinkManager.defaultName()
}
