package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.weavetext.ime.R
import com.weavetext.ime.ime.ClipHistory
import com.weavetext.ime.ime.ClipItem
import com.weavetext.ime.settings.WeavePrefs
import java.io.File
import kotlin.math.max

/**
 * 剪贴板仓库：监听系统剪贴板、过滤敏感内容、持久化历史与常用语（02 §10）。
 * Clipboard repository: listens to the system clipboard, filters sensitive clips, persists history
 * and quick phrases.
 */
class ClipboardRepo(private val ctx: Context, private val kb: WeaveKeyboard) {
    val history = com.weavetext.ime.link.LinkContent.history(ctx)
    val phrases: ClipHistory
    private val cm = ctx.getSystemService(ClipboardManager::class.java)
    private val listeners = ArrayList<() -> Unit>()
    private var lastSeen: String? = null

    /**
     * 只记录比上次记过的更新的剪贴：键盘视图重建（旋转、深色模式、进程重启）后补查时，
     * 不会把用户删掉或清空的那条重新加回来、标成「刚刚」。
     * Only record clips newer than the last one recorded, so the re-check after the keyboard view is rebuilt
     * (rotation, dark mode, process restart) cannot resurrect an item the user deleted, stamped "just now".
     */
    private fun unrecorded(clip: android.content.ClipData, fresh: Boolean): Boolean {
        val stamp = clip.description.timestamp
        if (!fresh && stamp <= kb.prefs.getLong(LAST_RECORDED, 0L)) return false
        kb.prefs.edit().putLong(LAST_RECORDED, maxOf(stamp, kb.prefs.getLong(LAST_RECORDED, 0L))).apply()
        return true
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClip(fresh = true) }
    private val historyListener: () -> Unit = { kb.view.post { notifyChanged() } }

