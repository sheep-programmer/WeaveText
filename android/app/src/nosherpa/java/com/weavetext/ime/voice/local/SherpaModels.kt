package com.weavetext.ime.voice.local

import com.weavetext.ime.models.ModelLocation
import com.weavetext.ime.models.ModelSpec

/**
 * 轻量版的空实现：不带端侧识别运行时。`BuildConfig.LOCAL_ASR` 为 false 时本地引擎不会出现在列表里，
 * 这里只在误用时报错。
 * Lite stub without the on-device runtime. With `BuildConfig.LOCAL_ASR` false the local engine is never
 * listed; these only fail on misuse.
 */
internal object SherpaModels {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("轻量版不含离线语音识别")

    fun streaming(spec: ModelSpec, loc: ModelLocation): StreamingAsr = unsupported()
    fun offline(spec: ModelSpec, loc: ModelLocation): OfflineAsr = unsupported()
    fun punctuator(loc: ModelLocation): Punctuator = unsupported()
}
