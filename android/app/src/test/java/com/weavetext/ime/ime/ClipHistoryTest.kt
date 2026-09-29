package com.weavetext.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** org.json 在纯 JVM 下是桩实现，因此用 Robolectric 运行。 Runs under Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
class ClipHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hour = 3600_000L

    private fun file() = File(tmp.root, "clip/history.json")

    @Test fun newestFirstAndPinnedOnTop() {
        val h = ClipHistory(file())
        h.add("a", 1); h.add("b", 2); h.add("c", 3)
        assertEquals(listOf("c", "b", "a"), h.list(4).map { it.text })
        h.setPinned(h.list(4).first { it.text == "a" }.id, true)
        assertEquals(listOf("a", "c", "b"), h.list(4).map { it.text })
    }

    @Test fun duplicateMovesToTopKeepingPin() {
        val h = ClipHistory(file())
        h.add("a", 1); h.add("b", 2)
        h.setPinned(h.list(3).first { it.text == "a" }.id, true)
        h.add("a", 5)
        assertEquals(2, h.size)
        assertTrue(h.list(6).first { it.text == "a" }.pinned)
    }

    @Test fun blankIgnored() {
        val h = ClipHistory(file())
        assertFalse(h.add("  \n", 1))
        assertEquals(0, h.size)
    }

    @Test fun capsUnpinnedButKeepsPinned() {
        val h = ClipHistory(file(), maxUnpinned = 3)
        h.add("pin", 0)
        h.setPinned(h.list(0).first().id, true)
        for (i in 1..5) h.add("t$i", i.toLong())
        val texts = h.list(6).map { it.text }
        assertEquals(listOf("pin", "t5", "t4", "t3"), texts)
    }

    @Test fun unpinnedExpireAfterTtl() {
        val h = ClipHistory(file(), ttlMs = 24 * hour)
        h.add("old", 0); h.add("keep", 0); h.add("new", 23 * hour)
        h.setPinned(h.list(1).first { it.text == "keep" }.id, true)
        assertEquals(listOf("keep", "new"), h.list(25 * hour).map { it.text })
    }

    @Test fun zeroTtlNeverExpires() {
        val h = ClipHistory(file(), ttlMs = 0)
        h.add("phrase", 0)
        assertEquals(1, h.list(1000 * hour).size)
    }

    @Test fun deleteAndClearUnpinned() {
        val h = ClipHistory(file())
        h.add("a", 1); h.add("b", 2); h.add("c", 3)
        h.setPinned(h.list(4).first { it.text == "c" }.id, true)
        h.delete(listOf(h.list(4).first { it.text == "a" }.id))
        assertEquals(listOf("c", "b"), h.list(4).map { it.text })
        h.clearUnpinned()
        assertEquals(listOf("c"), h.list(4).map { it.text })
    }

    @Test fun persistsAcrossInstances() {
        val a = ClipHistory(file())
        a.add("x", 10); a.add("y", 20)
        a.setPinned(a.list(21).first { it.text == "x" }.id, true)
        val b = ClipHistory(file())
        assertEquals(listOf("x", "y"), b.list(21).map { it.text })
        assertTrue(b.list(21).first().pinned)
        // 新 id 不与旧的冲突。 New ids don't collide.
        b.add("z", 30)
        assertEquals(3, b.list(31).map { it.id }.toSet().size)
    }

    @Test fun corruptFileIsIgnored() {
        file().parentFile!!.mkdirs(); file().writeText("{not json")
        val h = ClipHistory(file())
        assertEquals(0, h.size)
        h.add("ok", 1)
        assertEquals(1, ClipHistory(file()).size)
    }

    @Test fun oversizedEntryIgnored() {
        val h = ClipHistory(file())
        assertTrue(h.add("a".repeat(10 * 1024), 1))
        assertFalse(h.add("b".repeat(10 * 1024 + 1), 2))
        // 中文 3 字节：3414 字 = 10242 字节。 CJK is 3 bytes each.
        assertFalse(h.add("中".repeat(3414), 3))
        assertEquals(1, h.size)
    }

    @Test fun totalCapDropsOldestUnpinnedThenPinned() {
        val h = ClipHistory(file(), maxItemBytes = 4, maxTotalBytes = 10)
        h.add("p1", 0)
        h.setPinned(h.list(0).first().id, true)
        h.add("aaaa", 1); h.add("bbbb", 2)
        assertEquals(10, h.list(3).sumOf { it.text.length })
        h.add("cc", 3)
        // 超出 10 字节：删最旧的未固定 aaaa。 Over budget: the oldest unpinned goes first.
        assertEquals(listOf("p1", "cc", "bbbb"), h.list(4).map { it.text })
        h.setPinned(h.list(4).first { it.text == "bbbb" }.id, true)
        h.setPinned(h.list(4).first { it.text == "cc" }.id, true)
        h.add("dddd", 5)
        // 未固定只剩 dddd 本身不够腾：再删最旧的固定项 p1。 Then the oldest pinned entry.
        assertEquals(listOf("cc", "bbbb", "dddd"), h.list(6).map { it.text })
    }

    @Test fun clearAllRemovesPinnedAndFile() {
        val h = ClipHistory(file())
        h.add("a", 1); h.add("b", 2)
        h.setPinned(h.list(3).first().id, true)
        h.clearAll()
        assertEquals(0, h.size)
        ClipHistory.awaitIo()
        assertFalse(file().exists())
        assertEquals(0, ClipHistory(file()).size)
    }

    @Test fun loadSkipsOversizedEntries() {
        ClipHistory(file(), maxItemBytes = 100).apply { add("short", 1); add("x".repeat(50), 2) }
        val h = ClipHistory(file(), maxItemBytes = 10)
        assertEquals(listOf("short"), h.list(3).map { it.text })
    }

    @Test fun utf8SizeCountsBytes() {
        assertEquals(1, utf8Size("a"))
        assertEquals(2, utf8Size("é"))
        assertEquals(3, utf8Size("中"))
        assertEquals(4, utf8Size("😀"))
        assertEquals("a中😀é".toByteArray(Charsets.UTF_8).size, utf8Size("a中😀é"))
    }
}
