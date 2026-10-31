package com.weavetext.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

/** 快捷短语的 Android 封装：把设置页的操作翻译成引擎命令，并读回结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickPhrasesTest {
    private val sent = mutableListOf<JSONObject>()
    private var reply = "{}"
    private val phrases = QuickPhrases { command -> sent += JSONObject(command); reply }

    @Test fun listReadsEveryPhraseWithItsCodeAndWeight() {
        reply = """{"ok":true,"items":[{"code":"dz","text":"北京","weight":900},{"code":"dz","text":"上海","weight":10}]}"""
        assertEquals(listOf(QuickPhrase("dz", "北京", 900), QuickPhrase("dz", "上海", 10)), phrases.list())
        assertEquals("snippets", sent.last().getString("op"))
    }

    @Test fun addSendsTheCodeTextAndWeightAndReportsTheEngineReason() {
        reply = """{"ok":true}"""
        assertNull(phrases.add("dz", "北京", 30))
        assertEquals("addPhrase", sent.last().getString("op"))
        assertEquals(30, sent.last().getInt("weight"))
        reply = """{"ok":false,"error":"该输入码下已有相同的文字"}"""
        assertEquals("该输入码下已有相同的文字", phrases.add("dz", "北京", null))
    }

    @Test fun removeAndWeightChangesReturnWhetherTheEngineAcceptedThem() {
        reply = """{"ok":true}"""
        assertTrue(phrases.remove("dz", "北京"))
        assertEquals("removePhrase", sent.last().getString("op"))
        assertNull(phrases.setWeight("dz", "北京", 999))
        assertEquals("setPhraseWeight", sent.last().getString("op"))
        reply = """{"ok":false,"error":"没有这条短语"}"""
        assertEquals("没有这条短语", phrases.setWeight("dz", "不存在", 1))
    }

    @Test fun importAndExportUseTheSharedTextFormat() {
        reply = """{"ok":true,"added":2,"skipped":1}"""
        assertEquals(QuickPhrases.ImportResult(2, 1), phrases.import("dz\t30\t北京\n"))
        assertEquals("importPhrases", sent.last().getString("op"))
        reply = """{"ok":true,"text":"dz\t30\t北京\n"}"""
        assertEquals("dz\t30\t北京\n", phrases.export())
    }

    @Test fun anUnreadableReplyIsAnEmptyListNotACrash() {
        reply = "not json"
        assertEquals(emptyList<QuickPhrase>(), phrases.list())
    }
}
