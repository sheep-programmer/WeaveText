package com.weavetext.ime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ui.keyboard.KbMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 默认值：没动过设置的用户读到新默认值，明确选择过的保持不变。
 * Defaults: untouched settings read the new defaults; explicit choices are kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class DefaultsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val p get() = WeavePrefs.of(app)

    @Before fun clear() { p.edit().clear().commit() }

    @Test fun absentKeysReadNewDefaults() {
        assertEquals(3, WeavePrefs.heightLevel(p))
        assertEquals(0, WeavePrefs.vibration(p))
        assertEquals(WeavePrefs.SOUND_OFF, WeavePrefs.soundStyle(p))
        assertEquals(WeavePrefs.SOUND_VOLUME_DEFAULT, WeavePrefs.soundVolume(p))
        assertEquals(1, WeavePrefs.pinyinHint(p))
    }
    @Test fun tonedAnnotationsKeepOffChoicesAndUpgradeLegacyPlainHints() {
        p.edit().putInt(WeavePrefs.PINYIN_HINT, 0).commit()
        assertEquals(0, WeavePrefs.pinyinHint(p))
        p.edit().putInt(WeavePrefs.PINYIN_HINT, 2).commit()
        assertEquals(1, WeavePrefs.pinyinHint(p))
    }

    @Test fun explicitChoicesAreKept() {
        p.edit().putInt(WeavePrefs.HEIGHT_LEVEL, 2).putInt(WeavePrefs.VIBRATION, 1).commit()
        assertEquals(2, WeavePrefs.heightLevel(p))
        assertEquals(1, WeavePrefs.vibration(p))
    }

    @Test fun legacySoundLevelMapsToSystemStyle() {
        p.edit().putInt(WeavePrefs.SOUND, 3).commit()
        assertEquals(WeavePrefs.SOUND_SYSTEM, WeavePrefs.soundStyle(p))
        assertEquals(50, WeavePrefs.soundVolume(p))
        p.edit().putString(WeavePrefs.SOUND_STYLE, "wood").putInt(WeavePrefs.SOUND_VOLUME, 20).commit()
        assertEquals("wood", WeavePrefs.soundStyle(p))
        assertEquals(20, WeavePrefs.soundVolume(p))
    }

    @Test fun defaultHeightIsTallerThanStandard() {
        val standard = KbMetrics(app, 2)
        val default = KbMetrics(app, WeavePrefs.heightLevel(p))
        assertTrue(default.kbHeight > standard.kbHeight)
        // 1080×2400（411×914 dp）上默认行距 62 dp。 62 dp row pitch on a 411×914 dp phone.
        assertEquals(62f, default.rowPitch / default.density, 0.01f)
    }
}
