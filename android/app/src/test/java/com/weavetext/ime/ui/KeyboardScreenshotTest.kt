package com.weavetext.ime.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.ime.EnterAction
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeRecognizer
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.VoicePanel
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 键盘截图测试的公共部分。 Shared setup for keyboard screenshot tests. */
abstract class KeyboardSnapshotSupport {
    protected val app get() = ApplicationProvider.getApplicationContext<Application>()
    protected val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")
    protected lateinit var activity: Activity
    protected var kb: WeaveKeyboard? = null

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        VoiceAccess.enginesProvider = { engines }
        VoiceAccess.recognizerProvider = { FakeRecognizer() }
        WeavePrefs.of(app).edit().clear().commit()
        File(app.filesDir, "clipboard").deleteRecursively()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After fun tearDown() { kb?.dispose() }

    protected val engines = FakeEngines()

    protected fun keyboard(dark: Boolean, prefs: SharedPreferences.Editor.() -> Unit = {}): Pair<WeaveKeyboard, InputController> {
        WeavePrefs.of(app).edit().putString(WeavePrefs.THEME, if (dark) "dark" else "system").apply(prefs).commit()
        val controller = InputController { null }
        val k = WeaveKeyboard(activity, controller, host)
        kb = k
        val frame = FrameLayout(activity).apply { setBackgroundColor(if (dark) Color.rgb(0x0B, 0x0C, 0x0E) else Color.rgb(0xF5, 0xF6, 0xF8)) }
        frame.addView(k.view, FrameLayout.LayoutParams(-1, -2).apply { gravity = android.view.Gravity.BOTTOM })
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        k.onShown()
        idle()
        return k to controller
    }

    protected fun idle() = ShadowLooper.idleMainLooper()

    /** 设置系统字号并重建 Activity（KbMetrics 读取 Activity 的配置）。 Set font scale and rebuild the activity. */
    protected fun fontScale(f: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(f)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    protected open fun snap(name: String) {
        idle()
        kb!!.view.captureRoboImage(File(dir, "keyboard_$name.png").path)
    }

    protected val nihao = listOf("你好", "拟好", "你", "尼", "泥", "呢", "倪", "妮", "你号", "逆", "腻").map { Candidate(it, "", false) }

    protected fun composing(schema: String = "pinyin", preedit: String = "ni'hao", cands: List<Candidate> = nihao, pinyin: List<String> = emptyList()) = ImeState(
        preedit = preedit, candidates = cands, totalCandidates = 40, pinyinOptions = pinyin, composing = true,
        chinese = true, schema = schema, engineReady = true,
    )
}

/**
 * 键盘各面板的 JVM 截图（Robolectric 原生渲染）。输出到 src/test/snapshots/keyboard_*.png。
 * 状态全部是假的 ImeState，不加载内核原生库。
 * JVM screenshots of every keyboard panel with fake state; the native engine is never loaded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class KeyboardScreenshotTest : KeyboardSnapshotSupport() {
    @Test fun pinyinIdleLight() { keyboard(false); snap("pinyin_idle_light") }

    @Test fun pinyinIdleDarkSend() {
        val (_, c) = keyboard(true)
        c.previewState(ImeState(engineReady = true, enterAction = EnterAction.SEND))
        snap("pinyin_idle_dark")
    }

    @Test fun pinyinComposingLight() { val (_, c) = keyboard(false); c.previewState(composing()); snap("pinyin_composing_light") }

    @Test fun pinyinComposingDark() { val (_, c) = keyboard(true); c.previewState(composing()); snap("pinyin_composing_dark") }

    @Test fun t9Composing() {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "t9,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9") }
        c.previewState(composing("t9", pinyin = listOf("ni", "mi", "oh", "o", "n")))
        snap("t9_composing_light")
    }

    @Test fun t9IdleDark() {
        val (_, c) = keyboard(true) { putString(WeavePrefs.KEYBOARDS, "t9,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9") }
        c.previewState(ImeState(schema = "t9", engineReady = true))
        snap("t9_idle_dark")
    }

    @Test fun t14IdleLight() {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "t14,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t14") }
        c.previewState(ImeState(schema = "t14", engineReady = true))
        snap("t14_idle_light")
    }

    @Test fun t14ComposingDark() {
        val (_, c) = keyboard(true) { putString(WeavePrefs.KEYBOARDS, "t14,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t14") }
        c.previewState(composing("t14", pinyin = listOf("ni", "mi", "bi", "n", "m")))
        snap("t14_composing_dark")
    }

    @Test fun handIdleLight() {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "hand,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "hand") }
        c.previewState(ImeState(schema = "hand", engineReady = true))
        snap("hand_idle_light")
    }

    @Test fun handWritingLight() {
        val (k, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "hand,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "hand") }
        c.previewState(composing("hand", preedit = "", cands = handCands))
        writeZhong(k)
        snap("hand_writing_light")
    }

    @Test fun handWritingDark() {
        val (k, c) = keyboard(true) { putString(WeavePrefs.KEYBOARDS, "hand,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "hand") }
        c.previewState(composing("hand", preedit = "", cands = handCands))
        writeZhong(k)
        snap("hand_writing_dark")
    }

    private val handCands = "中申巾由甲电串史央虫".map { Candidate(it.toString(), "", false) }

    /** 在书写区写一个「中」（四笔，书写区内的比例坐标）。 Write 中 on the pad (four strokes, pad fractions). */
    private fun writeZhong(k: WeaveKeyboard) {
        val kv = k.keyboardView
        val r = kv.hand!!.rect
        val strokes = listOf(
            floatArrayOf(0.36f, 0.30f, 0.37f, 0.45f, 0.38f, 0.62f),
            floatArrayOf(0.36f, 0.30f, 0.50f, 0.29f, 0.64f, 0.28f, 0.635f, 0.45f, 0.63f, 0.60f),
            floatArrayOf(0.38f, 0.60f, 0.50f, 0.605f, 0.62f, 0.60f),
            floatArrayOf(0.50f, 0.10f, 0.502f, 0.45f, 0.50f, 0.92f),
        )
        var t = android.os.SystemClock.uptimeMillis()
        for (s in strokes) {
            fun ev(action: Int, i: Int) = android.view.MotionEvent.obtain(t, t, action, r.left + s[i] * r.width(), r.top + s[i + 1] * r.height(), 0)
                .also { kv.dispatchTouchEvent(it); it.recycle() }
            ev(android.view.MotionEvent.ACTION_DOWN, 0)
            for (i in 2 until s.size step 2) ev(android.view.MotionEvent.ACTION_MOVE, i)
            ev(android.view.MotionEvent.ACTION_UP, s.size - 2)
            t += 100
        }
    }

