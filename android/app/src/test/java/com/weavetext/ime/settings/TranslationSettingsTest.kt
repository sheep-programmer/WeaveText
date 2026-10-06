package com.weavetext.ime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.translate.DisabledTranslationService
import com.weavetext.ime.translate.HttpTranslationService
import com.weavetext.ime.translate.TranslationFailure
import com.weavetext.ime.translate.TranslationProtocol
import com.weavetext.ime.translate.TranslationRequest
import com.weavetext.ime.translate.TranslationResult
import com.weavetext.ime.translate.TranslationServices
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
/** 设置迁移与自定义配置；网页默认不构造 SDK 或请求语言包。 */
class TranslationSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun onlineTranslationIsOffAndProducesNoConfiguredClientByDefault() {
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        assertEquals("", TranslationSettings.endpoint(prefs))
        assertEquals("", TranslationSettings.apiKey(prefs))
        assertEquals("auto", TranslationSettings.sourceLanguage(prefs))
        assertFalse(TranslationSettings.allowMobileModelDownload(prefs))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.validatedProtocol(prefs))
        assertTrue(runCatching { TranslationSettings.httpConfig(prefs) }.isFailure)
    }

    @Test fun endpointAndTimeoutAreBoundedBySettingsHelpers() {
        prefs.edit()
            .putBoolean(TranslationSettings.ONLINE_ENABLED, true)
            .putString(TranslationSettings.ENDPOINT, "https://example.test/translate")
            .putInt(TranslationSettings.TIMEOUT_MS, 90_000)
            .commit()

        assertTrue(TranslationSettings.isValidEndpoint(TranslationSettings.endpoint(prefs)))
        assertTrue(TranslationSettings.timeoutMs(prefs) <= TranslationSettings.MAX_TIMEOUT_MS)
    }

    @Test fun malformedEndpointRemainsDisabledEvenWhenOnlineSwitchIsOn() {
        prefs.edit().putBoolean(TranslationSettings.ONLINE_ENABLED, true).putString(TranslationSettings.ENDPOINT, "file:///tmp/translate").commit()
        assertTrue(TranslationServices.from(prefs) is DisabledTranslationService)
    }

    @Test fun blankEndpointDoesNotPermitEnablingOrConstructNetworkService() {
        TranslationSettings.setProtocol(prefs, TranslationProtocol.LIBRE_TRANSLATE)
        assertFalse(TranslationSettings.setOnlineEnabled(prefs, true))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        prefs.edit().putBoolean(TranslationSettings.ONLINE_ENABLED, true).commit()
        assertTrue(TranslationServices.from(prefs) is DisabledTranslationService)
    }

    @Test fun googleNeedsNoCustomNetworkConsentEndpointOrApiKey() {
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.validatedProtocol(prefs))
        assertFalse(TranslationSettings.setOnlineEnabled(prefs, true))
        assertFalse(prefs.contains(TranslationSettings.ENDPOINT))
        assertFalse(prefs.contains(TranslationSettings.API_KEY))
        assertEquals("auto", TranslationSettings.sourceLanguage(prefs))
    }

    @Test fun googleSourceDefaultsToAutoAndKeepsAnExplicitChoice() {
        assertEquals("auto", TranslationSettings.sourceLanguage(prefs))
        prefs.edit().putString(TranslationSettings.SOURCE_LANGUAGE, "en").commit()
        assertEquals("en", TranslationSettings.sourceLanguage(prefs))
        TranslationSettings.setProtocol(prefs, TranslationProtocol.LIBRE_TRANSLATE)
        assertEquals("en", TranslationSettings.sourceLanguage(prefs))
        TranslationSettings.setProtocol(prefs, TranslationProtocol.GOOGLE_WEB)
        assertEquals("en", TranslationSettings.sourceLanguage(prefs))
    }

    @Test fun switchingToGoogleKeepsCustomSettingsWithoutUsingThem() {
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "private-libre-key", 8_000))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        TranslationSettings.setProtocol(prefs, TranslationProtocol.GOOGLE_WEB)
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.validatedProtocol(prefs))
        assertEquals("https://example.test/translate", TranslationSettings.endpoint(prefs))
        assertEquals("private-libre-key", TranslationSettings.apiKey(prefs))
        TranslationSettings.setProtocol(prefs, TranslationProtocol.LIBRE_TRANSLATE)
        assertEquals("private-libre-key", TranslationSettings.httpConfig(prefs).apiKey)
        assertFalse(TranslationSettings.onlineEnabled(prefs))
    }

    @Test fun retiredExplicitPresetMigratesOnceAndClearsOnlyPresetCredentials() {
        prefs.edit().putString(TranslationSettings.PROTOCOL, "mymemory")
            .putString(TranslationSettings.ENDPOINT, "https://api.mymemory.translated.net/get")
            .putString(TranslationSettings.API_KEY, "old-preset-key")
            .putBoolean(TranslationSettings.ONLINE_ENABLED, true).commit()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.validatedProtocol(prefs))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertEquals("", TranslationSettings.endpoint(prefs))
        assertEquals("", TranslationSettings.apiKey(prefs))
        assertTrue(prefs.getBoolean(TranslationSettings.GOOGLE_DEVICE_MIGRATION, false))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://local.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "new-key", 8_000))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        TranslationSettings.migrateLegacyProvider(prefs)
        assertEquals(TranslationProtocol.LIBRE_TRANSLATE, TranslationSettings.protocol(prefs))
        assertTrue(TranslationSettings.onlineEnabled(prefs))
        assertEquals("new-key", TranslationSettings.apiKey(prefs))
    }

    @Test fun oldImplicitEnabledPresetMigratesWithoutPublicServiceFallback() {
        prefs.edit().putBoolean(TranslationSettings.ONLINE_ENABLED, true)
            .putString(TranslationSettings.API_KEY, "stray-preset-key").commit()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.validatedProtocol(prefs))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        assertEquals("", TranslationSettings.endpoint(prefs))
        assertEquals("", TranslationSettings.apiKey(prefs))
    }

    @Test fun explicitPresetMigrationKeepsAPreviouslySavedCustomConfiguration() {
        prefs.edit().putString(TranslationSettings.PROTOCOL, "mymemory")
            .putString(TranslationSettings.ENDPOINT, "http://192.168.1.3:5000/translate")
            .putString(TranslationSettings.API_KEY, "saved-private-key")
            .putBoolean(TranslationSettings.ONLINE_ENABLED, true).commit()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.validatedProtocol(prefs))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertEquals("http://192.168.1.3:5000/translate", TranslationSettings.endpoint(prefs))
        assertEquals("saved-private-key", TranslationSettings.apiKey(prefs))
    }

    @Test fun migrationPreservesExplicitAndLegacyCustomPreferencesAndConsent() {
        for (protocol in listOf("libretranslate", "generic", null)) {
            prefs.edit().clear().putString(TranslationSettings.PROTOCOL, protocol)
                .putString(TranslationSettings.ENDPOINT, "https://custom.test/translate")
                .putString(TranslationSettings.API_KEY, "custom-key")
                .putString(TranslationSettings.SOURCE_LANGUAGE, "fr")
                .putInt(TranslationSettings.TIMEOUT_MS, 12_000)
                .putBoolean(TranslationSettings.ONLINE_ENABLED, true).commit()
            val expected = if (protocol == "libretranslate") TranslationProtocol.LIBRE_TRANSLATE else TranslationProtocol.GENERIC
            assertEquals(expected, TranslationSettings.validatedProtocol(prefs))
            assertTrue(TranslationSettings.onlineEnabled(prefs))
            assertEquals("https://custom.test/translate", TranslationSettings.endpoint(prefs))
            assertEquals("custom-key", TranslationSettings.apiKey(prefs))
            assertEquals("fr", TranslationSettings.sourceLanguage(prefs))
            assertEquals(12_000, TranslationSettings.timeoutMs(prefs))
            assertTrue(TranslationServices.from(app, prefs) is HttpTranslationService)
        }
    }

    @Test fun meteredModelPermissionHasTheInstrumentationKeyAndDefaultsOff() {
        assertEquals("translation_model_metered_allowed", TranslationSettings.MODEL_METERED_ALLOWED)
        assertFalse(TranslationSettings.allowMobileModelDownload(prefs))
        prefs.edit().putBoolean("translation_model_metered_allowed", true).commit()
        assertTrue(TranslationSettings.allowMobileModelDownload(prefs))
    }

    @Test fun savedConfigurationRequiresSeparateExplicitEnable() {
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "test-key", 8_000))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        assertTrue(TranslationServices.from(prefs) is HttpTranslationService)
        assertEquals(TranslationProtocol.LIBRE_TRANSLATE, TranslationSettings.httpConfig(prefs).protocol)
        assertEquals("test-key", TranslationSettings.httpConfig(prefs).apiKey)
        assertFalse(TranslationSettings.httpConfig(prefs).toString().contains("test-key"))
    }

    @Test fun changedDestinationProtocolOrKeyRevokesPreviousOptIn() {
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "test-key", 8_000))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://other.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "test-key", 8_000))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://other.test/translate", TranslationProtocol.GENERIC, "test-key", 8_000))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://other.test/translate", TranslationProtocol.GENERIC, "changed-key", 8_000))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
    }

    @Test fun savingSameConfigurationDoesNotRevokeConsentAndClearingKeyRemovesPreference() {
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "test-key", 8_000))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "test-key", 8_000))
        assertTrue(TranslationSettings.onlineEnabled(prefs))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "", 8_000))
        assertFalse(prefs.contains(TranslationSettings.API_KEY))
        assertEquals("", TranslationSettings.apiKey(prefs))
    }

    @Test fun legacyGenericEndpointKeepsItsProtocolAndChoiceCanBeChangedExplicitly() {
        prefs.edit().putString(TranslationSettings.ENDPOINT, "https://example.test/translate").commit()
        assertEquals(TranslationProtocol.GENERIC, TranslationSettings.protocol(prefs))
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "", 8_000))
        assertEquals(TranslationProtocol.LIBRE_TRANSLATE, TranslationSettings.protocol(prefs))
    }

    @Test fun credentialBearingOrMalformedAddressNeverOverwritesSavedConfiguration() {
        val old = "https://example.test/translate"
        assertTrue(TranslationSettings.saveConfiguration(prefs, old, TranslationProtocol.LIBRE_TRANSLATE, "secret-test-key", 8_000))
        for (address in listOf("https://user:key@example.test/translate", "$old?api_key=secret-test-key",
            "file:///tmp/translate", "https://example.test/secret-test-key", "not a URL")) {
            assertFalse(TranslationSettings.saveConfiguration(prefs, address, TranslationProtocol.LIBRE_TRANSLATE, "secret-test-key", 8_000))
            assertEquals(old, TranslationSettings.endpoint(prefs))
        }
    }

    @Test fun unknownProtocolFailsClosed() {
        prefs.edit().putBoolean(TranslationSettings.ONLINE_ENABLED, true)
            .putString(TranslationSettings.ENDPOINT, "https://example.test/translate")
            .putString(TranslationSettings.PROTOCOL, "unknown").commit()
        assertTrue(TranslationServices.from(prefs) is DisabledTranslationService)
        assertTrue(TranslationServices.from(app, prefs) is DisabledTranslationService)
        assertTrue(runCatching { TranslationSettings.validatedProtocol(prefs) }.isFailure)
        assertEquals("unknown", prefs.getString(TranslationSettings.PROTOCOL, null))
    }

    @Test fun previouslyCreatedServiceRechecksConsentBeforeAnyNetworkAccess() {
        assertTrue(TranslationSettings.saveConfiguration(prefs, "https://example.test/translate", TranslationProtocol.LIBRE_TRANSLATE, "", 8_000))
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, true))
        val service = TranslationServices.from(prefs)
        assertTrue(TranslationSettings.setOnlineEnabled(prefs, false))
        val results = java.util.concurrent.LinkedBlockingQueue<TranslationResult>()
        service.translate(TranslationRequest("auto", "en", "hello")) { results.add(it) }
        val result = results.poll(3, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(result is TranslationResult.Failure)
        assertEquals(TranslationFailure.NOT_ENABLED, (result as TranslationResult.Failure).error.kind)
    }

    @Test fun oldDeviceDefaultMigratesToWebEvenAfterThePreviousMigration() {
        prefs.edit().putString(TranslationSettings.PROTOCOL, "google_device")
            .putBoolean(TranslationSettings.GOOGLE_DEVICE_MIGRATION, true)
            .putBoolean(TranslationSettings.ONLINE_ENABLED, true).commit()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertTrue(prefs.getBoolean(TranslationSettings.GOOGLE_WEB_MIGRATION, false))
    }

    @Test fun migrationDoesNotRepeatAfterExplicitlyEnablingTheReadyPlugin() {
        TranslationSettings.migrateLegacyProvider(prefs)
        assertTrue(TranslationSettings.setOfflinePluginEnabled(prefs, enabled = true, ready = true))
        repeat(3) { TranslationSettings.migrateLegacyProvider(prefs) }
        assertEquals(TranslationProtocol.GOOGLE_DEVICE, TranslationSettings.validatedProtocol(prefs))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
    }

    @Test fun oldDeviceDefaultKeepsSavedCustomConfigurationAndLanguages() {
        prefs.edit().putString(TranslationSettings.PROTOCOL, "google_device")
            .putString(TranslationSettings.ENDPOINT, "https://custom.test/translate")
            .putString(TranslationSettings.API_KEY, "saved-key")
            .putInt(TranslationSettings.TIMEOUT_MS, 12_000)
            .putString(TranslationSettings.SOURCE_LANGUAGE, "fr")
            .putString(TranslationSettings.TARGET_LANGUAGE, "zh").commit()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        assertEquals("https://custom.test/translate", TranslationSettings.endpoint(prefs))
        assertEquals("saved-key", TranslationSettings.apiKey(prefs))
        assertEquals(12_000, TranslationSettings.timeoutMs(prefs))
        assertEquals("fr", TranslationSettings.sourceLanguage(prefs))
        assertEquals("zh", prefs.getString(TranslationSettings.TARGET_LANGUAGE, null))
    }

    @Test fun onlyReadyPluginCanBeEnabledAndDisablingItSelectsWeb() {
        assertFalse(TranslationSettings.setOfflinePluginEnabled(prefs, enabled = true, ready = false))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        assertTrue(TranslationSettings.setOfflinePluginEnabled(prefs, enabled = true, ready = true))
        assertEquals(TranslationProtocol.GOOGLE_DEVICE, TranslationSettings.protocol(prefs))
        assertTrue(TranslationSettings.setOfflinePluginEnabled(prefs, enabled = false, ready = false))
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
    }

    @Test fun disablingAnUnselectedPluginDoesNotOverrideCustomProvider() {
        TranslationSettings.setProtocol(prefs, TranslationProtocol.GENERIC)
        TranslationSettings.setOfflinePluginEnabled(prefs, enabled = false, ready = false)
        assertEquals(TranslationProtocol.GENERIC, TranslationSettings.protocol(prefs))
    }

    @Test fun bothGoogleModesRejectCustomHttpConfiguration() {
        for (protocol in listOf(TranslationProtocol.GOOGLE_WEB, TranslationProtocol.GOOGLE_DEVICE)) {
            TranslationSettings.setProtocol(prefs, protocol)
            assertFalse(TranslationSettings.usesCustomHttp(protocol))
            assertFalse(TranslationSettings.saveConfiguration(prefs, "https://custom.test/translate", protocol, "key", 8_000))
            assertFalse(TranslationSettings.setOnlineEnabled(prefs, true))
            assertTrue(runCatching { TranslationSettings.httpConfig(prefs) }.isFailure)
        }
    }
}
