package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.StaticLayout
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.R
import com.weavetext.ime.settings.PermissionActivity
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.LOCAL_ENGINE_ID
import com.weavetext.ime.voice.VoicePlugin
import kotlin.math.max
import kotlin.math.min

/** 插件图标：PNG 圆形/圆角裁切，缺失时用名称首字头像。 Plugin icon or letter avatar. */
class PluginIcon {
    private var id: String? = null
    private var version: String? = null
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val matrix = Matrix()
    private val src = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).zh()

    fun bind(plugin: VoicePlugin?) {
        if (plugin?.id == id && plugin?.version == version) return
        id = plugin?.id; version = plugin?.version
        bitmap = plugin?.iconPng?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    /** 在 [r] 内绘制，[radius] 圆角（取一半宽即为圆形）。 */
    fun draw(c: Canvas, r: RectF, radius: Float, name: String, accent: Int, onAccent: Int) {
        val b = bitmap
        val s = shader
        if (b != null && s != null) {
            src.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
            matrix.setRectToRect(src, r, Matrix.ScaleToFit.CENTER)
            s.setLocalMatrix(matrix)
            p.shader = s
            c.drawRoundRect(r, radius, radius, p)
            p.shader = null
            return
        }
        p.color = accent
        c.drawRoundRect(r, radius, radius, p)
        p.color = onAccent
        p.textAlign = Paint.Align.CENTER
        p.textSize = r.height() * 0.55f
        p.typeface = Typeface.DEFAULT_BOLD
        val ch = name.firstOrNull()?.toString() ?: "?"
        c.drawText(ch, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2, p)
    }
}

/**
 * 语音输入面板（02 §12）：接管顶栏 + 主区域。点按说话 / 按住说话、实时文本、波形、引擎 chip。
 * Voice panel over top bar + main area.
 */
class VoicePanel(kb: WeaveKeyboard) : KbPanel(kb), PrefAware {
    override val full = true
    /** 与浮动语音条共用的会话。 Session shared with the floating strip. */
    val session get() = kb.voiceSession
    override val view = VoiceView(kb.ctx)

    /** 上次见到的提示：变了说明换了引擎（可能已改为默认用本地识别），要重读引擎。 Last notice seen. */
    private var seenNotice: String? = null

    init {
        session.addListener {
            if (session.notice != seenNotice) { seenNotice = session.notice; view.refreshEngine() }
            view.invalidate()
            if (session.active && session.state != VoiceSession.State.LISTENING) view.postInvalidateDelayed(40)
        }
        com.weavetext.ime.voice.VoiceAutoDownload.addListener { view.postInvalidate() }
        // 没有引擎：打开面板显示安装引导，而不是报错。 No engine: show the guidance in the panel, not an error.
        session.onNoEngine = { if (kb.panel !== this) kb.showPanel("voice") else { view.refreshEngine(); view.invalidate() } }
    }

    private val holdMode get() = WeavePrefs.voiceMode(kb.prefs) == "hold"

    override fun applyTheme() { kb.paintBackground(view); view.invalidate() }
    override fun onPref(key: String?) { if (key == WeavePrefs.VOICE_MODE) view.invalidate() }

    override fun onShow() {
        runCatching { VoiceAccess.engines(kb.ctx).recheck() }
        session.warmUp()
        view.refreshEngine()
        view.invalidate()
    }

    override fun onHide() = stopSession()

    /** 顶栏 🎙 点击进入：点按模式下直接开始。 Opened from the toolbar: tap mode starts right away. */
    fun startFromToolbar() {
        if (!holdMode && session.hasPermission()) session.start()
    }

    fun stopSession() = session.detach()

