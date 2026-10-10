package com.weavetext.ime.ui

import android.app.Application
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ui.keyboard.Feedback
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowVibrator
import org.robolectric.shadows.ShadowSystemVibrator
import org.robolectric.util.ReflectionHelpers

/** Actual vibrator calls across legacy, predefined-effect and composed-effect APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29, 30, 31, 33, 35])
class HapticCompatibilityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var shadow: ShadowVibrator
    private lateinit var feedback: Feedback
    private lateinit var view: View

    @Before fun setup() {
        val v = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
            else app.getSystemService(Vibrator::class.java)
        shadow = Shadows.shadowOf(v)
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(false)
        shadow.setSupportedPrimitives(emptyList())
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        feedback = Feedback(app)
        Shadows.shadowOf(feedback.looper).pause()
        view = View(app)
    }

    @After fun cleanup() { feedback.release() }
    private fun drain() = Shadows.shadowOf(feedback.looper).idle()
    private fun assertTouchUsage() {
        if (Build.VERSION.SDK_INT >= 31) {
            val attrs = requireNotNull(shadow.vibrationAttributesFromLastVibration)
            assertEquals(VibrationAttributes.USAGE_TOUCH, ReflectionHelpers.callInstanceMethod<Int>(attrs, "getUsage"))
            if (Build.VERSION.SDK_INT >= 33) assertEquals(0, (attrs as VibrationAttributes).flags)
        } else {
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, requireNotNull(shadow.audioAttributesFromLastVibration).usage)
        }
    }

    @Test fun eachExplicitStrengthUsesTouchAttributes() {
        for (level in 2..4) {
            feedback.vibration = level; feedback.haptic(view); drain()
            assertTouchUsage()
        }
    }

    @Test fun cursorTickAlsoUsesTouchAttributes() {
        feedback.vibration = 3; feedback.tick(view); drain()
        assertTouchUsage()
    }

    @Test fun devicesWithoutAmplitudeOrPrimitivesStillGetASupportedEffect() {
        for (level in 2..4) {
            feedback.vibration = level; feedback.haptic(view); drain()
            if (Build.VERSION.SDK_INT >= 29) {
                val actual = if (Build.VERSION.SDK_INT >= 31) {
                    val segments: List<Any> = ReflectionHelpers.getField(shadow, "vibrationEffectSegments")
                    ReflectionHelpers.callInstanceMethod<Int>(segments.first(), "getEffectId")
                } else shadow.effectId
                assertEquals(when (level) { 2 -> VibrationEffect.EFFECT_TICK; 3 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_HEAVY_CLICK }, actual)
            } else {
                assertEquals(when (level) { 2 -> 12L; 3 -> 18L; else -> 26L }, shadow.milliseconds)
            }
        }
    }

    @Test fun turningOffBeforeTheWorkerRunsDropsBothQueuedEffects() {
        feedback.vibration = 4; feedback.haptic(view); feedback.tick(view)
        feedback.vibration = 0; drain()
        assertFalse(shadow.isVibrating)
        assertNull(shadow.vibrationAttributesFromLastVibration)
        assertNull(shadow.audioAttributesFromLastVibration)
    }

    @Test fun turningSystemFeedbackOffBeforeDeliveryDropsQueuedEffects() {
        feedback.vibration = 4; feedback.haptic(view); feedback.tick(view)
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        drain(); assertFalse(shadow.isVibrating)
        assertEquals(Feedback.HapticAvailability.SYSTEM_DISABLED, feedback.hapticAvailability())
    }

    @Test fun releasingBeforeDeliveryDoesNotEmitALateVibration() {
        feedback.vibration = 4; feedback.haptic(view); feedback.tick(view)
        feedback.release()
        assertFalse(shadow.isVibrating)
    }

    @Test fun malformedPreferencesCannotKillTheFeedbackWorker() {
        feedback.vibration = -100; feedback.haptic(view); drain()
        assertEquals(0, feedback.vibration)
        feedback.vibration = 100; feedback.haptic(view); drain()
        assertEquals(4, feedback.vibration)
        assertTouchUsage()
    }

    @Test fun absenceOfAVibrationMotorIsReported() {
        feedback.release(); shadow.setHasVibrator(false)
        feedback = Feedback(app)
        Shadows.shadowOf(feedback.looper).pause()
        assertEquals(Feedback.HapticAvailability.NO_VIBRATOR, feedback.hapticAvailability())
        feedback.vibration = 3; feedback.haptic(view); drain()
        assertFalse(shadow.isVibrating)
    }

    @Test fun lightFeedbackDoesNotRequireAnUnsupportedTrailingTick() {
        if (Build.VERSION.SDK_INT < 31) return
        shadow.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK))
        feedback.vibration = 2; feedback.haptic(view); drain()
        assertEquals(VibrationEffect.Composition.PRIMITIVE_CLICK, shadow.primitiveSegmentsInPrimitiveEffects.first().id)
        assertTouchUsage()
        feedback.vibration = 4; feedback.haptic(view); drain()
        val segments: List<Any> = ReflectionHelpers.getField(shadow, "vibrationEffectSegments")
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK, ReflectionHelpers.callInstanceMethod<Int>(segments.first(), "getEffectId"))
    }

    @Test @Config(sdk = [35], shadows = [RejectFirstVibration::class])
    fun aVendorRejectingTheFirstEffectGetsAFallbackAndTheNextKeyStillWorks() {
        RejectFirstVibration.calls = 0
        shadow.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK))
        feedback.vibration = 3; feedback.haptic(view); drain()
        assertEquals(2, RejectFirstVibration.calls)
        val segments: List<Any> = ReflectionHelpers.getField(shadow, "vibrationEffectSegments")
        assertEquals(VibrationEffect.EFFECT_CLICK, ReflectionHelpers.callInstanceMethod<Int>(segments.first(), "getEffectId"))
        feedback.haptic(view); drain()
        assertEquals(3, RejectFirstVibration.calls)
        assertTouchUsage()
    }
}

@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
class RejectFirstVibration : ShadowSystemVibrator() {
    companion object { var calls = 0 }
    @Implementation(minSdk = 33)
    override fun vibrate(uid: Int, opPkg: String?, @ClassName("android.os.VibrationEffect") effect: Any?, reason: String?, @ClassName("android.os.VibrationAttributes") attributes: Any?) {
        if (++calls == 1) throw IllegalArgumentException("Vendor rejected the composed effect")
        super.vibrate(uid, opPkg, effect, reason, attributes)
    }
}
