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
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.R
import com.weavetext.ime.settings.PermissionActivity
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.VoicePlugin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 插件图标：PNG 圆形/圆角裁切，缺失时用名称首字头像。 Plugin icon or letter avatar. */
class PluginIcon {
    private var id: String? = null
    private var version: String? = null
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val matrix = Matrix()
    private val src = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

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
    val session = VoiceSession(kb.ctx, kb.controller)
    override val view = VoiceView(kb.ctx)

    init {
        session.addListener { view.invalidate(); if (session.active) view.postInvalidateOnAnimation() }
    }

    private val holdMode get() = WeavePrefs.voiceMode(kb.prefs) == "hold"

    override fun applyTheme() { kb.paintBackground(view); view.invalidate() }
    override fun onPref(key: String?) { if (key == WeavePrefs.VOICE_MODE) view.invalidate() }

    override fun onShow() {
        session.autoStop = !holdMode
        session.warmUp()
        view.refreshEngine()
        view.invalidate()
    }

    override fun onHide() = stopSession()

    /** 顶栏 🎙 点击进入：点按模式下直接开始。 Opened from the toolbar: tap mode starts right away. */
    fun startFromToolbar() {
        if (!holdMode && engineAvailable() && session.hasPermission()) session.start()
    }

    fun stopSession() { if (session.active) session.stop() }

    private fun engineAvailable() = runCatching { VoiceAccess.engines(kb.ctx).list().isNotEmpty() }.getOrDefault(false)

    @SuppressLint("ViewConstructor")
    inner class VoiceView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        private val icon = PluginIcon()
        private var plugin: VoicePlugin? = null
        private var engines = 0

        // 命中区 / hit areas
        private val close = RectF(); private val chip = RectF(); private val gear = RectF()
        private val comma = RectF(); private val kbd = RectF(); private val del = RectF(); private val enter = RectF()
        private val mic = RectF(); private val seg = RectF(); private val segTap = RectF(); private val segHold = RectF()
        private val permCard = RectF(); private val importBtn = RectF()
        private val tmp = RectF()
        private val tmp2 = RectF()
        private val area = RectF()
        private val lines = ArrayList<String>()
        private var pressed = NONE
        private var downY = 0f
        private var holdActive = false
        private var holdCancel = false

        fun refreshEngine() {
            val e = runCatching { VoiceAccess.engines(kb.ctx) }.getOrNull()
            engines = e?.list()?.size ?: 0
            plugin = e?.active()
            icon.bind(plugin)
        }

        private fun geometry() {
            val m = kb.metrics
            val w = width.toFloat()
            val h = height.toFloat()
            val top = m.topBar
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
            return if (n.length > 10) n.take(9) + "…" else n
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

            drawTranscript(c)
            drawWave(c)
            // 侧键 / side keys
            sideKey(c, comma, pressed == COMMA); drawLabel(c, comma, "，")
            sideKey(c, kbd, pressed == KBD); kb.icons.draw(c, R.drawable.ic_keyboard, pal.icon, kbd.centerX(), kbd.centerY(), m.dp(22f))
            sideKey(c, del, pressed == DEL); kb.icons.draw(c, R.drawable.ic_backspace, pal.icon, del.centerX(), del.centerY(), m.dp(22f))
            sideKey(c, enter, pressed == ENTER); kb.icons.draw(c, R.drawable.ic_enter, pal.icon, enter.centerX(), enter.centerY(), m.dp(22f))
            drawMic(c)
            drawSeg(c)
            if (session.active) postInvalidateOnAnimation()
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

        private fun transcriptArea(out: RectF) {
            val m = kb.metrics
            out.set(m.dp(20f), kb.metrics.topBar + m.dp(2f), width - m.dp(20f), comma.top - m.dp(40f))
        }

        private fun drawTranscript(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            transcriptArea(area)
            if (engines == 0) {
                text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = m.dp(15f); text.color = pal.labelSecondary
                c.drawText("还没有语音引擎", area.centerX(), area.centerY() - m.dp(4f), text)
                text.textSize = m.dp(14f); text.typeface = medium; text.color = pal.candidateFirst
                val w = text.measureText("导入插件") + m.dp(24f)
                importBtn.set(area.centerX() - w / 2, area.centerY() + m.dp(6f), area.centerX() + w / 2, area.centerY() + m.dp(34f))
                c.drawText("导入插件 ›", area.centerX(), importBtn.centerY() - (text.ascent() + text.descent()) / 2, text)
                return
            }
            importBtn.setEmpty()
            val done = session.committed.toString()
            val part = session.partial
            if (done.isEmpty() && part.isEmpty()) return
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

        private fun drawWave(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val cy = comma.top - m.dp(20f)
            val bw = m.dp(3f); val gap = m.dp(3f)
            val total = 17 * bw + 16 * gap
            var x = width / 2f - total / 2
            fill.color = pal.voiceWave
            val listening = session.state == VoiceSession.State.LISTENING
            val t = SystemClock.uptimeMillis()
            val lv = session.level
            for (i in 0 until 17) {
                val win = sin(PI * i / 16).toFloat()
                val jitter = 0.55f + 0.45f * sin(t * 0.011 + i * 1.7).toFloat()
                val hh = if (listening) (m.dp(4f) + m.dp(28f) * min(1f, lv * 1.6f) * win * jitter).coerceAtLeast(m.dp(4f)) else m.dp(4f)
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
                holdCancel -> kb.icons.draw(c, R.drawable.ic_close, pal.onAccent, cx, cy, m.dp(32f))
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
                st == VoiceSession.State.CONNECTING -> "正在连接…"
                st == VoiceSession.State.FINALIZING -> "识别中…"
                listening -> if (hold) "松手结束，上滑取消" else "正在聆听…点击结束"
                hold -> "按住 说话"
                else -> "点击开始说话"
            }
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = m.dp(12f)
            text.color = if (st == VoiceSession.State.ERROR || holdCancel) pal.danger else pal.labelSecondary
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

        private fun hitAt(x: Float, y: Float): Int = when {
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
            segTap.contains(x, y) -> SEG_TAP
            segHold.contains(x, y) -> SEG_HOLD
            else -> NONE
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
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
            override fun run() { delRepeats++; kb.controller.onBackspace(); postDelayed(this, 50) }
        }

        private fun onTap(id: Int) {
            val c = kb.controller
            when (id) {
                CLOSE -> kb.closePanel()
                GEAR -> kb.openSettings(plugin?.let { "voice/${it.id}" } ?: "voice")
                CHIP -> kb.showEngineSheet()
                COMMA -> c.onText(if (kb.state.chinese) "，" else ",")
                KBD -> kb.closePanel()
                DEL -> { if (delRepeats == 0) { if (session.active) session.stop(); c.onBackspace() }; delRepeats = 0 }
                ENTER -> { if (session.active) session.stop(); c.onEnter() }
                PERM -> {
                    kb.ctx.startActivity(Intent(kb.ctx, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                IMPORT -> kb.openSettings("voice")
                MIC -> if (!holdMode) {
                    when {
                        engines == 0 -> kb.openSettings("voice")
                        session.active -> session.stop()
                        else -> session.start()
                    }
                }
                SEG_TAP, SEG_HOLD -> {
                    if (session.active) session.stop()
                    kb.prefs.edit().putString(WeavePrefs.VOICE_MODE, if (id == SEG_HOLD) "hold" else "tap").apply()
                    session.autoStop = id == SEG_TAP
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
    }
}
