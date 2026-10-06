package com.weavetext.ime.translate

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationWebTest {
    @Test fun officialURLPreservesTextAndNeverInjectsExtraParameters() {
        val text = "你好 & + ? #\nhttps://other.test/?q=hello"
        val uri = TranslationWeb.google(TranslationRequest("auto", "zh", text))
        assertEquals("https", uri.scheme)
        assertEquals("translate.google.com", uri.host)
        assertEquals("auto", uri.getQueryParameter("sl"))
        assertEquals("zh-CN", uri.getQueryParameter("tl"))
        assertEquals(text, uri.getQueryParameter("text"))
        assertEquals(setOf("sl", "tl", "text", "op"), uri.queryParameterNames)
        assertNull(uri.fragment)
    }
    @Test fun invalidOrTooLongInputDoesNotBuildALink() {
        for (request in listOf(TranslationRequest("auto", "en", ""),
            TranslationRequest("auto", "en", "a".repeat(5001)), TranslationRequest("zh", "auto", "你好"))) {
            assertTrue(runCatching { TranslationWeb.google(request) }.isFailure)
        }
    }
}
