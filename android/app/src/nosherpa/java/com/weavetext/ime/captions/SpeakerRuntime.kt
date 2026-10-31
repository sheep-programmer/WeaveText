package com.weavetext.ime.captions

import android.content.Context
import java.io.File

/** 轻量版没有 sherpa-onnx，说话人区分不可用。The lite build has no sherpa-onnx, so speaker labels are unavailable. */
object SpeakerRuntime {
    fun embedder(ctx: Context, model: File): ((FloatArray) -> FloatArray)? = null
}
