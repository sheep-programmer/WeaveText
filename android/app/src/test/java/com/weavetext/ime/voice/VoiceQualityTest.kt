package com.weavetext.ime.voice

import com.weavetext.ime.models.Bench
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceQualityTest {
    @Test fun chineseBenchmarkRanksByCleanAndNoisyErrorTogether() {
        val balanced = benchmarkQuality(Bench(0.98, 1.8, 0.06), VoiceLanguage.CHINESE)!!
        val cleanOnly = benchmarkQuality(Bench(0.33, 3.77, 0.28), VoiceLanguage.CHINESE)!!
        assertEquals(0.9861f, balanced, 0.00001f)
        org.junit.Assert.assertTrue(balanced > cleanOnly)
    }

    @Test fun chineseBenchmarkDoesNotClaimEnglishOrMixedAccuracy() {
        val bench = Bench(0.98, 1.8, 0.06)
        assertNull(benchmarkQuality(bench, VoiceLanguage.ENGLISH))
        assertNull(benchmarkQuality(bench, VoiceLanguage.MIXED))
        assertNull(benchmarkQuality(null, VoiceLanguage.CHINESE))
    }

    @Test fun invalidErrorRatesStayUnknown() {
        for (error in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0)) {
            assertNull(benchmarkQuality(Bench(error, 1.0, 0.1), VoiceLanguage.CHINESE))
            assertNull(benchmarkQuality(Bench(1.0, error, 0.1), VoiceLanguage.CHINESE))
        }
    }
}
