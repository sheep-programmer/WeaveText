package com.weavetext.translate.google

import com.weavetext.translation.contract.TranslationPluginContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ProtocolTest {
    private fun failure(json: String) = runCatching { PluginProtocol.parse(json) }.exceptionOrNull() as PluginFailure

    @Test fun limitsAndVersionMatchTheSharedContract() {
        assertEquals(TranslationPluginContract.VERSION, PluginProtocol.VERSION)
        assertEquals(TranslationPluginContract.MAX_TEXT_CHARS, PluginProtocol.MAX_INPUT_CHARS)
        assertEquals(TranslationPluginContract.MAX_JSON_CHARS, PluginProtocol.MAX_JSON_BYTES)
    }

    @Test fun malformedUnsupportedAndMistypedRequestsFailWithoutEchoingJson() {
        assertEquals("INVALID_JSON", failure("private text, not json").code)
        assertEquals("UNSUPPORTED_OPERATION", failure("{\"op\":\"unknown\"}").code)
        assertEquals("INVALID_REQUEST", failure("{\"op\":\"translate\",\"target\":\"en\",\"text\":123}").code)
        assertEquals("INVALID_REQUEST", failure("{\"op\":\"download\",\"language\":\"zh\",\"wifiOnly\":\"false\"}").code)
        assertFalse(failure("private text, not json").toString().contains("private text"))
    }

    @Test fun downloadDefaultIsWifiAndNoTranslateRequestHasADownloadField() {
        val command = PluginProtocol.parse("{\"op\":\"download\",\"language\":\"zh\"}") as PluginRequest.Download
        assertTrue(command.wifiOnly)
        val translation = PluginProtocol.parse("{\"op\":\"translate\",\"target\":\"en\",\"text\":\"你好\"}") as PluginRequest.Translate
        assertEquals("auto", translation.source)
        assertEquals("你好", translation.text)
        assertFalse(translation.toString().contains("你好"))
    }

    @Test fun jsonLimitAppliesToActualUtf8BytesAndInputHasAnIndependentCharacterLimit() {
        assertEquals("REQUEST_TOO_LARGE", failure("x".repeat(PluginProtocol.MAX_JSON_BYTES + 1)).code)
        val multibyte = JSONObject().put("op", "languages").put("extra", "你".repeat(44_000)).toString()
        assertTrue(multibyte.length < PluginProtocol.MAX_JSON_BYTES)
        assertEquals("REQUEST_TOO_LARGE", failure(multibyte).code)
        val text = JSONObject().put("op", "translate").put("source", "en").put("target", "zh").put("text", "x".repeat(32_001)).toString()
        assertEquals("INPUT_TOO_LARGE", failure(text).code)
    }

    @Test fun eventFieldNamesAreTheBinderContractAndResultPreservesWhitespace() {
        val result = JSONObject(PluginEvent.result("  译文\n", "zh").encode())
        assertEquals("result", result.getString("type"))
        assertEquals("  译文\n", result.getString("text"))
        assertEquals("zh", result.getString("detected_language"))
        assertTrue(JSONObject(PluginEvent.result("text", null).encode()).isNull("detected_language"))
        val languages = JSONObject(PluginEvent.languages(listOf(PluginLanguage("en", "English", true, true))).encode())
        assertTrue(languages.getJSONArray("languages").getJSONObject(0).getBoolean("builtin"))
    }

    @Test fun idsAreBoundedAndCannotContainControlCharacters() {
        assertTrue(PluginProtocol.validRequestId("request-123"))
        assertFalse(PluginProtocol.validRequestId(""))
        assertFalse(PluginProtocol.validRequestId("x".repeat(129)))
        assertFalse(PluginProtocol.validRequestId("a\nb"))
    }

    @Test fun bothPermissionAndSignatureAreRequired() {
        assertTrue(runCatching { CallerPolicy({ true }, { true }).requireTrusted(100) }.isSuccess)
        assertTrue(runCatching { CallerPolicy({ false }, { true }).requireTrusted(100) }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching { CallerPolicy({ true }, { false }).requireTrusted(100) }.exceptionOrNull() is SecurityException)
    }
}
