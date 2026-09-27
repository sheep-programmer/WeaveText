package com.weavetext.ime.core

import com.weavetext.ime.ui.keyboard.TopBarView
import com.weavetext.ime.ui.keyboard.styledPreedit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** 纠错标记：解码（标量位置换成 UTF-16 下标）与显示样式。 Correction marks: decoding and display styles. */
class PreeditMarkTest {
    private fun snapshotBytes(preedit: String, marks: List<Triple<Int, Int, Int>>, removed: String = "", withMarks: Boolean = true): ByteArray {
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)
        fun str(s: String) { val b = s.toByteArray(); o.writeInt(b.size); o.write(b) }
        o.writeByte(1); o.writeByte(1)
        str(""); str(preedit)
        o.writeInt(1); o.writeInt(1); str("现在"); str(""); o.writeByte(0)
        o.writeInt(0)
        str("pinyin")
        if (withMarks) {
            o.writeInt(marks.size)
            for ((s, e, k) in marks) { o.writeInt(s); o.writeInt(e); o.writeByte(k); str(if (k == 3) removed else "") }
        }
        return bo.toByteArray()
    }

    @Test fun decodesMarksAndConvertsScalarPositions() {
        // 😀 是两个 UTF-16 单元、一个标量。 😀 is two UTF-16 units but one scalar.
        val s = EngineSnapshot.decode(snapshotBytes("😀xian'zai", listOf(Triple(2, 4, 0), Triple(9, 9, 3)), removed = "i"))
        assertEquals("😀xian'zai", s.preedit)
        assertEquals(listOf(PreeditMark(3, 5, PreeditMark.Kind.SWAP), PreeditMark(10, 10, PreeditMark.Kind.DELETE, "i")), s.marks)
    }

    @Test fun oldSnapshotsWithoutMarksAndBadMarksAreTolerated() {
        assertEquals(emptyList<PreeditMark>(), EngineSnapshot.decode(snapshotBytes("xian", emptyList(), withMarks = false)).marks)
        assertEquals(emptyList<PreeditMark>(), EngineSnapshot.decode(snapshotBytes("xian", listOf(Triple(3, 9, 1), Triple(0, 1, 7)))).marks)
    }

    @Test fun styledPreeditPutsDroppedLettersBackAndStylesFixes() {
        val (t, st) = styledPreedit(
            "xian'zhong'guo",
            listOf(
                PreeditMark(1, 3, PreeditMark.Kind.SWAP),
                PreeditMark(8, 9, PreeditMark.Kind.INSERT),
                PreeditMark(14, 14, PreeditMark.Kind.DELETE, "o"),
            ),
        )
        assertEquals("xian'zhong'guoo", t)
        val p = TopBarView.STYLE_PLAIN
        val w = TopBarView.STYLE_SWAP
        val f = TopBarView.STYLE_FIXED
        val r = TopBarView.STYLE_REMOVED
        assertArrayEquals(byteArrayOf(p, w, w, p, p, p, p, p, f, p, p, p, p, p, r), st)
        val (plain, none) = styledPreedit("ni'hao", emptyList())
        assertEquals("ni'hao", plain)
        assertArrayEquals(ByteArray(6), none)
    }
}
