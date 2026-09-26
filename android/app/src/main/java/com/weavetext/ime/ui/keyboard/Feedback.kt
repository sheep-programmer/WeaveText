package com.weavetext.ime.ui.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 按键音与振动（01 §9）。振动在独立线程执行。 Key sound & haptics (01 §9), vibration off the UI thread.
 */
class Feedback(ctx: Context) {
    enum class Sound { STANDARD, DELETE, RETURN, SPACE }

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }
    private val thread = HandlerThread("weave-haptic").apply { start() }
    private val handler = Handler(thread.looper)

    /** 0 关 1 系统 2 轻 3 中 4 强。 */
    var vibration = 1
    /** 0–4。 */
    var sound = 0

    private val effects = arrayOfNulls<VibrationEffect>(5)

    fun key(view: View, s: Sound = Sound.STANDARD) {
        haptic(view)
        if (sound > 0) {
            val fx = when (s) {
                Sound.STANDARD -> AudioManager.FX_KEYPRESS_STANDARD
                Sound.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Sound.RETURN -> AudioManager.FX_KEYPRESS_RETURN
                Sound.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            }
            audio?.playSoundEffect(fx, VOLUMES[sound.coerceIn(1, 4)])
        }
    }

    fun haptic(view: View) {
        when (val lv = vibration) {
            0 -> {}
            1 -> view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
            )
            else -> {
                val v = vibrator ?: return
                val effect = effects[lv] ?: build(v, lv).also { effects[lv] = it }
                handler.post { v.vibrate(effect) }
            }
        }
    }

    private fun build(v: Vibrator, lv: Int): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 29 && v.hasAmplitudeControl()) {
            return VibrationEffect.createPredefined(
                when (lv) { 2 -> VibrationEffect.EFFECT_TICK; 3 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_HEAVY_CLICK },
            )
        }
        return if (v.hasAmplitudeControl()) {
            when (lv) { 2 -> VibrationEffect.createOneShot(8, 40); 3 -> VibrationEffect.createOneShot(12, 90); else -> VibrationEffect.createOneShot(18, 160) }
        } else {
            VibrationEffect.createOneShot(when (lv) { 2 -> 6L; 3 -> 10L; else -> 15L }, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    fun release() { thread.quitSafely() }

    companion object {
        private val VOLUMES = floatArrayOf(0f, 0.15f, 0.3f, 0.5f, 0.8f)
    }
}

/** 图标缓存：预先 mutate + tint，绘制时只 setBounds。 Icon cache with pre-tinted drawables. */
class Icons(private val ctx: Context) {
    private val cache = android.util.LongSparseArray<Drawable>()

    fun get(res: Int, color: Int): Drawable {
        val k = (res.toLong() shl 32) or (color.toLong() and 0xffffffffL)
        cache.get(k)?.let { return it }
        return ContextCompat.getDrawable(ctx, res)!!.mutate().apply { setTint(color) }.also { cache.put(k, it) }
    }

    fun draw(c: Canvas, res: Int, color: Int, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val d = get(res, color)
        val h = size / 2
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.alpha = alpha
        d.draw(c)
    }

    fun clear() = cache.clear()
}
