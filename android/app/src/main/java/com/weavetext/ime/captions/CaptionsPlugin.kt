package com.weavetext.ime.captions

import android.content.Context
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.ModelManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 字幕的说话人插件：可选下载 3D-Speaker CAM++ 说话人嵌入模型（Apache-2.0，sherpa-onnx 发布版，未修改）。
 * 不装也能看字幕，只是所有话都标成「说话人 1」。
 *
 * Optional speaker plugin for captions: the 3D-Speaker CAM++ embedding model (Apache-2.0, unmodified sherpa-onnx release).
 * Captions work without it; every line is then labelled speaker 1.
 */
object CaptionsPlugin {
    enum class State { NOT_INSTALLED, INSTALLED }

    private const val NAME = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    private const val SHA256 = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11"
    private const val SIZE = 28_281_138L
    private const val MARKER = "installed.json"

    fun dir(ctx: Context) = File(ctx.filesDir, "plugins/captions")
    fun model(ctx: Context) = File(dir(ctx), NAME)

    fun state(ctx: Context): State =
        if (File(dir(ctx), MARKER).isFile && model(ctx).length() == SIZE) State.INSTALLED else State.NOT_INSTALLED

    fun install(ctx: Context, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Float) -> Unit = {}) {
        val mirrors = ModelManager.get(ctx).mirrors()
        val staging = File(ctx.filesDir, "plugins/.captions.staging").apply { deleteRecursively(); mkdirs() }
        Downloader(mirrors).download(URL, SHA256, File(staging, NAME), cancel, ranked = mirrors, expectedSize = SIZE) { p ->
            onProgress(p.downloaded.toFloat() / SIZE)
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
}
