package com.weavetext.ime.voice

import com.weavetext.ime.voice.local.AsrCachePolicy
import org.junit.Assert.*
import org.junit.Test

class AsrCachePolicyTest {
    @Test fun hidingTheKeyboardDoesNotEvictModels() {
        assertFalse(AsrCachePolicy.releaseForTrim(20))
        assertFalse(AsrCachePolicy.releaseForTrim(40))
        assertTrue(AsrCachePolicy.releaseForTrim(10))
        assertTrue(AsrCachePolicy.releaseForTrim(15))
        assertTrue(AsrCachePolicy.releaseForTrim(60))
        assertTrue(AsrCachePolicy.releaseForTrim(80))
    }
    @Test fun parallelModelsStayWithinTheCpuBudget() {
        assertEquals(1, AsrCachePolicy.threads(3, 4))
        assertEquals(2, AsrCachePolicy.threads(1, 4))
        assertEquals(1, AsrCachePolicy.threads(3, 1))
    }
}
