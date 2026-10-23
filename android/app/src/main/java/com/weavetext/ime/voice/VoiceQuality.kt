package com.weavetext.ime.voice

import com.weavetext.ime.models.Bench

/** 中文合成语音评测仅用于中文档位的相对排序。 Chinese benchmark ranking only. */
internal fun benchmarkQuality(bench: Bench?, language: VoiceLanguage): Float? {
    if (language != VoiceLanguage.CHINESE || bench == null) return null
    if (!bench.cerClean.isFinite() || bench.cerClean !in 0.0..100.0 ||
        !bench.cerNoisy.isFinite() || bench.cerNoisy !in 0.0..100.0) return null
    return (1.0 - (bench.cerClean + bench.cerNoisy) / 200.0).toFloat()
}
