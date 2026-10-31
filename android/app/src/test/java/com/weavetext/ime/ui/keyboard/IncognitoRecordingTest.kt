package com.weavetext.ime.ui.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.ClipPrivacy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 无痕输入时，常用符号与剪贴板记录都不写入。 Incognito keeps symbol and clipboard history from being written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncognitoRecordingTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("symbol-usage-test", 0)

    @Before fun reset() {
        prefs.edit().clear().commit()
        ClipPrivacy.incognito = false
        ClipPrivacy.privateField = false
    }
    @After fun restore() { ClipPrivacy.incognito = false; ClipPrivacy.privateField = false }

    @Test fun symbolChoicesAreNotRememberedWhileIncognito() {
        SymbolUsage.record(prefs, "★")
        ClipPrivacy.incognito = true
        SymbolUsage.record(prefs, "※")
        assertEquals(listOf("★"), SymbolUsage.recent(prefs))
        assertFalse(SymbolUsage.order(prefs).contains("※"))
    }

    @Test fun clipboardRecordingIsBlockedByEitherPrivacyFlag() {
        assertFalse(ClipPrivacy.recordingBlocked)
        ClipPrivacy.incognito = true
        assertTrue(ClipPrivacy.recordingBlocked)
        ClipPrivacy.incognito = false
        ClipPrivacy.privateField = true
        assertTrue(ClipPrivacy.recordingBlocked)
    }
}
