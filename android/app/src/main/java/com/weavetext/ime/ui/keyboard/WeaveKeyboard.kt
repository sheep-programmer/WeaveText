package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import com.weavetext.ime.R
import com.weavetext.ime.ime.EnterAction
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.ime.KeyboardUi
import com.weavetext.ime.ime.WeaveImeService
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.style.KeyboardStyle
import com.weavetext.ime.style.StyleRepository
import com.weavetext.ime.style.ToolIds

/** 面板基类：主区域（与键区等高）或接管顶栏的全高面板。 Base class for panels. */
abstract class KbPanel(val kb: WeaveKeyboard) {
    abstract val view: View
    /** 接管顶栏 + 主区域。 Takes over the top bar too. */
    open val full: Boolean = false
    /** 对应的顶栏图标（高亮）。 Highlighted toolbar cell. */
    open val toolIndex: Int = -1
    open fun onShow() {}
    open fun onHide() {}
    open fun applyTheme() {}
    open fun onState(s: ImeState) {}
}

/**
 * 键盘所在窗口的能力（由输入法服务提供；截图测试用假实现）。
 * What the hosting IME window provides (fake in screenshot tests).
 */
interface ImeWindowHost {
    fun hideKeyboard()
    val window: android.view.Window?
}

private class ServiceWindowHost(private val service: WeaveImeService) : ImeWindowHost {
    override fun hideKeyboard() = service.requestHideSelf(0)
    override val window: android.view.Window? get() = service.window?.window
}

/**
 * 键盘根：顶栏 + 主区域（键盘/面板）+ 气泡层，并负责设置同步（02 §1）。
 * Keyboard root: top bar, main area (keys / panels), bubble overlay; also syncs settings.
 */
