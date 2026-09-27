package com.weavetext.ime.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.core.EngineSnapshot
import com.weavetext.ime.core.KeyEngine

/** 回车键此刻的语义（决定回车键文字与颜色）。 What Enter does right now. */
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE, PREVIOUS }

/**
 * 键盘界面需要的全部状态。 Everything the keyboard UI renders.
 *
 * @property chinese 中/英状态（中文模式下标点转全角）。 Chinese vs English mode.
 * @property schema 当前中文方案标识。 Current Chinese schema key.
 */
data class ImeState(
    val preedit: String = "",
    val candidates: List<Candidate> = emptyList(),
    val totalCandidates: Int = 0,
    val pinyinOptions: List<String> = emptyList(),
    val composing: Boolean = false,
    val chinese: Boolean = true,
    val schema: String = "pinyin",
    val enterAction: EnterAction = EnterAction.NEWLINE,
    val passwordField: Boolean = false,
    /** 密码框或要求不做个性化学习（IME_FLAG_NO_PERSONALIZED_LEARNING）：不显示/不记录剪贴板。 Private field. */
    val privateField: Boolean = false,
    val engineReady: Boolean = false,
)

/**
 * 按键逻辑：连接界面、内核与编辑器。界面只调用这里的方法、只读 [state]。
 * Key logic between UI, engine and editor. The UI only calls these methods and reads [state].
 *
 * 设计取舍：组合中的拼音**不写入编辑器**（只显示在候选栏上方），只在确定时 commitText。
 * 这样绕开了聊天应用/WebView 等对 setComposingText 支持不一的大量兼容问题。
 * Design choice: the preedit is never written into the editor (shown above candidates only); we
 * only commitText. This sidesteps composing-text quirks in chat apps, WebViews, etc.
 */
class InputController(private val icProvider: () -> InputConnection?) {

    private var engine: KeyEngine? = null
    var state = ImeState()
        private set
    private val listeners = mutableListOf<(ImeState) -> Unit>()
    /** 中文方案（中/英切换时保留）。 Chinese schema kept across 中/英 toggles. */
    private var chineseSchema = "pinyin"
    private var editorInfo: EditorInfo? = null
    /** 当前输入框允许英文联想。 English suggestions allowed in this field. */
    private var englishSuggest = false
    /** 上一次「单词后补空格」的时间，用于双击空格。 When the last word-space was committed. */
    private var lastSpaceAt = 0L
    /**
     * 每次改动内核或编辑器时递增；[undoStamp] 等于它时，最近一次字符输入仍可撤销（上滑/长按替换按下时已输出的字）。
     * Bumped by every engine/editor change; the last char input is undoable while [undoStamp] equals it.
     */
    private var stamp = 0
    private var undoStamp = -1
    /** null = 上次输入进了内核组合；否则为直接上屏的文字。 null = went into the engine; else the committed text. */
    private var undoText: String? = null
    /** 选区与光标前文字的本地镜像，按键路径上少走 IPC。 Local editor mirror that saves IPCs on the key path. */
    val editor = EditorCache()
    /** 编辑框只收按键事件（TYPE_NULL，如终端）。 The field only understands key events (TYPE_NULL). */
    private var keyEventsOnly = false
    private var capsCached = false
    private var capsVersion = -1

    fun addListener(l: (ImeState) -> Unit) { listeners += l; l(state) }
    fun removeListener(l: (ImeState) -> Unit) { listeners -= l }

    fun attachEngine(e: KeyEngine) {
        engine = e
        e.setSchema(chineseSchema)
        syncClock(e)
        update { it.copy(engineReady = true, schema = chineseSchema) }
    }

    fun detachEngine(): KeyEngine? = engine.also { engine = null }

