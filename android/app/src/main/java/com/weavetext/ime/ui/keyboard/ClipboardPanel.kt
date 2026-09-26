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
    val history = ClipHistory(File(ctx.filesDir, HISTORY_FILE))
    val phrases: ClipHistory
    private val cm = ctx.getSystemService(ClipboardManager::class.java)
    private val listeners = ArrayList<() -> Unit>()
    private var lastSeen: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClip(fresh = true) }

    init {
        val pf = File(ctx.filesDir, "clipboard/phrases.json")
        val seed = !pf.exists()
        phrases = ClipHistory(pf, maxUnpinned = 500, ttlMs = 0)
        if (seed) {
            var t = 5L
            for (s in DEFAULT_PHRASES.reversed()) phrases.add(s, t++)
        }
        runCatching { cm?.addPrimaryClipChangedListener(clipListener) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    private fun notifyChanged() = listeners.forEach { it() }

    val recording get() = WeavePrefs.clipboardRecord(kb.prefs)

    /** 刚同意记录时把当前剪贴板也记下。 Record the current clip right after the user opts in. */
    fun recordCurrent() {
        if (!recording || kb.controller.isSensitiveField) return
        val text = currentClip() ?: return
        lastSeen = text
        if (history.add(text, System.currentTimeMillis())) notifyChanged()
    }

    /** 清空全部历史（含已固定）。 Clear all history, pinned included. */
    fun clearHistory() {
        history.clearAll()
        notifyChanged()
    }

    /** 当前系统剪贴板文本（记录关闭时面板只显示它）。 Current system clip text. */
    fun currentClip(): String? = runCatching {
        cm?.primaryClip?.takeIf { it.itemCount > 0 && !isSensitive(it) }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 键盘显示时补查一次（进程未存活期间的复制）。 Re-check on show. */
    fun onShown() = onClip(fresh = false)

    private fun onClip(fresh: Boolean) {
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0 || isSensitive(clip) || kb.controller.isSensitiveField) return
        val text = runCatching { clip.getItemAt(0).coerceToText(ctx)?.toString() }.getOrNull()
        if (text.isNullOrBlank() || text == lastSeen) return
        lastSeen = text
        val now = System.currentTimeMillis()
        val recent = fresh || now - clip.description.timestamp < 30_000
        if (recording) {
            history.add(text, now)
            notifyChanged()
        }
        if (recent) kb.onNewClip(text)
    }

    private fun isSensitive(clip: android.content.ClipData): Boolean {
        val extras = clip.description.extras ?: return false
        return extras.getBoolean(EXTRA_IS_SENSITIVE) || extras.getBoolean("android.content.extra.IS_SENSITIVE")
    }

    fun release() { runCatching { cm?.removePrimaryClipChangedListener(clipListener) } }

    fun changed() = notifyChanged()

    companion object {
        /** 相对 filesDir。 Relative to filesDir. */
        const val HISTORY_FILE = "clipboard/history.json"
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
    override val view = LinearLayout(kb.ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(-1, kb.metrics.dp(44f).toInt()))
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
    }

    override fun applyTheme() {
        (header.layoutParams as LinearLayout.LayoutParams).height = kb.metrics.dp(44f).toInt()
        header.invalidate(); grid.rebuild()
    }

    override fun onShow() {
        selecting = false; selected.clear(); confirmClear = false; actionFor = -1; pinnedOnly = false
        reload()
        grid.scrollToTop()
    }

    fun reload() {
        val now = System.currentTimeMillis()
        items = when {
            privateField || asking -> emptyList()
            mode == Mode.CLIPBOARD && !repo.recording -> repo.currentClip()?.let { listOf(ClipItem(-1, it, now)) } ?: emptyList()
            pinnedOnly -> store.list(now).filter { it.pinned }
            else -> store.list(now)
        }
        selected.retainAll(items.map { it.id }.toSet())
        grid.rebuild()
        header.invalidate()
    }

    private fun setRecording(on: Boolean) {
        kb.prefs.edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, on).apply()
        if (on) repo.recordCurrent()
        reload()
    }

    private fun commit(item: ClipItem) {
        kb.controller.onText(item.text)
        if (mode == Mode.PHRASES) kb.closePanel()
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

    // ------------------------------------------------------------------ header

    @SuppressLint("ViewConstructor")
    private inner class Header(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        /** 右侧按钮区（从右往左）。 Right-side hit areas, right to left. */
        private val hits = Array(3) { RectF() }
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
                    val icons = if (mode == Mode.CLIPBOARD) intArrayOf(R.drawable.ic_delete, R.drawable.ic_edit, R.drawable.ic_pin)
                    else intArrayOf(R.drawable.ic_edit, R.drawable.ic_plus)
                    var right = width - m.dp(8f)
                    for ((i, ic) in icons.withIndex()) {
                        val r = hits[i]
                        r.set(right - m.dp(36f), cy - m.dp(18f), right, cy + m.dp(18f))
                        val on = mode == Mode.CLIPBOARD && ic == R.drawable.ic_pin && pinnedOnly
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
                0 -> if (items.any { !it.pinned }) { confirmClear = true; header.invalidate() }
                1 -> if (items.isNotEmpty()) { selecting = true; actionFor = -1; reload() }
                2 -> { pinnedOnly = !pinnedOnly; reload() }
            }
            else -> when (i) {
                0 -> addPhrase()
                1 -> if (items.isNotEmpty()) { selecting = true; actionFor = -1; reload() }
            }
        }
    }

    // ------------------------------------------------------------------ cards

    @SuppressLint("ViewConstructor")
    private inner class Cards(c: Context) : ScrollGridView(c) {
        private val rects = ArrayList<RectF>()
        private val layouts = ArrayList<StaticLayout>()
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val small = Paint(Paint.ANTI_ALIAS_FLAG)
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
            body.textSize = m.dp(14f)
            val s = PROMPT
            promptText = StaticLayout.Builder.obtain(s, 0, s.length, body, (width - 2 * pad).toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(m.dp(20f) - body.fontMetrics.let { it.descent - it.ascent }, 1f).build()
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
            body.textSize = m.dp(14f)
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
                    rowH = max(rowH, m.dp(10f) + lay.height + m.dp(6f) + m.dp(14f) + m.dp(10f))
                    i++
                }
                for (j in start until i) {
                    val col = j - start
                    rects += RectF(pad + col * (cw + gap), y, pad + col * (cw + gap) + cw, y + rowH)
                }
                y += rowH + gap
            }
            bottom = y + pad
            invalidate()
            a11yChanged()
        }

        private fun layoutFor(text: String, w: Int): StaticLayout {
            val m = kb.metrics
            val s = text.replace('\n', ' ').replace('\t', ' ').let { if (it.length > 300) it.substring(0, 300) else it }
            return StaticLayout.Builder.obtain(s, 0, s.length, body, w.coerceAtLeast(1))
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(m.dp(20f) - body.fontMetrics.let { it.descent - it.ascent }, 1f)
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
                if (items[i].id == actionFor) {
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
                    1 -> { kb.controller.onText(item.text); kb.closePanel(); return }
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
                c.translate(rc.left + m.dp(10f), rc.top + m.dp(10f))
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
                if (item.id == actionFor) drawActions(c, rc, item)
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
            val labels = arrayOf(if (item.pinned) "取消固定" else "固定", "编辑上屏", "删除")
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
        private const val PROMPT = "是否记录剪贴板历史？\n复制的文字会在本机保存 24 小时，密码框中不记录。"
    }
}
