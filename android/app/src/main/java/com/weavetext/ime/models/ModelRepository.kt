package com.weavetext.ime.models

/**
 * 模型管理对界面暴露的接口（[ModelManager] 实现），截图测试可替换为假实现。
 * Model management as seen by the UI; implemented by [ModelManager], faked in screenshot tests.
 */
interface ModelRepository {
    val catalog: ModelCatalog
    fun state(id: String): ModelState
    /** 开始下载；计流量网络下 [allowMetered] 为 false 时直接失败。 Start a download. */
    fun download(id: String, allowMetered: Boolean = !wifiOnly)
    fun cancel(id: String)
    fun delete(id: String): Boolean
    /** 状态变化回调（主线程）。 State-change callbacks, on the main thread. */
    fun addListener(l: () -> Unit)
    fun removeListener(l: () -> Unit)

    /** "auto" 或某个下载源 id。 "auto" or a source id. */
    var mirrorPreference: String
    /** 自定义镜像模板，须含 `{url}`。 Custom mirror template containing `{url}`. */
    var customMirror: String?
    var wifiOnly: Boolean
    fun allSources(): List<Mirror>
    fun isMetered(): Boolean
    /** 已下载模型占用的字节数。 Bytes used by downloaded models. */
    fun usedBytes(): Long
}