@SuppressLint("ViewConstructor")
class WeaveKeyboard(val ctx: Context, val controller: InputController, private val host: ImeWindowHost) :
    KeyboardUi, KeyboardHost, TopBarHost, SharedPreferences.OnSharedPreferenceChangeListener {

    constructor(service: WeaveImeService, controller: InputController) : this(service, controller, ServiceWindowHost(service))

    private val service = ctx
    val prefs: SharedPreferences = WeavePrefs.of(ctx)
    /** 当前解析完成的风格（docs/design/05）。 The resolved keyboard style. */
    lateinit var style: KeyboardStyle
        private set
    lateinit var palette: KbPalette
        private set
    lateinit var metrics: KbMetrics
        private set
    val icons = Icons(service)
    override val feedback = Feedback(service)
    override var overlay: PopupOverlay? = null
        private set
    override var previewEnabled = true
        private set

    private val root = RootLayout(service)
    val board = FrameLayout(service)
    val topBar = TopBarView(service, this)
    val main = FrameLayout(service)
    val full = FrameLayout(service)
    val keyboardView = KeyboardView(service, this)
    private val oneHandButton = OneHandButton(service)
    private val popup = PopupOverlay(service)
    private val engineSheet = EngineSheet(service, this)

    override val view: View get() = root

    var state = ImeState()
        private set
    private var layoutSig = ""
    private var numberMode = false
    private val shift = com.weavetext.ime.ime.ShiftState()
    private var lastSpaceTap = 0L
    private var localCands: List<String>? = null
    var navInset = 0
        private set

    private val panels = HashMap<String, KbPanel>()
    var panel: KbPanel? = null
        private set
    private var clipChipTimeout = Runnable { topBar.clipChip = null }

    private val stateListener: (ImeState) -> Unit = { onState(it) }

    init {
        overlay = popup
        board.addView(topBar)
        board.addView(main)
        board.addView(full)
        board.addView(engineSheet)
        engineSheet.visibility = View.GONE
        main.addView(keyboardView, FrameLayout.LayoutParams(-1, -1))
        main.addView(oneHandButton)
        root.addView(board)
        root.addView(popup, FrameLayout.LayoutParams(-1, -1))
        full.visibility = View.GONE
        root.setOnApplyWindowInsetsListener { _, insets ->
            val nav = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            if (nav != navInset) { navInset = nav; applyGeometry() }
            insets
        }
        prefs.registerOnSharedPreferenceChangeListener(this)
        applyAllPrefs()
        controller.addListener(stateListener)
    }

    // ================================================================ theme & geometry

    private fun applyAllPrefs() {
        feedback.vibration = WeavePrefs.vibration(prefs)
        feedback.soundStyle = WeavePrefs.soundStyle(prefs)
        feedback.soundVolume = WeavePrefs.soundVolume(prefs)
        previewEnabled = WeavePrefs.keyPreview(prefs)
        applyTheme()
        applyEngineOptions()
        applySchemaPref()
    }

    fun applyTheme() {
        style = StyleRepository.get(ctx).resolve(ctx, prefs)
        palette = style.palette
        metrics = style.metrics
        icons.clear()
        val backdrop = palette.backdrop
        if (backdrop == null) board.setBackgroundColor(palette.background) else board.background = BackdropDrawable(backdrop)
        popup.applyStyle(style)
        topBar.applyStyle(style, icons)
        keyboardView.applyStyle(style, icons)
        oneHandButton.invalidate()
        engineSheet.invalidate()
        for (p in panels.values) p.applyTheme()
        applyGeometry()
        // 导航栏颜色跟随键盘。 Tint the navigation bar to match.
        host.window?.let { w ->
            w.navigationBarColor = palette.background
            @Suppress("DEPRECATION")
            val flags = w.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (palette.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    /** 给接管整块键盘的面板铺上与键盘相同的背景。 Give a full-height panel the keyboard background. */
    fun paintBackground(v: View) {
        val b = palette.backdrop
        if (b == null) v.setBackgroundColor(palette.background) else v.background = BackdropDrawable(b)
    }

    private fun applyGeometry() {
        val m = metrics
        val kbH = m.kbHeight.toInt() + navInset
        board.layoutParams = FrameLayout.LayoutParams(-1, kbH).apply { topMargin = m.bubbleSpace.toInt() }
        board.setPadding(0, 0, 0, navInset)
        topBar.layoutParams = FrameLayout.LayoutParams(-1, m.topBar.toInt())
        val mainTop = (m.topBar + m.padTop).toInt()
        main.layoutParams = FrameLayout.LayoutParams(-1, m.mainHeight.toInt()).apply { topMargin = mainTop }
        full.layoutParams = FrameLayout.LayoutParams(-1, mainTop + m.mainHeight.toInt())
        engineSheet.layoutParams = FrameLayout.LayoutParams(-1, mainTop + m.mainHeight.toInt())
        applyOneHand()
        root.requestLayout()
    }

    private fun applyOneHand() {
        val mode = WeavePrefs.oneHand(prefs)
        val lp = keyboardView.layoutParams as FrameLayout.LayoutParams
        val w = ctx.resources.displayMetrics.widthPixels
        val landscapeMax = if (metrics.landscape) (720 * metrics.density).toInt().coerceAtMost(w) else w
        if (mode == 0) {
            lp.width = landscapeMax
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            oneHandButton.visibility = View.GONE
        } else {
            lp.width = (w * 0.82f).toInt()
            lp.gravity = if (mode == 1) android.view.Gravity.START else android.view.Gravity.END
            oneHandButton.visibility = View.VISIBLE
            oneHandButton.left = mode == 2
            oneHandButton.layoutParams = FrameLayout.LayoutParams(w - lp.width, -1).apply {
                gravity = if (mode == 1) android.view.Gravity.END else android.view.Gravity.START
            }
        }
        keyboardView.layoutParams = lp
    }

    override fun computeInsets(outInsets: InputMethodService.Insets) {
        if (!board.isLaidOut) return
        val loc = IntArray(2)
        board.getLocationInWindow(loc)
        val top = loc[1]
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(Rect(loc[0], top, loc[0] + board.width, top + board.height))
    }

    // ================================================================ settings sync

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        when (key) {
            WeavePrefs.THEME, WeavePrefs.HEIGHT_LEVEL, WeavePrefs.STYLE_LAYOUT, WeavePrefs.STYLE_THEME,
            WeavePrefs.STYLE_OVERRIDES, WeavePrefs.STYLE_STAMP -> { applyTheme(); layoutSig = ""; refreshLayout(); updateCandidates(null) }
            WeavePrefs.VIBRATION -> feedback.vibration = WeavePrefs.vibration(p)
            WeavePrefs.SOUND, WeavePrefs.SOUND_STYLE, WeavePrefs.SOUND_VOLUME -> {
                feedback.soundStyle = WeavePrefs.soundStyle(p)
                feedback.soundVolume = WeavePrefs.soundVolume(p)
            }
            WeavePrefs.KEY_PREVIEW -> previewEnabled = WeavePrefs.keyPreview(p)
            WeavePrefs.SHUANGPIN_HINTS, WeavePrefs.WUBI_ROOT_HINTS -> { layoutSig = ""; refreshLayout() }
            WeavePrefs.FUZZY, WeavePrefs.WUBI_PINYIN_MIX, WeavePrefs.TRADITIONAL -> applyEngineOptions()
            WeavePrefs.KEYBOARDS, WeavePrefs.SHUANGPIN_SCHEME, WeavePrefs.ACTIVE_KEYBOARD -> { applySchemaPref(); layoutSig = ""; refreshLayout() }
            WeavePrefs.ONE_HAND -> applyOneHand()
            WeavePrefs.CLIPBOARD_CLEARED -> clipboard.clearHistory()
        }
        for (pn in panels.values) if (pn is PrefAware) pn.onPref(key)
    }

    private fun applyEngineOptions() {
        val fuzzy = WeavePrefs.fuzzy(prefs)
        for ((k, _) in WeavePrefs.FUZZY_PAIRS) controller.setOption("fuzzy.$k", k in fuzzy)
        controller.setOption("wubi.pinyin_lookup", WeavePrefs.wubiPinyinMix(prefs))
        // 繁体输出：内核暂未提供选项，调用无副作用。 Traditional output: no engine option yet (no-op).
        controller.setOption("output.traditional", WeavePrefs.traditional(prefs))
    }

    private fun applySchemaPref() {
        val kb = WeavePrefs.activeKeyboard(prefs)
        val schema = WeavePrefs.engineSchema(prefs, kb)
        if (schema != state.schema || !state.engineReady) controller.setPreferredSchema(schema)
    }

    /** 选择键盘（浮层 / 中英长按）。 Choose a keyboard. */
    fun chooseKeyboard(kb: String) {
        if (kb == "english") {
            if (state.chinese) controller.toggleChinese()
            return
        }
        prefs.edit().putString(WeavePrefs.ACTIVE_KEYBOARD, kb).apply()
        controller.setSchema(WeavePrefs.engineSchema(prefs, kb))
    }

    fun currentKeyboardKey(): String = when {
        !state.chinese -> "english"
        state.schema.startsWith("shuangpin") -> "shuangpin"
        else -> state.schema
    }

    // ================================================================ state

    private var wasReady = false

    private fun onState(s: ImeState) {
        val prev = state
        state = s
        if (s.engineReady && !wasReady) {
            wasReady = true
            applyEngineOptions()
            applySchemaPref()
        }
        if (s.privateField && topBar.clipChip != null) { topBar.clipChip = null; pendingClip = null }
        if (s.composing || s.candidates.isNotEmpty()) {
            localCands = null
            topBar.clipChip = null
            topBar.clearAction()
        }
        refreshLayout()
        updateCandidates(prev)
        panel?.onState(s)
    }

    private fun updateCandidates(prev: ImeState?) {
        val lc = localCands
        if (lc != null) {
            topBar.setCandidates("", lc, emptyList(), lc.size, english = true, keepScroll = false)
            return
        }
        val s = state
        topBar.setCandidates(
            s.preedit, s.candidates.map { it.text }, s.candidates.map { it.comment }, s.totalCandidates,
            english = !s.chinese, keepScroll = prev != null && prev.preedit == s.preedit,
        )
    }

    /** 根据状态选择布局并更新标签。 Pick the layout and refresh labels. */
    fun refreshLayout() {
        val s = state
        val field = controller.numericFieldKind()
        val kind = when {
            numberMode || field != 0 -> "num$field"
            !s.chinese -> "en"
            s.schema == "t9" -> "t9"
            else -> "cn:" + s.schema
        }
        val hintsOn = when {
            s.schema.startsWith("shuangpin") -> WeavePrefs.shuangpinHints(prefs)
            s.schema == "wubi86" -> WeavePrefs.wubiRootHints(prefs)
            else -> false
        }
        val sig = "$kind:$hintsOn"
        if (sig != layoutSig) {
            layoutSig = sig
            val l = style.layout
            when {
                kind.startsWith("num") -> {
                    keyboardView.setNumpad(Layouts.numpad(field, l.labels))
                    keyboardView.side?.items = Layouts.NUM_SYMBOLS.map { it.toString() }
                }
                kind == "t9" -> keyboardView.setT9(Layouts.t9(l.t9, l.labels))
                kind == "en" -> keyboardView.setQwerty(Layouts.qwerty(english = true, l.qwerty, l.labels))
                else -> {
                    val keys = Layouts.qwerty(english = false, l.qwerty, l.labels)
                    decorateChinese(keys, s.schema, hintsOn)
                    keyboardView.setQwerty(keys)
                }
            }
        }
        updateLabels()
    }

    private fun decorateChinese(keys: List<Key>, schema: String, hintsOn: Boolean) {
        keyboardView.cornerMode = hintsOn
        if (schema.startsWith("shuangpin")) {
            val scheme = schema.substringAfter(':')
            val table = ShuangpinHints.of(scheme)
            for (k in keys) if (k.code in 'a'.code..'z'.code) {
                val h = table[k.code.toChar()] ?: continue
                k.corner = if (h.length > 5) h.substringBefore('/') else h
            }
            if (ShuangpinHints.usesSemicolon(scheme)) {
                keys.firstOrNull { it.code == 'l'.code }?.let { it.hint = "；"; it.up = ";"; it.longPress = listOf(";", "？", "l") }
                keys.firstOrNull { it.code == 'l'.code }?.corner = "ai"
            }
        } else if (schema == "wubi86") {
            for (k in keys) if (k.code in 'a'.code..'z'.code) {
                val c = k.code.toChar()
                k.corner = WubiRoots.NAMES[c]
                k.cornerAccent = c == 'z'
                if (hintsOn) k.longInfo = WubiRoots.RHYMES[c]
            }
        }
    }

    private fun updateLabels() {
        val s = state
        val kv = keyboardView
        kv.chinese = s.chinese
        if (!layoutSig.startsWith("cn")) kv.cornerMode = false
        // 回车 / Enter
        val composing = s.composing
        kv.enterDescription = if (composing) "回车，输入字母" else when (s.enterAction) {
            EnterAction.SEND -> "发送"
            EnterAction.SEARCH -> "搜索"
            EnterAction.GO -> "前往"
            EnterAction.NEXT -> "下一项"
            EnterAction.DONE -> "完成"
            EnterAction.PREVIOUS -> "上一项"
            EnterAction.NEWLINE -> "换行"
        }
        val labels = style.layout.labels
        if (composing) {
            kv.enterLabel = null; kv.enterIcon = R.drawable.ic_enter; kv.enterAccent = false
            if (labels.enter == "text") kv.enterLabel = "确认"
        } else {
            when (s.enterAction) {
                EnterAction.SEND -> { kv.enterLabel = "发送"; kv.enterAccent = true }
                EnterAction.SEARCH -> { kv.enterLabel = null; kv.enterIcon = R.drawable.ic_search; kv.enterAccent = true }
                EnterAction.GO -> { kv.enterLabel = "前往"; kv.enterAccent = true }
                EnterAction.NEXT -> { kv.enterLabel = "下一项"; kv.enterAccent = false }
                EnterAction.DONE -> { kv.enterLabel = "完成"; kv.enterAccent = true }
                EnterAction.PREVIOUS -> { kv.enterLabel = "上一项"; kv.enterAccent = false }
                EnterAction.NEWLINE -> { kv.enterLabel = null; kv.enterIcon = R.drawable.ic_enter; kv.enterAccent = false }
            }
            if (labels.enter == "text" && kv.enterLabel == null) kv.enterLabel = if (s.enterAction == EnterAction.SEARCH) "搜索" else "换行"
        }
        when (labels.enterAccent) {
            "always" -> kv.enterAccent = true
            "never" -> kv.enterAccent = false
        }
        // Shift / 分词
        kv.keyOf(KeyCode.SHIFT)?.let { k ->
            val split = s.chinese && s.schema == "pinyin" && (composing || style.layout.labels.shift == "split")
            if (split) {
                k.icon = 0; k.label = "分词"; k.medium = true; k.active = false
            } else {
                k.label = ""
                k.icon = when (shift.value) {
                    com.weavetext.ime.ime.ShiftState.ONCE -> R.drawable.ic_shift_filled
                    com.weavetext.ime.ime.ShiftState.LOCK -> R.drawable.ic_shift_lock
                    else -> R.drawable.ic_shift
                }
                k.active = shift.value == com.weavetext.ime.ime.ShiftState.LOCK
            }
        }
        if (!s.chinese) {
            val upper = shift.upper
            for (k in kv.keys) if (k.code in 'a'.code..'z'.code) {
                k.label = if (upper) k.code.toChar().uppercase() else k.code.toChar().toString()
                val size = style.layout.qwerty.letterSize
                k.labelSize = if (upper) metrics.letter(size) else metrics.letter(size + 1f)
            }
        }
        // 九键 / T9
        if (layoutSig.startsWith("t9")) {
            kv.keyOf(KeyCode.T9_ONE)?.let { it.label = if (composing) "分词" else "，。?!"; it.medium = composing }
            kv.keyOf(KeyCode.T9_RESET)?.let { it.label = if (composing) "重输" else "@" }
            kv.side?.let { side ->
                val idle = if (style.layout.t9.side == "symbols") Layouts.T9_SYMBOLS else Layouts.T9_PUNCT
                val items = if (composing && s.pinyinOptions.isNotEmpty()) s.pinyinOptions else idle.map { it.toString() }
                if (items != side.items) { side.items = items; side.scroll = 0f }
            }
        }
        // 中/英 长按：已启用的方案 / enabled schemes on long-press
        kv.keyOf(KeyCode.LANG)?.let { k ->
            val names = WeavePrefs.keyboards(prefs).map { WeavePrefs.KEYBOARD_NAMES[it] ?: it }
            k.longPress = names
            k.label = WeavePrefs.KEYBOARD_NAMES[currentKeyboardKey()] ?: ""
        }
        kv.labelsChanged()
    }

    // ================================================================ KeyboardHost

    override fun onKey(key: Key) {
        val s = state
        topBar.clearAction()
        when (key.code) {
            KeyCode.SHIFT -> onShift()
            KeyCode.DELETE -> controller.onBackspace()
            KeyCode.SYMBOL -> showPanel("symbol")
            KeyCode.EMOJI -> { showPanel("symbol"); (panels["symbol"] as? SymbolPanel)?.selectEmoji() }
            KeyCode.NUMBER -> { numberMode = true; refreshLayout() }
            KeyCode.BACK -> { numberMode = false; refreshLayout() }
            KeyCode.SPACE -> {
                val now = SystemClock.uptimeMillis()
                val dbl = !s.chinese && now - lastSpaceTap < 300
                lastSpaceTap = now
                if (!(dbl && controller.doubleSpacePeriod())) controller.onSpace()
            }
            KeyCode.LANG -> {
                if (numberMode) numberMode = false
                controller.toggleChinese()
            }
            KeyCode.ENTER -> controller.onEnter()
            KeyCode.T9_RESET -> if (s.composing) controller.reset() else controller.onText("@")
            KeyCode.T9_ONE -> if (s.composing) controller.onChar('\''.code) else showLocalCandidates(T9_ONE_PUNCT)
            else -> onCharKey(key)
        }
        afterKey()
    }

    private fun onCharKey(key: Key) {
        val s = state
        val code = key.code
        if (layoutSig.startsWith("num")) {
            controller.onText(key.label.ifEmpty { String(Character.toChars(code)) }.let { if (code == ' '.code) " " else it })
            return
        }
        if (code in 'a'.code..'z'.code) {
            if (!s.chinese) {
                controller.onChar(if (shift.upper) code - 32 else code)
                if (shift.consume()) updateLabels()
                return
            }
            if (shift.upper && !s.composing) {
                controller.onText(code.toChar().uppercase())
                if (shift.consume()) updateLabels()
                return
            }
        }
        controller.onChar(code)
    }

    private fun afterKey() {
        localCandsClearIfNeeded()
        val s = state
        if (!s.chinese && !layoutSig.startsWith("num") && shift.autoCap(controller.capsModeActive())) updateLabels()
    }

    private fun localCandsClearIfNeeded() {
        if (localCands != null && state.composing) { localCands = null; updateCandidates(null) }
    }

    private fun onShift() {
        val s = state
        if (s.chinese && s.composing) {
            if (s.schema == "pinyin") controller.onChar('\''.code)
            return
        }
        if (shift.tap(SystemClock.uptimeMillis(), allowLock = !s.chinese)) updateLabels()
    }

    override fun onKeyText(key: Key, text: String) {
        topBar.clearAction()
        if (key.code == KeyCode.LANG) {
            val kb = WeavePrefs.KEYBOARD_NAMES.entries.firstOrNull { it.value == text }?.key ?: return
            chooseKeyboard(kb)
            return
        }
        if (text.length == 1 && state.chinese && state.composing && text[0] in 'a'..'z' && key.code in '2'.code..'9'.code) {
            // 九键长按单个字母：直接上屏字母。 T9 single letter.
            controller.onText(text)
            return
        }
        if (text == ";" && state.schema.startsWith("shuangpin") && state.composing) {
            controller.onChar(';'.code)
            return
        }
        controller.onText(text)
        if (!state.chinese && shift.consume()) updateLabels()
        afterKey()
    }

    override fun onCursorSteps(steps: Int) {
        if (state.composing) return
        controller.moveCursor(steps)
    }

    override fun onDeleteRepeat(count: Int) {
        if (count > 20) controller.deleteWordBefore() else controller.onBackspace()
    }

    override fun onDeleteClear() {
        val removed = controller.clearBeforeCursor() ?: return
        topBar.showAction("已清空", "撤销", 3000) { controller.onText(removed) }
    }

    override fun onLongPressFunc(key: Key): Int = when (key.code) {
        KeyCode.SPACE -> if (startHoldVoice()) KeyboardView.LONG_VOICE else KeyboardView.LONG_CONSUMED
        KeyCode.SYMBOL -> { showPanel("symbol"); (panels["symbol"] as? SymbolPanel)?.selectEmoji(); KeyboardView.LONG_CONSUMED }
        else -> 0
    }

    override fun onVoiceHoldMove(dy: Float) { voiceStrip?.onMove(dy) }
    override fun onVoiceHoldEnd(cancelled: Boolean) { voiceStrip?.end(cancelled) }

    override fun onSideItem(index: Int) {
        val s = state
        val side = keyboardView.side ?: return
        val item = side.items.getOrNull(index) ?: return
        when {
            layoutSig.startsWith("num") -> controller.onText(item)
            s.composing && s.pinyinOptions.isNotEmpty() -> controller.onPinyinOption(index)
            else -> controller.onText(item)
        }
    }

    private var voiceStrip: VoiceStrip? = null

    private fun startHoldVoice(): Boolean {
        val strip = voiceStrip ?: VoiceStrip(this).also { voiceStrip = it }
        return strip.start()
    }

    // ================================================================ TopBarHost

    override fun onToolbar(index: Int) {
        when (index) {
            0 -> togglePanel("toolbox")
            1 -> {
                if (WeavePrefs.keyboards(prefs).size <= 1) controller.toggleChinese() else togglePanel("picker")
            }
            2 -> if (panel is VoicePanel) closePanel() else { showPanel("voice"); (panel as? VoicePanel)?.startFromToolbar() }
            3 -> togglePanel("cursor")
            4 -> togglePanel("clipboard")
            5 -> host.hideKeyboard()
            ToolIds.EMOJI -> if (panel is SymbolPanel) closePanel() else { showPanel("symbol"); (panels["symbol"] as? SymbolPanel)?.selectEmoji() }
            ToolIds.SETTINGS -> openSettings(null)
        }
    }

    override fun onToolbarLong(index: Int) {
        when (index) {
            0 -> openSettings(null)
            // 长按 🎙：不打开面板，直接按住说话，松手结束。 Long-press mic: hold-to-talk, release to end.
            2 -> startHoldVoice()
        }
    }

    override fun onToolbarLongMove(index: Int, dy: Float) { if (index == 2) voiceStrip?.onMove(dy) }
    override fun onToolbarLongEnd(index: Int, cancelled: Boolean) { if (index == 2) voiceStrip?.end(cancelled) }

    override fun onCandidate(index: Int) {
        val lc = localCands
        if (lc != null) {
            localCands = null
            controller.onText(lc[index])
            updateCandidates(null)
            return
        }
        controller.onCandidate(index)
    }

    override fun onCandidateLong(index: Int) {
        val c = state.candidates.getOrNull(index) ?: return
        if (!c.isUser) return
        feedback.haptic(topBar)
        topBar.showAction("删除用户词「${c.text}」", "删除", 4000) {
            controller.onForgetCandidate(index)
        }
    }

    override fun onExpand() { togglePanel("grid") }

    override fun onNeedMore() {
        if (localCands != null) return
        val loaded = topBar.loadedCount
        if (loaded >= state.totalCandidates) return
        val more = controller.loadCandidates(loaded, 30)
        if (more.isNotEmpty()) topBar.appendCandidates(more.map { it.text }, more.map { it.comment })
    }

    override fun onClipChip() {
        val t = pendingClip ?: return
        topBar.clipChip = null
        controller.onText(t)
    }

    override fun onHideByDrag() { host.hideKeyboard() }

    override fun onFloatingPreedit(text: String?) { popup.showPreedit(text) }

    private fun showLocalCandidates(list: List<String>) {
        localCands = list
        updateCandidates(null)
    }

    // ================================================================ panels

    private fun createPanel(name: String): KbPanel? = when (name) {
        "grid" -> CandidateGridPanel(this)
        "picker" -> PickerPanel(this)
        "symbol" -> SymbolPanel(this)
        "cursor" -> CursorPanel(this)
        "clipboard" -> ClipboardPanel(this, ClipboardPanel.Mode.CLIPBOARD)
        "phrases" -> ClipboardPanel(this, ClipboardPanel.Mode.PHRASES)
        "toolbox" -> ToolboxPanel(this)
        "height" -> HeightPanel(this)
        "voice" -> VoicePanel(this)
        else -> null
    }

    fun panelNamed(name: String): KbPanel? = panels[name] ?: createPanel(name)?.also { p ->
        panels[name] = p
        p.applyTheme()
        (if (p.full) full else main).addView(p.view, FrameLayout.LayoutParams(-1, -1))
        p.view.visibility = View.GONE
    }

    fun togglePanel(name: String) {
        if (panel === panels[name] && panel != null) closePanel() else showPanel(name)
    }

    fun showPanel(name: String) {
        val p = panelNamed(name) ?: return
        if (panel === p) return
        keyboardView.cancelTouch()
        popup.hideAll()
        val old = panel
        old?.let { it.onHide(); fadeOut(it.view) }
        panel = p
        if (p.full) full.visibility = View.VISIBLE
        else if (old?.full == true) full.visibility = View.GONE
        p.onShow()
        fadeIn(p.view)
        keyboardView.visibility = if (p.full) View.VISIBLE else View.INVISIBLE
        oneHandButton.visibility = if (p.full || WeavePrefs.oneHand(prefs) == 0) View.GONE else oneHandButton.visibility
        if (!p.full) oneHandButton.visibility = View.GONE
        topBar.activeTool = p.toolIndex
        topBar.expanded = name == "grid"
    }

    fun closePanel() {
        val p = panel ?: return
        p.onHide()
        fadeOut(p.view)
        panel = null
        if (p.full) full.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        applyOneHand()
        topBar.activeTool = -1
        topBar.expanded = false
        fadeIn(keyboardView, keep = true)
    }

    private fun animScale() = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    private fun fadeOut(v: View) {
        v.animate().cancel()
        if (animScale() == 0f) { v.visibility = View.GONE; return }
        v.animate().alpha(0f).setDuration(70).withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
    }

    private fun fadeIn(v: View, keep: Boolean = false) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (animScale() == 0f) { v.alpha = 1f; v.translationY = 0f; return }
        v.alpha = 0f
        v.translationY = metrics.dp(8f)
        v.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(110)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    /** 语音引擎切换弹层（02 §12.4）。 Engine switcher sheet. */
    fun showEngineSheet() {
        keyboardView.cancelTouch()
        popup.hideAll()
        engineSheet.show()
    }

    fun stopVoice() {
        (panels["voice"] as? VoicePanel)?.stopSession()
        voiceStrip?.end(true)
    }

    fun onEngineChanged() {
        (panels["voice"] as? VoicePanel)?.let { it.view.refreshEngine(); it.view.invalidate() }
    }

    override fun handleBack(): Boolean {
        if (engineSheet.shown) { engineSheet.hide(); return true }
        if (panel != null) { closePanel(); return true }
        if (numberMode) { numberMode = false; refreshLayout(); return true }
        return false
    }

    fun openSettings(route: String?) {
        val i = Intent(ctx, com.weavetext.ime.settings.SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (route != null) i.data = android.net.Uri.parse("weavetext://settings/$route")
        ctx.startActivity(i)
        host.hideKeyboard()
    }

    // ================================================================ clipboard chip

    private var pendingClip: String? = null

    /** 剪贴板有新内容时（由剪贴板仓库回调）。 New clip copied. */
    fun onNewClip(text: String) {
        if (state.composing || state.privateField) return
        pendingClip = text
        topBar.clipChip = text.replace('\n', ' ')
        topBar.removeCallbacks(clipChipTimeout)
        topBar.postDelayed(clipChipTimeout, 10_000)
    }

    // ================================================================ lifecycle

    override fun onShown() {
        numberMode = false
        shift.reset()
        localCands = null
        if (panel != null) closePanel()
        layoutSig = ""
        refreshLayout()
        afterKey()
        updateCandidates(null)
        clipboard.onShown()
    }

    override fun onHidden() {
        keyboardView.cancelTouch()
        popup.hideAll()
        voiceStrip?.end(true)
        panel?.let { if (it is VoicePanel) it.stopSession() }
        engineSheet.visibility = View.GONE
        topBar.clipChip = null
        popup.showPreedit(null)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        (panel as? CursorPanel)?.onSelection(selStart != selEnd)
    }

    val clipboard: ClipboardRepo by lazy { ClipboardRepo(ctx, this) }

    override fun dispose() {
        controller.removeListener(stateListener)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        voiceStrip?.end(true)
        for (p in panels.values) p.onHide()
        clipboard.release()
        feedback.release()
    }

    /** 单手模式空出一侧的切换按钮。 Side button in one-hand mode. */
    private inner class OneHandButton(c: Context) : View(c) {
        var left = false
        init {
            setOnClickListener {
                val mode = WeavePrefs.oneHand(prefs)
                prefs.edit().putInt(WeavePrefs.ONE_HAND, if (mode == 1) 2 else 1).apply()
            }
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            icons.draw(canvas, if (left) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right, palette.icon, width / 2f, height / 2f, metrics.dp(32f))
        }
    }

    /** 根布局：键盘区在下，上方留出透明气泡区。 Root: keyboard at bottom with a transparent bubble area on top. */
    private inner class RootLayout(c: Context) : FrameLayout(c) {
        init {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = (metrics.bubbleSpace + metrics.kbHeight).toInt() + navInset
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    companion object {
        val T9_ONE_PUNCT = listOf("，", "。", "？", "！", "、", "：", "；", "…", "～", "“", "”", "@", ".", ",", "?", "!")
    }
}

/** 需要感知设置变化的面板。 Panels reacting to preference changes. */
interface PrefAware { fun onPref(key: String?) }
