package com.weavetext.ime.ui.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Recognition keeps the original accepted x/y samples. Speed, pressure, smoothing and pointed
 * tails belong only to rendering. Changing appearance redraws finished and active strokes.
 * Move events reuse primitive buffers and Paths; buffers grow geometrically only when full.
 * Like the original pad, this object is confined to the view's UI thread.
 */
class HandPad(private val clock: () -> Long = SystemClock::uptimeMillis) {
    val rect = RectF()
    private val done = ArrayList<FloatArray>()
    // Three display-only values per accepted point: speed (px/ms), pressure, distance (px).
    private val doneSamples = ArrayList<FloatArray>()
    private val redoGeom = ArrayList<FloatArray>()
    private val redoSamples = ArrayList<FloatArray>()
    val strokes: List<FloatArray> get() = done
    /** A live stroke must finish or cancel before an undone stroke can be restored. */
    val canRedo: Boolean get() = !drawing && redoGeom.isNotEmpty()
    private var cur = FloatArray(512)
    private var samples = FloatArray(256 * SAMPLE_SIZE)
    private var curN = 0
    private var renderXy = FloatArray(514)
    private var renderW = FloatArray(257)
    private val fadingGeom = ArrayList<FloatArray>()
    private val fadingSamples = ArrayList<FloatArray>()

    var drawing = false
        private set
    var lastStrokeEnd = 0L
        private set
    /** Recognition's distance filter, in px; appearance never changes this threshold. */
    var minStep = 2f

    private var appearance = HandInkAppearance()
    private var pixelDensity = 1f
    private var customColor: Int? = null
    var style: HandInkStyle
        get() = appearance.style
        set(value) { configure(appearance.copy(style = value), pixelDensity) }
    var widthDp: Float
        get() = appearance.widthDp
        set(value) { configure(appearance.copy(widthDp = value), pixelDensity) }
    /** "theme" or #RRGGBB. The host supplies its current palette label colour to [inkColor]. */
    var color: String
        get() = appearance.color
        set(value) { configure(appearance.copy(color = value), pixelDensity) }
    var density: Float
        get() = pixelDensity
        set(value) { configure(appearance, value) }
    /** Source-compatible legacy width in px; prefer [widthDp] and [density] for new callers. */
    var strokeWidth: Float
        get() = widthDp * density
        set(value) { widthDp = value / density }
    /** Legacy switch: affects only the brush's display taper. */
    var taper = true
        set(value) {
            if (field == value) return
            field = value
            refreshInk()
        }

    private val finishedInk = Path()
    val ink = Path()
    val fading = Path()
    var fadeStart = -1L
        private set
    val hasInk: Boolean get() = done.isNotEmpty() || drawing
    fun sinceLastStroke(now: Long = clock()): Long = now - lastStrokeEnd

    /** Apply prefs + display density together, with at most one geometry rebuild. */
    fun configure(value: HandInkAppearance, density: Float = this.density) {
        val next = value.copy(widthDp = HandInkPrefs.normalizeWidth(value.widthDp),
            color = HandInkPrefs.normalizeColor(value.color) ?: HandInkPrefs.THEME_COLOR)
        val nextDensity = if (density.isFinite() && density > 0f) density else 1f
        if (next == appearance && nextDensity == pixelDensity) return
        val shapeChanged = next.style != appearance.style || next.widthDp != appearance.widthDp || nextDensity != pixelDensity
        appearance = next
        pixelDensity = nextDensity
        customColor = HandInkPrefs.parseColor(next.color)
        if (shapeChanged) refreshInk()
    }

