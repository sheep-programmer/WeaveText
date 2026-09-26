package com.weavetext.ime.models

import com.weavetext.ime.BuildConfig
import java.io.File

/**
 * 端侧识别运行库：离线语音版随包带着；轻量版在「语音包」里按需下载（sherpa-onnx C 接口 + onnxruntime）。
 * 本地识别是否可用只取决于「运行库在不在 + 有没有实时模型」，不再按构建类型写死。
 *
 * The on-device speech runtime: bundled in the offline-voice build, downloaded on demand by the lite build
 * as part of the voice pack. Whether local recognition is available depends only on "runtime present and a
 * streaming model installed", not on the build type.
 */
object AsrRuntime {
    /** 目录里运行库条目的 id。 Catalog id of the runtime entry. */
    const val ID = "asr-runtime"

    /** 本构建是否随包带运行库（测试可覆盖）。 Whether this build bundles the runtime; tests may override. */
    @Volatile var bundled: Boolean = BuildConfig.LOCAL_ASR

    /** 运行库可用：随包，或已下载。 Runtime usable: bundled or downloaded. */
    fun ready(repo: ModelRepository, bundled: Boolean = this.bundled): Boolean =
        bundled || repo.state(ID) == ModelState.Installed

    /** 本地识别引擎可用：运行库 + 至少一个实时模型。 Local engine usable: runtime plus a streaming model. */
    fun engineReady(repo: ModelRepository, bundled: Boolean = this.bundled): Boolean =
        ready(repo, bundled) && repo.catalog.models.any { it.kind == ModelKind.ASR_STREAMING && repo.state(it.id).isReady }

    /**
     * 运行库文件设为只读：Android 14 起动态载入的代码必须不可写。
     * Make the runtime files read-only: Android 14+ requires dynamically loaded code to be non-writable.
     */
    fun makeReadOnly(dir: File) {
        dir.walkTopDown().filter { it.isFile && it.canWrite() }.forEach { it.setReadOnly() }
    }
}
