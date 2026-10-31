package com.weavetext.ime.ocr

import java.io.File

/** 轻量版没有 ONNX Runtime，拍照识字不可用。The lite build has no ONNX Runtime, so OCR is unavailable. */
object OcrRuntime {
    fun create(dir: File): OcrPipeline? = null
}
