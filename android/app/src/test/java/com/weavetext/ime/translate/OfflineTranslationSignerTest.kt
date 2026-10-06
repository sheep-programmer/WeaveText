package com.weavetext.ime.translate

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class OfflineTranslationSignerTest {
    @Suppress("DEPRECATION")
    private fun info(vararg certificates: String): PackageInfo {
        val signatures = certificates.map { Signature(it.toByteArray()) }.toTypedArray()
        return PackageInfo().apply {
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                    shadowOf(it).setSignatures(signatures)
                }
            } else this.signatures = signatures
        }
    }

    @Test fun identicalCurrentSignersAreTrustedRegardlessOfOrder() {
        assertTrue(OfflineTranslationPlugin.sameSigners(info("a", "b"), info("b", "a")))
    }

    @Test fun differentSignersAndPartialMatchesAreRejected() {
        assertFalse(OfflineTranslationPlugin.sameSigners(info("a"), info("b")))
        assertFalse(OfflineTranslationPlugin.sameSigners(info("a", "b"), info("a")))
    }

    @Test fun missingSignaturesNeverAuthorizeAnArchive() {
        assertFalse(OfflineTranslationPlugin.sameSigners(PackageInfo(), PackageInfo()))
        assertFalse(OfflineTranslationPlugin.sameSigners(info(), info()))
        assertFalse(OfflineTranslationPlugin.sameSigners(info("a"), info()))
    }

    @Test
    @Config(sdk = [35])
    fun historicalCertificateDoesNotReplaceTheCurrentSignerCheck() {
        val plugin = info("new")
        shadowOf(plugin.signingInfo!!).setPastSigningCertificates(arrayOf(Signature("old".toByteArray())))
        assertFalse(OfflineTranslationPlugin.sameSigners(info("old"), plugin))
    }
}