    // ---------------------------------------------------------------- lifecycle

    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        editorInfo = info
        engine?.let(::syncClock)
        editor.reset(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
        keyEventsOnly = info == null || info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        val e = engine
        if (!restarting) {
            e?.clear()
            e?.setContext(null)
        }
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noLearn = password || (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        e?.setLearning(!noLearn)
        val urlOrEmail = cls == InputType.TYPE_CLASS_TEXT && variation in LATIN_VARIATIONS
        val noSuggest = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        englishSuggest = cls == InputType.TYPE_CLASS_TEXT && !password && !urlOrEmail && !noSuggest
        val chinese = !password && !urlOrEmail
        applyMode(chinese)
        update {
            it.copy(
                enterAction = enterActionOf(info),
                passwordField = password,
                privateField = noLearn,
                chinese = chinese,
            )
        }
        refresh()
    }

    /** 编辑器回报的选区变化。 Selection update from the editor. */
    fun onSelectionUpdate(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        // 光标被挪到别处（点了别的位置、粘贴…）：联想词对应的上文已经不对了，收起并断开连续造词。
        // The cursor moved elsewhere (a tap, a paste…): the predictions no longer fit, dismiss them and break the chain.
        if (editor.onUpdate(selStart, selEnd, candidatesStart, candidatesEnd)) {
            val e = engine ?: return
            e.breakChain()
            if (!state.composing && state.candidates.isNotEmpty()) {
                e.clear()
                refresh()
            }
        }
    }

    fun onFinishInput() {
        engine?.let { it.clear(); it.flush() }
        refresh()
    }

    // ---------------------------------------------------------------- keys

    /**
     * 字符键；[near] / [closeness] 为触点靠近交界时另一侧的字母与贴近度（0 = 无），内核用来纠正误触。
     * A character key; [near] / [closeness] are the letter across a nearby key border and how close the tap was
     * (0 = none), used by the engine to fix taps on the neighbouring key.
     */
    fun onChar(codePoint: Int, near: Int = 0, closeness: Float = 0f) {
        val e = engine
        val ch = codePoint.toChar()
        lastSpaceAt = 0L
        // 中文方案，或普通文本框里的英文联想，都交给内核组合。 Chinese, or English with suggestions.
        if (e != null && (state.chinese || englishSuggest) &&
            (if (near != 0 && state.chinese) e.inputKey(codePoint, near, closeness) else e.inputChar(codePoint))
        ) {
            stamp++
            refresh()
            markUndo(null)
            return
        }
        // 组合中的首选先上屏，并刷新状态（候选栏收起、手写墨迹清掉）。 Commit the pending top candidate and refresh.
        e?.commitFirst()
        refresh()
        val text = if (state.chinese) fullWidthPunct(ch) ?: ch.toString() else ch.toString()
        commit(text)
        markUndo(text)
    }

    /** 直接上屏一段文字（符号面板、表情、剪贴板）。 Commit literal text (symbols, emoji, clips). */
    fun onText(text: String) {
        val e = engine
        // 拼音 v 模式（v1234、v12*3）：数字与运算符继续进组合串。 Pinyin v mode: digits and operators keep composing.
        if (e != null && text.length == 1 && state.chinese && state.schema == "pinyin" && e.isComposing() && e.inputChar(text[0].code)) {
            stamp++
            refresh()
            markUndo(null)
            return
        }
        e?.commitFirst()
        refresh()
        if (text.length == 1 && pairText(text[0])) return
        commit(text)
        markUndo(text)
        if (text == "=" || text == "＝") offerCalc()
    }

    /** 成对符号开关（设置里可关）。 Paired-punctuation switch. */
    var autoPair = true

    /**
     * 成对符号：输入「“（《【」等左半边时补上右半边、光标停在中间；紧接着输入右半边时只把光标移过去，不重复插入。
     * Paired punctuation: an opening mark also inserts its closing mark with the cursor between; typing the closing
     * mark right after just steps over it.
     */
    private fun pairText(c: Char): Boolean {
        if (!autoPair || keyEventsOnly || isSensitiveField) return false
        val ic = ic() ?: return false
        PAIRS[c]?.let { close ->
            if (!ic.getSelectedText(0).isNullOrEmpty()) return false
            ic.beginBatchEdit()
            ic.commitText(c.toString(), 1)
            ic.commitText(close.toString(), 0)
            ic.endBatchEdit()
            editor.invalidate()
            markUndo(null)
            return true
        }
        if (c in CLOSERS && ic.getTextAfterCursor(1, 0)?.firstOrNull() == c) {
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            editor.invalidate()
            return true
        }
        return false
    }

    /** 联想词还在候选栏时收起（空格、回车等不选联想的操作）。 Dismiss predictions on space, enter and similar. */
    private fun dismissPredictions() {
        val e = engine ?: return
        if (!state.composing && state.candidates.isNotEmpty()) {
            e.clear()
            refresh()
        }
    }

    /** 敲下等号时，光标前是算式就把结果当候选给出。 After typing "=", offer the result when an expression precedes it. */
    var onCalc: ((List<String>) -> Unit)? = null

    private fun offerCalc() {
        val e = engine ?: return
        val cb = onCalc ?: return
        val before = editor.textBefore(64) ?: ic()?.getTextBeforeCursor(64, 0)?.toString() ?: return
        val body = before.dropLast(1)
        val expr = body.takeLastWhile { it.isDigit() || it in CALC_CHARS }.trimStart { it == ')' || it == '）' }
        if (expr.length < 3) return
        val r = e.evaluate(expr) ?: return
        cb(listOf(r))
    }

    /** 时区可能变化：每次开始输入时同步给内核。 Keep the engine's UTC offset current. */
    private fun syncClock(e: KeyEngine) {
        e.setUtcOffset(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
    }

    private fun markUndo(text: String?) {
        undoText = text
        undoStamp = stamp
    }

    /**
     * 撤销最近一次 [onChar] / [onText]（其后没有别的改动时）：组合中退一格，直接上屏的删掉。返回是否撤销。
     * Undo the latest [onChar] / [onText] if nothing changed since: one engine backspace, or delete the committed text.
     */
    fun undoLastInput(): Boolean {
        if (undoStamp != stamp) return false
        undoStamp = -1
        val t = undoText
        if (t == null) {
            val e = engine ?: return false
            if (!e.backspace()) return false
            refresh()
            return true
        }
        if (t.isEmpty()) return true
        val ic = ic(modeled = true) ?: return false
        if ((editor.textBefore(t.length) ?: ic.getTextBeforeCursor(t.length, 0)?.toString()) != t) return false
        ic.deleteSurroundingText(t.length, 0)
        editor.onDeleteBefore(t.length)
        return true
    }

    fun onBackspace() {
        val e = engine
        if (e != null && e.backspace()) {
            refresh()
            return
        }
        // 内核这时收起了联想词：界面同步收起，旧候选不能留着（点了也不会上屏）。
        // The engine just dropped its predictions: drop them in the UI too, stale ones would do nothing when tapped.
        if (e != null && !state.composing && state.candidates.isNotEmpty()) refresh()
        val ic = ic(modeled = true) ?: return
        if (!keyEventsOnly && editor.selectionKnown) {
            // 已知选区：一次 IPC 删掉选区或光标前一个字形。 Known selection: one IPC per delete.
            if (!editor.selectionEmpty) {
                ic.commitText("", 1)
                editor.onCommit("")
                return
            }
            if (editor.textBefore(1) == null) ic.getTextBeforeCursor(EditorCache.FILL, 0)?.let { editor.fill(it, EditorCache.FILL) }
            val n = editor.lastClusterLength()
            if (n > 0) {
                ic.deleteSurroundingText(n, 0)
                editor.onDeleteBefore(n)
                return
            }
        }
        // 不回报选区的编辑器、终端、文本开头：按原来的方式发删除键。 Fallback: query, then a DEL key event.
        editor.invalidate()
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendKey(KeyEvent.KEYCODE_DEL)
        }
    }

    fun onSpace() {
        val e = engine
        dismissPredictions()
        if (e != null && e.isComposing()) {
            e.select(0)
            refresh()
            // 英文：上屏单词后补一个空格。 English: a space follows the committed word.
            if (!state.chinese) commitSpaceAfterWord()
            return
        }
        // 英文双击空格 → ". "。 English double-space → ". ".
        val now = android.os.SystemClock.uptimeMillis()
        if (!state.chinese && lastSpaceAt != 0L && now - lastSpaceAt < DOUBLE_SPACE_MS) {
            lastSpaceAt = 0L
            val ic = ic(modeled = true)
            if (ic != null && (editor.textBefore(1) ?: ic.getTextBeforeCursor(1, 0)?.toString()) == " ") {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                editor.onDeleteBefore(1)
                ic.commitText(". ", 1)
                editor.onCommit(". ")
                ic.endBatchEdit()
                return
            }
        }
        commit(" ")
        lastSpaceAt = 0L
    }

    private fun commitSpaceAfterWord() {
        commit(" ")
        lastSpaceAt = android.os.SystemClock.uptimeMillis()
    }

    fun onEnter() {
        val e = engine
        lastSpaceAt = 0L
        dismissPredictions()
        if (e != null && e.isComposing()) {
            // 英文候选首项即原样输入（保留撇号）；手写没有输入码，上屏首选。
            // English: the first candidate is the typed word; handwriting has no raw keys, so the top candidate goes.
            if (state.chinese && state.schema == "hand") e.commitFirst() else if (state.chinese) e.commitRaw() else e.select(0)
            refresh()
            return
        }
        val ic = ic() ?: return
        val info = editorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (state.enterAction != EnterAction.NEWLINE && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun onCandidate(index: Int) {
        engine?.select(index)
        refresh()
        // 英文：选词上屏后补空格。 English: a space follows a chosen suggestion.
        if (!state.chinese && !state.composing) commitSpaceAfterWord()
    }

    /**
     * 手写：一笔写完后交给内核这个字的全部笔画，候选随快照更新。没有内核时不改动状态。
     * Handwriting: after each stroke the engine gets all strokes of the char; candidates follow via the snapshot.
     */
    fun onHandStrokes(strokes: List<FloatArray>) {
        val e = engine ?: return
        lastSpaceAt = 0L
        e.handInput(strokes)
        stamp++
        refresh()
    }

    /** 上屏当前首选（手写停笔后再落笔）。 Commit the top candidate (handwriting: pen down after a pause). */
    fun commitFirst() {
        val e = engine ?: return
        if (!e.isComposing()) return
        e.commitFirst()
        refresh()
    }

    fun onPinyinOption(index: Int) {
        engine?.selectPinyin(index)
        refresh()
    }

    /** 长按删除用户词。 Forget a learned candidate. */
    fun onForgetCandidate(index: Int): Boolean = (engine?.forget(index) == true).also { refresh() }

    /** 候选展开时分页加载。 Load more candidates for the expanded grid. */
    fun loadCandidates(offset: Int, limit: Int): List<Candidate> = engine?.candidates(offset, limit).orEmpty()

    fun toggleChinese() {
        engine?.commitRaw()
        drainCommit()
        applyMode(!state.chinese)
        refresh()
    }

    /** 切换中文方案并保持中文模式。 Switch the Chinese schema. */
    fun setSchema(key: String): Boolean {
        val e = engine ?: return false
        e.commitRaw()
        drainCommit()
        if (!e.setSchema(key)) return false
        chineseSchema = key
        update { it.copy(chinese = true, schema = key) }
        refresh()
        return true
    }

    fun setOption(key: String, value: Boolean) { engine?.setOption(key, value) }

    /**
     * 设定中文方案但不改变中/英状态（设置同步、内核未就绪时用）。
     * Set the preferred Chinese schema without touching 中/英 mode (settings sync, engine not ready).
     */
    fun setPreferredSchema(key: String): Boolean {
        if (engine != null && state.chinese) return setSchema(key)
        chineseSchema = key
        update { it.copy(schema = key) }
        return true
    }

    /** 光标左右移动（空格滑动、光标面板）。 Move the cursor. */
    fun moveCursor(dx: Int) {
        val code = if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(dx)) { sendKey(code) }
    }

    fun sendKey(code: Int, meta: Int = 0) {
        val ic = ic() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** 清空组合（收起键盘等）。 Drop the composition. */
    fun reset() {
        engine?.clear()
        refresh()
    }

    // ---------------------------------------------------------------- editing helpers (UI panels)

    /** 当前编辑框信息（数字键盘、自动大写判断用）。 Current editor info. */
    val currentEditorInfo: EditorInfo? get() = editorInfo

    /** 编辑框是否数字/电话类。 Number or phone field. */
    fun numericFieldKind(): Int {
        val cls = (editorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        return when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> 1
            InputType.TYPE_CLASS_PHONE -> 2
            else -> 0
        }
    }

    fun hasSelection(): Boolean =
        if (editor.selectionKnown) !editor.selectionEmpty else !icProvider()?.getSelectedText(0).isNullOrEmpty()

    /** 复制/剪切/粘贴/全选。 Copy / cut / paste / select all. */
    fun contextMenuAction(id: Int) {
        val ic = ic() ?: return
        if (id == android.R.id.paste) {
            engine?.commitFirst()
            drainCommit()
            refresh()
        }
        ic.performContextMenuAction(id)
    }

    fun deleteForward() = sendKey(KeyEvent.KEYCODE_FORWARD_DEL)

    /**
     * 删除光标前全部文本（上限 [limit]），返回被删内容供撤销；有组合串时只清空组合串并返回 null。
     * Delete everything before the cursor (capped); returns the removed text for undo.
     */
    fun clearBeforeCursor(limit: Int = 2000): String? {
        val e = engine
        if (e != null && e.isComposing()) {
            e.clear()
            refresh()
            return null
        }
        val ic = ic() ?: return null
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
            return sel.toString()
        }
        val before = ic.getTextBeforeCursor(limit, 0)?.toString().orEmpty()
        if (before.isEmpty()) return null
        ic.deleteSurroundingText(before.length, 0)
        return before
    }

    /** 按词删除（长按删除加速后）。 Delete one word/run before the cursor. */
    fun deleteWordBefore() {
        val e = engine
        if (e != null && e.backspace()) {
            refresh()
            return
        }
        if (e != null && !state.composing && state.candidates.isNotEmpty()) refresh()
        val ic = ic(modeled = true) ?: return
        val before = editor.textBefore(64) ?: ic.getTextBeforeCursor(64, 0)?.toString().orEmpty().also { editor.fill(it, 64) }
        if (before.isEmpty()) { sendKey(KeyEvent.KEYCODE_DEL); return }
        var i = before.length
        // 先跳过尾部空白，再删同类字符（字母数字一段 / 汉字一段 / 单个标点）。
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0) {
            val cls = charClass(before[i - 1])
            if (cls == 2) i-- else while (i > 0 && charClass(before[i - 1]) == cls) i--
        }
        val n = (before.length - i).coerceAtLeast(1)
        ic.deleteSurroundingText(n, 0)
        editor.onDeleteBefore(n)
    }

    private fun charClass(c: Char): Int = when {
        Character.isIdeographic(c.code) -> 1
        c.isLetterOrDigit() -> 0
        else -> 2
    }

    /**
     * 句首自动大写（英文）。优先用本地镜像计算；编辑器没变时复用上次结果，不再每键查询。
     * Auto-capitalise at sentence start: computed from the local mirror, or reused while the editor is
     * unchanged, instead of an IPC per key.
     */
    fun capsModeActive(): Boolean {
        val info = editorInfo ?: return false
        val req = info.inputType and CAPS_FLAGS
        if (req == 0) return false
        if (req and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        editor.capsMode(req)?.let { return it != 0 }
        if (capsVersion == editor.version) return capsCached
        val ic = icProvider() ?: return false
        // 读一次光标前文字，之后的按键都在本地算。 One read fills the mirror for the following keys.
        if (editor.selectionEmpty) {
            ic.getTextBeforeCursor(EditorCache.FILL, 0)?.let { editor.fill(it, EditorCache.FILL) }
            editor.capsMode(req)?.let { return it != 0 }
        }
        capsCached = ic.getCursorCapsMode(info.inputType) != 0
        capsVersion = editor.version
        return capsCached
    }

    /** 英文双击空格 → ". "。 Double-space period; true if applied. */
    fun doubleSpacePeriod(): Boolean {
        val ic = ic(modeled = true) ?: return false
        val before = editor.textBefore(2) ?: ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
        if (before.length < 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        ic.deleteSurroundingText(1, 0)
        editor.onDeleteBefore(1)
        ic.commitText(". ", 1)
        editor.onCommit(". ")
        return true
    }

    /** 成对符号：插入并把光标放中间。 Paired symbols with the cursor placed inside. */
    fun onPairedText(open: String, close: String) {
        onText(open + close)
        moveCursor(-close.length)
    }

    // ---------------------------------------------------------------- cursor panel (02 §9)

    /**
     * 方向键；[select] 为真时扩选（附带 Shift）。组合中先上屏原始字母，避免方向键打断内核状态。
     * Arrow key; extends the selection with Shift when [select].
     */
    fun cursorArrow(keyCode: Int, select: Boolean) {
        commitRawIfComposing()
        sendKey(keyCode, if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
    }

    /** 按词移动（Ctrl+←/→），扩选模式下连带选中。 Move by word (Ctrl+←/→), extending the selection when selecting. */
    fun cursorWord(right: Boolean, select: Boolean) {
        commitRawIfComposing()
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (select) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        sendKey(if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, meta)
    }

    /**
     * 撤销 / 重做编辑器里的修改：先走编辑器的菜单动作，不支持时退回 Ctrl+Z / Ctrl+Shift+Z。
     * Undo / redo in the editor: its context-menu action first, falling back to Ctrl+Z / Ctrl+Shift+Z.
     */
    fun undoRedo(redo: Boolean) {
        commitRawIfComposing()
        val ic = ic() ?: return
        if (ic.performContextMenuAction(if (redo) android.R.id.redo else android.R.id.undo)) return
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (redo) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        sendKey(KeyEvent.KEYCODE_Z, meta)
    }

    /**
     * 移到整段开头/末尾；扩选模式下保留锚点。优先用 ExtractedText 精确设置选区，拿不到时退回 Ctrl+Home/End。
     * Move to the very start/end of the field (keeps the anchor when selecting).
     */
    fun cursorToEdge(end: Boolean, select: Boolean) {
        commitRawIfComposing()
        val ic = ic() ?: return
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (et?.text == null) {
            var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (select) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            sendKey(if (end) KeyEvent.KEYCODE_MOVE_END else KeyEvent.KEYCODE_MOVE_HOME, meta)
            return
        }
        val target = if (end) et.startOffset + et.text.length else 0
        if (select) {
            // selectionStart 即锚点（可能大于 selectionEnd）。 selectionStart is the anchor.
            ic.setSelection(et.startOffset + et.selectionStart, target)
        } else {
            ic.setSelection(target, target)
        }
    }

    /** Tab 键输入 \t。 Insert a tab. */
    fun onTab() = onText("\t")

    /** 组合中时原样上屏字母（实体键盘的方向键、快捷键前）。 Commit the raw letters if composing. */
    fun commitRaw() = commitRawIfComposing()

    private fun commitRawIfComposing() {
        val e = engine ?: return
        if (e.isComposing()) { e.commitRaw(); refresh() }
    }

    /**
     * 当前编辑框是否敏感（密码框或禁止个性化学习），剪贴板不记录、不显示历史。
     * Password / no-personalized-learning field: clips are neither recorded nor shown.
     */
    val isSensitiveField: Boolean get() = state.privateField

    /**
     * 仅供截图测试 / 设计预览：直接替换界面状态（不经过内核）。
     * For screenshot tests & previews only: replace the UI state without the engine.
     */
    @androidx.annotation.VisibleForTesting
    fun previewState(s: ImeState) = update { s }

    // ---------------------------------------------------------------- voice (composing text allowed here)

    /** 语音中间结果以 composing 文本显示（带下划线）。 Voice interim result as composing text. */
    fun voicePartial(text: String) {
        val ic = ic() ?: return
        if (engine?.isComposing() == true) { engine?.commitFirst(); drainCommit(); refresh() }
        ic.setComposingText(text, 1)
    }

    /** 语音最终结果上屏（替换 composing）。 Commit a final voice segment. */
    fun voiceFinal(text: String) {
        val ic = ic() ?: return
        if (text.isEmpty()) ic.finishComposingText() else ic.commitText(text, 1)
    }

    /** 插件事后修正已上屏文本。 Post-hoc correction of committed voice text. */
    fun voiceReplace(old: String, new: String) {
        val ic = ic() ?: return
        val before = ic.getTextBeforeCursor(old.length, 0)?.toString() ?: return
        if (before == old) {
            ic.deleteSurroundingText(old.length, 0)
            ic.commitText(new, 1)
        }
    }

    /** 取消语音：丢弃 composing。 Drop the voice composing text. */
    fun voiceCancel() {
        val ic = ic() ?: return
        ic.setComposingText("", 1)
        ic.finishComposingText()
    }

    // ---------------------------------------------------------------- internals

    private fun applyMode(chinese: Boolean) {
        val e = engine
        if (e != null) {
            e.setSchema(if (chinese) chineseSchema else "english")
        }
        update { it.copy(chinese = chinese) }
    }

    /** 输入法直接写的字（标点、空格、符号），不是内核上屏：之后的退格不撤销学习。 Written directly, not by the engine. */
    private fun commit(text: String) {
        if (text.isEmpty()) return
        engine?.breakChain()
        write(text)
    }

    private fun write(text: String) {
        if (text.isEmpty()) return
        val ic = ic(modeled = true) ?: return
        ic.commitText(text, 1)
        editor.onCommit(text)
    }

    /**
     * 要改动编辑器时取连接（使撤销失效）；[modeled] 为假时调用方做的改动不在本地镜像里，镜像等下一次回报。
     * Connection for an edit; invalidates the pending undo. Unless [modeled], the edit isn't mirrored
     * locally and the mirror waits for the editor's next report.
     */
    private fun ic(modeled: Boolean = false): InputConnection? {
        stamp++
        if (!modeled) editor.invalidate()
        return icProvider()
    }

    private fun drainCommit(): EngineSnapshot? {
        stamp++
        val snap = engine?.snapshot() ?: return null
        if (snap.commit.isNotEmpty()) write(snap.commit)
        return snap
    }

    private fun refresh() {
        val snap = drainCommit() ?: EngineSnapshot.EMPTY
        update {
            it.copy(
                preedit = snap.preedit,
                candidates = snap.candidates,
                totalCandidates = snap.totalCandidates,
                pinyinOptions = snap.pinyinOptions,
                composing = snap.composing,
            )
        }
    }

    private inline fun update(f: (ImeState) -> ImeState) {
        val next = f(state)
        if (next != state) {
            state = next
            listeners.forEach { it(next) }
        }
    }

    companion object {
        /** 自动成对的中文标点。 Chinese punctuation that pairs automatically. */
        private val PAIRS = mapOf('“' to '”', '‘' to '’', '（' to '）', '《' to '》', '【' to '】', '「' to '」', '『' to '』', '〈' to '〉')
        private val CLOSERS = PAIRS.values.toSet()
        /** 算式里可出现的非数字字符。 Non-digit characters allowed in an expression. */
        private const val CALC_CHARS = ".+-*/×÷%^()（）"
        private const val DOUBLE_SPACE_MS = 450L
        private const val CAPS_FLAGS = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        private val LATIN_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )

        fun enterActionOf(info: EditorInfo?): EnterAction {
            val opts = info?.imeOptions ?: return EnterAction.NEWLINE
            if (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
            val multiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            return when (opts and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                EditorInfo.IME_ACTION_GO -> EnterAction.GO
                EditorInfo.IME_ACTION_NEXT -> if (multiLine) EnterAction.NEWLINE else EnterAction.NEXT
                EditorInfo.IME_ACTION_DONE -> if (multiLine) EnterAction.NEWLINE else EnterAction.DONE
                EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
                else -> EnterAction.NEWLINE
            }
        }

        /** 中文模式下的标点。 Chinese punctuation for ASCII keys. */
        fun fullWidthPunct(c: Char): String? = when (c) {
            ',' -> "，"
            '.' -> "。"
            '?' -> "？"
            '!' -> "！"
            ':' -> "："
            ';' -> "；"
            '(' -> "（"
            ')' -> "）"
            '\\' -> "、"
            '^' -> "……"
            '_' -> "——"
            '<' -> "《"
            '>' -> "》"
            '[' -> "【"
            ']' -> "】"
            '~' -> "～"
            '$' -> "￥"
            else -> null
        }
    }
}
