package com.weavetext.ime.ime

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.core.EngineSnapshot
import com.weavetext.ime.core.KeyEngine
import com.weavetext.ime.core.PreeditMark

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
    /** 预编辑里的纠错标记。 Correction marks in the preedit. */
    val preeditMarks: List<PreeditMark> = emptyList(),
    val candidates: List<Candidate> = emptyList(),
    val totalCandidates: Int = 0,
    val pinyinOptions: List<String> = emptyList(),
    val composing: Boolean = false,
    val handRecognizing: Boolean = false,
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
    private var inputEpoch = 0L
    private var translationRevision = 0L

    /** A translation belongs to the editor and selection where its source was read. */
    class TranslationTarget internal constructor(
        internal val epoch: Long,
        internal val revision: Long,
        internal val connection: InputConnection,
        internal val start: Int,
        internal val end: Int,
        val selectedText: String,
        internal val before: String,
        internal val after: String,
    )

    fun captureTranslationTarget(): TranslationTarget? {
        if (isSensitiveField) return null
        // Drop unconfirmed typing and pending handwriting before binding the target.
        discardHand()
        endReconversion()
        engineRef?.clear()
        pendingKeys.clear()
        refresh()
        val connection = icProvider() ?: return null
        return readTranslationTarget(connection)
    }

    private fun readTranslationTarget(connection: InputConnection): TranslationTarget? = runCatching {
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 128 }, 0)
        val start = extracted?.let { it.startOffset + it.selectionStart } ?: editor.selStart
        val end = extracted?.let { it.startOffset + it.selectionEnd } ?: editor.selEnd
        if (start < 0 || end < 0) return null
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (selected.length > com.weavetext.ime.translate.TranslationRequest.MAX_TEXT_CHARS ||
            selected.length != kotlin.math.abs(end - start)) return null
        val before = connection.getTextBeforeCursor(64, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(64, 0)?.toString() ?: return null
        TranslationTarget(inputEpoch, translationRevision, connection, start, end, selected, before, after)
    }.getOrNull()

    fun writeTranslation(target: TranslationTarget, text: String, replace: Boolean): Boolean {
        if (isSensitiveField || text.isBlank() || target.epoch != inputEpoch ||
            target.revision != translationRevision || state.composing || handJob != null ||
            handCommits.isNotEmpty() || icProvider() !== target.connection) return false
        val now = readTranslationTarget(target.connection) ?: return false
        if (now.start != target.start || now.end != target.end || now.selectedText != target.selectedText ||
            now.before != target.before || now.after != target.after) return false
        if (replace && target.selectedText.isEmpty()) return false
        val connection = target.connection
        return runCatching {
            connection.beginBatchEdit()
            try {
                // Insert follows the original selection; it never replaces it.
                if (!replace && !connection.setSelection(maxOf(target.start, target.end), maxOf(target.start, target.end))) return false
                val ok = connection.commitText(text, 1)
                if (ok) {
                    translationRevision++
                    stamp++
                    editor.invalidate()
                    engineRef?.breakChain()
                    engineRef?.setContext(null)
                    dismissPredictions()
                }
                ok
            } finally { connection.endBatchEdit() }
        }.getOrDefault(false)
    }
    private var reconversion: Triple<String,Int,Int>? = null
    private var reconversionSchema = "pinyin"
    fun feature(command: org.json.JSONObject): org.json.JSONObject = runCatching { org.json.JSONObject(engine?.features(command.toString()) ?: "{}") }.getOrDefault(org.json.JSONObject())
    fun reselect(): Boolean {
        if (isSensitiveField || state.composing) return false
        val text=icProvider()?.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() } ?: return false
        reconversionSchema=if(state.chinese) chineseSchema else "english"
        if (!feature(org.json.JSONObject().put("op","reconvert").put("text",text)).optBoolean("ok")) return false
        reconversion=Triple(text,editor.selStart,editor.selEnd)
        refresh(); return true
    }
    /**
     * 放弃重选：清掉组合、恢复原方案。切换中/英、换方案、方向键、换输入框前都要先走这里，
     * 否则重选的拼音会被当作原文上屏，盖掉用户选中的文字。
     * Abandon reconversion: drop the composition and restore the schema. Mode/schema switches, arrow keys and new
     * editors must come through here first, or the reconversion pinyin would be committed over the selection.
     */
    private fun endReconversion() {
        if (reconversion == null) return
        engine?.clear(); engine?.setSchema(reconversionSchema); reconversion = null
    }
    fun candidatePolicy(index: Int, text: String, mode: String? = null): String {
        val command=org.json.JSONObject().put("op","policy").put("index",index).put("text",text)
        if (mode!=null) command.put("mode",mode)
        val result=feature(command)
        if (mode!=null) refresh()
        return result.optString("mode")
    }
    fun onContent(uri: android.net.Uri, mime: String, name: String): Boolean {
        val editor = editorInfo ?: return false
        if (isSensitiveField) return false
        val supported = androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(editor)
        if (!supported.any { android.content.ClipDescription.compareMimeTypes(mime, it) }) return false
        commitRawIfComposing()
        return runCatching {
            androidx.core.view.inputmethod.InputConnectionCompat.commitContent(icProvider() ?: return false, editor,
                androidx.core.view.inputmethod.InputContentInfoCompat(uri, android.content.ClipDescription(name, arrayOf(mime)), null),
                androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
    }

    /**
     * 内核。读取时先交回还在后台识别的手写结果，所以任何内核操作看到的都是最新的笔画与候选。
     * The engine. Reading it first settles a handwriting recognition still running in the background, so every
     * engine call sees the latest strokes and candidates.
     */
    private var engine: KeyEngine?
        get() { settleHand(); return engineRef }
        set(v) { discardHand(); engineRef = v }
    private var engineRef: KeyEngine? = null

    /**
     * 手写识别的后台线程（null = 在主线程同步识别，测试用）与把结果送回主线程的方法。
     * Background thread for handwriting recognition (null = synchronous on the caller, for tests), and how the
     * result is posted back to the main thread.
     */
    var handWorker: java.util.concurrent.ExecutorService? = null
    var postMain: (Runnable) -> Unit = { it.run() }
    private var handJob: HandJob? = null
    private var handGeneration = 0L
    private val handCommits = LinkedHashSet<HandJob>()
    private var applyingHandResult = false
    private class HandJob(val engine: KeyEngine, val strokes: List<FloatArray>, val result: java.util.concurrent.Future<IntArray?>, val generation: Long)

    private fun discardHand() {
        handGeneration++
        handJob?.result?.cancel(false)
        handJob = null
        handCommits.forEach { it.result.cancel(false) }
        handCommits.clear()
    }
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
    /** 网址 / 邮箱框：不做英文句号快捷方式，方向键保持按键事件。 URL / e-mail field. */
    private var latinField = false
    /** 网页里的输入框（WebView / 浏览器）。 A field inside a web page. */
    private var webField = false
    /** 上一个输入框的类别（决定重新开始输入时是否保留中/英）。 Kind of the last field, see [onStartInput]. */
    private var fieldKind = -1
    private var capsCached = false
    private var capsVersion = -1
    /** [stamp] 等于它时，光标前的空格是选英文词后自动补的。 While equal to [stamp], the space before the cursor was auto-added. */

    /**
     * 内核还没加载完时按下的键（冷启动时进程刚被拉起）：先记下，内核就绪后按原顺序重放，不把拼音字母直接上屏。
     * Keys pressed before the engine has loaded (a cold start): kept and replayed in order once it is ready,
     * instead of committing the pinyin letters as they are.
     */
    private val pendingKeys = ArrayList<PendingKey>()
    private var replaying = false
    /** 上次加载内核失败（重新加载成功前按键照没有内核时处理）。 The last engine load failed. */
    private var engineFailed = false

    fun addListener(l: (ImeState) -> Unit) { listeners += l; l(state) }
    fun removeListener(l: (ImeState) -> Unit) { listeners -= l }

    /**
     * 内核加载完成。它可能晚于输入框开始：按当前输入框补上学习开关与中/英方案，再重放之前按下的键。
     * The engine is ready. It may arrive after the field started: apply the field's learning switch and
     * Chinese/English schema, then replay the keys pressed meanwhile.
     */
    fun attachEngine(e: KeyEngine) {
        engine = e
        engineFailed = false
        syncClock(e)
        e.setLearning(!state.privateField)
        // 首选方案缺词库时退回全拼，只公布内核接受的方案。 Fall back to pinyin when the schema's lexicon is missing.
        if (!e.setSchema(chineseSchema) && chineseSchema != "pinyin" && e.setSchema("pinyin")) chineseSchema = "pinyin"
        if (!state.chinese) e.setSchema("english")
        e.clear()
        e.setContext(null)
        update { it.copy(engineReady = true, schema = chineseSchema) }
        refresh()
        replayPending()
    }

    /**
     * 内核加载失败：之前记下的键按没有内核时的方式输出（原样上屏），之后的键也一样，直到重新加载成功。
     * The engine failed to load: the kept keys go out the engine-less way (committed as typed), as will later
     * keys until a reload succeeds.
     */
    fun engineUnavailable() {
        if (engine != null) return
        engineFailed = true
        replayPending()
    }

    fun detachEngine(): KeyEngine? = engine.also { engine = null }

    // ---------------------------------------------------------------- lifecycle

    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        inputEpoch++
        translationRevision++
        if (!restarting) discardHand()
        endReconversion()
        editorInfo = info
        engine?.let(::syncClock)
        editor.reset(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
        val e = engine
        if (!restarting) {
            e?.clear()
            e?.setContext(null)
            pendingKeys.clear()
        }
        applyEditorInfo(info, restarting)
        ClipPrivacy.privateField = isSensitiveField
        refresh()
    }

    /**
     * 键盘显示时再读一次 EditorInfo：浏览器等在 onStartInput 与 onStartInputView 之间可能改了它。
     * Re-read EditorInfo when the keyboard shows: browsers and others may change it between onStartInput and
     * onStartInputView.
     */
    fun onStartInputView(info: EditorInfo?) {
        val old = editorInfo
        if (info == null || old == null) return
        if (info.inputType == old.inputType && info.imeOptions == old.imeOptions && info.actionId == old.actionId &&
            info.actionLabel?.toString() == old.actionLabel?.toString()
        ) return
        editorInfo = info
        applyEditorInfo(info, keepMode = true)
        ClipPrivacy.privateField = isSensitiveField
        refresh()
    }

    /**
     * 按输入框类型设定学习开关、中/英、回车键。[keepMode] 为真（同一输入框重新开始，如聊天发送后清空）且输入框类别
     * 没变时保留用户选的中/英。
     * Learning, Chinese/English and the Enter key from the field type. With [keepMode] (the same field
     * restarting, e.g. a chat box cleared after sending) the user's Chinese/English choice is kept unless the kind
     * of field changed.
     */
    private fun applyEditorInfo(info: EditorInfo?, keepMode: Boolean) {
        keyEventsOnly = info == null || info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noLearn = password || (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        engine?.setLearning(!noLearn)
        val urlOrEmail = cls == InputType.TYPE_CLASS_TEXT && variation in LATIN_VARIATIONS
        latinField = urlOrEmail
        webField = cls == InputType.TYPE_CLASS_TEXT && variation in WEB_VARIATIONS
        val noSuggest = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        englishSuggest = cls == InputType.TYPE_CLASS_TEXT && !password && !urlOrEmail && !noSuggest
        // 数字、电话、日期框里没有中文可打：按英文处理，实体键盘的「.」不会变成「。」。
        // Number, phone and date fields have no Chinese to type: treat them as English so a physical "." stays ".".
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        val forceAscii = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_FORCE_ASCII != 0
        val latinOnly = password || urlOrEmail || numeric || forceAscii
        val kind = if (latinOnly) 1 else 0
        val chinese = if (keepMode && kind == fieldKind) state.chinese else !latinOnly
        fieldKind = kind
        if (chinese != state.chinese || !keepMode) applyMode(chinese)
        update {
            it.copy(
                enterAction = enterActionOf(info),
                passwordField = password,
                privateField = noLearn,
                chinese = chinese,
            )
        }
    }

    /** 编辑器回报的选区变化。 Selection update from the editor. */
    fun onSelectionUpdate(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (selStart != editor.selStart || selEnd != editor.selEnd) translationRevision++
        // 光标被挪到别处（点了别的位置、粘贴…）：联想词对应的上文已经不对了，收起并断开连续造词。
        // The cursor moved elsewhere (a tap, a paste…): the predictions no longer fit, dismiss them and break the chain.
        if (editor.onUpdate(selStart, selEnd, candidatesStart, candidatesEnd)) {
            if (!applyingHandResult && (handJob != null || handCommits.isNotEmpty())) {
                discardHand()
                engineRef?.clear()
                refresh()
            }
            val e = engine ?: return
            e.breakChain()
            if (!state.composing && state.candidates.isNotEmpty()) {
                e.clear()
                refresh()
            }
        }
    }

    fun onFinishInput() {
        translationRevision++
        discardHand()
        endReconversion()
        engine?.let { it.clear(); it.flush() }
        pendingKeys.clear()
        lastSpaceAt = 0L
        ClipPrivacy.privateField = false
        refresh()
    }

    // ---------------------------------------------------------------- keys

    /**
     * 字符键；[near] / [closeness] 为触点靠近交界时另一侧的字母与贴近度（0 = 无），内核用来纠正误触。
     * A character key; [near] / [closeness] are the letter across a nearby key border and how close the tap was
     * (0 = none), used by the engine to fix taps on the neighbouring key.
     */
    fun onChar(codePoint: Int, near: Int = 0, closeness: Float = 0f) {
        if (reconversion!=null) {engine?.clear();engine?.setSchema(reconversionSchema);reconversion=null;refresh()}
        if (keep(PendingKey(PendingKey.CHAR, codePoint, near, closeness))) return
        val e = engine
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
        val raw = String(Character.toChars(codePoint))
        val text = if (state.chinese && codePoint < 0x10000) chinesePunct(codePoint.toChar()) ?: raw else raw
        commit(text)
        markUndo(text)
    }

    /**
     * 中文模式下的标点：紧跟在数字后的「. , :」保持半角（3.14、12:30、1,000）。
     * Chinese punctuation, except ". , :" right after a digit stay ASCII (3.14, 12:30, 1,000).
     */
    private fun chinesePunct(c: Char): String? {
        val full = fullWidthPunct(c) ?: return null
        if (c == '.' || c == ',' || c == ':') {
            val before = editor.textBefore(1) ?: icProvider()?.getTextBeforeCursor(1, 0)?.toString()
            if (before != null && before.length == 1 && before[0] in '0'..'9') return null
        }
        return full
    }

    /** 直接上屏一段文字（符号面板、表情、剪贴板）。 Commit literal text (symbols, emoji, clips). */
    fun onText(text: String) {
        if(reconversion!=null){engine?.clear();engine?.setSchema(reconversionSchema);reconversion=null;refresh()}
        if (keep(PendingKey(PendingKey.TEXT, text = text))) return
        val e = engine
        if (text.length==1 && text[0].isUpperCase() && state.chinese && state.schema=="pinyin" && e?.isComposing()==true && e.inputChar(text[0].code)) {refresh();return}
        // 拼音 v 模式（v1234、v12*3）：数字与运算符继续进组合串；字母（实体键盘的大写字母）不进。
        // Pinyin v mode: digits and operators keep composing; letters (uppercase from a physical keyboard) don't.
        if (e != null && text.length == 1 && !text[0].isLetter() && state.chinese && state.schema == "pinyin" && e.isComposing() &&
            e.inputChar(text[0].code)
        ) {
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

    // ---------------------------------------------------------------- keys before the engine is ready

    /** 内核就绪前按下的一个键。 One key pressed before the engine was ready. */
    private class PendingKey(val kind: Int, val code: Int = 0, val near: Int = 0, val closeness: Float = 0f, val text: String? = null) {
        companion object {
            const val CHAR = 0
            const val TEXT = 1
            const val SPACE = 2
            const val ENTER = 3
            const val BACKSPACE = 4
        }
    }

    /**
     * 内核未就绪时记下这个键并返回 true：中文模式下的字母开始记录，记录开始后其余的键也按顺序记下。
     * Keep this key while the engine isn't ready (returns true): a letter in Chinese mode starts keeping, after
     * which every other key is kept too, in order.
     */
    private fun keep(k: PendingKey): Boolean {
        if (engine != null || replaying || engineFailed) return false
        if (pendingKeys.isEmpty()) {
            val letter = k.kind == PendingKey.CHAR && (k.code in 'a'.code..'z'.code || k.code in 'A'.code..'Z'.code)
            if (!letter || !state.chinese) return false
        }
        if (k.kind == PendingKey.BACKSPACE && pendingKeys.lastOrNull()?.kind == PendingKey.CHAR) {
            pendingKeys.removeAt(pendingKeys.size - 1)
        } else if (pendingKeys.size < MAX_PENDING) {
            pendingKeys += k
        }
        showPending()
        return true
    }

    /** 记下的字母显示在候选栏上方，让用户知道键没丢。 Show the kept letters as the preedit so no key looks lost. */
    private fun showPending() {
        val sb = StringBuilder()
        for (k in pendingKeys) if (k.kind == PendingKey.CHAR) sb.appendCodePoint(k.code)
        val text = sb.toString()
        update { it.copy(preedit = text, preeditMarks = emptyList(), candidates = emptyList(), totalCandidates = 0, composing = pendingKeys.isNotEmpty()) }
    }

    private fun replayPending() {
        if (pendingKeys.isEmpty()) return
        val keys = pendingKeys.toList()
        pendingKeys.clear()
        replaying = true
        try {
            for (k in keys) when (k.kind) {
                PendingKey.CHAR -> onChar(k.code, k.near, k.closeness)
                PendingKey.TEXT -> onText(k.text.orEmpty())
                PendingKey.SPACE -> onSpace()
                PendingKey.ENTER -> onEnter()
                PendingKey.BACKSPACE -> onBackspace()
            }
        } finally {
            replaying = false
        }
        refresh()
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
            moveCursor(1)
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
        // 上屏还没回报时向编辑器确认：它可能拒收了（字数已满），这时不能删掉真实的字。
        // Ask the editor while the commit is unconfirmed: it may have been rejected (field full), and then real
        // characters must not be deleted.
        val mirrored = if (editor.commitPending) null else editor.textBefore(t.length)
        if ((mirrored ?: ic.getTextBeforeCursor(t.length, 0)?.toString()) != t) return false
        ic.deleteSurroundingText(t.length, 0)
        editor.onDeleteBefore(t.length)
        return true
    }

    fun onBackspace() {
        if (keep(PendingKey(PendingKey.BACKSPACE))) return
        val e = engine
        if (e != null && e.backspace()) {
            refresh()
            return
        }
        // 内核这时收起了联想词：界面同步收起，旧候选不能留着（点了也不会上屏）。
        // The engine just dropped its predictions: drop them in the UI too, stale ones would do nothing when tapped.
        if (e != null && !state.composing && state.candidates.isNotEmpty()) refresh()
        lastSpaceAt = 0L
        val ic = ic(modeled = true) ?: return
        if (!keyEventsOnly && editor.selectionKnown) {
            // 已知选区：一次 IPC 删掉选区。 Known selection: one IPC deletes it.
            if (!editor.selectionEmpty) {
                ic.commitText("", 1)
                editor.onCommit("")
                return
            }
            // 光标前一个字形用删除键事件删：聊天应用的表情标签、@ 提及、网页编辑器只在按键里整体删除。
            // 镜像按最后一个字形簇同步（编辑器删得不一样时，它的回报会让镜像重置）。
            // The char before the cursor goes with a DEL key event: chat apps' emoji tags and @-mentions and web
            // editors only delete as a unit on the key. The mirror follows the last grapheme cluster (if the editor
            // deleted something else, its report resets the mirror).
            if (editor.needsFill() || editor.commitPending) {
                ic.getTextBeforeCursor(EditorCache.FILL, 0)?.let { editor.fill(it, EditorCache.FILL) }
            }
            val n = editor.lastClusterLength()
            sendKeyTo(ic, KeyEvent.KEYCODE_DEL)
            if (n > 0) editor.onDeleteBefore(n) else editor.invalidate()
            return
        }
        // 不回报选区的编辑器、终端：先查选区，再发删除键。 Fallback: query the selection, then a DEL key event.
        editor.invalidate()
        val sel = if (keyEventsOnly) null else ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendKeyTo(ic, KeyEvent.KEYCODE_DEL)
        }
    }

    /**
     * 空格。英文里两次空格间隔很短、前面是「字母 + 空格」时改成 ". "（[periodShortcut] 为假时不做，如实体键盘）。
     * Space. In English a quick second space after "letter + space" becomes ". " (not with [periodShortcut]
     * off, e.g. on a physical keyboard).
     */
    fun onSpace(periodShortcut: Boolean = true) {
        if (state.chinese && state.schema == "hand" && handJob != null) { commitFirst(); return }
        if(reconversion!=null){onCandidate(0);return}
        if (keep(PendingKey(PendingKey.SPACE))) return
        val e = engine
        dismissPredictions()
        if (e != null && e.isComposing()) {
            e.select(0)
            refresh()
            // This separator was explicitly pressed; choosing a candidate never inserts one.
            if (!state.chinese) {
                commit(" ")
                lastSpaceAt = if (periodShortcut && periodAllowed) android.os.SystemClock.uptimeMillis() else 0L
            }
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (periodShortcut && lastSpaceAt != 0L && now - lastSpaceAt < DOUBLE_SPACE_MS) {
            lastSpaceAt = 0L
            if (doubleSpacePeriod()) return
        }
        commit(" ")
        lastSpaceAt = if (periodShortcut && periodAllowed) now else 0L
    }

    /** 这个输入框可以用双击空格打句号（英文、非密码 / 网址 / 邮箱 / 终端）。 The double-space period applies here. */
    private val periodAllowed get() = !state.chinese && !state.passwordField && !latinField && !keyEventsOnly

    fun onEnter() {
        if (state.chinese && state.schema == "hand" && handJob != null) { commitFirst(); return }
        if(reconversion!=null){onCandidate(0);return}
        if (keep(PendingKey(PendingKey.ENTER))) return
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
            // 自定义回车文字时用输入框给的动作编号。 A custom Enter label comes with its own action id.
            ic.performEditorAction(if (info?.actionLabel != null && info.actionId != 0) info.actionId else action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun onCandidate(index: Int) {
        if (state.handRecognizing) return
        reconversion?.let { old ->
            if (icProvider()?.getSelectedText(0)?.toString()!=old.first || (old.second>=0 && editor.selectionKnown && (editor.selStart!=old.second || editor.selEnd!=old.third))) {
                engine?.clear();engine?.setSchema(reconversionSchema);reconversion=null;refresh();return
            }
        }
        engine?.select(index)
        if(reconversion!=null) engine?.setSchema(reconversionSchema)
        reconversion=null
        refresh()
    }

    /**
     * 手写：一笔写完后交给内核这个字的全部笔画，候选随快照更新。没有内核时不改动状态。
     * Handwriting: after each stroke the engine gets all strokes of the char; candidates follow via the snapshot.
     */
    fun onHandStrokes(strokes: List<FloatArray>) {
        // 新笔画会取代旧任务，不在这里等待上一轮识别；选词、退格等操作仍通过 engine 等待最新任务。
        // New ink supersedes the old job without waiting for it; selections/backspace still settle the latest job.
        val e = engineRef ?: return
        lastSpaceAt = 0L
        val worker = handWorker
        if (worker == null) {
            e.handInput(strokes)
            stamp++
            refresh()
            return
        }
        // 识别放到后台：主线程只画墨迹，下一笔不会卡。结果回来时若已有更新的笔画就丢掉。
        // Recognise in the background so the main thread only draws ink and the next stroke never stutters. A
        // result is dropped if newer strokes arrived meanwhile.
        val copy = strokes.map { it.copyOf() }
        handJob?.result?.cancel(false)
        val job = HandJob(e, copy, worker.submit(java.util.concurrent.Callable { e.handRecognize(copy) }), handGeneration)
        handJob = job
        update { it.copy(handRecognizing = true, composing = true, candidates = emptyList(), totalCandidates = 0, preedit = "") }
        // 单线程执行器：这一步排在识别之后。 Single-thread executor: this runs after the recognition.
        worker.execute { postMain(Runnable { if (job.generation == handGeneration && (handJob === job || job in handCommits)) finishHand(job) }) }
    }

    /** 还有后台识别没交回时，等它算完并交回（最多一次识别的时间）。 Wait for and apply a pending recognition. */
    private fun settleHand() {
        while (handCommits.isNotEmpty()) finishHand(handCommits.first())
        val job = handJob ?: return
        finishHand(job)
    }

    private fun finishHand(job: HandJob) {
        val commit = handCommits.remove(job)
        if (handJob === job) handJob = null
        if (engineRef !== job.engine || job.generation != handGeneration) return
        val cps = try { job.result.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
        val wasApplying = applyingHandResult
        applyingHandResult = true
        try {
            if (cps == null) job.engine.handInput(job.strokes) else job.engine.handApply(job.strokes, cps)
            if (commit) job.engine.select(0)
            stamp++
            refresh()
        } finally { applyingHandResult = wasApplying }
    }

    /** Re-evaluate current ink after a mode switch without waiting for an obsolete recognition. */
    fun setHandLineMode(on: Boolean, strokes: List<FloatArray> = emptyList()) {
        handJob?.result?.cancel(false)
        handJob = null
        engineRef?.features(org.json.JSONObject().put("op", "setHandLine").put("on", on).toString())
        if (strokes.isNotEmpty()) onHandStrokes(strokes) else refresh()
    }

    /** Undo/redo replaces geometry without settling the canceled network pass. */
    fun replaceHandInk(strokes: List<FloatArray>) {
        handJob?.result?.cancel(false)
        handJob = null
        engineRef?.clear()
        if (strokes.isNotEmpty()) onHandStrokes(strokes) else refresh()
    }

    /**
     * 手写停笔后再落笔：上屏首选。与点候选一样保留词链（手写的字也能连成用户词），并给出联想。
     * Handwriting, pen down after a pause: commit the top candidate. Like tapping it, this keeps the word chain
     * (handwritten chars can form user words) and offers predictions.
     */
    fun commitFirst(): Boolean {
        // A pen pause must not block the UI while the network finishes. Keep committed jobs
        // in order and let the next character start with its own strokes immediately.
        handJob?.takeIf { !it.result.isDone }?.let { job ->
            handCommits += job
            handJob = null
            update { it.copy(handRecognizing = true, composing = true, candidates = emptyList(), totalCandidates = 0, preedit = "") }
            return true
        }
        val e = engine ?: return false
        if (!e.isComposing()) return false
        if (state.schema == "hand" && state.candidates.isEmpty()) return false
        if (state.schema == "hand" && state.candidates.isNotEmpty()) e.select(0) else e.commitFirst()
        refresh()
        return true
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
        // 内核未就绪时记下的字母按原样上屏，不带到另一种模式里。 Kept letters go out as typed, not into the other mode.
        if (engine == null) replayPending()
        endReconversion()
        engine?.commitRaw()
        drainCommit()
        applyMode(!state.chinese)
        refresh()
    }

    /** 切换中文方案并保持中文模式。 Switch the Chinese schema. */
    fun setSchema(key: String): Boolean {
        val e = engine ?: return false
        endReconversion()
        e.commitRaw()
        drainCommit()
        if (!e.setSchema(key)) return false
        chineseSchema = key
        update { it.copy(chinese = true, schema = key) }
        refresh()
        return true
    }

    fun setOption(key: String, value: Boolean) { engine?.setOption(key, value) }
    /** Apply changes to the existing composition and notify candidate views immediately. */
    fun refreshEngineOptions() {
        if (engine == null) return
        feature(org.json.JSONObject().put("op", "refreshOptions"))
        refresh()
    }

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

    /**
     * 光标左右移动 [dx] 个字（空格滑动、光标面板）。知道光标位置时直接 setSelection，按字形簇移动、到文本两端就停，
     * 不发方向键：方向键在文本边缘会让焦点跳到别的控件，还会让 App 退出触摸模式。只在不知道位置时（终端、网址栏、
     * 不回报选区的编辑器）才发方向键。
     * Move the cursor by [dx] characters (space-bar slide, cursor panel). With a known cursor position this is a
     * setSelection that steps whole grapheme clusters and stops at either end of the text, without arrow keys:
     * at the text edge an arrow key moves focus to another widget, and it takes the app out of touch mode. Arrow
     * keys are only used when the position is unknown (terminals, URL bars, editors that don't report it).
     */
    fun moveCursor(dx: Int) {
        if (dx == 0) return
        val ic = ic(modeled = true) ?: return
        // 上屏还没回报时光标位置可能不准（编辑器可能拒收了）。 An unconfirmed commit makes the position uncertain.
        if (keyEventsOnly || latinField || !editor.selectionEmpty || editor.commitPending) {
            editor.invalidate()
            val code = if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(dx)) { sendKeyTo(ic, code) }
            return
        }
        val steps = kotlin.math.abs(dx)
        // 每个字形簇最多按 8 个 UTF-16 单元估算读取范围（再多读一些，簇的边界才准）。 Read enough for the clusters.
        val window = (steps * 8 + EditorCache.CLUSTER_LOOKBEHIND).coerceAtMost(MAX_MOVE_READ)
        val pos = editor.selStart
        if (dx < 0) {
            val before = editor.textBefore(window) ?: ic.getTextBeforeCursor(window, 0)?.toString()
            if (before == null) { editor.invalidate(); repeat(steps) { sendKeyTo(ic, KeyEvent.KEYCODE_DPAD_LEFT) }; return }
            var i = before.length
            repeat(steps) { if (i > 0) i = EditorCache.clusterStart(before, i) }
            val n = before.length - i
            // 已在开头：什么都不做（不发方向键，焦点不会跳走）。 Already at the start: do nothing.
            if (n == 0) return
            ic.setSelection(pos - n, pos - n)
            editor.onCursorMove(-n, null)
        } else {
            val after = ic.getTextAfterCursor(window, 0)?.toString()
            if (after == null) { editor.invalidate(); repeat(steps) { sendKeyTo(ic, KeyEvent.KEYCODE_DPAD_RIGHT) }; return }
            val n = clusterPrefix(after, steps)
            if (n == 0) return
            ic.setSelection(pos + n, pos + n)
            editor.onCursorMove(n, after.substring(0, n))
        }
    }

    /** [text] 开头 [count] 个字形簇的长度。 Length of the first [count] grapheme clusters of [text]. */
    private fun clusterPrefix(text: String, count: Int): Int {
        if (text.isEmpty()) return 0
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(text)
        var end = 0
        repeat(count) {
            val next = bi.following(end)
            if (next == android.icu.text.BreakIterator.DONE) return end
            end = next
        }
        return end
    }

    /** 发一个按键（按下 + 抬起），带软键盘标记。 Send a key (down + up) marked as coming from a soft keyboard. */
    fun sendKey(code: Int, meta: Int = 0) {
        val ic = ic() ?: return
        sendKeyTo(ic, code, meta)
    }

    /**
     * 与系统输入法框架发按键的方式一致：虚拟键盘设备 + FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE，App 不会因此退出
     * 触摸模式（按钮、列表不会突然出现焦点框）。
     * Like the framework's own key sending: the virtual keyboard device plus FLAG_SOFT_KEYBOARD |
     * FLAG_KEEP_TOUCH_MODE, so the app stays in touch mode (no focus highlights popping up on buttons and lists).
     */
    private fun sendKeyTo(ic: InputConnection, code: Int, meta: Int = 0) {
        val now = android.os.SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    /** 清空组合（收起键盘等）。 Drop the composition. */
    fun reset() {
        discardHand()
        endReconversion()
        engine?.clear()
        pendingKeys.clear()
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
        if (e != null && e.isComposing() || pendingKeys.isNotEmpty()) {
            e?.clear()
            pendingKeys.clear()
            refresh()
            return null
        }
        val ic = ic() ?: return null
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
            return sel.toString()
        }
        var before = ic.getTextBeforeCursor(limit, 0)?.toString().orEmpty()
        // 读到的一段从代理对中间开始：那半个字留着，不拆开。 The read starts inside a surrogate pair: keep that char whole.
        if (before.length >= limit && before.isNotEmpty() && Character.isLowSurrogate(before[0])) before = before.substring(1)
        if (before.isEmpty()) return null
        ic.deleteSurroundingText(before.length, 0)
        return before
    }

    /**
     * 撤销「清空」：原样放回，不走打字逻辑（不补配对括号、不弹计算候选、不先上屏组合）。
     * Undo a clear: put the text back verbatim, bypassing typing logic (no bracket pairing, no calculator
     * candidates, no committing a pending composition first).
     */
    fun restoreCleared(text: String) {
        ic()?.commitText(text, 1)
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
        val mirrored = if (editor.commitPending) null else editor.textBefore(64)
        val before = mirrored ?: ic.getTextBeforeCursor(64, 0)?.toString().orEmpty().also { editor.fill(it, 64) }
        if (before.isEmpty()) { editor.invalidate(); sendKeyTo(ic, KeyEvent.KEYCODE_DEL); return }
        val n = wordLengthBefore(before)
        ic.deleteSurroundingText(n, 0)
        editor.onDeleteBefore(n)
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

    /** 英文双击空格 → ". "（光标前须是「字母或数字 + 空格」）。 Double-space period; true if applied. */
    private fun doubleSpacePeriod(): Boolean {
        if (!periodAllowed) return false
        val ic = ic(modeled = true) ?: return false
        val before = editor.textBefore(2) ?: ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
        if (before.length < 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        editor.onDeleteBefore(1)
        ic.commitText(". ", 1)
        editor.onCommit(". ")
        ic.endBatchEdit()
        return true
    }

    /**
     * 成对符号：插入并把光标放中间（一次批量编辑，不发方向键）。
     * Paired symbols with the cursor placed inside (one batch edit, no arrow keys).
     */
    fun onPairedText(open: String, close: String) {
        engine?.commitFirst()
        refresh()
        val ic = ic() ?: return
        ic.beginBatchEdit()
        ic.commitText(open, 1)
        ic.commitText(close, 0)
        ic.endBatchEdit()
        engine?.breakChain()
        markUndo(null)
    }

    // ---------------------------------------------------------------- cursor panel (02 §9)

    /**
     * 方向键；[select] 为真时扩选（附带 Shift）。组合中先上屏原始字母，避免方向键打断内核状态。
     * Arrow key; extends the selection with Shift when [select].
     */
    fun cursorArrow(keyCode: Int, select: Boolean) {
        commitRawIfComposing()
        // 左右移动不扩选时与空格滑动一样用 setSelection。 Plain left/right moves use setSelection like the space slide.
        if (!select && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            moveCursor(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return
        }
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
     * 撤销 / 重做编辑器里的修改：网页与终端里的输入框发 Ctrl+Z / Ctrl+Shift+Z，其余走编辑器的菜单动作。
     * 菜单动作是单向调用，返回值只说明连接还在，不能据此判断编辑器是否支持，所以按输入框类型二选一，不两个都发
     * （两个都支持的编辑器会撤销两次）。
     * Undo / redo in the editor: Ctrl+Z / Ctrl+Shift+Z in web pages and terminals, the editor's context-menu
     * action elsewhere. The menu action is one-way, its result only says the connection is alive, so the choice is
     * made by field type; never both (editors that support both would undo twice).
     */
    fun undoRedo(redo: Boolean) {
        commitRawIfComposing()
        val ic = ic() ?: return
        if (!webField && !keyEventsOnly) {
            ic.performContextMenuAction(if (redo) android.R.id.redo else android.R.id.undo)
            return
        }
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
        // 到开头不需要知道文本长度：不读整段文字。 The start needs no length: skip reading the whole text.
        if (!end && !select && !keyEventsOnly) {
            ic.setSelection(0, 0)
            return
        }
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
        if (reconversion != null) { endReconversion(); refresh(); return }
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

    /** 开始说话时所在的输入框。 The editor dictation started in. */
    private var voiceField: Pair<String?, Int>? = null
    private var voiceEpoch: Long? = null
    private fun field(info: EditorInfo?) = info?.packageName to (info?.fieldId ?: 0)

    /** 记下开始说话的输入框。 Remember which editor dictation started in. */
    fun voiceBegin() { voiceField = field(editorInfo); voiceEpoch = inputEpoch }

    /**
     * 语音的输入连接：收起键盘后才出来的终稿，只写回开始说话的那个输入框；用户已经换到别的应用或输入框就丢掉，
     * 免得一句话落进别人的搜索框。
     * The connection for voice output: a final that arrives after the keyboard was hidden is only written to the
     * editor dictation started in; if the user has moved to another app or field it is dropped, so a sentence
     * never lands in someone else's search box.
     */
    private fun voiceIc(): InputConnection? {
        val started = voiceField
        if (started != null && (started != field(editorInfo) || voiceEpoch != inputEpoch)) return null
        return ic()
    }

    /** 语音中间结果以 composing 文本显示（带下划线）。 Voice interim result as composing text. */
    fun voicePartial(text: String) {
        val ic = voiceIc() ?: return
        prepareVoiceText()
        ic.setComposingText(text, 1)
    }

    /** 没有中间结果的识别器也要先结束拼音/手写组合。 Settle typed/handwritten input even when no partial arrives. */
    private fun prepareVoiceText() {
        engine?.let { e -> if (e.isComposing()) { e.commitFirst(); refresh() } }
        voiceTouched()
    }

    /**
     * 语音往输入框里写了字：清掉旧上文和词链，再同步收起界面的联想词。
     * Voice wrote into the field: clear the old context and word chain, then dismiss the predictions in the UI.
     */
    private fun voiceTouched() {
        engine?.breakChain()
        engine?.setContext(null)
        dismissPredictions()
    }

    /** 语音最终结果上屏（替换 composing）。 Commit a final voice segment. */
    fun voiceFinal(text: String) {
        val ic = voiceIc() ?: return
        if (text.isEmpty()) voiceTouched() else prepareVoiceText()
        if (text.isEmpty()) ic.finishComposingText() else ic.commitText(text, 1)
    }

    /** 插件事后修正已上屏文本。 Post-hoc correction of committed voice text. */
    fun voiceReplace(old: String, new: String) {
        val ic = voiceIc() ?: return
        val before = ic.getTextBeforeCursor(old.length, 0)?.toString() ?: return
        if (before == old) {
            voiceTouched()
            ic.deleteSurroundingText(old.length, 0)
            ic.commitText(new, 1)
        }
    }

    /** 取消语音：丢弃 composing。 Drop the voice composing text. */
    fun voiceCancel() {
        val ic = voiceIc() ?: return
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
        // Editor changes must follow queued handwriting commits; delivering a result must
        // never recursively settle a newer glyph before writing the older one.
        if (!applyingHandResult && (handJob != null || handCommits.isNotEmpty())) settleHand()
        stamp++
        if (!modeled) editor.invalidate()
        return icProvider()
    }

    private fun drainCommit(): EngineSnapshot? {
        stamp++
        val snap = engineRef?.snapshot() ?: return null
        if (snap.commit.isNotEmpty()) write(snap.commit)
        return snap
    }

    private fun refresh() {
        if (engineRef == null && pendingKeys.isNotEmpty()) { showPending(); return }
        val snap = drainCommit() ?: EngineSnapshot.EMPTY
        update {
            it.copy(
                preedit = snap.preedit,
                preeditMarks = snap.marks,
                candidates = snap.candidates,
                totalCandidates = snap.totalCandidates,
                pinyinOptions = snap.pinyinOptions,
                composing = snap.composing || handJob != null || handCommits.isNotEmpty(),
                handRecognizing = handJob != null || handCommits.isNotEmpty(),
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
        /** 内核就绪前最多记下的键数。 Most keys kept before the engine is ready. */
        private const val MAX_PENDING = 64
        /** 移动光标时最多读取的字符数。 Most chars read for one cursor move. */
        private const val MAX_MOVE_READ = 1024
        private val WEB_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )

        /**
         * 按词删除时删掉的长度：先跳过尾部空白，再删同类的一段（字母数字 / 汉字），其它字符一次删一个字形簇；
         * 按码位判断，表情与扩展区汉字的代理对不会被拆开。
         * How much a word delete removes: trailing whitespace, then one run of the same class (letters/digits or
         * ideographs), or a single grapheme cluster for anything else; classified by code point so surrogate pairs
         * (emoji, CJK extension characters) are never split.
         */
        internal fun wordLengthBefore(before: String): Int {
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            if (i > 0) {
                val cls = charClass(Character.codePointBefore(before, i))
                if (cls == 2) {
                    i = EditorCache.clusterStart(before, i)
                } else {
                    while (i > 0) {
                        val cp = Character.codePointBefore(before, i)
                        if (charClass(cp) != cls) break
                        i -= Character.charCount(cp)
                    }
                }
            }
            // 读到的一段从代理对中间开始：留下那半个，前面的另一半才不会落单。 Don't split a pair at the window start.
            if (i == 0 && Character.isLowSurrogate(before[0])) i = 1
            return (before.length - i).coerceAtLeast(1)
        }

        private fun charClass(cp: Int): Int = when {
            Character.isIdeographic(cp) -> 1
            Character.isLetterOrDigit(cp) -> 0
            else -> 2
        }
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
