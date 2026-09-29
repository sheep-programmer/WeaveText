package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.weavetext.ime.R
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.VoicePlugin
import kotlin.math.abs
import kotlin.math.max

/**
 * 引擎切换底部弹层（02 §12.4、04 §7）：覆盖顶栏 + 主区域，带遮罩；不替换当前面板。
 * Engine switcher sheet over top bar + main area with a scrim; the current panel stays beneath.
 */
@SuppressLint("ViewConstructor")
class EngineSheet(ctx: Context, private val kb: WeaveKeyboard) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).zh()
    private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
    private var plugins: List<VoicePlugin> = emptyList()
    private val icons = ArrayList<PluginIcon>()
    private var activeId: String? = null
    private val sheet = RectF()
    private val list = RectF()
    private val manage = RectF()
    private val install = RectF()
    /** 轻量版还没装好离线识别：底栏右侧给出「安装离线语音」。 Lite without offline voice: offer to install it. */
    private var offerInstall = false
    private val tmp = RectF()
    private var scroll = 0f
    private var pressed = NONE
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private val titles = ArrayList<CharSequence>()
    private val descs = ArrayList<CharSequence>()
    var onClosed: (() -> Unit)? = null

    val shown get() = visibility == View.VISIBLE

    fun show() {
        val e = runCatching { VoiceAccess.engines(context) }.getOrNull()
        plugins = e?.list().orEmpty()
        activeId = e?.active()?.id
        offerInstall = runCatching {
            com.weavetext.ime.voice.VoiceHelp.canOfferOfflineBuild &&
                !com.weavetext.ime.models.AsrRuntime.engineReady(com.weavetext.ime.models.ModelManager.get(context))
        }.getOrDefault(false)
        while (icons.size < plugins.size) icons += PluginIcon()
        plugins.forEachIndexed { i, p -> icons[i].bind(p) }
        scroll = 0f
        titles.clear(); descs.clear()
        visibility = View.VISIBLE
        if (animScale() == 0f) { translationY = 0f; alpha = 1f } else {
            alpha = 0f
            translationY = kb.metrics.dp(48f)
            animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start()
        }
        invalidate()
    }

    fun hide() {
        if (!shown) return
        animate().cancel()
        if (animScale() == 0f) { visibility = View.GONE; onClosed?.invoke(); return }
        animate().alpha(0f).translationY(kb.metrics.dp(48f)).setDuration(180)
            .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
            .withEndAction { visibility = View.GONE; translationY = 0f; alpha = 1f; onClosed?.invoke() }.start()
    }

    private fun animScale() = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    private fun rowH() = kb.metrics.dp(56f)

    private fun geometry() {
        val m = kb.metrics
        val head = m.dp(16f) + m.dp(36f)
        val foot = m.dp(44f)
        val want = head + plugins.size.coerceAtLeast(1) * rowH() + foot + m.dp(8f)
        val top = max(m.dp(8f), height - want)
        sheet.set(0f, top, width.toFloat(), height.toFloat() + m.dp(16f))
        list.set(m.dp(12f), top + head, width - m.dp(12f), height - foot)
        // 整条底栏都可点（文字仍靠左）；有「安装离线语音」时左右各占一半。 Whole footer tappable; split when offering install.
        if (offerInstall) {
            manage.set(0f, height - foot, width / 2f, height.toFloat())
            install.set(width / 2f, height - foot, width.toFloat(), height.toFloat())
        } else {
            manage.set(0f, height - foot, width.toFloat(), height.toFloat())
            install.setEmpty()
        }
    }

    private fun maxScroll() = max(0f, plugins.size * rowH() - list.height())

    override fun onDraw(c: Canvas) {
        val pal = kb.palette
        val m = kb.metrics
        geometry()
        c.drawColor(pal.scrim)
        fill.color = pal.background
        c.drawRoundRect(sheet, m.dp(16f), m.dp(16f), fill)
        fill.color = pal.divider
        c.drawRect(sheet.left + m.dp(16f), sheet.top, sheet.right - m.dp(16f), sheet.top + max(1f, m.dp(0.5f)), fill)
        fill.color = pal.labelDisabled
        tmp.set(width / 2f - m.dp(16f), sheet.top + m.dp(8f), width / 2f + m.dp(16f), sheet.top + m.dp(12f))
        c.drawRoundRect(tmp, m.dp(2f), m.dp(2f), fill)
        text.typeface = medium; text.textSize = m.dp(15f); text.color = pal.label; text.textAlign = Paint.Align.LEFT
        c.drawText("选择语音引擎", m.dp(16f), sheet.top + m.dp(16f) + m.dp(18f) - (text.ascent() + text.descent()) / 2, text)
        // 行卡片 / rows card
        fill.color = pal.card
        tmp.set(list.left, list.top, list.right, list.top + (plugins.size * rowH()).coerceAtMost(list.height()))
        c.drawRoundRect(tmp, m.dp(12f), m.dp(12f), fill)
        c.save()
        c.clipRect(list)
        if (plugins.isEmpty()) {
            text.typeface = Typeface.DEFAULT; text.textSize = m.dp(14f); text.color = pal.labelSecondary; text.textAlign = Paint.Align.CENTER
            c.drawText("还没有语音引擎", list.centerX(), list.top + rowH() / 2 - (text.ascent() + text.descent()) / 2, text)
        }
        if (titles.size != plugins.size) buildTexts()
        for ((i, p) in plugins.withIndex()) {
            val top = list.top + i * rowH() - scroll
            if (top > list.bottom || top + rowH() < list.top) continue
            if (i == pressed) {
                fill.color = pal.keyPressed
                c.drawRect(list.left, top, list.right, top + rowH(), fill)
            }
            val dim = !p.configured
            tmp.set(list.left + m.dp(12f), top + m.dp(12f), list.left + m.dp(44f), top + m.dp(44f))
            icons[i].draw(c, tmp, m.dp(8f), p.name, if (dim) pal.labelDisabled else pal.keyAccent, pal.onAccent)
            text.textAlign = Paint.Align.LEFT
            text.typeface = Typeface.DEFAULT; text.textSize = m.dp(15f); text.color = if (dim) pal.labelSecondary else pal.label
            c.drawText(titles[i], 0, titles[i].length, tmp.right + m.dp(12f), top + m.dp(24f), text)
            text.textSize = m.dp(12f); text.color = pal.labelSecondary
            c.drawText(descs[i], 0, descs[i].length, tmp.right + m.dp(12f), top + m.dp(43f), text)
            // 单选 / radio
            val cx = list.right - m.dp(26f); val cy = top + rowH() / 2
            val on = p.id == activeId
            fill.style = Paint.Style.STROKE; fill.strokeWidth = m.dp(2f); fill.color = if (on) pal.keyAccent else pal.labelHint
            c.drawCircle(cx, cy, m.dp(9f), fill)
            fill.style = Paint.Style.FILL
            if (on) { fill.color = pal.keyAccent; c.drawCircle(cx, cy, m.dp(5f), fill) }
            if (i > 0) {
                fill.color = pal.divider
                c.drawRect(tmp.right + m.dp(12f), top, list.right, top + max(1f, m.dp(0.5f)), fill)
            }
        }
        c.restore()
        text.typeface = medium; text.textSize = m.dp(14f); text.color = pal.candidateFirst; text.textAlign = Paint.Align.LEFT
        c.drawText("管理语音引擎 ›", m.dp(16f), manage.centerY() - (text.ascent() + text.descent()) / 2, text)
        if (offerInstall) {
            text.textAlign = Paint.Align.RIGHT
            c.drawText("安装离线语音 ›", width - m.dp(16f), install.centerY() - (text.ascent() + text.descent()) / 2, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun buildTexts() {
        val m = kb.metrics
        titles.clear(); descs.clear()
        val w = list.width() - m.dp(56f) - m.dp(52f)
        for (p in plugins) {
            text.textSize = m.dp(15f)
            titles += TextUtils.ellipsize(p.name, text, w, TextUtils.TruncateAt.END)
            text.textSize = m.dp(12f)
            descs += TextUtils.ellipsize(if (p.configured) p.description else "需要先在设置中完成配置", text, w, TextUtils.TruncateAt.END)
        }
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (!list.contains(x, y)) return NONE
        val i = ((y - list.top + scroll) / rowH()).toInt()
        return if (i in plugins.indices) i else NONE
    }

    /** 弹层吃掉悬停：读屏触摸浏览不会穿过它落到下面的键上。 Swallow hover so touch exploration can't reach the keys below. */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        super.onHoverEvent(event)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; downScroll = scroll; dragging = false
                pressed = when {
                    e.y < sheet.top -> SCRIM
                    manage.contains(e.x, e.y) -> MANAGE
                    !install.isEmpty && install.contains(e.x, e.y) -> INSTALL
                    else -> rowAt(e.x, e.y)
                }
                if (pressed >= 0 || pressed == MANAGE || pressed == INSTALL) kb.feedback.key(this)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (!dragging && abs(dy) > kb.metrics.dp(8f)) { dragging = true; if (pressed >= 0) pressed = NONE }
                if (dragging) { scroll = (downScroll - dy).coerceIn(0f, maxScroll()); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = NONE
                if (!dragging) when {
                    p == SCRIM -> hide()
                    p == MANAGE -> { hide(); kb.openSettings("voice") }
                    p == INSTALL -> { hide(); kb.openSettings("voice/upgrade") }
                    p >= 0 -> choose(plugins[p])
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidate() }
        }
        return true
    }

    private fun choose(p: VoicePlugin) {
        kb.stopVoice()
        runCatching { VoiceAccess.engines(context).activeId = p.id }
        activeId = p.id
        invalidate()
        kb.onEngineChanged()
        hide()
    }

    companion object {
        private const val NONE = -1
        private const val SCRIM = -2
        private const val MANAGE = -3
        private const val INSTALL = -4
    }
}

/**
 * 空格长按「按住说话」的浮动语音条（02 §12.2）：画在气泡层上，手势仍由空格键追踪。
 * Floating hold-to-talk strip drawn on the popup overlay; the gesture stays on the space key.
 */
class VoiceStrip(private val kb: WeaveKeyboard) {
    private val session = kb.voiceSession
    private var cancel = false
    /** 会话由语音条发起（与语音面板共用同一个会话）。 The shared session was started from the strip. */
    private var engaged = false

    init {
        session.addListener { render() }
    }

    /** 开始；无权限或无引擎时返回 false。 Start; false without permission or engine. */
    fun start(): Boolean {
        val ov = kb.overlay ?: return false
        if (!session.hasPermission()) {
            ov.showStripMessage("需要麦克风权限：打开语音面板授权")
            return false
        }
        val hasEngine = runCatching { VoiceAccess.engines(kb.ctx).list().isNotEmpty() }.getOrDefault(false)
        if (!hasEngine) { ov.showStripMessage("还没有语音引擎"); return false }
        cancel = false
        session.autoStop = false
        engaged = true
        if (!session.start()) { engaged = false; return false }
        kb.feedback.haptic(kb.keyboardView)
        render()
        return true
    }

    fun onMove(dy: Float) {
        val c = -dy > kb.metrics.dp(64f)
        if (c != cancel) { cancel = c; kb.feedback.haptic(kb.keyboardView); render() }
    }

    fun end(cancelled: Boolean) {
        // 只结束由语音条发起的会话，不影响语音面板。 Only end sessions the strip started.
        if (!engaged || !session.active) { if (engaged) { engaged = false; kb.overlay?.hideStrip() }; return }
        if (cancelled || cancel) session.cancel() else session.stop()
        cancel = false
        render()
    }

    private fun render() {
        if (!engaged) return
        val ov = kb.overlay ?: return
        if (session.state == VoiceSession.State.CHOOSING) {
            // 多引擎：松手后在语音面板里选结果。 Multi-engine: pick the result in the voice panel.
            engaged = false
            ov.hideStrip()
            kb.showPanel("voice")
            return
        }
        if (!session.active && session.state != VoiceSession.State.ERROR) { engaged = false; ov.hideStrip(); return }
        val msg = when {
            session.state == VoiceSession.State.ERROR -> session.error ?: "识别失败"
            cancel -> "松手取消"
            else -> (session.committed.toString() + session.partial).ifEmpty { if (session.state == VoiceSession.State.FINALIZING) "识别中…" else "正在聆听…上滑取消" }
        }
        ov.showStrip(msg, session.level, cancel || session.state == VoiceSession.State.ERROR, session.state == VoiceSession.State.LISTENING)
        // 可取消的延时收起：1.5 秒内重新按住说话时，新语音条不会被这次的计时收掉。
        // A cancellable delayed hide: a new hold-to-talk within 1.5 s isn't hidden by this timer.
        if (session.state == VoiceSession.State.ERROR) { engaged = false; ov.hideStripAfter(1500) }
    }
}
