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

    /** 反馈线程的 Looper（测试里要把它跑起来才能看到振动与放音）。 The feedback looper, so tests can run its queue. */
    @androidx.annotation.VisibleForTesting
    internal val looper get() = thread.looper

    /** 0 关 1 系统 2 轻 3 中 4 强。振动效果在 feedback 线程预先建好。 Effects are prebuilt on the feedback thread. */
    var vibration = WeavePrefs.VIBRATION_DEFAULT
        set(v) {
            field = v
            if (v >= 1 && vibrator != null) handler.post { effect(v) }
        }

    /**
     * 用触感原语（Android 11+）自建振动：系统预置的 TICK / CLICK / HEAVY_CLICK 各家长短不一，听起来更像
     * 一次点击事件而不是「按键下去的那一下」；自己用原语拼出来，档位之间只差在力度与一点点时长，质感统一。
     * Prefer primitives (Android 11+) over the platform's TICK / CLICK / HEAVY_CLICK presets, whose lengths vary
     * by vendor: a composition of a click plus a touch tick reads as one even key strike across levels.
     */
    private fun prefersPrimitives(v: Vibrator) =
        Build.VERSION.SDK_INT >= 30 &&
            v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )

    /** 各档的（点击力度, 尾随刻度力度, 尾随延迟 ms）。 Per level: (click scale, tick scale, tick delay ms). */
    private fun strike(lv: Int): Triple<Float, Float, Int> = when (lv) {
        2 -> Triple(0.35f, 0f, 0)
        3 -> Triple(0.65f, 0.25f, 22)
        else -> Triple(1f, 0.45f, 30)
    }

    /** 自建振动效果。 Composition of a click and an optional trailing tick. Requires API 30 (see [prefersPrimitives]). */
    @android.annotation.TargetApi(30)
    private fun composed(lv: Int): VibrationEffect {
        val (click, tick, delay) = strike(lv)
        val c = VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, click)
        if (tick > 0f) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, tick, delay)
        return c.compose()
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
    /**
     * 一次只排一次振动。连打时按键比振动本身还密，若一键一个任务排下去，马达会先安静、再成串补震，
     * 手感就「散」了；上一次还没发出去就跳过这一次，节奏才是均匀的（系统触摸反馈也是这么做的）。
     * At most one vibration in flight: when keys arrive faster than the vibration lasts, queuing every one
     * makes the motor fall silent and then fire in bursts, which reads as a scattered feel. Skipping while
     * one is pending keeps the rhythm even (the platform's own touch feedback coalesces the same way).
     */
    @Volatile private var vibratePending = false
    private val vibrateTask = Runnable {
        vibratePending = false
        vibrate(vibration)
    }
    /** 触摸反馈用途（遵从系统的触摸振动开关与强度）。 Touch usage, honouring the system touch-haptics setting. */
    private val touchAttrs: Any? = if (Build.VERSION.SDK_INT >= 33) android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH) else null

    /**
     * 系统的「触摸振动」总开关。关掉时界面不再擅自动马达——键盘里的「按键震动」是键盘自己的设置，
     * 系统那一个是全机的，用户在系统里关掉就是不想任何界面震，这一层必须听。
     * The system-wide touch-haptics switch. Ours is a keyboard setting, theirs is device-wide: when they turn
     * it off they mean nothing should buzz, so the keyboard must not keep vibrating on its own.
     */
    private fun systemTouchHapticsOn(): Boolean = runCatching {
        android.provider.Settings.System.getInt(
            app.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
        ) != 0
    }.getOrDefault(true)

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
        if (id != 0 && id in loaded) {
            // 空格与回车比字母重一点，听着更像真的按了那一下。 Space and enter sit a touch above the letters.
            val g = gain * when (s) {
                Sound.SPACE -> 1.15f
                Sound.RETURN -> 1.1f
                else -> 1f
            }
            pool?.play(id, g, g, 1, 0, 1f)
        }
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
        if (!systemTouchHapticsOn()) return
        if (lv == 1 && (Build.VERSION.SDK_INT < 33 || vibrator == null)) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
            )
            return
        }
        if (vibrator == null) return
        if (vibratePending) return
        vibratePending = true
        handler.post(vibrateTask)
    }

    /**
     * 细小的刻度感（空格滑动移光标时每走一格）：支持时用系统的轻触原语，否则退回最轻一档；振动关闭时不动。
     * A fine detent while sliding the cursor: the system's light tick primitive when supported, else the lightest
     * level; nothing when vibration is off.
     */
    fun tick(view: View) {
        if (vibration == 0 || !systemTouchHapticsOn()) return
        val v = vibrator
        if (v == null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return
        }
        handler.post(tickTask)
    }

    private val tickTask = Runnable {
        val v = vibrator ?: return@Runnable
        val e = tickEffect ?: run {
            val built = if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f).compose()
            } else if (Build.VERSION.SDK_INT >= 29) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            tickEffect = built
            built
        }
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, touchAttrs as android.os.VibrationAttributes) else v.vibrate(e)
    }
    @Volatile private var tickEffect: VibrationEffect? = null

    /** feedback 线程上振动。 Vibrate, on the feedback thread. */
    private fun vibrate(lv: Int) {
        val v = vibrator ?: return
        val e = effect(lv) ?: return
        if (lv == 1 && Build.VERSION.SDK_INT >= 33) v.vibrate(e, touchAttrs as android.os.VibrationAttributes) else v.vibrate(e)
    }

    /** 测试用：按某档建一个效果（与按键路径同一套逻辑）。 Test hook: build the effect for a level. */
    @androidx.annotation.VisibleForTesting
    internal fun buildEffect(lv: Int): VibrationEffect? = vibrator?.let { build(it, lv) }

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
        if (prefersPrimitives(v)) return composed(lv)
        if (Build.VERSION.SDK_INT >= 29 && v.hasAmplitudeControl()) {
            return VibrationEffect.createPredefined(
                when (lv) { 2 -> VibrationEffect.EFFECT_TICK; 3 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_HEAVY_CLICK },
            )
        }
        // 老设备：明确按毫秒与振幅给一击，而不是交给系统预置（长短不可控）。
        // Older devices: an explicit one-shot with our own duration and amplitude.
        return if (v.hasAmplitudeControl()) {
            when (lv) { 2 -> VibrationEffect.createOneShot(9, 60); 3 -> VibrationEffect.createOneShot(14, 130); else -> VibrationEffect.createOneShot(20, 255) }
        } else {
            VibrationEffect.createOneShot(when (lv) { 2 -> 8L; 3 -> 13L; else -> 20L }, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    fun release() {
        handler.post { pool?.release(); pool = null }
        thread.quitSafely()
    }

    companion object {
        /** 合成公式变化时递增，让旧缓存失效。 Bump when the synthesis changes to invalidate the cache. */
        private const val CACHE_VERSION = 2

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
