package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Choreographer
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
    /** 切换到另一个输入法（如其他语音输入法）；做不到返回 false。 Switch to another IME; false if impossible. */
    fun switchToIme(id: String, subtype: android.view.inputmethod.InputMethodSubtype?): Boolean = false
}

private class ServiceWindowHost(private val service: WeaveImeService) : ImeWindowHost {
    override fun hideKeyboard() = service.requestHideSelf(0)
    override val window: android.view.Window? get() = service.window?.window

    override fun switchToIme(id: String, subtype: android.view.inputmethod.InputMethodSubtype?): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            service.switchInputMethod(id, subtype)
        } else {
            val token = service.window?.window?.attributes?.token ?: return false
            val imm = service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            @Suppress("DEPRECATION")
            if (subtype != null) imm.setInputMethodAndSubtype(token, id, subtype) else imm.setInputMethod(token, id)
        }
        true
    }.getOrDefault(false)
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
    /** 键盘卡片：常规模式下铺满底部；悬浮模式下是可拖动的小卡片（06 §5）。 Docked full width, or the floating card. */
    private val card = FrameLayout(service)
    private val handle = FloatHandle(service)
    private val grip = ResizeGrip(service)
    /** 悬浮卡片的缩放（按当前方向读取）。 Floating card scale for the current orientation. */
    private var floatScale = 1f
    /** 正在拖动缩放：布局时保持右下角不动，不按保存的比例重新放置。 Resizing: layout keeps the bottom-right corner. */
    private var resizing = false
    private var resizeRight = 0
    private var resizeBottom = 0
    val board = FrameLayout(service)
    val topBar = TopBarView(service, this)
    val main = FrameLayout(service)
    val full = FrameLayout(service)
    val keyboardView = KeyboardView(service, this)
    private val oneHandButton = OneHandButton(service)
    private val inkLayer = InkLayer(service, keyboardView)
    private val popup = PopupOverlay(service)
    private val engineSheet = EngineSheet(service, this)

    override val view: View get() = root

    /** 实体键盘模式下顶栏移到这里，单独作为候选栏显示。 With a physical keyboard the top bar moves here. */
    private val candidatesHost = FrameLayout(service)
    override val candidatesView: View get() = candidatesHost
    /** 正在用实体键盘（只显示候选栏）。 A physical keyboard is in use (candidate bar only). */
    var hardwareMode = false
        private set

    override fun setHardwareMode(on: Boolean) {
        if (on == hardwareMode) return
        hardwareMode = on
        keyboardView.cancelTouch()
        popup.hideAll()
        (topBar.parent as? ViewGroup)?.removeView(topBar)
        if (on) {
            if (panel != null) closePanel()
            candidatesHost.addView(topBar, FrameLayout.LayoutParams(-1, metrics.topBar.toInt()))
        } else {
            board.addView(topBar, 0)
        }
        applyGeometry()
    }

    var state = ImeState()
        private set
    private var layoutSig = ""
    private var numberMode = false
    private val shift = com.weavetext.ime.ime.ShiftState()
    private var localCands: List<String>? = null
    var navInset = 0
        private set
    /** 横屏时侧边导航栏占的宽度（左、右）。 Side navigation bar widths in landscape (left, right). */
    private var navLeft = 0
    private var navRight = 0
    private var navigationBars = NavigationClearance.Edges()
    private val navigationLayout = Runnable { updateNavigationClearance() }

    /**
     * 按系统导航栏（底部或横屏时的侧边）留出空白，键不被三键导航或手势条盖住。
     * Keep clear of the system navigation bar (bottom, or the side in landscape) so no key sits under it.
     */
    private fun applyInsets(insets: WindowInsets) {
        fun edges(source: WindowInsets): NavigationClearance.Edges = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val type = WindowInsets.Type.navigationBars()
            val current = source.getInsets(type)
            // Consuming insets may zero their size while retaining the visibility flag.
            val n = if (current == android.graphics.Insets.NONE && source.isVisible(type))
                source.getInsetsIgnoringVisibility(type) else current
            NavigationClearance.Edges(n.bottom, n.left, n.right)
        } else {
            // Stable system bars exclude the IME itself on older Android versions.
            fun barSize(name: String): Int {
                @SuppressLint("DiscouragedApi")
                val id = ctx.resources.getIdentifier(name, "dimen", "android")
                return if (id != 0) ctx.resources.getDimensionPixelSize(id) else 0
            }
            @Suppress("DEPRECATION")
            NavigationClearance.Edges(
                NavigationClearance.legacyInset(source.systemWindowInsetBottom, source.stableInsetBottom, barSize("navigation_bar_height")),
                NavigationClearance.legacyInset(source.systemWindowInsetLeft, source.stableInsetLeft, barSize("navigation_bar_width")),
                NavigationClearance.legacyInset(source.systemWindowInsetRight, source.stableInsetRight, barSize("navigation_bar_width")),
            )
        }
        navigationBars = edges(insets)
        val visible = android.os.Build.VERSION.SDK_INT < 30 || insets.isVisible(WindowInsets.Type.navigationBars())
        if (visible) {
            // Read the window-level source when a framework parent consumed the child's insets.
            val raw = host.window?.decorView?.rootWindowInsets ?: root.rootWindowInsets
            raw?.let { source ->
                val n = edges(source)
                navigationBars = NavigationClearance.Edges(
                    maxOf(navigationBars.bottom, n.bottom), maxOf(navigationBars.left, n.left), maxOf(navigationBars.right, n.right),
                )
            }
        }
        updateNavigationClearance()
    }

    private fun updateNavigationClearance() {
        var clearance = navigationBars
        if (root.isLaidOut && host.window != null) {
            val bounds = runCatching {
                val wm = ctx.getSystemService(android.view.WindowManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else {
                    val size = android.graphics.Point()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealSize(size)
                    Rect(0, 0, size.x, size.y)
                }
            }.getOrNull()
            if (bounds != null && !bounds.isEmpty) {
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                clearance = NavigationClearance.overlap(bounds, Rect(
                    location[0], location[1], location[0] + root.width, location[1] + root.height,
                ), navigationBars)
            }
        }
        val (b, l, r) = clearance
        if (b != navInset || l != navLeft || r != navRight) {
            navInset = b; navLeft = l; navRight = r
            applyGeometry()
        }
    }
    /** 当前是否悬浮。 Whether the keyboard floats. */
    var floating = false
        private set

    private val panels = HashMap<String, KbPanel>()
    var panel: KbPanel? = null
        private set
    private var clipChipTimeout = Runnable { topBar.clipChip = null }

    private val stateListener: (ImeState) -> Unit = { onState(it) }
    private val cloudWords by lazy {com.weavetext.ime.core.CloudWords.get(ctx)}
    private val cloudListener:()->Unit={topBar.cloudLoading=cloudWords.status().let {it.enabled && it.updating}}
    /** 上一次渲染到界面的状态。 State last rendered. */
    private var rendered: ImeState? = null
    private var renderPending = false
    private val frameRender = Choreographer.FrameCallback { flushRender() }
    /** 已完成的渲染次数（测试用）。 Number of renders done (for tests). */
    @get:androidx.annotation.VisibleForTesting
    var renderCount = 0
        private set

    init {
        overlay = popup
        popup.surface = board
        board.addView(topBar)
        board.addView(main)
        board.addView(full)
        board.addView(engineSheet)
        engineSheet.visibility = View.GONE
        main.addView(keyboardView, FrameLayout.LayoutParams(-1, -1))
        // 墨迹单独一层，写字时不重画整块键盘。 Ink on its own layer, so writing doesn't redraw the whole keyboard.
        main.addView(inkLayer, FrameLayout.LayoutParams(-1, -1))
        keyboardView.inkLayer = inkLayer
        main.addView(oneHandButton)
        card.addView(handle)
        card.addView(board)
        card.addView(grip)
        root.addView(card)
        // 预览气泡在覆盖层之下（长按浮层、提示盖在它上面）。 The preview bubble sits below the overlay.
        root.addView(popup.bubbleView, FrameLayout.LayoutParams(0, 0, android.view.Gravity.TOP or android.view.Gravity.START))
        root.addView(popup, FrameLayout.LayoutParams(-1, -1))
        full.visibility = View.GONE
        // 整块面板吃掉悬停：读屏触摸浏览不会穿过面板的空白处落到下面看不见的键上（松手即输入）。
        // Full panels swallow hover, so touch exploration never falls through their blank parts onto the hidden keys
        // below (lift-to-activate would type them).
        full.setOnHoverListener { _, _ -> true }
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        // 视图重建（旋转屏幕、重启输入法）后系统不一定再发一次边衬：挂到窗口上时主动要一次。
        // After the view is recreated (rotation, IME restart) the system may not resend insets: ask on attach.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets(); v.rootWindowInsets?.let(::applyInsets) }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        prefs.registerOnSharedPreferenceChangeListener(this)
        applyAllPrefs()
        controller.addListener(stateListener)
        cloudWords.addListener(cloudListener);cloudListener()
        // 敲等号后的计算结果：点一下接在等号后面。 A result after "=": tap to append it.
        controller.onCalc = { showLocalCandidates(it) }
    }

    // ================================================================ theme & geometry

    private fun applyAllPrefs() {
        floating = WeavePrefs.floating(prefs)
        feedback.vibration = WeavePrefs.vibration(prefs)
        feedback.soundStyle = WeavePrefs.soundStyle(prefs)
        feedback.soundVolume = WeavePrefs.soundVolume(prefs)
        previewEnabled = WeavePrefs.keyPreview(prefs)
        keyboardView.splitWide = WeavePrefs.splitWide(prefs)
        keyboardView.handPauseMs = if(prefs.getBoolean(WeavePrefs.HAND_LINE,false)) Long.MAX_VALUE else WeavePrefs.handPauseMs(prefs)
        applyTheme()
        applyEngineOptions()
        applySchemaPref()
    }

    fun applyTheme() {
        // 悬浮卡片更窄，键高用最紧凑档，比例更协调。 The narrower floating card uses the compact level.
        style = if (floating) floatingStyle() else StyleRepository.get(ctx).resolve(ctx, prefs)
        palette = style.palette
        metrics = style.metrics
        icons.clear()
        val backdrop = palette.backdrop
        if (backdrop == null) board.setBackgroundColor(palette.background) else board.background = BackdropDrawable(backdrop)
        popup.applyStyle(style)
        topBar.applyStyle(style, icons)
        keyboardView.applyStyle(style, icons)
        oneHandButton.invalidate()
        handle.setBackgroundColor(palette.background)
        handle.invalidate()
        engineSheet.invalidate()
        for (p in panels.values) p.applyTheme()
        applyGeometry()
        // 导航栏颜色跟随键盘。 Tint the navigation bar to match.
        host.window?.let { w ->
            w.navigationBarColor = if (floating) Color.TRANSPARENT else palette.background
            @Suppress("DEPRECATION")
            val flags = w.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (palette.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun floatingStyle(): KeyboardStyle {
        val landscape = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        floatScale = FloatingGeometry.decodeScale(prefs.getString(if (landscape) WeavePrefs.FLOAT_SIZE_LAND else WeavePrefs.FLOAT_SIZE_PORT, null))
        val base = StyleRepository.get(ctx).resolve(ctx, prefs, FLOAT_LEVEL)
        if (floatScale == 1f) return base
        val m = KbMetrics(ctx, FLOAT_LEVEL, KeyboardStyle.geometry(base.layout, base.overrides), floatScale)
        return KeyboardStyle(base.layout, base.theme, base.dark, base.overrides, base.palette, m)
    }

    /** 给接管整块键盘的面板铺上与键盘相同的背景。 Give a full-height panel the keyboard background. */
    fun paintBackground(v: View) {
        val b = palette.backdrop
        if (b == null) v.setBackgroundColor(palette.background) else v.background = BackdropDrawable(b)
    }

    private fun applyGeometry() {
        val m = metrics
        if (floating) {
            val kbH = m.kbHeight.toInt()
            val handleH = m.dp(HANDLE_DP).toInt()
            val available = ((root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) - navLeft - navRight).coerceAtLeast(1)
            val w = (FloatingGeometry.cardWidth(available, m.landscape, m.density) * floatScale).toInt()
            card.layoutParams = FrameLayout.LayoutParams(w, handleH + kbH)
            handle.layoutParams = FrameLayout.LayoutParams(-1, handleH)
            handle.visibility = View.VISIBLE
            // 缩放手柄在拖动条左端，不压住任何按键。 The grip sits in the drag bar, clear of every key.
            val g = FloatingGeometry.gripBox(handleH, m.density)
            grip.layoutParams = FrameLayout.LayoutParams(g.width, g.height, android.view.Gravity.TOP or android.view.Gravity.START)
            grip.visibility = View.VISIBLE
            grip.bringToFront()
            board.layoutParams = FrameLayout.LayoutParams(-1, kbH).apply { topMargin = handleH }
            board.setPadding(0, 0, 0, 0)
            card.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, m.dp(16f))
            }
            card.clipToOutline = true
            card.elevation = m.dp(8f)
        } else {
            val kbH = m.kbHeight.toInt() + navInset
            card.layoutParams = FrameLayout.LayoutParams(-1, kbH).apply { topMargin = m.bubbleSpace.toInt() }
            card.translationX = 0f
            card.translationY = 0f
            card.clipToOutline = false
            card.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            card.elevation = 0f
            handle.visibility = View.GONE
            grip.visibility = View.GONE
            board.layoutParams = FrameLayout.LayoutParams(-1, kbH)
            board.setPadding(navLeft, 0, navRight, navInset)
        }
        topBar.layoutParams = FrameLayout.LayoutParams(-1, m.topBar.toInt())
        paintBackground(candidatesHost)
        val mainTop = (m.topBar + m.padTop).toInt()
        main.layoutParams = FrameLayout.LayoutParams(-1, m.mainHeight.toInt()).apply { topMargin = mainTop }
        full.layoutParams = FrameLayout.LayoutParams(-1, mainTop + m.mainHeight.toInt())
        engineSheet.layoutParams = FrameLayout.LayoutParams(-1, mainTop + m.mainHeight.toInt())
        applyOneHand()
        root.requestLayout()
    }

    private fun applyOneHand() {
        // 悬浮卡片本身已经够小，不叠加单手模式。 One-hand mode doesn't apply to the floating card.
        val mode = if (floating) 0 else WeavePrefs.oneHand(prefs)
        val lp = keyboardView.layoutParams as FrameLayout.LayoutParams
        // 可用宽度扣掉横屏时的侧边导航栏。 Available width, minus a side navigation bar in landscape.
        val w = ctx.resources.displayMetrics.widthPixels - if (floating) 0 else navLeft + navRight
        val landscapeMax = if (metrics.landscape) (720 * metrics.density).toInt().coerceAtMost(w) else w
        if (mode == 0) {
            lp.width = if (floating) -1 else landscapeMax
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            oneHandButton.visibility = View.GONE
        } else {
            // 横屏按常规宽度上限算，单手不会比双手还宽；切换按钮紧挨键区。
            // In landscape the normal width cap applies, so one-hand is never wider than normal; the button sits beside it.
            lp.width = (landscapeMax * 0.82f).toInt()
            lp.gravity = if (mode == 1) android.view.Gravity.START else android.view.Gravity.END
            oneHandButton.visibility = View.VISIBLE
            oneHandButton.left = mode == 2
            oneHandButton.layoutParams = FrameLayout.LayoutParams(landscapeMax - lp.width, -1).apply {
                gravity = if (mode == 1) android.view.Gravity.START else android.view.Gravity.END
                if (mode == 1) marginStart = lp.width else marginEnd = lp.width
            }
        }
        keyboardView.layoutParams = lp
    }

    override fun computeInsets(outInsets: InputMethodService.Insets) {
        if (!board.isLaidOut) return
        val loc = IntArray(2)
        if (floating) {
            // 内容区从窗口底部开始（App 保持全高），只有卡片可触摸。 App keeps full height; only the card is touchable.
            root.getLocationInWindow(loc)
            val i = FloatingGeometry.insets(loc[1] + root.height, cardBox())
            outInsets.contentTopInsets = i.contentTop
            outInsets.visibleTopInsets = i.visibleTop
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(i.touchable.left, i.touchable.top, i.touchable.right, i.touchable.bottom)
            return
        }
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
            WeavePrefs.SPLIT_WIDE -> keyboardView.splitWide = WeavePrefs.splitWide(p)
            WeavePrefs.HAND_PAUSE, WeavePrefs.HAND_LINE -> {
                keyboardView.handPauseMs=if(p.getBoolean(WeavePrefs.HAND_LINE,false)) Long.MAX_VALUE else WeavePrefs.handPauseMs(p)
                controller.reset();applyEngineOptions()
            }
            WeavePrefs.SHUANGPIN_HINTS, WeavePrefs.WUBI_ROOT_HINTS -> { layoutSig = ""; refreshLayout() }
            WeavePrefs.FUZZY, WeavePrefs.WUBI_PINYIN_MIX, WeavePrefs.TRADITIONAL, WeavePrefs.PREDICTION, WeavePrefs.AUTOCORRECT, WeavePrefs.AUTO_PAIR -> applyEngineOptions()
            WeavePrefs.KEYBOARDS, WeavePrefs.SHUANGPIN_SCHEME, WeavePrefs.ACTIVE_KEYBOARD -> { applySchemaPref(); layoutSig = ""; refreshLayout() }
            WeavePrefs.ONE_HAND -> applyOneHand()
            WeavePrefs.FLOATING -> setFloatingMode(WeavePrefs.floating(p))
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
        controller.setOption("candidates.prediction", WeavePrefs.prediction(prefs))
        controller.setOption("input.autocorrect", WeavePrefs.autocorrect(prefs))
        controller.feature(org.json.JSONObject().put("op","setHandLine").put("on",prefs.getBoolean(WeavePrefs.HAND_LINE,false)))
        controller.autoPair = WeavePrefs.autoPair(prefs)
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

    /**
     * 状态立即生效（按键逻辑读到的总是最新值），界面渲染合并到下一帧、每帧最多一次：
     * 连打时触摸分发里只剩内核调用，候选栏与键面在帧回调里画最后一个状态。
     * State applies at once (key logic always sees the latest), rendering is coalesced to at most
     * once per frame: touch dispatch only runs the engine call, the frame callback draws the final state.
     */
    private fun onState(s: ImeState) {
        state = s
        if (s.engineReady && !wasReady) {
            wasReady = true
            applyEngineOptions()
            applySchemaPref()
        }
        if (s.privateField && topBar.clipChip != null) { topBar.clipChip = null; pendingClip = null }
        // 这个字已上屏或被丢弃：墨迹随即清掉（同步进行，下一笔不会混进旧笔画）。
        // The char was committed or dropped: clear its ink right away, so the next stroke never joins old ones.
        if (!s.composing && keyboardView.hand?.strokes?.isNotEmpty() == true) keyboardView.clearInk()
        if (s.composing || s.candidates.isNotEmpty()) {
            localCands = null
            topBar.clipChip = null
            topBar.clearAction()
        }
        if (!renderPending) {
            renderPending = true
            Choreographer.getInstance().postFrameCallback(frameRender)
        }
    }

    /** 把待渲染的状态画出来（帧回调；测试也可直接调用）。 Render the pending state (frame callback; tests may call it). */
    fun flushRender() {
        if (!renderPending) return
        renderPending = false
        Choreographer.getInstance().removeFrameCallback(frameRender)
        val prev = rendered
        val s = state
        rendered = s
        refreshLayout()
        updateCandidates(prev)
        panel?.onState(s)
        renderCount++
    }

    private fun updateCandidates(prev: ImeState?) {
        val lc = localCands
        if (lc != null) {
            topBar.setCandidateTexts("", lc, lc.size, english = true, keepScroll = false)
            return
        }
        val s = state
        topBar.setCandidates(
            s.preedit, s.candidates, s.totalCandidates,
            english = !s.chinese, keepScroll = prev != null && prev.preedit == s.preedit,
            marks = s.preeditMarks,
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
            s.schema == "t14" -> "t14"
            s.schema == "hand" -> "hand"
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
            labelsFor = null
            val l = style.layout
            when {
                kind.startsWith("num") -> {
                    keyboardView.setNumpad(Layouts.numpad(field, l.labels))
                    keyboardView.side?.items = Layouts.NUM_SYMBOLS.map { it.toString() }
                }
                kind == "t9" -> keyboardView.setT9(Layouts.t9(l.t9, l.labels))
                kind == "hand" -> keyboardView.setHand(Layouts.hand(l.t9, l.labels))
                kind == "t14" -> keyboardView.setT14(Layouts.t14(l.t9, l.labels, lower = l.qwerty.letterCase == "lower"))
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

    /** 上次更新键面标签时的输入（相同则跳过，不重绘整个键区）。 Inputs of the last label update; skip when unchanged. */
    private var labelsFor: ImeState? = null
    private var labelsShift = -1

    private fun updateLabels() {
        val s = state
        val last = labelsFor
        if (last != null && labelsShift == shift.value && last.chinese == s.chinese && last.composing == s.composing &&
            last.enterAction == s.enterAction && last.schema == s.schema && last.pinyinOptions == s.pinyinOptions
        ) return
        labelsFor = s
        labelsShift = shift.value
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
        // 九键、14 键 / T9, 14-key
        if (layoutSig.startsWith("t9") || layoutSig.startsWith("t14")) {
            kv.keyOf(KeyCode.T9_ONE)?.let { it.label = if (composing) "分词" else "，。?!"; it.medium = composing }
            kv.keyOf(KeyCode.T9_RESET)?.let { it.label = if (composing) "重输" else "@" }
            kv.side?.let { side ->
                val idle = if (style.layout.t9.side == "symbols") Layouts.T9_SYMBOLS else Layouts.T9_PUNCT
                val items = if (composing && s.pinyinOptions.isNotEmpty()) s.pinyinOptions else idle.map { it.toString() }
                if (items != side.items) { side.items = items; side.scroll = 0f }
            }
        }
        kv.keyOf(KeyCode.HAND_CLEAR)?.disabled = !composing
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
            KeyCode.DELETE -> {
                // 手写有笔画时退一笔（内核同样退掉最后一笔并重新识别）。 Handwriting: undo the last stroke.
                keyboardView.undoStroke()
                controller.onBackspace()
            }
            KeyCode.HAND_CLEAR -> clearHand()
            KeyCode.SYMBOL -> showPanel("symbol")
            KeyCode.EMOJI -> { showPanel("symbol"); (panels["symbol"] as? SymbolPanel)?.selectEmoji() }
            KeyCode.NUMBER -> {
                // 写到一半切到数字键盘：先上屏这个字，回来时书写区和候选对得上。 Commit a half-written char first.
                if (s.schema == "hand" && s.composing) { controller.commitFirst(); keyboardView.clearInk() }
                numberMode = true
                refreshLayout()
            }
            KeyCode.BACK -> { numberMode = false; refreshLayout() }
            // 双击空格打句号在控制器里统一处理。 The double-space period lives in the controller.
            KeyCode.SPACE -> controller.onSpace()
            KeyCode.LANG -> {
                if (numberMode) numberMode = false
                controller.toggleChinese()
            }
            KeyCode.ENTER -> controller.onEnter()
            KeyCode.T9_RESET -> if (s.composing) controller.reset() else controller.onText("@")
            KeyCode.T9_ONE -> if (s.composing) controller.onChar('\''.code) else showLocalCandidates(T9_ONE_PUNCT)
            else -> onCharKey(key)
        }
        // 刚按的是 Shift：不马上按句首规则改回去（用户关掉的自动大写保持关）。 Don't undo a Shift tap right away.
        if (key.code == KeyCode.SHIFT) localCandsClearIfNeeded() else afterKey()
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
        val near = keyboardView.nearCode
        if (near != 0 && code in 'a'.code..'z'.code) controller.onChar(code, near, keyboardView.nearCloseness)
        else controller.onChar(code)
    }

    private fun afterKey() {
        localCandsClearIfNeeded()
        val s = state
        // 组合中编辑器里的文字没变（单词还在内核里），不能再按句首判断，否则每个字母都会大写（HELLO）。
        // While composing the editor text hasn't changed (the word is still in the engine): judging sentence
        // start again would capitalise every letter (HELLO).
        // 中文里不自动大写：从英文带过来的自动大写收回，否则切到中文后第一个字母会直接上屏成大写（N 而不是拼音 n）。
        // No auto-caps in Chinese: an auto capital carried over from English is taken back, or the first letter after
        // switching would be committed as a capital (N instead of pinyin n).
        if (s.chinese) { if (shift.autoCap(false)) updateLabels() }
        else if (!s.composing && !layoutSig.startsWith("num") && shift.autoCap(controller.capsModeActive())) updateLabels()
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
        if (text.length == 1 && state.chinese && state.composing && text[0] in 'a'..'z' && (key.code in '2'.code..'9'.code || key.code in 'A'.code..'N'.code)) {
            // 九键 / 14 键长按单个字母：直接上屏字母。 T9 / 14-key single letter.
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

    override fun onKeyReplace(key: Key, text: String) {
        controller.undoLastInput()
        onKeyText(key, text)
    }

    override fun onKeyRevert(key: Key) { controller.undoLastInput() }

    override fun cursorDragAllowed() = !state.composing

    override fun onCursorSteps(steps: Int) {
        if (state.composing) return
        controller.moveCursor(steps)
    }

    override fun onDeleteRepeat(count: Int): Boolean {
        // 手写有笔画时长按删除 = 重写，之后不再连删。 Handwriting: holding Delete clears the strokes, then stops.
        if (keyboardView.hand?.hasInk == true || (state.composing && state.schema == "hand")) {
            clearHand()
            return false
        }
        if (count > 20) controller.deleteWordBefore() else controller.onBackspace()
        return true
    }

    private fun clearHand() {
        controller.reset()
        keyboardView.clearInk()
    }

    override fun onHandStroke(strokes: List<FloatArray>) {
        topBar.clearAction()
        controller.onHandStrokes(strokes)
    }

    override fun onHandCommit() { controller.commitFirst() }

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
    /** 语音面板与浮动语音条共用的会话（多引擎结果可以从语音条交给面板）。 Shared voice session. */
    val voiceSession: VoiceSession by lazy { VoiceSession(ctx, controller) }

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

    override fun onToolbarLong(index: Int): Boolean {
        when (index) {
            0 -> openSettings(null)
            // 长按 🎙：不打开面板，直接按住说话，松手结束。 Long-press mic: hold-to-talk, release to end.
            2 -> startHoldVoice()
            else -> return false
        }
        return true
    }

    override fun onToolbarLongMove(index: Int, dy: Float) { if (index == 2) voiceStrip?.onMove(dy) }
    override fun onToolbarLongEnd(index: Int, cancelled: Boolean) { if (index == 2) voiceStrip?.end(cancelled) }

    override fun onCandidate(index: Int) {
        val lc = localCands
        if (lc != null) {
            val t = lc.getOrNull(index) ?: return
            localCands = null
            controller.onText(t)
            updateCandidates(null)
            return
        }
        controller.onCandidate(index)
    }

    override fun onCandidateLong(index: Int): Boolean {
        // 本地列表（九键标点、计算结果）与内核候选无关，不能拿它的序号去删用户词。
        // A local list (9-key punctuation, a calculator result) isn't the engine's: its index can't delete a user word.
        if (localCands != null) return false
        val c = state.candidates.getOrNull(index) ?: return false
        if (controller.isSensitiveField) return false
        feedback.haptic(topBar)
        val mode=controller.candidatePolicy(index,c.text)
        val content=android.widget.LinearLayout(ctx).apply {
            orientation=android.widget.LinearLayout.VERTICAL
            setPadding(20,12,20,12);setBackgroundColor(palette.card)
        }
        val popup=android.widget.PopupWindow(content,metrics.dp(240f).toInt(),android.view.ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            inputMethodMode=android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED
            isOutsideTouchable=true;elevation=metrics.dp(8f)
        }
        fun action(label:String,operation:()->Unit) {
            content.addView(android.widget.TextView(ctx).apply {
                text=label;textSize=16f;setTextColor(palette.label);setPadding(12,20,12,20)
                setOnClickListener{popup.dismiss();operation()}
            })
        }
        action(if(mode=="pin") "取消固定「${c.text}」" else "固定「${c.text}」为首选") {controller.candidatePolicy(index,c.text,if(mode=="pin") "" else "pin")}
        action(if(mode=="down") "恢复正常排序" else "降低优先级") {controller.candidatePolicy(index,c.text,if(mode=="down") "" else "down")}
        if(c.isUser) action("删除学习记录") {if(controller.loadCandidates(index,1).firstOrNull()?.text==c.text)controller.onForgetCandidate(index)}
        popup.showAtLocation(view,android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,0,metrics.dp(90f).toInt())
        return true
    }

    override fun onExpand() { togglePanel("grid") }

    override fun onNeedMore() {
        if (localCands != null) return
        val loaded = topBar.loadedCount
        if (loaded >= state.totalCandidates) return
        val more = controller.loadCandidates(loaded, 30)
        if (more.isNotEmpty()) topBar.appendCandidates(more)
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
        // 整块面板盖住键区与顶栏（为淡入仍可见）：对读屏隐藏它们。 Hidden from screen readers under a full panel.
        hideUnderPanel(p.full)
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
        hideUnderPanel(false)
        applyOneHand()
        topBar.activeTool = -1
        topBar.expanded = false
        fadeIn(keyboardView, keep = true)
    }

    private fun hideUnderPanel(hide: Boolean) {
        val mode = if (hide) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        keyboardView.importantForAccessibility = mode
        topBar.importantForAccessibility = mode
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

    /** 切换到另一个输入法；做不到时打开系统的输入法选择框。 Switch IME, falling back to the system picker. */
    fun switchToIme(id: String, subtype: android.view.inputmethod.InputMethodSubtype?) {
        if (!host.switchToIme(id, subtype)) {
            runCatching { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker() }
        }
    }

    /**
     * 工具箱「发到电脑」：把当前剪贴板文字发给已连接的电脑；未开启或未连接时引导去设置。
     * Toolbox "send to computer": send the current clipboard text to the connected computer, or point to settings.
     */
    fun sendClipboardToComputer() {
        val link = com.weavetext.ime.link.LinkManager.get(ctx)
        val s = link.state.value
        val target = s.connected.firstOrNull()
        when {
            !s.enabled || target == null ->
                topBar.showAction(if (!s.enabled) "织文互联未开启" else "没有已连接的电脑", "去设置", 4000) { openSettings("link") }
            else -> {
                val text = clipboard.currentClip()
                if (text.isNullOrBlank()) {
                    topBar.showAction("剪贴板是空的", null, 2500, null)
                } else {
                    val ok = link.sendText(target.id, text, clip = false)
                    topBar.showAction(if (ok) "已发送到「${target.name}」" else "发送失败", null, 2500, null)
                }
            }
        }
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
        // 复制的是带验证码的短信：候选栏只给出验证码本身。 A message with a one-time code: offer just the code.
        val code = com.weavetext.ime.ime.ClipExtract.code(text)
        pendingClip = code ?: text
        topBar.clipChip = if (code != null) "验证码 $code" else text.replace('\n', ' ')
        topBar.removeCallbacks(clipChipTimeout)
        topBar.postDelayed(clipChipTimeout, 10_000)
    }

    // ================================================================ floating keyboard (06 §5)

    fun toggleFloating() = prefs.edit().putBoolean(WeavePrefs.FLOATING, !floating).apply()

    private fun setFloatingMode(on: Boolean) {
        if (on == floating) return
        floating = on
        keyboardView.cancelTouch()
        popup.hideAll()
        applyTheme()
        layoutSig = ""
        refreshLayout()
        updateCandidates(null)
        root.requestLayout()
    }

    private fun posKey() = if (metrics.landscape) WeavePrefs.FLOAT_POS_LAND else WeavePrefs.FLOAT_POS_PORT
    private fun floatMinTop() = metrics.dp(24f).toInt()
    private fun floatMaxBottom() = root.height - navInset - metrics.dp(8f).toInt()
    private fun floatAvailableWidth() = (root.width - navLeft - navRight).coerceAtLeast(1)

    /** 按保存的比例放置卡片（窗口尺寸变化时也调用）。 Place the card from the stored fractions. */
    private fun placeCard() {
        if (!floating || root.height == 0) return
        val (fx, fy) = FloatingGeometry.decode(prefs.getString(posKey(), null))
        val lp = card.layoutParams
        val b = FloatingGeometry.place(fx, fy, floatAvailableWidth(), lp.width, lp.height, floatMinTop(), floatMaxBottom())
        card.translationX = (b.left + navLeft).toFloat()
        card.translationY = b.top.toFloat()
        syncOverlayAnchor()
    }

    /** 卡片在本窗口中的矩形。 The card rect in window coordinates. */
    private fun cardBox(): FloatingGeometry.Box {
        val loc = IntArray(2)
        card.getLocationInWindow(loc)
        return FloatingGeometry.Box(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
    }

    private fun moveCardBy(dx: Float, dy: Float) {
        val b = FloatingGeometry.clamp(
            (card.translationX + dx).toInt() - navLeft, (card.translationY + dy).toInt(),
            floatAvailableWidth(), card.width, card.height, floatMinTop(), floatMaxBottom(),
        )
        card.translationX = (b.left + navLeft).toFloat()
        card.translationY = b.top.toFloat()
        syncOverlayAnchor()
    }

    private fun saveCardPosition() {
        val b = FloatingGeometry.Box(card.translationX.toInt() - navLeft, card.translationY.toInt(),
            card.translationX.toInt() - navLeft + card.width, card.translationY.toInt() + card.height)
        val f = FloatingGeometry.fractions(b, floatAvailableWidth(), floatMinTop(), floatMaxBottom())
        prefs.edit().putString(posKey(), FloatingGeometry.encode(f)).apply()
    }

    /** 浮动组合串 / 语音条跟随卡片。 Preedit chip and voice strip follow the card. */
    private fun syncOverlayAnchor() {
        if (floating) popup.setAnchor(RectF(card.translationX, card.translationY, card.translationX + card.width, card.translationY))
        else popup.setAnchor(null)
    }

    /** 悬浮卡片顶部的拖动条：拖动移动，双击或点右侧按钮停靠。 Drag handle: drag to move; double-tap or the button docks. */
    private inner class FloatHandle(c: Context) : View(c) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var lastTap = 0L

        init {
            contentDescription = "拖动悬浮键盘；双击停靠到底部"
        }

        private fun dockButtonLeft() = FloatingGeometry.dockBox(width, height, metrics.density).left

        override fun onDraw(canvas: android.graphics.Canvas) {
            val m = metrics
            paint.color = palette.labelHint
            val w = m.dp(36f)
            val cy = height / 2f
            canvas.drawRoundRect(width / 2f - w / 2, cy - m.dp(2f), width / 2f + w / 2, cy + m.dp(2f), m.dp(2f), m.dp(2f), paint)
            icons.draw(canvas, R.drawable.ic_float, palette.icon, width - m.dp(22f), cy, m.dp(18f))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; downX = e.rawX; downY = e.rawY; dragging = false
                    keyboardView.cancelTouch()
                    popup.hideAll()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > metrics.dp(6f)) dragging = true
                    if (dragging) {
                        moveCardBy(e.rawX - lastX, e.rawY - lastY)
                        lastX = e.rawX; lastY = e.rawY
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging) saveCardPosition()
                    else if (e.x >= dockButtonLeft()) { feedback.key(this); toggleFloating() }
                    else {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap < 300) { lastTap = 0; toggleFloating() } else lastTap = now
                    }
                    dragging = false
                }
                android.view.MotionEvent.ACTION_CANCEL -> { if (dragging) saveCardPosition(); dragging = false }
            }
            return true
        }
    }

    /** 卡片当前宽度 px（测试用）。 Current card width in px (for tests). */
    @get:androidx.annotation.VisibleForTesting
    val cardWidth: Int get() = card.width

    private fun sizeKey() = if (metrics.landscape) WeavePrefs.FLOAT_SIZE_LAND else WeavePrefs.FLOAT_SIZE_PORT

    /**
     * 按缩放重建尺寸。拖动中（[save] 为假）只换尺寸：不写设置、不重新解析样式、不清图标缓存；
     * 松手时保存并完整刷新一次。
     * Re-apply sizes for a new scale. While dragging ([save] false) only the sizes change: no preference write, no
     * style re-resolve, no icon-cache clear; on release the scale is saved and everything refreshed once.
     */
    private fun applyFloatScale(s: Float, save: Boolean) {
        if (save) {
            prefs.edit().putString(sizeKey(), "%.3f".format(java.util.Locale.ROOT, s)).apply()
            applyTheme()
        } else {
            floatScale = s
            val m = KbMetrics(ctx, FLOAT_LEVEL, KeyboardStyle.geometry(style.layout, style.overrides), s)
            style = KeyboardStyle(style.layout, style.theme, style.dark, style.overrides, style.palette, m)
            metrics = m
            popup.applyStyle(style)
            topBar.applyStyle(style, icons)
            keyboardView.applyStyle(style, icons)
            panel?.applyTheme()
            applyGeometry()
        }
        layoutSig = ""
        refreshLayout()
        updateCandidates(null)
    }

    /** 缩放中：保持右下角不动，再夹回屏幕内。 While resizing: keep the bottom-right corner, then clamp on screen. */
    private fun placeResizing() {
        val (l, t) = FloatingGeometry.anchorBottomRight(resizeRight, resizeBottom, card.width, card.height)
        val b = FloatingGeometry.clamp(l - navLeft, t, floatAvailableWidth(), card.width, card.height, floatMinTop(), floatMaxBottom())
        card.translationX = (b.left + navLeft).toFloat()
        card.translationY = b.top.toFloat()
        syncOverlayAnchor()
    }

    /**
     * 悬浮卡片的缩放手柄：在顶部拖动条的左端（不占键区），向左上拖放大、向右下拖缩小，右下角不动；
     * 按键保持比例，范围见 [FloatingGeometry.clampScale]，横竖屏各记一份。
     * Resize grip at the left end of the top drag bar (outside the key area): drag up/left to grow,
     * down/right to shrink, with the bottom-right corner fixed; remembered per orientation.
     */
    private inner class ResizeGrip(c: Context) : View(c) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private var downX = 0f
        private var downY = 0f
        private var startScale = 1f
        private var startW = 0
        private var dragScale = 1f
        private var startH = 0

        init { contentDescription = "拖动调整悬浮键盘大小" }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val m = metrics
            paint.color = palette.labelHint
            paint.strokeWidth = m.dp(1.5f)
            // 左上角的双层直角标记。 Two nested corner marks pointing to the top-left.
            val l = width / 2f - m.dp(6f)
            val t = height / 2f - m.dp(6f)
            for (i in 0..1) {
                val o = m.dp(4f) * i
                val len = m.dp(10f) - o
                canvas.drawLine(l + o, t + o, l + o + len, t + o, paint)
                canvas.drawLine(l + o, t + o, l + o, t + o + len, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startScale = floatScale; dragScale = floatScale; startW = card.width; startH = card.height
                    resizeRight = card.translationX.toInt() + card.width
                    resizeBottom = card.translationY.toInt() + card.height
                    resizing = true
                    keyboardView.cancelTouch()
                    popup.hideAll()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val raw = FloatingGeometry.resizeScale(startScale, startW, startH, e.rawX - downX, e.rawY - downY)
                    val dm = ctx.resources.displayMetrics
                    val baseW = (startW / startScale).toInt()
                    val baseH = (startH / startScale).toInt()
                    val s = FloatingGeometry.clampScale(raw, root.width.takeIf { it > 0 } ?: dm.widthPixels, root.height.takeIf { it > 0 } ?: dm.heightPixels, baseW, baseH, dm.density)
                    if (kotlin.math.abs(s - dragScale) >= 0.02f) { dragScale = s; applyFloatScale(s, save = false) }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragScale != startScale) applyFloatScale(dragScale, save = true)
                    resizing = false
                    saveCardPosition()
                }
            }
            return true
        }
    }

    // ================================================================ lifecycle

    /**
     * 键盘出现。[restarting] 为真时是同一输入框重新开始（聊天应用发送后清空、App 改写了文字）：保留数字键盘、
     * 大写锁定、打开的面板，只按新内容重新判断句首大写。
     * The keyboard shows. With [restarting] the same field restarted (a chat app cleared it after sending, the
     * app rewrote the text): keep the number layout, caps lock and the open panel; only re-check sentence caps.
     */
    override fun onShown(restarting: Boolean) {
        // 每次弹出都按当前边衬校一遍（导航方式、横竖屏可能已变）。 Re-check insets on every show.
        root.rootWindowInsets?.let(::applyInsets)
        root.requestApplyInsets()
        if (!restarting) {
            numberMode = false
            shift.reset()
            localCands = null
            if (panel != null) closePanel()
            // 「已清空 · 撤销」只属于原来的输入框。 The undo chip belongs to the previous field.
            topBar.clearAction()
        }
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
        topBar.clearAction()
        popup.showPreedit(null)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        (panel as? CursorPanel)?.onSelection(selStart != selEnd)
    }

    private val clipboardLazy = lazy { ClipboardRepo(ctx, this) }
    val clipboard: ClipboardRepo by clipboardLazy

    override fun dispose() {
        root.removeCallbacks(navigationLayout)
        Choreographer.getInstance().removeFrameCallback(frameRender)
        renderPending = false
        controller.removeListener(stateListener)
        cloudWords.removeListener(cloudListener)
        controller.onCalc = null
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        voiceStrip?.end(true)
        for (p in panels.values) p.onHide()
        // 没用过剪贴板就不必为了释放而创建（创建会读历史文件）。 Don't create (and load) the repo just to release it.
        if (clipboardLazy.isInitialized()) clipboard.release()
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

    /**
     * 根布局：键盘区在下，上方留出透明气泡区；悬浮模式下铺满整个窗口（透明，只有卡片可触摸）。
     * Root: keyboard at the bottom with a transparent bubble area; fills the whole (transparent)
     * window in floating mode, where only the card is touchable.
     */
    private inner class RootLayout(c: Context) : FrameLayout(c) {
        init {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = if (floating) {
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels
                else MeasureSpec.getSize(heightMeasureSpec)
            } else (metrics.bubbleSpace + metrics.kbHeight).toInt() + navInset
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // Recheck after the framework positions the IME, without changing layout mid-pass.
            removeCallbacks(navigationLayout)
            post(navigationLayout)
            if (floating) { if (resizing) placeResizing() else placeCard() } else syncOverlayAnchor()
        }
    }

    companion object {
        /** 悬浮卡片使用的键高档位。 Height level used by the floating card. */
        const val FLOAT_LEVEL = 0
        private const val HANDLE_DP = 22f
        val T9_ONE_PUNCT = listOf("，", "。", "？", "！", "、", "：", "；", "…", "～", "“", "”", "@", ".", ",", "?", "!")
    }
}

/** 需要感知设置变化的面板。 Panels reacting to preference changes. */
interface PrefAware { fun onPref(key: String?) }
