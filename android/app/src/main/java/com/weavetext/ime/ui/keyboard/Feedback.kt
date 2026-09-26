package com.weavetext.ime.ui.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import com.weavetext.ime.settings.WeavePrefs
import java.io.File

/**
 * 按键音与振动（01 §9、06 §3）。振动与放音都在独立线程执行，不占按键路径。
 * 自有按键音由 [KeySoundSynth] 在首次使用时生成 WAV 写入缓存，用 SoundPool 低延迟播放。
 * Key sound & haptics, both off the UI thread. Our own sounds are synthesized by [KeySoundSynth]
 * into cached WAVs on first use and played through SoundPool for low latency.
 */
class Feedback(ctx: Context) {
    enum class Sound(val variant: KeySoundSynth.Variant) {
        STANDARD(KeySoundSynth.Variant.LETTER), DELETE(KeySoundSynth.Variant.DELETE),
        RETURN(KeySoundSynth.Variant.ENTER), SPACE(KeySoundSynth.Variant.SPACE),
    }

    private val app = ctx.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }
    private val thread = HandlerThread("weave-feedback").apply { start() }
    private val handler = Handler(thread.looper)

    /** 0 关 1 系统 2 轻 3 中 4 强。振动效果在 feedback 线程预先建好。 Effects are prebuilt on the feedback thread. */
    var vibration = WeavePrefs.VIBRATION_DEFAULT
        set(v) {
            field = v
            if (v >= 1 && vibrator != null) handler.post { effect(v) }
        }

    /** [WeavePrefs.SOUND_STYLE] 的取值。 A [WeavePrefs.SOUND_STYLE] value. */
    @Volatile var soundStyle: String = WeavePrefs.SOUND_OFF
        set(v) {
            field = v
            if (v in KeySoundSynth.STYLES) handler.post { prepare(v) }
        }
    /** 0–100。 */
    @Volatile var soundVolume = WeavePrefs.SOUND_VOLUME_DEFAULT

    /** 只在 feedback 线程访问。 Feedback thread only. */
    private val effects = arrayOfNulls<VibrationEffect>(5)
    /** 预先分配的投递任务：按键路径上零分配。 Preallocated tasks: no allocation on the key path. */
    private val soundTasks = Array(Sound.entries.size) { i -> Runnable { play(Sound.entries[i]) } }
    private val vibrateTasks = Array(5) { lv -> Runnable { vibrate(lv) } }
    /** 触摸反馈用途（遵从系统的触摸振动开关与强度）。 Touch usage, honouring the system touch-haptics setting. */
    private val touchAttrs: Any? = if (Build.VERSION.SDK_INT >= 33) android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH) else null

    // 以下只在 feedback 线程访问。 Accessed on the feedback thread only.
    private var pool: SoundPool? = null
    private val samples = HashMap<String, IntArray>()
    private val loaded = HashSet<Int>()
    private var pendingPreview = 0

    fun key(view: View, s: Sound = Sound.STANDARD) {
        haptic(view)
        sound(s)
    }

    /** 播放按键音；静音 / 振动模式下不响。 Play the key sound; silent in silent/vibrate ringer modes. */
    fun sound(s: Sound = Sound.STANDARD) {
        val style = soundStyle
        val vol = soundVolume
        if (style == WeavePrefs.SOUND_OFF || vol <= 0) return
        val am = audio ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        handler.post(soundTasks[s.ordinal])
    }

    /** feedback 线程上按当前风格与音量放音。 Play with the current style and volume, on the feedback thread. */
    private fun play(s: Sound) {
        val style = soundStyle
        val gain = gain(soundVolume)
        if (style == WeavePrefs.SOUND_SYSTEM) {
            audio?.playSoundEffect(
                when (s) {
                    Sound.STANDARD -> AudioManager.FX_KEYPRESS_STANDARD
                    Sound.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                    Sound.RETURN -> AudioManager.FX_KEYPRESS_RETURN
                    Sound.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                },
                gain,
            )
            return
        }
        val id = samples[style]?.get(s.ordinal) ?: 0
        if (id != 0 && id in loaded) pool?.play(id, gain, gain, 1, 0, 1f)
    }

    /** 设置页试听：样本还在加载时，加载完成后补放一次。 Settings preview; plays once loading finishes. */
    fun preview() {
        val style = soundStyle
        if (style in KeySoundSynth.STYLES) handler.post {
            val id = samples[style]?.get(Sound.STANDARD.ordinal) ?: 0
            if (id != 0 && id !in loaded) { pendingPreview = id; return@post }
            sound()
        } else sound()
    }

    /** 生成（或复用缓存的）WAV 并载入 SoundPool。 Synthesize (or reuse cached) WAVs and load them. */
    private fun prepare(style: String) {
        if (samples.containsKey(style)) return
        val p = pool ?: SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build().also { sp ->
                pool = sp
                sp.setOnLoadCompleteListener { pl, id, status ->
                    if (status != 0) return@setOnLoadCompleteListener
                    handler.post {
                        loaded += id
                        if (id == pendingPreview) { pendingPreview = 0; pl.play(id, gain(soundVolume), gain(soundVolume), 1, 0, 1f) }
                    }
                }
            }
        val dir = File(app.cacheDir, "keysounds").apply { mkdirs() }
        samples[style] = IntArray(Sound.entries.size) { i ->
            val v = Sound.entries[i].variant
            val f = File(dir, "v$CACHE_VERSION-$style-${v.name.lowercase()}.wav")
            runCatching {
                if (!f.isFile) f.writeBytes(KeySoundSynth.wav(KeySoundSynth.render(style, v)))
                p.load(f.path, 1)
            }.getOrDefault(0)
        }
    }

    /**
     * 按键振动。都投递到 feedback 线程，UI 线程上不走系统 IPC；只有 Android 13 以前的「系统」档
     * 仍用 performHapticFeedback（需要它来遵从系统的触摸反馈开关）。
     * Key haptics, posted to the feedback thread so the UI thread does no system IPC; only the "system"
     * level before Android 13 still uses performHapticFeedback, which is what honours the system switch there.
     */
    fun haptic(view: View) {
        val lv = vibration
        if (lv == 0) return
        if (lv == 1 && (Build.VERSION.SDK_INT < 33 || vibrator == null)) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
            )
            return
        }
        if (vibrator == null) return
        handler.post(vibrateTasks[lv])
    }

    /** feedback 线程上振动。 Vibrate, on the feedback thread. */
    private fun vibrate(lv: Int) {
        val v = vibrator ?: return
        val e = effect(lv) ?: return
        if (lv == 1 && Build.VERSION.SDK_INT >= 33) v.vibrate(e, touchAttrs as android.os.VibrationAttributes) else v.vibrate(e)
    }

    /** 取（必要时新建）某档的效果；feedback 线程。 Get or build the effect for a level, on the feedback thread. */
    private fun effect(lv: Int): VibrationEffect? {
        effects[lv]?.let { return it }
        val v = vibrator ?: return null
        val e = if (lv == 1) {
            if (Build.VERSION.SDK_INT < 33) return null
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else build(v, lv)
        effects[lv] = e
        return e
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

    fun release() {
        handler.post { pool?.release(); pool = null }
        thread.quitSafely()
    }

    companion object {
        /** 合成公式变化时递增，让旧缓存失效。 Bump when the synthesis changes to invalidate the cache. */
        private const val CACHE_VERSION = 1

        /** 滑块 0–100 → 播放增益（略带曲线，低音量更细腻）。 Slider value → playback gain, gently curved. */
        fun gain(volume: Int): Float {
            val x = volume.coerceIn(0, 100) / 100f
            return x * x * 0.6f + x * 0.4f
        }
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