    @Test fun wubiHints() {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "wubi86,english").putBoolean(WeavePrefs.WUBI_ROOT_HINTS, true) }
        c.previewState(
            composing("wubi86", "ggll", listOf("王" to "", "五" to "gg", "一" to "g", "玉" to "gy", "现" to "gm").map { Candidate(it.first, it.second, false) }),
        )
        snap("wubi_hints_light")
    }

    @Test fun shuangpinHints() {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "shuangpin,english") }
        c.previewState(ImeState(schema = "shuangpin:xiaohe", engineReady = true, enterAction = EnterAction.SEARCH))
        snap("shuangpin_hints_light")
    }

    @Test fun englishShift() {
        val (k, c) = keyboard(false)
        c.previewState(ImeState(chinese = false, engineReady = true))
        k.onKey(k.keyboardView.keyOf(KeyCode.SHIFT)!!)
        snap("english_shift_once_light")
    }

    /** 英文输入中：正在敲的单词是首个候选，带光标。 English typing: the word being typed leads, with a caret. */
    @Test fun englishTyping() {
        val (_, c) = keyboard(false)
        c.previewState(
            ImeState(
                chinese = false, engineReady = true, composing = true, preedit = "hel",
                candidates = listOf("hel", "hello", "help", "held", "helmet").map { com.weavetext.ime.core.Candidate(it, "", false) }, totalCandidates = 5,
            ),
        )
        snap("english_typing_light")
    }

    /** 按住字母时的预览气泡（单独的小视图）。 The key-preview bubble (its own small view) while a letter is held. */
    @Test fun keyPreview() {
        val (k, _) = keyboard(false)
        val kv = k.keyboardView
        val g = kv.keyOf('g'.code)!!
        val t = android.os.SystemClock.uptimeMillis()
        val e = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, g.rect.centerX(), g.rect.centerY(), 0)
        kv.dispatchTouchEvent(e)
        e.recycle()
        idle()
        assertTrue(k.overlay!!.bubbleVisible)
        assertTrue("bubble view is small", k.overlay!!.bubbleView.width in 1 until k.view.width / 2)
        snap("key_preview_light")
        kv.cancelTouch()
        idle()
        assertEquals(android.view.View.INVISIBLE, k.overlay!!.bubbleView.visibility)
    }

    /** 实体键盘：软键盘隐藏，只剩候选栏；拔掉后顶栏回到键盘里。 Physical keyboard: candidate bar only, restored on detach. */
    @Test fun hardwareCandidateBar() {
        val (k, c) = keyboard(false)
        c.previewState(
            ImeState(
                preedit = "ni'hao", composing = true, engineReady = true, totalCandidates = 5,
                candidates = listOf("你好", "拟好", "你", "尼", "泥").map { Candidate(it, "", false) },
            ),
        )
        k.setHardwareMode(true)
        val bar = k.candidatesView
        assertTrue(k.topBar.parent === bar)
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(0xF5, 0xF6, 0xF8)) }
        frame.addView(bar, FrameLayout.LayoutParams(-1, -2).apply { gravity = android.view.Gravity.BOTTOM })
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        k.flushRender()
        idle()
        bar.captureRoboImage(File(dir, "keyboard_hardware_candidates_light.png").path)
        frame.removeView(bar)
        k.setHardwareMode(false)
        assertTrue(k.topBar.parent === k.board)
    }

    @Test fun numberPad() {
        val (k, _) = keyboard(false)
        k.onKey(k.keyboardView.keyOf(KeyCode.NUMBER)!!)
        snap("numpad_light")
    }

    @Test fun numberPadDark() {
        val (k, _) = keyboard(true)
        k.onKey(k.keyboardView.keyOf(KeyCode.NUMBER)!!)
        snap("numpad_dark")
    }

    @Test fun symbols() { val (k, _) = keyboard(false); k.showPanel("symbol"); snap("symbols_light") }

    @Test fun symbolsEmojiDark() {
        val (k, _) = keyboard(true)
        k.showPanel("symbol"); (k.panelNamed("symbol") as com.weavetext.ime.ui.keyboard.SymbolPanel).selectEmoji()
        snap("symbols_emoji_dark")
    }

    @Test fun cursor() { val (k, _) = keyboard(false); k.showPanel("cursor"); snap("cursor_light") }

    @Test fun cursorDark() { val (k, _) = keyboard(true); k.showPanel("cursor"); snap("cursor_dark") }

    @Test fun clipboard() {
        val (k, _) = keyboard(false) { putBoolean(WeavePrefs.CLIPBOARD_RECORD, true) }
        val now = System.currentTimeMillis()
        val h = k.clipboard.history
        h.add("好的，明天见", now - 3_600_000)
        h.add("验证码 482913，5 分钟内有效", now - 600_000)
        h.add("https://example.org/weavetext/docs/getting-started", now - 120_000)
        h.add("收货地址：示例市示例区示例路 88 号 3 栋 1201 室，电话 138****0000", now - 7_200_000)
        h.setPinned(h.list(now).first { it.text.startsWith("收货") }.id, true)
        k.showPanel("clipboard")
        snap("clipboard_light")
    }

    @Test fun clipboardEmptyDark() {
        val (k, _) = keyboard(true) { putBoolean(WeavePrefs.CLIPBOARD_RECORD, true) }
        k.showPanel("clipboard")
        snap("clipboard_empty_dark")
    }

    /** 首次打开：询问是否记录。 First open asks whether to record. */
    @Test fun clipboardFirstRunPrompt() { val (k, _) = keyboard(false); k.showPanel("clipboard"); snap("clipboard_prompt_light") }

    @Test fun clipboardFirstRunPromptDark() { val (k, _) = keyboard(true); k.showPanel("clipboard"); snap("clipboard_prompt_dark") }

    /** 只看当前剪贴板：标题栏提供「开启记录」。 Current-clip-only mode. */
    @Test fun clipboardCurrentOnly() {
        val (k, _) = keyboard(false) { putBoolean(WeavePrefs.CLIPBOARD_RECORD, false) }
        k.showPanel("clipboard")
        snap("clipboard_current_only_light")
    }

    /** 密码框：不显示任何历史。 Private field shows no history. */
    @Test fun clipboardPrivateField() {
        val (k, c) = keyboard(false) { putBoolean(WeavePrefs.CLIPBOARD_RECORD, true) }
        k.clipboard.history.add("不应出现的内容", System.currentTimeMillis())
        c.previewState(ImeState(engineReady = true, passwordField = true, privateField = true))
        k.showPanel("clipboard")
        snap("clipboard_private_light")
    }

    @Test fun phrases() { val (k, _) = keyboard(false); k.showPanel("phrases"); snap("phrases_light") }

    @Test fun toolbox() {
        val (k, _) = keyboard(false) { putBoolean(WeavePrefs.TRADITIONAL, true) }
        k.showPanel("toolbox")
        snap("toolbox_light")
    }

    @Test fun toolboxDark() { val (k, _) = keyboard(true); k.showPanel("toolbox"); snap("toolbox_dark") }

    @Test fun heightAdjust() { val (k, _) = keyboard(false); k.showPanel("height"); snap("height_light") }

    @Test fun voiceListening() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        (k.panel as VoicePanel).session.preview(VoiceSession.State.LISTENING, "今天下午三点在会议室开会，记得带上电脑", "和充电", 0.6f)
        snap("voice_listening_light")
    }

    /** 多引擎结果列表：完成 / 识别中 / 超时，默认高亮主引擎。 Multi-engine result list. */
    private fun multiResults(): com.weavetext.ime.voice.MultiEngineResults {
        val r = com.weavetext.ime.voice.MultiEngineResults(
            listOf("weave.local" to "本地离线识别", "org.example.asr.cloud" to "示例云端识别", "org.example.asr.b" to "示例插件 B"),
            primaryId = "weave.local",
        )
        r.final("weave.local", "今天下午三点在会议室开会，记得带上电脑")
        r.final("org.example.asr.cloud", "今天下午3点在会议室开会，记得带上电脑。")
        r.stop(0)
        r.end("weave.local", 420)
        r.end("org.example.asr.cloud", 1_380)
        return r
    }

    private fun useLocalPrimary() {
        engines.plugins = listOf(com.weavetext.ime.voice.VoicePlugin("weave.local", "本地离线识别", "", "", null, emptyList())) + FakeEngines.SAMPLE.drop(1)
        engines.activeId = "weave.local"
        engines.extraIds = setOf("org.example.asr.cloud", "org.example.asr.b")
    }

    @Test fun voiceMultiResults() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        useLocalPrimary()
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        (k.panel as VoicePanel).session.previewResults(multiResults())
        snap("voice_multi_results_light")
    }

    @Test fun voiceMultiResultsDark() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        useLocalPrimary()
        val (k, _) = keyboard(true)
        k.showPanel("voice")
        val r = multiResults()
        r.tick(8_000)
        (k.panel as VoicePanel).session.previewResults(r)
        snap("voice_multi_results_timeout_dark")
    }

    @Test fun voiceIdleDark() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(true)
        k.showPanel("voice")
        snap("voice_idle_dark")
    }

    @Test fun voiceNoPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        snap("voice_no_permission_light")
    }

    /** 没有可用引擎（轻量版）：给出下载、系统设置、导入三个办法。 No engine on the lite build: three ways out. */
    @Test fun voiceNoEngineLite() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        engines.plugins = emptyList()
        com.weavetext.ime.models.AsrRuntime.bundled = false
        try {
            val (k, _) = keyboard(false)
            k.showPanel("voice")
            snap("voice_no_engine_lite_light")
        } finally {
            com.weavetext.ime.models.AsrRuntime.bundled = com.weavetext.ime.BuildConfig.LOCAL_ASR
        }
    }

    /** 手机上有其他语音输入法：零下载的办法排第一。 Another voice IME present: the zero-download option first. */
    @Test fun voiceNoEngineOtherIme() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        engines.plugins = emptyList()
        com.weavetext.ime.models.AsrRuntime.bundled = false
        com.weavetext.ime.voice.VoiceIme.finder = { listOf(com.weavetext.ime.voice.VoiceIme.Option("org.example.voice/.Ime", "示例语音输入", null, enabled = true)) }
        try {
            val (k, _) = keyboard(false)
            k.showPanel("voice")
            snap("voice_no_engine_other_ime_light")
        } finally {
            com.weavetext.ime.models.AsrRuntime.bundled = com.weavetext.ime.BuildConfig.LOCAL_ASR
            com.weavetext.ime.voice.VoiceIme.finder = { emptyList() }
        }
    }

    /** 系统识别连接失败：说明原因并给出办法。 System recognizer failed: reason plus ways out. */
    @Test fun voiceSystemError() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        engines.plugins = listOf(com.weavetext.ime.voice.VoicePlugin("weave.system", "系统语音识别", "", "", null, emptyList()))
        engines.activeId = "weave.system"
        com.weavetext.ime.models.AsrRuntime.bundled = false
        try {
            val (k, _) = keyboard(false)
            k.showPanel("voice")
            (k.panel as VoicePanel).session.preview(VoiceSession.State.ERROR, "", "", 0f, "系统语音服务连接失败")
            snap("voice_system_error_lite_light")
        } finally {
            com.weavetext.ime.models.AsrRuntime.bundled = com.weavetext.ime.BuildConfig.LOCAL_ASR
        }
    }

    @Test fun voiceEngineSheet() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        k.showEngineSheet()
        snap("voice_engine_sheet_light")
    }

    /** 轻量版还没装语音包：引擎弹层底栏右侧给出「安装离线语音」。 Lite: the sheet footer offers installing. */
    @Test fun voiceEngineSheetLite() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        engines.plugins = listOf(com.weavetext.ime.voice.VoicePlugin("weave.system", "系统语音识别", "使用手机自带的语音识别服务。", "", null, emptyList()))
        com.weavetext.ime.models.AsrRuntime.bundled = false
        try {
            val (k, _) = keyboard(false)
            k.showPanel("voice")
            k.showEngineSheet()
            snap("voice_engine_sheet_lite_light")
        } finally {
            com.weavetext.ime.models.AsrRuntime.bundled = com.weavetext.ime.BuildConfig.LOCAL_ASR
        }
    }

    @Test fun candidateGrid() {
        val (k, c) = keyboard(false)
        c.previewState(composing(cands = nihao + listOf("你好吗", "你好啊", "拟", "昵称", "泥土", "你们好").map { Candidate(it, "", false) }))
        k.showPanel("grid")
        snap("candidates_expanded_light")
    }

    @Test fun candidateGridDark() {
        val (k, c) = keyboard(true)
        c.previewState(composing())
        k.showPanel("grid")
        snap("candidates_expanded_dark")
    }

    @Test fun layoutPicker() {
        val (k, _) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "pinyin,t9,shuangpin,wubi86,english") }
        k.showPanel("picker")
        snap("layout_picker_light")
    }

    @Test fun layoutPickerDark() {
        val (k, _) = keyboard(true) { putString(WeavePrefs.KEYBOARDS, "pinyin,t9,shuangpin,wubi86,english") }
        k.showPanel("picker")
        snap("layout_picker_dark")
    }

    @Test fun oneHand() {
        val (_, _) = keyboard(false) { putInt(WeavePrefs.ONE_HAND, 2) }
        snap("one_hand_right_light")
    }

    /** 悬浮键盘：透明全屏窗口里的小卡片，App 不被压缩。 Floating card in a transparent full-height window. */
    @Test fun floating() {
        val (k, c) = keyboard(false) { putBoolean(WeavePrefs.FLOATING, true) }
        c.previewState(composing())
        idle()
        snap("floating_light")
        val insets = android.inputmethodservice.InputMethodService.Insets()
        k.computeInsets(insets)
        val v = k.view
        assertEquals(v.height, insets.contentTopInsets)
        assertEquals(v.height, insets.visibleTopInsets)
        val card = insets.touchableRegion.bounds
        assertTrue(card.width() < v.width)
        assertTrue(card.bottom <= v.height && card.top > 0)
    }

    @Test fun floatingDark() {
        keyboard(true) { putBoolean(WeavePrefs.FLOATING, true).putString(WeavePrefs.FLOAT_POS_PORT, "0.1,0.4") }
        snap("floating_dark")
    }

    @Test fun floatingToolbox() {
        val (k, _) = keyboard(false) { putBoolean(WeavePrefs.FLOATING, true) }
        k.onToolbar(0)
        snap("floating_toolbox_light")
    }

    @Test fun floatingResized() {
        val (k, _) = keyboard(false) { putBoolean(WeavePrefs.FLOATING, true) }
        val before = k.cardWidth
        // 重新进入悬浮模式时读取保存的缩放。 The stored scale is read when floating mode is entered.
        WeavePrefs.of(app).edit().putString(WeavePrefs.FLOAT_SIZE_PORT, "0.8").putBoolean(WeavePrefs.FLOATING, false).commit()
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.FLOATING, true).commit()
        idle()
        assertTrue("card shrank: ${k.cardWidth} < $before", k.cardWidth < before * 0.85f)
        snap("floating_small_light")
    }

    @Test fun compactHeight() { keyboard(false) { putInt(WeavePrefs.HEIGHT_LEVEL, 0) }; snap("pinyin_compact_light") }
}