    @SuppressLint("ViewConstructor")
    inner class VoiceView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).zh()
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        private val icon = PluginIcon()
        private var plugin: VoicePlugin? = null
        private var engines = 0
        /** 本地离线识别在引擎列表里。 The local engine is listed. */
        private var hasLocal = false
        /** 同时使用的其它引擎数。 Number of extra engines used together. */
        private var extras = 0

        // 命中区 / hit areas
        private val languageRects = List(3) { RectF() }
        private val contentTop get() = kb.metrics.topBar + kb.metrics.dp(28f)
        private val close = RectF(); private val chip = RectF(); private val gear = RectF()
        private val comma = RectF(); private val kbd = RectF(); private val del = RectF(); private val enter = RectF()
        private val mic = RectF(); private val seg = RectF(); private val segTap = RectF(); private val segHold = RectF()
        private val permCard = RectF(); private val importBtn = RectF()
        private val offlineBtn = RectF()
        private val tmp = RectF()
        private val tmp2 = RectF()
        private val area = RectF()
        private val lines = ArrayList<String>()
        private var pressed = NONE
        private var downY = 0f
        private var downX = 0f
        private var holdActive = false
        private var holdCancel = false

        fun refreshEngine() {
            val e = runCatching { VoiceAccess.engines(kb.ctx) }.getOrNull()
            val list = e?.list().orEmpty()
            engines = list.size
            hasLocal = list.any { it.id == LOCAL_ENGINE_ID }
            plugin = e?.active()
            extras = ((e?.let { runCatching { it.selection().size }.getOrDefault(1) } ?: 1) - 1).coerceAtLeast(0)
            icon.bind(plugin)
        }

        private fun geometry() {
            val m = kb.metrics
            val w = width.toFloat()
            val h = height.toFloat()
            val top = m.topBar
            val gap = m.dp(6f)
            val lw = (w - m.dp(24f) - gap * 2) / 3
            for (i in languageRects.indices) {
                val left = m.dp(12f) + i * (lw + gap)
                languageRects[i].set(left, top, left + lw, contentTop - m.dp(3f))
            }
            close.set(m.dp(6f), 0f, m.dp(50f), top)
            gear.set(w - m.dp(50f), 0f, w - m.dp(6f), top)
            text.textSize = m.dp(14f); text.typeface = medium
            val name = chipName()
            val cw = m.dp(6f) + m.dp(20f) + m.dp(6f) + text.measureText(name) + m.dp(6f) + m.dp(16f) + m.dp(10f)
            chip.set(w / 2 - cw / 2, top / 2 - m.dp(16f), w / 2 + cw / 2, top / 2 + m.dp(16f))
            seg.set(0f, h - m.dp(28f), w, h)
            val ctlBottom = seg.top - m.dp(2f)
            val ctlTop = ctlBottom - m.dp(104f).coerceAtMost((h - top) * 0.5f)
            val kw = m.dp(64f); val kh = m.dp(44f)
            val midY = (ctlTop + ctlBottom) / 2
            comma.set(m.dp(12f), midY - m.dp(4f) - kh, m.dp(12f) + kw, midY - m.dp(4f))
            kbd.set(comma.left, midY + m.dp(4f), comma.right, midY + m.dp(4f) + kh)
            del.set(w - m.dp(12f) - kw, comma.top, w - m.dp(12f), comma.bottom)
            enter.set(del.left, kbd.top, del.right, kbd.bottom)
            val r = m.dp(36f)
            val micCy = midY - m.dp(9f)
            mic.set(w / 2 - r, micCy - r, w / 2 + r, micCy + r)
            permCard.set(w / 2 - m.dp(100f), micCy - m.dp(28f), w / 2 + m.dp(100f), micCy + m.dp(28f))
        }

        private fun chipName(): String {
            val n = plugin?.name ?: "未选择引擎"
            val base = if (n.length > 10) n.take(9) + "…" else n
            return if (extras > 0) "$base +$extras" else base
        }

        override fun onDraw(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            geometry()
            // 顶行 / top row
            pressBg(c, close, pressed == CLOSE)
            kb.icons.draw(c, R.drawable.ic_chevron_down, pal.icon, close.centerX(), close.centerY(), m.dp(24f))
            pressBg(c, gear, pressed == GEAR)
            kb.icons.draw(c, R.drawable.ic_settings, pal.icon, gear.centerX(), gear.centerY(), m.dp(22f))
            fill.color = pal.keyShadow
            tmp.set(chip); tmp.offset(0f, m.dp(1f))
            c.drawRoundRect(tmp, m.dp(16f), m.dp(16f), fill)
            fill.color = if (pressed == CHIP) pal.keyPressed else pal.key
            c.drawRoundRect(chip, m.dp(16f), m.dp(16f), fill)
            tmp.set(chip.left + m.dp(6f), chip.centerY() - m.dp(10f), chip.left + m.dp(26f), chip.centerY() + m.dp(10f))
            if (plugin != null) icon.draw(c, tmp, m.dp(10f), plugin!!.name, pal.keyAccent, pal.onAccent)
            text.textSize = m.dp(14f); text.typeface = medium; text.color = pal.label; text.textAlign = Paint.Align.LEFT
            c.drawText(chipName(), tmp.right + m.dp(6f), chip.centerY() - (text.ascent() + text.descent()) / 2, text)
            kb.icons.draw(c, R.drawable.ic_chevron_down, pal.icon, chip.right - m.dp(18f), chip.centerY(), m.dp(16f))

            val language = runCatching { VoiceAccess.engines(kb.ctx).language }.getOrDefault(com.weavetext.ime.voice.VoiceLanguage.MIXED)
            for ((i, mode) in com.weavetext.ime.voice.VoiceLanguage.entries.withIndex()) {
                val r = languageRects[i]
                fill.color = if (mode == language) pal.accentSoft else pal.keyFunc
                c.drawRoundRect(r, m.dp(8f), m.dp(8f), fill)
                text.textAlign = Paint.Align.CENTER; text.textSize = m.dp(12f); text.typeface = medium
                text.color = if (mode == language) pal.keyAccent else pal.labelSecondary
                c.drawText(mode.label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
            if (choosing()) {
                drawResults(c)
                if (session.active && session.state != VoiceSession.State.LISTENING) postInvalidateDelayed(40)
                return
            }
            drawTranscript(c)
            drawWave(c)
            // 侧键 / side keys
            sideKey(c, comma, pressed == COMMA); drawLabel(c, comma, "，")
            sideKey(c, kbd, pressed == KBD); kb.icons.draw(c, R.drawable.ic_keyboard, pal.icon, kbd.centerX(), kbd.centerY(), m.dp(22f))
            sideKey(c, del, pressed == DEL); kb.icons.draw(c, R.drawable.ic_backspace, pal.icon, del.centerX(), del.centerY(), m.dp(22f))
            sideKey(c, enter, pressed == ENTER); kb.icons.draw(c, R.drawable.ic_enter, pal.icon, enter.centerX(), enter.centerY(), m.dp(22f))
            drawMic(c)
            drawSeg(c)
            if (session.active && session.state != VoiceSession.State.LISTENING) postInvalidateDelayed(40)
        }

        private fun pressBg(c: Canvas, r: RectF, on: Boolean) {
            if (!on) return
            val m = kb.metrics
            fill.color = kb.palette.toolbarActive
            tmp.set(r.centerX() - m.dp(20f), r.centerY() - m.dp(18f), r.centerX() + m.dp(20f), r.centerY() + m.dp(18f))
            c.drawRoundRect(tmp, m.dp(10f), m.dp(10f), fill)
        }

        private fun sideKey(c: Canvas, r: RectF, on: Boolean) {
            val pal = kb.palette
            val m = kb.metrics
            KeyPainter.draw(c, r, tmp, tmp2, fill, if (on) pal.keyFuncPressed else pal.keyFunc, on, m.keyRadiusLarge, pal, m)
        }

        private fun drawLabel(c: Canvas, r: RectF, s: String) {
            text.textAlign = Paint.Align.CENTER; text.textSize = kb.metrics.dp(18f); text.typeface = medium; text.color = kb.palette.label
            c.drawText(s, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
        }

        private fun compactWide() = height < kb.metrics.dp(240f) && mic.left - comma.right >= kb.metrics.dp(122f)

        private fun transcriptArea(out: RectF) {
            val m = kb.metrics
            val bottom = if (compactWide()) mic.top - m.dp(8f) else comma.top - m.dp(40f)
            out.set(m.dp(20f), contentTop + m.dp(2f), width - m.dp(20f), bottom)
        }

        /**
         * 没有可用引擎：说明原因并给出能直接点的办法，而不是只报错。
         * No engine: explain why and offer tappable ways out instead of only an error.
         */
        private fun drawNoEngine(c: Canvas, title: String) {
            val pal = kb.palette
            val m = kb.metrics
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = m.dp(15f); text.color = pal.labelSecondary
            val titleSize = min(m.dp(15f), m.dp(15f) * (area.width() / max(1f, text.measureText(title))))
            text.textSize = titleSize
            c.drawText(title, area.centerX(), area.centerY() - m.dp(12f), text)
            clearPills()
            val labels = mutableListOf(offlineBtn to "下载离线语音包")
            text.textSize = m.dp(13f); text.typeface = medium
            var pad = m.dp(12f); val gap = m.dp(8f); val bh = m.dp(32f)
            fun width() = labels.sumOf { (text.measureText(it.second) + 2 * pad).toDouble() }.toFloat() + gap * (labels.size - 1)
            // 窄屏放不下一行：先缩小到 0.85 倍，仍放不下就去掉末尾的次要项（导入插件在设置里也能找到）。
            // Too narrow for one row: shrink to 0.85×, then drop trailing secondary pills (import is also in settings).
            if (width() > area.width()) {
                val k = max(0.85f, area.width() / width())
                text.textSize *= k; pad *= k
                while (labels.size > 1 && width() > area.width()) labels.removeAt(labels.size - 1)
            }
            val total = width()
            var x = area.centerX() - total / 2
            val top = area.centerY() + m.dp(2f)
            for ((rect, label) in labels) {
                val bw = text.measureText(label) + 2 * pad
                rect.set(x, top, x + bw, top + bh)
                val id = OFFLINE
                fill.color = if (pressed == id) pal.keyPressed else pal.card
                c.drawRoundRect(rect, bh / 2, bh / 2, fill)
                text.color = pal.candidateFirst
                c.drawText(label, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2, text)
                x += bw + gap
            }
        }

        private fun drawTranscript(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            transcriptArea(area)
            if (engines == 0) {
                val auto = com.weavetext.ime.voice.VoiceAutoDownload.state
                drawNoEngine(c, if (auto == com.weavetext.ime.voice.VoiceAutoDownload.State.Downloading) "正在自动下载中英混合语音…" else "正在准备中英混合语音")
                return
            }
            clearPills()
            val done = session.committed.toString()
            val part = session.partial
            if (done.isEmpty() && part.isEmpty()) {
                // 引擎的一行提示（如已改用本地识别）。 The engine's one-line note (e.g. switched to local).
                val note = session.notice ?: return
                text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.color = pal.labelSecondary
                text.textSize = m.dp(14f)
                text.textSize = min(m.dp(14f), m.dp(14f) * (area.width() / max(1f, text.measureText(note))))
                c.drawText(note, area.centerX(), area.top + m.dp(26f) * 0.8f, text)
                return
            }
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = m.dp(18f)
            val lineH = m.dp(26f)
            val maxLines = max(1, (area.height() / lineH).toInt()).coerceAtMost(3)
            // 按宽度折行（已确定 + 中间结果），只显示最后几行。 Wrap and keep the last lines.
            val all = done + part
            lines.clear()
            var start = 0
            while (start < all.length) {
                val n = text.breakText(all, start, all.length, true, area.width(), null).coerceAtLeast(1)
                lines += all.substring(start, start + n)
                start += n
            }
            val first = max(0, lines.size - maxLines)
            var offset = lines.subList(0, first).sumOf { it.length }
            var y = area.top + lineH * 0.8f
            for (i in first until lines.size) {
                val s = lines[i]
                // 已确定部分 label，中间结果 labelSecondary。 Final vs interim colours.
                val split = (done.length - offset).coerceIn(0, s.length)
                var x = area.left
                if (split > 0) { text.color = pal.label; c.drawText(s, 0, split, x, y, text); x += text.measureText(s, 0, split) }
                if (split < s.length) { text.color = pal.labelSecondary; c.drawText(s, split, s.length, x, y, text) }
                offset += s.length
                y += lineH
            }
        }

        private fun clearPills() {
            importBtn.setEmpty(); offlineBtn.setEmpty()
        }

        private fun drawWave(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val cy = if (compactWide()) mic.centerY() else comma.top - m.dp(20f)
            val bw = m.dp(3f); val gap = m.dp(3f)
            val total = 17 * bw + 16 * gap
            var x = if (compactWide()) mic.left - m.dp(20f) - total else width / 2f - total / 2
            fill.color = pal.voiceWave
            val listening = session.state == VoiceSession.State.LISTENING
            for (i in 0 until 17) {
                val amplitude = if (listening) session.levels[i] else 0f
                val hh = m.dp(4f) + m.dp(32f) * amplitude
                tmp.set(x, cy - hh / 2, x + bw, cy + hh / 2)
                c.drawRoundRect(tmp, bw / 2, bw / 2, fill)
                x += bw + gap
            }
        }

        private fun drawMic(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val st = session.state
            if (!session.hasPermission()) {
                fill.color = if (pressed == PERM) pal.keyPressed else pal.card
                c.drawRoundRect(permCard, m.dp(12f), m.dp(12f), fill)
                kb.icons.draw(c, R.drawable.ic_mic, pal.keyAccent, permCard.left + m.dp(24f), permCard.centerY(), m.dp(22f))
                text.textAlign = Paint.Align.LEFT; text.typeface = medium; text.textSize = m.dp(14f); text.color = pal.label
                c.drawText("需要麦克风权限", permCard.left + m.dp(44f), permCard.centerY() - m.dp(3f), text)
                text.typeface = Typeface.DEFAULT; text.textSize = m.dp(12f); text.color = pal.candidateFirst
                c.drawText("去授权 ›", permCard.left + m.dp(44f), permCard.centerY() + m.dp(14f), text)
                return
            }
            val hold = holdMode
            var r = mic.width() / 2
            if (hold && holdActive) r = m.dp(40f)
            val cx = mic.centerX(); val cy = mic.centerY()
            val listening = st == VoiceSession.State.LISTENING
            if (listening && !holdCancel) {
                // 呼吸环 / breathing ring
                val phase = (SystemClock.uptimeMillis() % 1200) / 1200f
                fill.color = pal.keyAccent
                fill.alpha = (255 * 0.35f * (1 - phase)).toInt()
                c.drawCircle(cx, cy, r * (1f + 0.18f * phase), fill)
                fill.alpha = 255
            }
            fill.color = when {
                holdCancel -> pal.danger
                pressed == MIC -> pal.keyAccentPressed
                else -> pal.keyAccent
            }
            c.drawCircle(cx, cy, r, fill)
            when {
                holdCancel -> kb.icons.draw(c, R.drawable.ic_close, pal.onDanger, cx, cy, m.dp(32f))
                st == VoiceSession.State.CONNECTING || st == VoiceSession.State.FINALIZING -> {
                    fill.style = Paint.Style.STROKE; fill.strokeWidth = m.dp(3f); fill.color = pal.onAccent
                    fill.strokeCap = Paint.Cap.ROUND
                    val a = (SystemClock.uptimeMillis() % 900) / 900f * 360f
                    tmp.set(cx - m.dp(14f), cy - m.dp(14f), cx + m.dp(14f), cy + m.dp(14f))
                    c.drawArc(tmp, a, 270f, false, fill)
                    fill.style = Paint.Style.FILL
                }
                listening -> kb.icons.draw(c, R.drawable.ic_stop, pal.onAccent, cx, cy, m.dp(32f))
                else -> kb.icons.draw(c, R.drawable.ic_mic, pal.onAccent, cx, cy, m.dp(32f))
            }
            val hint = when {
                holdCancel -> "松手取消"
                st == VoiceSession.State.ERROR -> (session.error ?: "识别失败") + "，点击重试"
                st == VoiceSession.State.CONNECTING -> "正在加载离线模型…"
                st == VoiceSession.State.FINALIZING -> "识别中…"
                listening -> if (hold) "松手结束，上滑取消" else "正在聆听 · 可以停顿，点击结束"
                engines == 0 -> "请先下载离线语音包"
                hold -> "按住 说话"
                else -> "点击开始说话"
            }
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = m.dp(12f)
            text.color = if ((st == VoiceSession.State.ERROR) || holdCancel) pal.danger else pal.labelSecondary
            c.drawText(hint, cx, mic.bottom + m.dp(6f) - text.ascent(), text)
        }

        private fun drawSeg(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val hold = holdMode
            text.textSize = m.dp(12f)
            val cy = seg.top + m.dp(11f)
            val base = cy - (text.ascent() + text.descent()) / 2
            text.textAlign = Paint.Align.RIGHT
            text.typeface = if (!hold) medium else Typeface.DEFAULT
            text.color = if (!hold) pal.label else pal.labelHint
            c.drawText("点按说话", width / 2f - m.dp(12f), base, text)
            segTap.set(width / 2f - m.dp(90f), seg.top, width / 2f, seg.bottom)
            text.textAlign = Paint.Align.LEFT
            text.typeface = if (hold) medium else Typeface.DEFAULT
            text.color = if (hold) pal.label else pal.labelHint
            c.drawText("按住说话", width / 2f + m.dp(12f), base, text)
            segHold.set(width / 2f, seg.top, width / 2f + m.dp(90f), seg.bottom)
            fill.color = pal.divider
            c.drawRect(width / 2f - m.dp(0.5f), cy - m.dp(5f), width / 2f + m.dp(0.5f), cy + m.dp(5f), fill)
        }

        // ------------------------------------------------------------ multi-engine results (06 §6)

        private val listArea = RectF()
        private val cancelBtn = RectF()
        private val redoBtn = RectF()
        private val commitBtn = RectF()
        private var listScroll = 0f
        private var listDrag = false
        private var pressedRow = -1
        private var resultSet: com.weavetext.ime.voice.MultiEngineResults? = null
        private val rowOffsets = HashMap<String, Float>()
        private val rowWidths = HashMap<String, Float>()
        private var dragRow = -1
        private var dragAxis = 0
        private var downOffset = 0f
        private var previewId: String? = null
        private var previewScroll = 0f
        private var previewMaxScroll = 0f
        private var openedPreview = false
        private val expandResult = Runnable {
            val row = session.results?.rows()?.getOrNull(pressedRow) ?: return@Runnable
            previewId = row.id
            previewScroll = 0f
            pressedRow = -1
            openedPreview = true
            kb.feedback.haptic(this)
            invalidate()
        }

        private fun choosing() = session.state == VoiceSession.State.CHOOSING && session.results != null

        private fun rowH(): Float {
            val n = session.results?.rows()?.size?.coerceIn(1, 3) ?: 3
            return min(kb.metrics.dp(50f), (listArea.height() - (n - 1) * rowGap()) / n).coerceAtLeast(kb.metrics.dp(27f))
        }
        private fun rowGap() = kb.metrics.dp(6f)

        private fun resultsGeometry() {
            val m = kb.metrics
            val w = width.toFloat(); val h = height.toFloat()
            val bar = m.dp(56f)
            listArea.set(m.dp(12f), contentTop + m.dp(2f), w - m.dp(12f), h - bar - m.dp(2f))
            val cy = h - bar / 2
            cancelBtn.set(m.dp(12f), cy - m.dp(20f), m.dp(12f) + m.dp(88f), cy + m.dp(20f))
            commitBtn.set(w - m.dp(12f) - m.dp(88f), cy - m.dp(20f), w - m.dp(12f), cy + m.dp(20f))
            redoBtn.set(w / 2 - m.dp(24f), cy - m.dp(24f), w / 2 + m.dp(24f), cy + m.dp(24f))
        }

        private fun maxListScroll(n: Int) = max(0f, n * (rowH() + rowGap()) - rowGap() - listArea.height())

        private fun drawResults(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val res = session.results ?: return
            if (resultSet !== res) {
                resultSet = res; rowOffsets.clear(); rowWidths.clear()
                previewId = null; previewScroll = 0f; listScroll = 0f
            }
            resultsGeometry()
            val rows = res.rows()
            val previewIndex = rows.indexOfFirst { it.id == previewId }
            val def = if (previewIndex >= 0 && rows[previewIndex].selectable) previewIndex else
                if (previewId == null) session.defaultRow else -1
            listScroll = listScroll.coerceIn(0f, maxListScroll(rows.size))
            c.save()
            c.clipRect(listArea)
            if (previewIndex < 0) for ((i, r) in rows.withIndex()) {
                val top = listArea.top + i * (rowH() + rowGap()) - listScroll
                if (top > listArea.bottom || top + rowH() < listArea.top) continue
                tmp.set(listArea.left, top, listArea.right, top + rowH())
                fill.color = when { i == pressedRow && r.selectable -> pal.keyPressed; i == def -> pal.accentSoft; else -> pal.card }
                c.drawRoundRect(tmp, m.dp(10f), m.dp(10f), fill)
                if (i == def) {
                    fill.style = Paint.Style.STROKE; fill.strokeWidth = m.dp(1.5f); fill.color = pal.keyAccent
                    c.drawRoundRect(tmp, m.dp(10f), m.dp(10f), fill)
                    fill.style = Paint.Style.FILL
                }
                val left = tmp.left + m.dp(12f)
                val right = tmp.right - m.dp(12f)
                // 第一行：引擎名 + 状态 / line 1: engine name + status
                val status = when (r.status) {
                    com.weavetext.ime.voice.MultiEngineResults.Status.LISTENING,
                    com.weavetext.ime.voice.MultiEngineResults.Status.LOADING -> "识别中…"
                    com.weavetext.ime.voice.MultiEngineResults.Status.DONE -> r.latencyMs?.let { "%.1f 秒".format(java.util.Locale.ROOT, it / 1000f) } ?: ""
                    else -> r.error ?: "失败"
                }
                val bad = r.status == com.weavetext.ime.voice.MultiEngineResults.Status.ERROR ||
                    r.status == com.weavetext.ime.voice.MultiEngineResults.Status.TIMEOUT
                text.textSize = if (rowH() < m.dp(40f)) m.dp(10f) else m.dp(12f); text.typeface = Typeface.DEFAULT
                text.textAlign = Paint.Align.RIGHT
                text.color = if (bad) pal.danger else pal.labelHint
                val line1 = top + min(m.dp(17f), rowH() * 0.38f)
                c.drawText(status, right, line1, text)
                val statusW = text.measureText(status) + m.dp(8f)
                text.textAlign = Paint.Align.LEFT
                text.typeface = medium
                text.color = if (i == def) pal.keyAccent else pal.labelSecondary
                val name = (if (r.id == res.primaryId) "★ " else "") + r.name
                c.drawText(android.text.TextUtils.ellipsize(name, text, right - left - statusW, android.text.TextUtils.TruncateAt.END).toString(), left, line1, text)
                // 第二行：识别文字 / line 2: transcript
                text.typeface = Typeface.DEFAULT; text.textSize = if (rowH() < m.dp(40f)) m.dp(13f) else m.dp(16f)
                val body = r.text.ifEmpty { if (r.pending) "…" else "" }
                text.color = if (r.selectable) pal.label else pal.labelSecondary
                val singleLine = body.replace('\n', ' ')
                val overflow = (text.measureText(singleLine) - (right - left)).coerceAtLeast(0f)
                rowWidths[r.id] = overflow
                val offset = (rowOffsets[r.id] ?: 0f).coerceIn(0f, overflow)
                rowOffsets[r.id] = offset
                c.save()
                c.clipRect(left, top + rowH() * 0.40f, right, top + rowH())
                c.drawText(singleLine, left - offset, top + min(m.dp(40f), rowH() * 0.82f), text)
                c.restore()
                if (overflow > 0f) {
                    fill.color = pal.divider
                    c.drawRect(left, top + rowH() - m.dp(3f), right, top + rowH() - m.dp(2f), fill)
                    val thumb = ((right - left) * (right - left) / text.measureText(singleLine)).coerceAtLeast(m.dp(16f))
                    val position = (right - left - thumb) * offset / overflow
                    fill.color = pal.keyAccent
                    c.drawRect(left + position, top + rowH() - m.dp(3f), left + position + thumb, top + rowH() - m.dp(2f), fill)
                }
            }
            if (previewIndex >= 0) drawResultPreview(c, rows[previewIndex], previewIndex, rows.size)
            c.restore()
            // 底部操作 / bottom actions
            text.textSize = m.dp(14f); text.typeface = medium; text.textAlign = Paint.Align.CENTER
            fill.color = if (pressed == R_CANCEL) pal.keyFuncPressed else pal.keyFunc
            c.drawRoundRect(cancelBtn, m.dp(20f), m.dp(20f), fill)
            text.color = pal.label
            c.drawText(if (previewId != null) "返回" else "取消", cancelBtn.centerX(), cancelBtn.centerY() - (text.ascent() + text.descent()) / 2, text)
            val canCommit = def >= 0
            fill.color = when { !canCommit -> pal.keyFunc; pressed == R_COMMIT -> pal.keyAccentPressed; else -> pal.keyAccent }
            c.drawRoundRect(commitBtn, m.dp(20f), m.dp(20f), fill)
            text.color = if (canCommit) pal.onAccent else pal.labelDisabled
            c.drawText("上屏", commitBtn.centerX(), commitBtn.centerY() - (text.ascent() + text.descent()) / 2, text)
            fill.color = if (pressed == R_REDO) pal.keyPressed else pal.key
            c.drawCircle(redoBtn.centerX(), redoBtn.centerY(), redoBtn.width() / 2, fill)
            kb.icons.draw(c, R.drawable.ic_mic, pal.keyAccent, redoBtn.centerX(), redoBtn.centerY(), m.dp(24f))
            if (rows.any { it.pending }) postInvalidateDelayed(250)
        }

        private fun rowAt(x: Float, y: Float): Int {
            if (!listArea.contains(x, y)) return -1
            val i = ((y - listArea.top + listScroll) / (rowH() + rowGap())).toInt()
            val within = (y - listArea.top + listScroll) - i * (rowH() + rowGap()) <= rowH()
            return if (within && i in 0 until (session.results?.rows()?.size ?: 0)) i else -1
        }

        private fun drawResultPreview(c: Canvas, row: com.weavetext.ime.voice.MultiEngineResults.Row, index: Int, count: Int) {
            val m = kb.metrics
            fill.color = kb.palette.card
            c.drawRoundRect(listArea, m.dp(10f), m.dp(10f), fill)
            text.typeface = medium; text.textSize = m.dp(12f); text.color = kb.palette.keyAccent
            text.textAlign = Paint.Align.LEFT
            val title = "${row.name}  ${index + 1}/$count · 左右切换"
            c.drawText(android.text.TextUtils.ellipsize(title, text, listArea.width() - m.dp(24f), android.text.TextUtils.TruncateAt.END).toString(), listArea.left + m.dp(12f), listArea.top + m.dp(20f), text)
            val top = listArea.top + m.dp(30f)
            text.typeface = Typeface.DEFAULT; text.textSize = m.dp(16f); text.color = kb.palette.label
            val body = row.text.ifEmpty { row.error ?: "正在识别…" }
            val layout = StaticLayout.Builder.obtain(body, 0, body.length, text, (listArea.width() - m.dp(24f)).toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(m.dp(3f), 1f).build()
            previewMaxScroll = (layout.height - (listArea.bottom - top - m.dp(8f))).coerceAtLeast(0f)
            previewScroll = previewScroll.coerceIn(0f, previewMaxScroll)
            c.save()
            c.clipRect(listArea.left, top, listArea.right, listArea.bottom - m.dp(8f))
            c.translate(listArea.left + m.dp(12f), top - previewScroll)
            layout.draw(c)
            c.restore()
        }

        private fun resultsTouch(e: MotionEvent): Boolean {
            val m = kb.metrics
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resultsGeometry()
                    downX = e.x; downY = e.y; listDrag = false; dragAxis = 0; openedPreview = false
                    pressed = when {
                        languageRects.any { it.contains(e.x, e.y) } -> LANGUAGE_BASE + languageRects.indexOfFirst { it.contains(e.x, e.y) }
                        close.contains(e.x, e.y) -> CLOSE
                        gear.contains(e.x, e.y) -> GEAR
                        chip.contains(e.x, e.y) -> CHIP
                        cancelBtn.contains(e.x, e.y) -> R_CANCEL
                        commitBtn.contains(e.x, e.y) -> R_COMMIT
                        redoBtn.contains(e.x, e.y) -> R_REDO
                        else -> NONE
                    }
                    dragRow = if (pressed == NONE && previewId == null) rowAt(e.x, e.y) else -1
                    pressedRow = dragRow
                    downOffset = session.results?.rows()?.getOrNull(dragRow)?.id?.let { rowOffsets[it] } ?: 0f
                    downScroll = if (previewId != null) previewScroll else listScroll
                    if (pressed != NONE || pressedRow >= 0) kb.feedback.key(this)
                    if (pressedRow >= 0) postDelayed(expandResult, 450)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (pressed == NONE && listArea.contains(downX, downY) && !openedPreview) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (dragAxis == 0 && max(kotlin.math.abs(dx), kotlin.math.abs(dy)) > m.dp(8f)) {
                        removeCallbacks(expandResult)
                        dragAxis = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1 else 2
                        listDrag = true; pressedRow = -1
                    }
                    if (dragAxis == 1 && previewId == null) {
                        session.results?.rows()?.getOrNull(dragRow)?.id?.let { id ->
                            rowOffsets[id] = (downOffset - dx).coerceIn(0f, rowWidths[id] ?: 0f)
                        }
                    } else if (dragAxis == 2) {
                        if (previewId != null) previewScroll = (downScroll - dy).coerceIn(0f, previewMaxScroll)
                        else listScroll = (downScroll - dy).coerceIn(0f, maxListScroll(session.results?.rows()?.size ?: 0))
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(expandResult)
                    val p = pressed; val row = pressedRow
                    pressed = NONE; pressedRow = -1
                    if (previewId != null && dragAxis == 1 && kotlin.math.abs(e.x - downX) > m.dp(48f)) {
                        val rows = session.results?.rows().orEmpty()
                        val index = rows.indexOfFirst { it.id == previewId }
                        val next = (index + if (e.x < downX) 1 else -1).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                        previewId = rows.getOrNull(next)?.id; previewScroll = 0f
                    } else if (!listDrag && !openedPreview) when {
                        row >= 0 && row == rowAt(e.x, e.y) -> session.choose(row)
                        p == R_CANCEL && cancelBtn.contains(e.x, e.y) -> if (previewId != null) { previewId = null; previewScroll = 0f } else session.cancel()
                        p == R_COMMIT && commitBtn.contains(e.x, e.y) -> {
                            val index = if (previewId == null) session.defaultRow else session.results?.rows()?.indexOfFirst { it.id == previewId } ?: -1
                            if (index >= 0) session.choose(index)
                        }
                        p == R_REDO && redoBtn.contains(e.x, e.y) -> { previewId = null; listScroll = 0f; session.start() }
                        p != NONE && p == hitAt(e.x, e.y) -> onTap(p)
                    }
                    listDrag = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(expandResult)
                    pressed = NONE; pressedRow = -1; listDrag = false; dragAxis = 0; invalidate()
                }
            }
            return true
        }

        private var downScroll = 0f
        private var resultsGesture = false

        private fun hitAt(x: Float, y: Float): Int = when {
            languageRects.any { it.contains(x, y) } -> LANGUAGE_BASE + languageRects.indexOfFirst { it.contains(x, y) }
            close.contains(x, y) -> CLOSE
            gear.contains(x, y) -> GEAR
            chip.contains(x, y) -> CHIP
            comma.contains(x, y) -> COMMA
            kbd.contains(x, y) -> KBD
            del.contains(x, y) -> DEL
            enter.contains(x, y) -> ENTER
            !session.hasPermission() && permCard.contains(x, y) -> PERM
            session.hasPermission() && mic.contains(x, y) -> MIC
            !importBtn.isEmpty && importBtn.contains(x, y) -> IMPORT
            !offlineBtn.isEmpty && offlineBtn.contains(x, y) -> OFFLINE
            segTap.contains(x, y) -> SEG_TAP
            segHold.contains(x, y) -> SEG_HOLD
            else -> NONE
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // 结果列表的手势从按下到抬起都交给 resultsTouch（选中后会话会立即回到空闲）。
            // A result-list gesture stays with resultsTouch until it ends (choosing resets the session).
            if (e.actionMasked == MotionEvent.ACTION_DOWN) resultsGesture = choosing()
            if (resultsGesture) {
                val end = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
                return resultsTouch(e).also { if (end) resultsGesture = false }
            }
            val m = kb.metrics
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hitAt(e.x, e.y)
                    downY = e.y
                    if (pressed != NONE) kb.feedback.key(this)
                    if (pressed == MIC && holdMode && engines > 0) {
                        holdActive = session.start()
                        holdCancel = false
                    }
                    if (pressed == DEL) postDelayed(repeatDel, 400)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (holdActive) {
                    val cancel = downY - e.y > m.dp(64f)
                    if (cancel != holdCancel) { holdCancel = cancel; kb.feedback.haptic(this); invalidate() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(repeatDel)
                    val p = pressed
                    pressed = NONE
                    if (holdActive) {
                        holdActive = false
                        if (holdCancel || e.actionMasked == MotionEvent.ACTION_CANCEL) session.cancel() else session.stop()
                        holdCancel = false
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && p == hitAt(e.x, e.y)) onTap(p)
                    invalidate()
                }
            }
            return true
        }

        private var delRepeats = 0
        private val repeatDel = object : Runnable {
            override fun run() {
                if (delRepeats == 0) session.settle()
                delRepeats++
                kb.controller.onBackspace()
                postDelayed(this, 50)
            }
        }

        private fun onTap(id: Int) {
            if (id in LANGUAGE_BASE..LANGUAGE_BASE + 2) {
                session.cancel()
                kb.stopVoice()
                VoiceAccess.engines(kb.ctx).language = com.weavetext.ime.voice.VoiceLanguage.entries[id - LANGUAGE_BASE]
                kb.onEngineChanged()
                session.warmUp()
                return
            }
            val c = kb.controller
            when (id) {
                CLOSE -> kb.closePanel()
                GEAR -> kb.openSettings(plugin?.let { "voice/${it.id}" } ?: "voice")
                CHIP -> kb.showEngineSheet()
                // 说话中点逗号：先上屏已识别的文字，再加逗号，然后接着听。
                // Comma while speaking: commit the recognized text, add the comma, then keep listening.
                COMMA -> {
                    val talking = !holdMode && (session.state == VoiceSession.State.LISTENING || session.state == VoiceSession.State.CONNECTING)
                    session.settle()
                    c.onText(if (kb.state.chinese) "，" else ",")
                    if (talking) session.start()
                }
                KBD -> kb.closePanel()
                // 先把已识别的文字定下来，不再等晚到的结果：否则回车会把半句发出去、结果再落进清空的输入框。
                // Settle the recognized text first and drop late results: otherwise Enter sends half a sentence and
                // the result then lands in the emptied box.
                DEL -> { if (delRepeats == 0) { session.settle(); c.onBackspace() }; delRepeats = 0 }
                ENTER -> { session.settle(); c.onEnter() }
                PERM -> {
                    kb.ctx.startActivity(Intent(kb.ctx, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                IMPORT -> kb.openSettings("voice")
                OFFLINE -> kb.openSettings("voice/upgrade")
                MIC -> if (!holdMode) {
                    when {
                        engines == 0 -> session.start()
                        // 「识别中」等了一会儿还没结果：不再等，重新开始。 Finalizing for a while: start over.
                        session.state == VoiceSession.State.FINALIZING -> session.start()
                        session.active -> session.stop()
                        else -> session.start()
                    }
                }
                SEG_TAP, SEG_HOLD -> {
                    if (session.active) session.stop()
                    kb.prefs.edit().putString(WeavePrefs.VOICE_MODE, if (id == SEG_HOLD) "hold" else "tap").apply()
                }
            }
            if (id != DEL) delRepeats = 0
        }
    }

    companion object {
        private const val NONE = -1; private const val CLOSE = 0; private const val GEAR = 1; private const val CHIP = 2
        private const val COMMA = 3; private const val KBD = 4; private const val DEL = 5; private const val ENTER = 6
        private const val MIC = 7; private const val PERM = 8; private const val IMPORT = 9
        private const val SEG_TAP = 10; private const val SEG_HOLD = 11
        private const val R_CANCEL = 12; private const val R_COMMIT = 13; private const val R_REDO = 14
        private const val OFFLINE = 15
        private const val LANGUAGE_BASE = 16
    }
}