    /** Tool opacity includes the theme colour's alpha; [opacity] is also used for fading. */
    fun inkColor(themeColor: Int, opacity: Float = 1f): Int {
        val base = customColor ?: themeColor
        val fade = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f
        val alpha = ((base ushr 24) * (style.alpha / 255f) * fade).toInt()
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    /** Draw in pad-local coordinates after the host clips/translates. True requests another fade frame. */
    fun drawInk(canvas: Canvas, paint: Paint, themeColor: Int): Boolean {
        paint.style = Paint.Style.FILL
        val fade = fadeProgress()
        if (fade >= 0f) {
            paint.color = inkColor(themeColor, 1f - fade)
            canvas.drawPath(fading, paint)
        }
        paint.color = inkColor(themeColor)
        canvas.drawPath(ink, paint)
        return fade >= 0f
    }

    private var vel = 0f
    private var lastT = 0L
    private var distance = 0f

    /** Original API, including its JVM signature; fingers always use neutral pressure. */
    fun begin(x: Float, y: Float, t: Long = clock()) = begin(x, y, t, pressure = 1f)

    /** Pass MotionEvent pressure ONLY for stylus samples. */
    fun begin(x: Float, y: Float, t: Long, pressure: Float = 1f) {
        drawing = true
        curN = 0
        vel = 0f
        distance = 0f
        lastT = t
        push(x, y, pressure)
        rebuild()
    }

    /** Original API, including its JVM signature; historical finger samples use this overload too. */
    fun add(x: Float, y: Float, t: Long = clock()) = add(x, y, t, pressure = 1f)

    /** Accepted geometry is unchanged by tool, width, colour, pressure or rendering smoothing. */
    fun add(x: Float, y: Float, t: Long, pressure: Float = 1f) {
        if (!drawing || curN < 1) return
        val dx = x - cur[(curN - 1) * 2]
        val dy = y - cur[(curN - 1) * 2 + 1]
        if (dx * dx + dy * dy < minStep * minStep) return
        val step = hypot(dx, dy)
        val dt = (t - lastT).coerceAtLeast(1L).toFloat()
        vel = 0.7f * vel + 0.3f * step / dt
        distance += step
        lastT = t
        push(x, y, pressure)
        rebuild()
    }

    /** Lift returns only recognition x/y. No synthetic tail or display metadata enters [strokes]. */
    fun end(t: Long = clock()): FloatArray? {
        if (!drawing) return null
        drawing = false
        lastStrokeEnd = t
        val geom = cur.copyOf(curN * 2)
        val displaySamples = samples.copyOf(curN * SAMPLE_SIZE)
        done += geom
        doneSamples += displaySamples
        fill(finishedInk, geom, displaySamples, curN, ended = true)
        curN = 0
        rebuild()
        clearRedo()
        return geom
    }

    fun cancel() {
        if (!drawing) return
        drawing = false
        curN = 0
        rebuild()
    }

    fun undo(): Boolean {
        if (done.isEmpty()) return false
        // Transfer the original arrays, including pressure/speed samples; do not clone or resample.
        if (redoGeom.size == MAX_REDO_STROKES) {
            redoGeom.removeAt(0)
            redoSamples.removeAt(0)
        }
        redoGeom.add(done.removeAt(done.lastIndex))
        redoSamples.add(doneSamples.removeAt(doneSamples.lastIndex))
        refreshInk()
        return true
    }

    /** Restore recognition geometry by reference and redraw with the current appearance. */
    fun redo(): Boolean {
        if (!canRedo) return false
        done.add(redoGeom.removeAt(redoGeom.lastIndex))
        doneSamples.add(redoSamples.removeAt(redoSamples.lastIndex))
        refreshInk()
        return true
    }

    private fun clearRedo() {
        redoGeom.clear()
        redoSamples.clear()
    }

    /** A live stroke survives clear as the next character's first stroke. */
    fun clear(fade: Boolean) {
        clearRedo()
        if (done.isEmpty()) return
        clearFade()
        if (fade && !drawing) {
            fading.set(ink)
            fadingGeom.addAll(done)
            fadingSamples.addAll(doneSamples)
            fadeStart = clock()
        }
        done.clear()
        doneSamples.clear()
        finishedInk.rewind()
        rebuild()
    }

    fun fadeProgress(): Float {
        if (fadeStart < 0) return -1f
        val f = (clock() - fadeStart).toFloat() / FADE_MS
        if (f >= 1f) { clearFade(); return -1f }
        return f
    }

    fun reset() {
        done.clear()
        doneSamples.clear()
        clearRedo()
        drawing = false
        curN = 0
        clearFade()
        ink.rewind()
        finishedInk.rewind()
    }

    private fun clearFade() {
        fadeStart = -1L
        fading.rewind()
        fadingGeom.clear()
        fadingSamples.clear()
    }

    private fun push(x: Float, y: Float, pressure: Float) {
        if ((curN + 1) * 2 > cur.size) {
            cur = cur.copyOf(cur.size * 2)
            samples = samples.copyOf(samples.size * 2)
        }
        cur[curN * 2] = x
        cur[curN * 2 + 1] = y
        samples[curN * SAMPLE_SIZE] = vel
        samples[curN * SAMPLE_SIZE + 1] = if (pressure.isFinite()) pressure.coerceIn(0.1f, 2f) else 1f
        samples[curN * SAMPLE_SIZE + 2] = distance
        curN++
    }

    /** Rebuild display only; recognition arrays retain both their contents and identity. */
    fun refreshInk() {
        finishedInk.rewind()
        for (i in done.indices) fill(finishedInk, done[i], doneSamples[i], done[i].size / 2, ended = true)
        fading.rewind()
        for (i in fadingGeom.indices) fill(fading, fadingGeom[i], fadingSamples[i], fadingGeom[i].size / 2, ended = true)
        rebuild()
    }

    private fun rebuild() {
        ink.set(finishedInk)
        if (drawing) fill(ink, cur, samples, curN, ended = false)
    }

    private fun fill(out: Path, geom: FloatArray, data: FloatArray, count: Int, ended: Boolean) {
        if (count < 1) return
        if (count + 1 > renderW.size) {
            val capacity = maxOf(count + 1, renderW.size * 2)
            renderW = FloatArray(capacity)
            renderXy = FloatArray(capacity * 2)
        }
        val base = strokeWidth
        val totalDistance = data[(count - 1) * SAMPLE_SIZE + 2]
        for (i in 0 until count) {
            renderXy[i * 2] = geom[i * 2]
            renderXy[i * 2 + 1] = geom[i * 2 + 1]
            val speed = min(data[i * SAMPLE_SIZE] / density / 1.6f, 1f)
            val pressure = data[i * SAMPLE_SIZE + 1]
            val travelled = data[i * SAMPLE_SIZE + 2]
            val factor = when (style) {
                HandInkStyle.BALLPOINT -> (1f - 0.06f * speed) * (0.9f + 0.1f * pressure)
                HandInkStyle.BRUSH -> {
                    val head = if (taper) 0.35f + 0.65f * min(travelled / (3f * density), 1f) else 1f
                    val tip = if (taper && ended && count > 1)
                        0.35f + 0.65f * min((totalDistance - travelled) / base, 1f) else 1f
                    (1f - 0.65f * speed) * (0.45f + 0.55f * pressure) * head * tip
                }
                HandInkStyle.PENCIL -> {
                    // Stable graphite roughness: switching style and back reproduces the same ink.
                    val grain = 0.8f + 0.2f * ((i * 7) % 11) / 10f
                    0.65f * grain * (1f - 0.12f * speed) * (0.5f + 0.5f * pressure)
                }
                HandInkStyle.HIGHLIGHTER -> {
                    val p = maxOf(0, i - 1)
                    val q = minOf(count - 1, i + 1)
                    val tx = geom[q * 2] - geom[p * 2]
                    val ty = geom[q * 2 + 1] - geom[p * 2 + 1]
                    val len = hypot(tx, ty)
                    // Projection of a fixed 45-degree chisel nib onto the local normal.
                    val chisel = if (len < 0.001f) 1f else 0.3f + 0.7f * abs((tx - ty) * 0.70710677f / len)
                    chisel * (0.8f + 0.2f * pressure)
                }
            }
            renderW[i] = base * factor
        }
        var n = count
        if (ended && taper && style == HandInkStyle.BRUSH && count >= 2 && data[(count - 1) * SAMPLE_SIZE] / density > 0.8f) {
            val dx = geom[(count - 1) * 2] - geom[(count - 2) * 2]
            val dy = geom[(count - 1) * 2 + 1] - geom[(count - 2) * 2 + 1]
            val len = hypot(dx, dy).coerceAtLeast(0.001f)
            val tail = min(base * 1.4f, 10f * density)
            renderXy[n * 2] = geom[(count - 1) * 2] + dx / len * tail
            renderXy[n * 2 + 1] = geom[(count - 1) * 2 + 1] + dy / len * tail
            renderW[n++] = 0f
        }
        outline(out, n)
    }

    private fun outline(out: Path, count: Int) {
        if (count == 1) {
            val radius = renderW[0] / 2f
            if (style == HandInkStyle.HIGHLIGHTER) out.addRect(renderXy[0] - radius, renderXy[1] - radius,
                renderXy[0] + radius, renderXy[1] + radius, Path.Direction.CW)
            else out.addCircle(renderXy[0], renderXy[1], radius, Path.Direction.CW)
            return
        }
        val smooth = style == HandInkStyle.BALLPOINT || style == HandInkStyle.BRUSH
        for (side in -1..1 step 2) {
            val step = -side
            var i = if (side < 0) 0 else count - 1
            var previousX = 0f
            var previousY = 0f
            var first = true
            while (i in 0 until count) {
                val p = maxOf(0, i - 1)
                val q = minOf(count - 1, i + 1)
                val tx = renderXy[q * 2] - renderXy[p * 2]
                val ty = renderXy[q * 2 + 1] - renderXy[p * 2 + 1]
                val len = hypot(tx, ty)
                val half = renderW[i] / 2f * side
                val x = renderXy[i * 2] + if (len < 0.001f) 0f else -ty / len * half
                val y = renderXy[i * 2 + 1] + if (len < 0.001f) half else tx / len * half
                if (first) {
                    if (side < 0) out.moveTo(x, y) else out.lineTo(x, y)
                    first = false
                } else if (smooth) {
                    out.quadTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
                } else out.lineTo(x, y)
                previousX = x
                previousY = y
                i += step
            }
            if (smooth) out.lineTo(previousX, previousY)
        }
        out.close()
        if (style == HandInkStyle.BALLPOINT || style == HandInkStyle.PENCIL || (style == HandInkStyle.BRUSH && !taper)) {
            out.addCircle(renderXy[0], renderXy[1], renderW[0] / 2f, Path.Direction.CW)
            out.addCircle(renderXy[(count - 1) * 2], renderXy[(count - 1) * 2 + 1], renderW[count - 1] / 2f, Path.Direction.CW)
        }
    }

    companion object {
        private const val SAMPLE_SIZE = 3
        private const val MAX_REDO_STROKES = 128
        const val COMMIT_PAUSE_MS = 800L
        const val FADE_MS = 160L
    }
}
