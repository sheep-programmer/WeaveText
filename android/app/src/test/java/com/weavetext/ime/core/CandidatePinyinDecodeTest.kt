package com.weavetext.ime.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class CandidatePinyinDecodeTest {
    @Test fun annotationMetadataDoesNotConsumeNotesOrTheNextCandidate() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun str(s: String) { val b = s.toByteArray(); out.writeInt(b.size); out.write(b) }
            out.writeInt(3)
            str("银行"); str("已固定"); out.writeByte(7); str("yín háng")
            str("你好"); str(""); out.writeByte(4); str("nǐ hǎo")
            str("123"); str("千分位"); out.writeByte(0)
        }
        val list = EngineSnapshot.decodeCandidates(ByteBuffer.wrap(bytes.toByteArray()))
        assertEquals(Candidate("银行", "已固定", true, true, "yín háng"), list[0])
        assertEquals("nǐ hǎo", list[1].pinyin)
        assertEquals(Candidate("123", "千分位", false), list[2])
    }
}
