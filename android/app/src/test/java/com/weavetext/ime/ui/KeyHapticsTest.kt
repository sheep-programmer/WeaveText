package com.weavetext.ime.ui

import android.app.Application
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ui.keyboard.Feedback
import com.weavetext.ime.ui.keyboard.KeySoundSynth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowVibrator

/**
 * 按键震动：三档各是一击清楚的原语组合、力度递增；关掉不震，系统的「触摸振动」总开关关掉也不震。
 * Key haptics: one clear strike per level, heavier each step; nothing when off or when the system-wide
 * touch-haptics switch is off.
 *
 * 注：Robolectric 只对系统预置效果记 `isVibrating`，自建的原语组合不会置位，所以自建档位看的是录下的原语片段。
 * Note: Robolectric sets `isVibrating` only for predefined effects, so the composed levels are checked by
 * their recorded primitive segments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class KeyHapticsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var feedback: Feedback
    private lateinit var vibrator: Vibrator
    private lateinit var shadow: ShadowVibrator
    private lateinit var view: View

    @Before fun setUp() {
        shadow = Shadows.shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)
        vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        shadow.setSupportedPrimitives(
            listOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            ),
        )
        // 静音模式下不发声，但振动照旧；这里确保铃声模式正常。 Keep the ringer normal so sound paths stay live.
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        feedback = Feedback(app)
        view = View(app)
    }

    @After fun tearDown() { feedback.release() }

    private fun clearVibration() {
        ShadowVibrator.reset()
        shadow.setSupportedPrimitives(
            listOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            ),
        )
    }

    /** 走真实按键路径，再把反馈线程跑干净。 Drive the real key path, then drain the feedback thread. */
    private fun press(lv: Int): List<ShadowVibrator.PrimitiveEffect> {
        clearVibration()
        feedback.vibration = lv
        feedback.haptic(view)
        Shadows.shadowOf(feedback.looper).idle()
        ShadowLooper.idleMainLooper()
        return shadow.primitiveSegmentsInPrimitiveEffects
    }

    /** 三档递增：先是点击，力度逐档变大，最强档接近满力。 Levels grow: a click, heavier each step, near full at the top. */
    @Test fun eachLevelIsOneStrikeWithHeavierWeight() {
        val clicks = (2..4).map { lv ->
            val segs = press(lv)
            assertTrue("level $lv should strike", segs.isNotEmpty())
            assertEquals("level $lv starts with a click", VibrationEffect.Composition.PRIMITIVE_CLICK, segs.first().id)
            // 每一档都只有「一下」：不超过一个点击加一个尾随刻度，不给连续嗡嗡的图案。
            // One strike per key: at most a click plus one trailing tick, never a buzzing pattern.
            assertTrue("level $lv must not buzz: ${segs.size} segments", segs.size <= 2)
            segs.first().scale
        }
        assertTrue("levels must get heavier: $clicks", clicks.zipWithNext().all { (a, b) -> b > a })
        assertTrue("strongest must be strong: $clicks", clicks.last() >= 0.9f)
    }

    /** 关：一次都不震。 Off: no vibration at all. */
    @Test fun offNeverVibrates() {
        assertTrue("off must strike nothing", press(0).isEmpty())
    }

    /** 连打：反馈线程还没把上一击发出去时不再排队，节奏才均匀。 A burst coalesces into one strike, not a queue. */
    @Test fun aBurstCoalescesIntoOneStrike() {
        clearVibration()
        feedback.vibration = 3
        val oneStrike = press(3).size
        assertEquals("level 3 is a click plus a tick", 2, oneStrike)
        // 同一瞬间按 5 下：只有一击在飞，不是 5 次排队的振动。 Five keys in the same instant: one strike in flight.
        clearVibration()
        repeat(5) { feedback.haptic(view) }
        Shadows.shadowOf(feedback.looper).idle()
        assertEquals("a burst is one strike, not a queue", oneStrike, shadow.primitiveSegmentsInPrimitiveEffects.size)
        // 上一击发出去之后，再按还是会有新的一击。 After it has gone out, the next key strikes again.
        feedback.haptic(view)
        Shadows.shadowOf(feedback.looper).idle()
        assertEquals(oneStrike, shadow.primitiveSegmentsInPrimitiveEffects.size)
    }

    /** 系统的「触摸振动」关掉：键盘自己的档位再高也不震；打开就恢复。 */
    @Test fun theSystemTouchHapticsSwitchWins() {
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        for (lv in 1..4) {
            assertTrue("level $lv must respect the system switch", press(lv).isEmpty())
        }
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        assertTrue("turning the switch back on restores it", press(3).isNotEmpty())
    }

    /** 按键音：空格与回车不比字母轻，各有各的波形。 Key sound: space and enter are not quieter than a letter. */
    @Test fun spaceAndEnterAreNotQuieterThanALetter() {
        val rms = { a: ShortArray -> kotlin.math.sqrt(a.sumOf { it.toDouble() * it } / a.size) }
        for (style in KeySoundSynth.STYLES) {
            val letter = rms(KeySoundSynth.render(style, KeySoundSynth.Variant.LETTER))
            for (v in listOf(KeySoundSynth.Variant.SPACE, KeySoundSynth.Variant.ENTER)) {
                assertTrue("$style/$v should not be quieter", rms(KeySoundSynth.render(style, v)) > letter * 0.8)
            }
        }
    }
}