    init {
        val pf = File(ctx.filesDir, "clipboard/phrases.json")
        val seed = !pf.exists()
        phrases = ClipHistory(pf, maxUnpinned = 500, ttlMs = 0)
        if (seed) {
            var t = 5L
            for (s in DEFAULT_PHRASES.reversed()) phrases.add(s, t++)
        }
        runCatching { cm?.addPrimaryClipChangedListener(clipListener) }
        history.observe(historyListener)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    private fun notifyChanged() = listeners.forEach { it() }

    val recording get() = WeavePrefs.clipboardRecord(kb.prefs)

    /** 刚同意记录时把当前剪贴板也记下。 Record the current clip right after the user opts in. */
    fun recordCurrent() {
        if (!recording || kb.controller.isSensitiveField) return
        lastSeen = null
        onClip(fresh = false)
    }

    /** 清空全部历史（含已固定）。 Clear all history, pinned included. */
    fun clearHistory() {
        history.clearAll()
        notifyChanged()
    }

    /** 当前系统剪贴板文本（记录关闭时面板只显示它）。 Current system clip text. */
    fun currentClip(): String? = runCatching {
        cm?.primaryClip?.takeIf { it.itemCount > 0 && !isSensitive(it) }?.getItemAt(0)?.let { if (it.uri == null) it.text?.toString() else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 键盘显示时补查一次（进程未存活期间的复制）。 Re-check on show. */
    fun onShown() = onClip(fresh = false)

    private fun onClip(fresh: Boolean) {
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0 || isSensitive(clip) || kb.controller.isSensitiveField) return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) {
            val key = uris.joinToString("|")
            if (key == lastSeen) return
            lastSeen = key
            val recent = fresh || System.currentTimeMillis() - clip.description.timestamp < 30_000
            val record = recording && unrecorded(clip, fresh)
            uris.forEach { uri ->
                if (record) com.weavetext.ime.link.LinkContent.io.execute {
                    if (recording && !com.weavetext.ime.ime.ClipPrivacy.privateField) runCatching { com.weavetext.ime.link.LinkContent.import(ctx, uri) }
                }
                if (recent) com.weavetext.ime.link.LinkManager.get(ctx).onLocalMedia(uri)
            }
            notifyChanged(); return
        }
        val text = clip.getItemAt(0).text?.toString()
        if (text.isNullOrBlank() || text == lastSeen) return
        lastSeen = text
        val now = System.currentTimeMillis()
        val recent = fresh || now - clip.description.timestamp < 30_000
        if (recording && unrecorded(clip, fresh)) {
            history.add(text, now)
            notifyChanged()
        }
        if (recent) {
            kb.onNewClip(text)
            // 织文互联：新复制的文字同步给已连接的电脑（关闭或未连接时什么都不做）。
            // WeaveLink: a fresh copy goes to the connected computer (no-op when off or disconnected).
            com.weavetext.ime.link.LinkManager.get(ctx).onLocalClip(text)
        }
    }

    private fun isSensitive(clip: android.content.ClipData): Boolean {
        val extras = clip.description.extras ?: return false
        return extras.getBoolean(EXTRA_IS_SENSITIVE) || extras.getBoolean("android.content.extra.IS_SENSITIVE")
    }

    fun release() { history.unobserve(historyListener); runCatching { cm?.removePrimaryClipChangedListener(clipListener) } }

    fun currentItem(): ClipItem? {
        val clip = runCatching { cm?.primaryClip }.getOrNull()?.takeIf { it.itemCount > 0 && !isSensitive(it) } ?: return null
        val item = clip.getItemAt(0)
        val uri = item.uri ?: return currentClip()?.let { ClipItem(-1, it, System.currentTimeMillis()) }
        val mime = clip.description.getMimeType(0)
        return ClipItem(-1, if (mime.startsWith("image/")) "图片" else "文件", System.currentTimeMillis(), uri = uri.toString(), mime = mime)
    }

    fun changed() = notifyChanged()

    companion object {
        /** 相对 filesDir。 Relative to filesDir. */
        const val HISTORY_FILE = "clipboard/history.json"
        private const val LAST_RECORDED = "clipboard_last_recorded_stamp"
        private val EXTRA_IS_SENSITIVE = if (Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
        val DEFAULT_PHRASES = listOf("好的，收到", "稍等，马上回复你", "我在开会，晚点联系", "谢谢！", "辛苦了")
    }
}

/**
 * 剪贴板面板 / 常用语面板（02 §10、§11 #6）：标题行 + 两列卡片网格。
 * Clipboard / quick-phrase panel: title row + two-column card grid.
 */
class ClipboardPanel(kb: WeaveKeyboard, private val mode: Mode) : KbPanel(kb) {
    enum class Mode { CLIPBOARD, PHRASES }

    override val toolIndex = if (mode == Mode.CLIPBOARD) 4 else 0
    private val header = Header(kb.ctx)
    private val grid = Cards(kb.ctx)
    /** 搜索行：默认收着，点表头的放大镜才出现（表头文档见 [headerIcons]）。 The search row, opened from the header. */
    private val searchRow = SearchRow(kb.ctx, kb, if (mode == Mode.CLIPBOARD) "搜索剪贴板" else "搜索常用语")
    /**
     * 搜索时面板盖住了主键盘，字母没有地方打，所以搜索行下方自带一排字母键。
     * While searching the panel covers the main keyboard, so letters have nowhere to go: the search row brings its
     * own letter pad.
     */
    private val searchKeys = PanelKeys(kb.ctx, kb)
    override val view = LinearLayout(kb.ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(-1, kb.metrics.dp(44f).toInt()))
        addView(searchRow, LinearLayout.LayoutParams(-1, 0))
        addView(searchKeys, LinearLayout.LayoutParams(-1, 0))
        addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private val repo get() = kb.clipboard
    private val store get() = if (mode == Mode.CLIPBOARD) repo.history else repo.phrases
    /** 密码框 / 禁止个性化学习的输入框：不显示任何剪贴板内容。 Private field: show nothing. */
    private val privateField get() = mode == Mode.CLIPBOARD && kb.controller.isSensitiveField
    /** 首次打开：询问是否记录。 First open: ask whether to record. */
    private val asking get() = mode == Mode.CLIPBOARD && !privateField && !WeavePrefs.clipboardAsked(kb.prefs)
    /** 显示可管理的历史（常用语总是）。 Showing a manageable list. */
    private val historyShown get() = mode == Mode.PHRASES || (!privateField && !asking && repo.recording)
    private var items: List<ClipItem> = emptyList()
    /** 全部条目（搜索前）。 All items, before the query filters them. */
    private var allItems: List<ClipItem> = emptyList()
    private var search = SearchQuery()
    private var searching = false
    private var pinnedOnly = false
    private var selecting = false
    private val selected = HashSet<Long>()
    private var confirmClear = false
    /** 长按展开动作的卡片。 Card showing its action row. */
    private var actionFor = -1L

    init {
        repo.addListener { if (view.visibility == View.VISIBLE) reload() }
        grid.onPressFeedback = { kb.feedback.key(grid) }
        grid.onLongFeedback = { kb.feedback.haptic(grid) }
        searchRow.onClose = { closeSearch() }
        // 清除只清查询，搜索行开着：重新显示时要把行上的旧字一起抹掉。 Clear only the query; the row stays open.
        searchRow.onClear = { search.clear(); updateSearchRow(); reload() }
        // 点搜索行就把字母键亮出来：面板盖住主键盘，这里是唯一能打字的地方。
        // Tapping the row raises the letter pad: the panel covers the main keyboard, so this is the only place to type.
        searchRow.onOpen = {
            kb.feedback.key(searchRow)
            openSearch()
        }
        searchKeys.onLetter = { c -> if (search.letter(c)) reload() }
        searchKeys.onBackspace = { if (search.backspace()) reload() else closeSearch() }
    }

    override fun applyTheme() {
        (header.layoutParams as LinearLayout.LayoutParams).height = kb.metrics.dp(44f).toInt()
        updateSearchRow()
        header.invalidate(); grid.rebuild()
    }

    override fun onShow() {
        selecting = false; selected.clear(); confirmClear = false; actionFor = -1; pinnedOnly = false
        closeSearch()
        reload()
        grid.scrollToTop()
    }

    override fun onHide() { closeSearch() }

    /** 搜索框开着时，键盘上的字母、退格与空格归这里。 While searching, the keyboard's letters go to the query. */
    override val searchOpen get() = searching

    override fun leaveSearch(): Boolean {
        if (!searching) return false
        closeSearch()
        return true
    }

    /** 打开搜索：表头多一行输入，列表随输入的词过滤。 Open the search row. */
    private fun openSearch() {
        if (searching) return
        searching = true
        updateSearchRow()
        reload()
    }

    private fun closeSearch() {
        if (!searching && !search.active) return
        searching = false
        search.clear()
        updateSearchRow()
        reload()
    }

    private fun updateSearchRow() {
        // 查询清零后行上不能还留着旧词：清除、退出搜索都走这里。
        // A cleared query must not leave its old text behind: clear and leave both come through here.
        searchRow.show(search.committed.toString(), search.composing.toString())
        (searchRow.layoutParams as LinearLayout.LayoutParams).height =
            if (searching) kb.metrics.dp(38f).toInt() else 0
        // 字母键跟着搜索行一起出现与收起。 The letter pad appears and folds with the search row.
        searchKeys.visibility = if (searching) View.VISIBLE else View.GONE
        (searchKeys.layoutParams as LinearLayout.LayoutParams).height =
            if (searching) LinearLayout.LayoutParams.WRAP_CONTENT else 0
        searchRow.requestLayout()
        searchKeys.requestLayout()
    }

    /**
     * 一条记录是否命中查询：字面按词匹配（媒体条目用它的文件名／说明），中文内容还可以通过内核把拼音读成的词命中
     * ——搜索框只能收字母，打 weixiao 想找含「微笑」的那条复制记录。
     * Whether an item matches: every term literally (media items by their name), or — because the box only takes
     * letters — any reading the engine gave the typed pinyin, so weixiao finds a clip containing 微笑.
     */
    private fun matches(item: ClipItem, terms: List<String>, readings: List<String>): Boolean {
        val text = item.text.lowercase()
        if (terms.isNotEmpty() && terms.all { text.contains(it) }) return true
        return readings.any { text.contains(it.lowercase()) }
    }

    override fun onKey(key: Key): Boolean {
        if (!searching) return false
        val had = search.active
        // 空查询只吃字母、数字、退格与空格：回车与功能键照旧走面板与编辑器。
        // With an empty query only letters, digits, backspace and space are taken; Enter and function keys pass through.
        if (!had && key.code != KeyCode.DELETE && key.code != KeyCode.SPACE && key.code !in 'a'.code..'z'.code &&
            key.code !in '0'.code..'9'.code && !isPunctKey(key.code)) return false
        val handled = when (key.code) {
            // 删空之后再按一次退格就收搜索行（这一下不再透传给编辑器）。
            // One more backspace on an empty query closes the row, and that press does not reach the editor.
            KeyCode.DELETE -> if (search.backspace()) true else { closeSearch(); true }
            // 空格归搜索框，空查询也吃掉：否则会在正文里打出一个空格（或两个空格变句号）。
            // Space belongs to the box even with an empty query, or it types into the document.
            KeyCode.SPACE -> { search.commitComposing(); true }
            KeyCode.ENTER -> { commitFirst(); true }
            else -> when {
                key.code in 'a'.code..'z'.code -> { search.letter(key.code.toChar()); true }
                // 数字进查询（订单号、验证码这类内容几乎都得按数字搜）。
                // Digits go in: order numbers and codes are searched by their digits.
                key.code in '0'.code..'9'.code -> { search.literal(key.code.toChar().toString()); true }
                // 标点同样进查询（引号、括号常是内容的一部分），不能落进正文。
                // Punctuation goes in too (quotes and brackets are often part of a clip); it must not reach the text.
                isPunctKey(key.code) -> { search.literal(String(Character.toChars(key.code))); true }
                else -> false
            }
        }
        if (handled) reload()
        return handled
    }

    override fun onKeyText(key: Key, text: String): Boolean {
        if (!searching) return false
        val handled = when {
            text.length == 1 && text[0].isLetter() -> { search.letter(text[0]); true }
            text.length == 1 && text[0].isDigit() -> { search.literal(text); true }
            text.length == 1 && !text[0].isLetterOrDigit() && !text[0].isWhitespace() -> { search.literal(text); true }
            else -> false
        }
        if (handled) reload()
        return handled
    }

    /** 上滑 / 长按改写：先撤掉按下时进查询的那个字符，再放入新的文字。 Replace: take back the pressed char, then add the new text. */
    override fun onKeyReplace(key: Key, text: String): Boolean {
        if (!searching) return false
        if (!search.composing.isEmpty()) search.composing.setLength(search.composing.length - 1) else search.backspace()
        return onKeyText(key, text)
    }

    /** 回车：先确定拼音，再上屏第一个结果。 Enter settles the pinyin, then inserts the first hit. */
    private fun commitFirst() {
        search.commitComposing()
        reload()
        items.firstOrNull()?.let { commit(it) }
    }

    @androidx.annotation.VisibleForTesting
    fun searchResultsForTest(): List<ClipItem> = items

    fun reload() {
        val now = System.currentTimeMillis()
        allItems = when {
            privateField || asking -> emptyList()
            mode == Mode.CLIPBOARD && !repo.recording -> repo.currentItem()?.let { listOf(it) } ?: emptyList()
            pinnedOnly -> store.list(now).filter { it.pinned }
            else -> store.list(now)
        }
        val query = search.text.trim()
        val terms = ExpressionSearch.terms(query)
        // 拼音读法（weixiao → 微笑）：中文模式下内核帮搜索框把字母读成词。 Readings from the engine in Chinese mode.
        val readings = if (searching && query.isNotEmpty()) kb.controller.lookupText(query.filter { it in 'a'..'z' }) else emptyList()
        items = if (searching && query.isNotEmpty()) allItems.filter { matches(it, terms, readings) } else allItems
        selected.retainAll(items.map { it.id }.toSet())
        if (searching) {
            searchRow.show(search.committed.toString(), search.composing.toString())
            // 读屏播报结果数，打字时不用摸列表。 Announce the hit count.
            if (search.active) kb.topBar.showAction(
                if (items.isEmpty()) "没有匹配的${if (mode == Mode.CLIPBOARD) "剪贴板" else "常用语"}" else "找到 ${items.size} 项", null, 1200, null)
        }
        grid.rebuild()
        header.invalidate()
    }

    private fun setRecording(on: Boolean) {
        kb.prefs.edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, on).apply()
        if (on) repo.recordCurrent()
        reload()
    }

    private fun commit(item: ClipItem) {
        if (item.media) {
            if (!kb.controller.onContent(android.net.Uri.parse(item.uri), item.mime ?: "application/octet-stream", item.text)) {
                share(item)
                kb.topBar.showAction("此输入框不支持文件粘贴，可选择应用转发", null, 2500, null)
            }
            return
        }
        kb.controller.onText(item.text)
        if (mode == Mode.PHRASES) kb.closePanel()
    }

    private fun share(item: ClipItem) {
        val uri = android.net.Uri.parse(item.uri)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = item.mime ?: "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(kb.ctx.contentResolver, item.text, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        kb.ctx.startActivity(android.content.Intent.createChooser(send, "转发 · ${item.text}").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun addPhrase() {
        val text = repo.currentClip()
        if (text == null) {
            kb.topBar.showAction("先复制要添加的文字", null, 2500, null)
            return
        }
        repo.phrases.add(text, System.currentTimeMillis())
        reload()
        kb.topBar.showAction("已添加为常用语", null, 2000, null)
    }

    /**
     * 表头按钮的图标，从右往左排：第一项画在最右一格，动作也按这个次序分派（见 onHeader），两者必须一致。
     * Header icons, ordered right to left: entry 0 is the rightmost cell and the actions are dispatched in that same
     * order (see onHeader), so the two have to agree.
     */
    private fun headerIcons(): IntArray =
        if (mode == Mode.CLIPBOARD) intArrayOf(R.drawable.ic_delete, R.drawable.ic_edit, R.drawable.ic_pin, R.drawable.ic_search)
        else intArrayOf(R.drawable.ic_plus, R.drawable.ic_edit, R.drawable.ic_search)

    @androidx.annotation.VisibleForTesting
    fun headerIconsForTest(): IntArray = headerIcons()

    // ------------------------------------------------------------------ header

    @SuppressLint("ViewConstructor")
    private inner class Header(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).zh()
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        /** 右侧按钮区（从右往左）。 Right-side hit areas, right to left. */
        private val hits = Array(4) { RectF() }
        private var hitCount = 0
        private var pressed = -2

        override fun onDraw(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val cy = height / 2f
            p.typeface = medium
            p.textSize = m.dp(15f)
            p.textAlign = Paint.Align.LEFT
            hitCount = 0
            when {
                confirmClear -> {
                    p.color = pal.label
                    c.drawText("清空未固定的记录？", m.dp(16f), base(cy), p)
                    textButtons(c, listOf("清空" to pal.danger, "取消" to pal.candidateFirst))
                }
                selecting -> {
                    p.color = pal.label
                    c.drawText("已选 ${selected.size} 项", m.dp(16f), base(cy), p)
                    textButtons(c, listOf("完成" to pal.candidateFirst, "删除" to if (selected.isEmpty()) pal.labelDisabled else pal.danger))
                }
                !historyShown -> {
                    drawTitle(c, cy)
                    // 只看当前剪贴板时给出开启入口。 Offer to turn recording on in current-clip-only mode.
                    if (!privateField && !asking) textButtons(c, listOf("开启记录" to pal.candidateFirst))
                }
                else -> {
                    drawTitle(c, cy)
                    val icons = headerIcons()
                    var right = width - m.dp(8f)
                    for ((i, ic) in icons.withIndex()) {
                        val r = hits[i]
                        r.set(right - m.dp(36f), cy - m.dp(18f), right, cy + m.dp(18f))
                        val on = (mode == Mode.CLIPBOARD && ic == R.drawable.ic_pin && pinnedOnly) || (ic == R.drawable.ic_search && searching)
                        if (on || pressed == i) {
                            p.color = if (on) pal.accentSoft else pal.toolbarActive
                            c.drawRoundRect(r, m.dp(10f), m.dp(10f), p)
                        }
                        kb.icons.draw(c, ic, if (on) pal.keyAccent else pal.icon, r.centerX(), cy, m.dp(22f))
                        right -= m.dp(40f)
                    }
                    hitCount = icons.size
                }
            }
            p.color = pal.divider
            c.drawRect(0f, height - max(1f, m.dp(0.5f)), width.toFloat(), height.toFloat(), p)
        }

        private fun base(cy: Float) = cy - (p.ascent() + p.descent()) / 2

        private fun drawTitle(c: Canvas, cy: Float) {
            val m = kb.metrics
            kb.icons.draw(c, R.drawable.ic_chevron_left, kb.palette.icon, m.dp(22f), cy, m.dp(22f))
            p.color = kb.palette.label
            c.drawText(if (mode == Mode.CLIPBOARD) "剪贴板" else "常用语", m.dp(40f), base(cy), p)
        }

        private fun textButtons(c: Canvas, list: List<Pair<String, Int>>) {
            val m = kb.metrics
            var right = width - m.dp(16f)
            p.textAlign = Paint.Align.RIGHT
            for ((i, e) in list.withIndex()) {
                p.color = e.second
                val w = p.measureText(e.first)
                c.drawText(e.first, right, base(height / 2f), p)
                hits[i].set(right - w - m.dp(10f), 0f, right + m.dp(10f), height.toFloat())
                right -= w + m.dp(28f)
            }
            hitCount = list.size
            p.textAlign = Paint.Align.LEFT
        }

        private fun hitAt(x: Float, y: Float): Int {
            for (i in 0 until hitCount) if (hits[i].contains(x, y)) return i
            return if (!confirmClear && !selecting && x < kb.metrics.dp(120f)) -1 else -2
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hitAt(e.x, e.y)
                    if (pressed != -2) kb.feedback.key(this)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val i = pressed
                    pressed = -2
                    if (i != -2 && i == hitAt(e.x, e.y)) onHeader(i)
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -2; invalidate() }
            }
            return true
        }
    }

    private fun onHeader(i: Int) {
        when {
            confirmClear -> {
                if (i == 0) store.clearUnpinned()
                confirmClear = false
                reload()
            }
            selecting -> {
                if (i == 1 && selected.isNotEmpty()) { store.delete(selected.toList()); selected.clear() }
                if (i == 0) { selecting = false; selected.clear() }
                reload()
            }
            i == -1 -> kb.closePanel()
            !historyShown -> if (i == 0 && !privateField && !asking) setRecording(true)
            mode == Mode.CLIPBOARD -> when (i) {
                0 -> if (allItems.any { !it.pinned }) { confirmClear = true; header.invalidate() }
                1 -> if (allItems.isNotEmpty()) { selecting = true; actionFor = -1; reload() }
                2 -> { pinnedOnly = !pinnedOnly; reload() }
                3 -> if (searching) closeSearch() else openSearch()
            }
            else -> when (i) {
                0 -> addPhrase()
                1 -> if (allItems.isNotEmpty()) { selecting = true; actionFor = -1; reload() }
                2 -> if (searching) closeSearch() else openSearch()
            }
        }
    }

    // ------------------------------------------------------------------ cards

    @SuppressLint("ViewConstructor")
    private inner class Cards(c: Context) : ScrollGridView(c) {
        private val rects = ArrayList<RectF>()
        private val layouts = ArrayList<StaticLayout>()
        private val thumbnails = HashMap<String, android.graphics.Bitmap?>()
        private val loading = HashSet<String>()
        private fun thumbnail(item: ClipItem) {
            val uri = item.uri ?: return
            if (item.mime?.startsWith("image/") != true || thumbnails.containsKey(uri) || !loading.add(uri)) return
            com.weavetext.ime.link.LinkContent.io.execute {
                val bitmap = runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    kb.ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    kb.ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                }.getOrNull()
                post { thumbnails[uri] = bitmap; loading.remove(uri); invalidate() }
            }
        }
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).zh()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).zh()
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).zh()
        private var bottom = 0f
        /** 动作行的三个按钮（固定/编辑/删除）。 The three action buttons of the expanded card. */
        private val actions = Array(3) { RectF() }
        private val tmp = RectF()
        /** 首次询问：说明文字与两个按钮（仅当前 / 开启）。 First-run prompt: text + two buttons. */
        private var promptText: StaticLayout? = null
        private val promptButtons = Array(2) { RectF() }
        private var promptHeight = 0f

        private fun promptTop() = max(0f, (height - promptHeight) / 2)

        private fun buildPrompt() {
            val m = kb.metrics
            val pad = m.dp(20f)
            body.textSize = m.panel(14f)
            val s = PROMPT
            promptText = StaticLayout.Builder.obtain(s, 0, s.length, body, (width - 2 * pad).toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(m.panel(20f) - body.fontMetrics.let { it.descent - it.ascent }, 1f).build()
            var y = m.dp(16f) + promptText!!.height + m.dp(16f)
            val gap = m.dp(12f)
            val bw = (width - 2 * pad - gap) / 2
            val bh = m.dp(40f)
            promptButtons[0].set(pad, y, pad + bw, y + bh)
            promptButtons[1].set(pad + bw + gap, y, width - pad, y + bh)
            y += bh + m.dp(16f)
            promptHeight = y
        }

        fun rebuild() {
            if (width == 0) { post { rebuild() }; return }
            val m = kb.metrics
            if (asking) { buildPrompt(); invalidate(); a11yChanged(); return }
            val pad = m.dp(12f)
            val gap = m.dp(8f)
            val cw = (width - 2 * pad - gap) / 2
            body.textSize = m.panel(14f)
            rects.clear(); layouts.clear()
            var y = pad
            var i = 0
            while (i < items.size) {
                var rowH = 0f
                val start = i
                for (col in 0..1) {
                    if (i >= items.size) break
                    // 已固定项右上角留出图钉位置。 Leave room for the pin icon.
                    val lay = layoutFor(items[i].text, (cw - m.dp(20f) - if (items[i].pinned) m.dp(14f) else 0f).toInt())
                    layouts += lay
                    if (items[i].media) thumbnail(items[i])
                    rowH = max(rowH, m.dp(10f) + lay.height + m.dp(6f) + m.dp(14f) + m.dp(10f) + if (items[i].media) m.dp(64f) else 0f)
                    i++
                }
                for (j in start until i) {
                    val col = j - start
                    rects += RectF(pad + col * (cw + gap), y, pad + col * (cw + gap) + cw, y + rowH)
                }
                y += rowH + gap
            }
            bottom = y + pad
            // 列表变短（删除、清空、切「只看已固定」、过期裁剪）后把滚动位置收回可用范围，否则内容整体被推到
            // 视口上方，整块面板（连空状态提示）都画不出来，看起来像记录被清空了。
            // After the list shrinks (a delete, a clear, pinned-only, expiry) pull the scroll back into range; otherwise
            // the whole content sits above the viewport and nothing is drawn — not even the empty-state hint.
            scroll = scroll.coerceIn(0f, maxScroll())
            invalidate()
            a11yChanged()
        }

        private fun layoutFor(text: String, w: Int): StaticLayout {
            val m = kb.metrics
            val s = text.replace('\n', ' ').replace('\t', ' ').let { if (it.length > 300) it.substring(0, 300) else it }
            return StaticLayout.Builder.obtain(s, 0, s.length, body, w.coerceAtLeast(1))
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(m.panel(20f) - body.fontMetrics.let { it.descent - it.ascent }, 1f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        }

        override fun contentHeight() = when {
            asking -> max(height.toFloat(), promptHeight)
            items.isEmpty() -> height.toFloat()
            else -> bottom
        }

        override fun hit(x: Float, y: Float): Int {
            if (asking) {
                for (b in 0..1) if (promptButtons[b].contains(x, y - promptTop())) return PROMPT_BASE + b
                return -1
            }
            for (i in rects.indices) if (rects[i].contains(x, y)) {
                // 只有真正记录下来的条目才有动作：当前剪贴板那张卡片的 id 是 -1，它不是历史项，固定和删除都无从下手。
                // Only recorded entries carry actions: the current-clipboard card has id -1 and is not a history entry,
                // so pinning or deleting it has nothing to act on.
                if (items[i].id >= 0 && items[i].id == actionFor) {
                    for (a in 0..2) if (actions[a].contains(x, y)) return ACTION_BASE + a
                }
                return i
            }
            return -1
        }

        override fun a11yCount() = if (asking) 2 else items.size
        override fun a11yRect(index: Int, out: RectF) {
            if (asking) { out.set(promptButtons[index]); out.offset(0f, promptTop()) } else rects.getOrNull(index)?.let { out.set(it) }
        }
        override fun a11yLabel(index: Int): CharSequence? {
            if (asking) return if (index == 0) "仅显示当前剪贴板" else "开启记录"
            val it = items.getOrNull(index) ?: return null
            val t = if (it.text.length > 200) it.text.substring(0, 200) + "…" else it.text
            return if (it.pinned) "$t，已固定" else t
        }
        override fun a11yLongLabel(index: Int): CharSequence? =
            if (!asking && !selecting && (items.getOrNull(index)?.id ?: -1) >= 0) "固定、编辑或删除" else null
        override fun a11yTap(index: Int) = onItemTap(if (asking) PROMPT_BASE + index else index)

        override fun onItemTap(index: Int) {
            if (index >= PROMPT_BASE) { setRecording(index == PROMPT_BASE + 1); return }
            if (index >= ACTION_BASE) {
                val item = items.firstOrNull { it.id == actionFor } ?: return
                actionFor = -1
                when (index - ACTION_BASE) {
                    0 -> store.setPinned(item.id, !item.pinned)
                    1 -> { if (item.media) share(item) else { kb.controller.onText(item.text); kb.closePanel() }; return }
                    2 -> store.delete(listOf(item.id))
                }
                reload()
                return
            }
            val item = items.getOrNull(index) ?: return
            when {
                selecting -> {
                    if (!selected.remove(item.id)) selected += item.id
                    header.invalidate(); invalidate()
                }
                actionFor >= 0 -> { actionFor = -1; invalidate() }
                else -> commit(item)
            }
        }

        override fun onItemLong(index: Int): Boolean {
            val item = items.getOrNull(index) ?: return false
            if (selecting || item.id < 0) return false
            actionFor = item.id
            invalidate()
            return true
        }

        override fun drawContent(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            if (asking) { drawPrompt(c); return }
            if (items.isEmpty()) { drawEmpty(c); return }
            val r = m.dp(12f)
            val now = System.currentTimeMillis()
            for (i in rects.indices) {
                val rc = rects[i]
                if (rc.bottom < scroll || rc.top > scroll + height) continue
                val item = items[i]
                val sel = selecting && item.id in selected
                p.color = when { sel -> pal.accentSoft; i == pressed -> pal.keyPressed; else -> pal.card }
                c.drawRoundRect(rc, r, r, p)
                if (sel) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = m.dp(1.5f); p.color = pal.keyAccent
                    c.drawRoundRect(rc, r, r, p)
                    p.style = Paint.Style.FILL
                }
                body.color = pal.label
                c.save()
                if (item.media) {
                    val bitmap = thumbnails[item.uri]
                    if (bitmap != null) {
                        val target = RectF(rc.left + m.dp(10f), rc.top + m.dp(8f), rc.right - m.dp(10f), rc.top + m.dp(66f))
                        val scale = minOf(target.width() / bitmap.width, target.height() / bitmap.height)
                        val w = bitmap.width * scale; val h = bitmap.height * scale
                        target.set(target.centerX() - w / 2, target.centerY() - h / 2, target.centerX() + w / 2, target.centerY() + h / 2)
                        c.drawBitmap(bitmap, null, target, p)
                    } else {
                        small.textSize = m.dp(14f); small.color = pal.labelSecondary; small.textAlign = Paint.Align.CENTER
                        c.drawText(if (item.mime?.startsWith("image/") == true) "图片" else "文件", rc.centerX(), rc.top + m.dp(38f), small)
                    }
                }
                c.translate(rc.left + m.dp(10f), rc.top + m.dp(10f) + if (item.media) m.dp(64f) else 0f)
                layouts[i].draw(c)
                c.restore()
                small.textSize = m.dp(11f)
                small.color = pal.labelHint
                small.textAlign = Paint.Align.RIGHT
                val label = if (item.pinned) "已固定" else if (mode == Mode.PHRASES) "" else ago(now - item.time)
                c.drawText(label, rc.right - m.dp(10f), rc.bottom - m.dp(10f), small)
                if (item.pinned) kb.icons.draw(c, R.drawable.ic_pin, pal.keyAccent, rc.right - m.dp(13f), rc.top + m.dp(15f), m.dp(14f))
                if (selecting) {
                    val cx = rc.left + m.dp(12f); val cy = rc.bottom - m.dp(15f)
                    p.color = if (sel) pal.keyAccent else pal.labelDisabled
                    if (sel) c.drawCircle(cx, cy, m.dp(8f), p)
                    else { p.style = Paint.Style.STROKE; p.strokeWidth = m.dp(1.5f); c.drawCircle(cx, cy, m.dp(7.5f), p); p.style = Paint.Style.FILL }
                    if (sel) kb.icons.draw(c, R.drawable.ic_check, pal.onAccent, cx, cy, m.dp(12f))
                }
                if (item.id >= 0 && item.id == actionFor) drawActions(c, rc, item)
            }
        }

        private fun drawActions(c: Canvas, rc: RectF, item: ClipItem) {
            val pal = kb.palette
            val m = kb.metrics
            p.color = pal.card
            p.alpha = 235
            c.drawRoundRect(rc, m.dp(12f), m.dp(12f), p)
            p.alpha = 255
            val w = (rc.width() - m.dp(16f)) / 3
            val h = (rc.height() - m.dp(16f)).coerceAtMost(m.dp(56f))
            val top = rc.centerY() - h / 2
            val icons = intArrayOf(R.drawable.ic_pin, R.drawable.ic_edit, R.drawable.ic_delete)
            val labels = arrayOf(if (item.pinned) "取消固定" else "固定", if (item.media) "转发" else "编辑上屏", "删除")
            small.textAlign = Paint.Align.CENTER
            small.textSize = m.dp(11f)
            for (a in 0..2) {
                val r = actions[a]
                r.set(rc.left + m.dp(8f) + a * w, top, rc.left + m.dp(8f) + (a + 1) * w, top + h)
                tmp.set(r); tmp.inset(m.dp(2f), 0f)
                p.color = if (pressed == ACTION_BASE + a) pal.keyFuncPressed else pal.keyFunc
                c.drawRoundRect(tmp, m.dp(8f), m.dp(8f), p)
                val col = if (a == 2) pal.danger else pal.icon
                kb.icons.draw(c, icons[a], col, r.centerX(), r.centerY() - m.dp(7f), m.dp(18f))
                small.color = if (a == 2) pal.danger else pal.labelSecondary
                c.drawText(labels[a], r.centerX(), r.centerY() + m.dp(15f), small)
            }
        }

        private fun drawPrompt(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val lay = promptText ?: return
            val top = promptTop()
            c.save()
            c.translate(m.dp(20f), top + m.dp(16f))
            body.color = pal.label
            lay.draw(c)
            c.restore()
            p.textSize = m.dp(14f)
            p.textAlign = Paint.Align.CENTER
            val labels = arrayOf("仅显示当前剪贴板", "开启记录")
            for (b in 0..1) {
                tmp.set(promptButtons[b]); tmp.offset(0f, top)
                val accent = b == 1
                val down = pressed == PROMPT_BASE + b
                p.color = if (accent) (if (down) pal.keyAccentPressed else pal.keyAccent) else (if (down) pal.keyFuncPressed else pal.keyFunc)
                c.drawRoundRect(tmp, m.dp(10f), m.dp(10f), p)
                p.color = if (accent) pal.onAccent else pal.label
                c.drawText(labels[b], tmp.centerX(), tmp.centerY() - (p.ascent() + p.descent()) / 2, p)
            }
            p.textAlign = Paint.Align.LEFT
        }

        private fun drawEmpty(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val cy = height / 2f - m.dp(12f)
            kb.icons.draw(c, R.drawable.ic_clipboard, pal.labelDisabled, width / 2f, cy, m.dp(40f))
            small.textSize = m.dp(13f)
            small.color = pal.labelSecondary
            small.textAlign = Paint.Align.CENTER
            val msg = when {
                mode == Mode.PHRASES -> "点右上角 + 把剪贴板内容添加为常用语"
                privateField -> "此输入框不显示剪贴板内容"
                pinnedOnly -> "还没有固定的内容"
                !repo.recording -> "未记录剪贴板历史，只显示当前复制的内容"
                else -> "复制的内容会出现在这里"
            }
            c.drawText(msg, width / 2f, cy + m.dp(28f) + m.dp(8f), small)
        }

        private fun ago(ms: Long): String {
            val min = ms / 60_000
            return when {
                min < 1 -> "刚刚"
                min < 60 -> "$min 分钟前"
                else -> "${min / 60} 小时前"
            }
        }
    }

    companion object {
        private const val ACTION_BASE = 100_000
        private const val PROMPT_BASE = 200_000
        private const val PROMPT = "是否记录剪贴板历史？\n复制的文字、图片和文件在本机保存 24 小时，密码框中不记录。"
    }
}
