package com.weavetext.ime.ocr

import android.content.Context
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.ModelManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拍照识字插件：可选下载的 PP-OCRv5 mobile 模型（Apache-2.0，RapidOCR 的 ONNX 发行版，未修改）。
 * 模型不进安装包；安装前不加载任何 OCR 运行时。
 *
 * The OCR plugin: optional PP-OCRv5 mobile models (Apache-2.0, unmodified RapidOCR ONNX build). Not in the APK;
 * nothing OCR-related loads until it is installed.
 */
object OcrPlugin {
    enum class State { NOT_INSTALLED, INSTALLED }

    private class Asset(val name: String, val url: String, val sha256: String, val size: Long)

    private val ASSETS = listOf(
        Asset("ch_PP-OCRv5_det_mobile.onnx",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx",
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae", 4_819_576),
        Asset("ch_PP-OCRv5_rec_mobile.onnx",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile.onnx",
            "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5", 16_631_306),
        // 字符表：RapidOCR 发布的 PP-OCRv5 字典（Apache-2.0），与识别模型配套。
        // Character table: the PP-OCRv5 dictionary RapidOCR publishes (Apache-2.0), paired with the recognition model.
        Asset("ppocrv5_dict.txt",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/paddle/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile/ppocrv5_dict.txt",
            "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", 74_012),
    )
    const val DET = "ch_PP-OCRv5_det_mobile.onnx"
    const val REC = "ch_PP-OCRv5_rec_mobile.onnx"
    const val DICT = "ppocrv5_dict.txt"
    private const val MARKER = "installed.json"

    fun dir(ctx: Context) = File(ctx.filesDir, "plugins/ocr")

    fun state(ctx: Context): State =
        if (File(dir(ctx), MARKER).isFile && ASSETS.all { File(dir(ctx), it.name).length() == it.size }) State.INSTALLED else State.NOT_INSTALLED

    /** 下载并校验两个模型，全部通过后才替换安装目录。Download and verify both models, then swap the directory in. */
    fun install(ctx: Context, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Float) -> Unit = {}) {
        val mirrors = ModelManager.get(ctx).mirrors()
        val staging = File(ctx.filesDir, "plugins/.ocr.staging").apply { deleteRecursively(); mkdirs() }
        val total = ASSETS.sumOf { it.size }.toFloat()
        var done = 0L
        val downloader = Downloader(mirrors)
        for (a in ASSETS) {
            downloader.download(a.url, a.sha256, File(staging, a.name), cancel, ranked = mirrors, expectedSize = a.size) { p ->
                onProgress((done + p.downloaded) / total)
            }
            done += a.size
        }
        File(staging, MARKER).writeText("{}")
        val target = dir(ctx)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        check(staging.renameTo(target)) { "安装失败" }
    }

    fun remove(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    /** 已安装时才创建识别流程；未安装或运行时不可用时返回 null。 Null unless installed and the runtime is available. */
    fun pipeline(ctx: Context): OcrPipeline? =
        if (state(ctx) == State.INSTALLED) runCatching { OcrRuntime.create(dir(ctx)) }.getOrNull() else null
}
