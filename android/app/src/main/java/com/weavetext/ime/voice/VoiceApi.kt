package com.weavetext.ime.voice

import android.content.Context

/**
 * 语音输入对界面暴露的接口。界面只依赖这里；实现（Lua 插件宿主 + 录音）由 [VoiceHub] 提供。
 * Voice input API for the UI. The UI depends only on this; [VoiceHub] provides the implementation
 * (Lua plugin host + audio capture).
 */

/** 插件配置表单的一个字段（来自 manifest.yaml 的 configSchema）。 One configSchema field. */
data class ConfigField(
    val key: String,
    val label: String,
    /** "switch" / "select" / "text" / "password" / "number" */
    val type: String,
    val section: String? = null,
    val options: List<String> = emptyList(),
    val defaultValue: String? = null,
    val helpText: String? = null,
    val required: Boolean = false,
)

/** 已安装的语音插件。 An installed voice plugin. */
data class VoicePlugin(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    /** 插件包里的 icon.png；没有时为 null（界面用名称首字作图标）。 */
    val iconPng: ByteArray?,
    val configSchema: List<ConfigField>,
    /** 是否已配置好可用（plugin.isConfigured）。 */
    val configured: Boolean = true,
    /** manifest 声明可访问的域名。 Hosts the plugin may contact. */
    val networkHosts: List<String> = emptyList(),
    /** 插件要求不受限的网络访问（应向用户明确提示）。 Plugin asks for unrestricted network access. */
    val unrestrictedNetwork: Boolean = false,
) {
    override fun equals(other: Any?) = other is VoicePlugin && other.id == id && other.version == version
    override fun hashCode() = id.hashCode() * 31 + version.hashCode()
}

/** 识别回调，全部在主线程回调。 Recognition callbacks, all on the main thread. */
interface VoiceListener {
    /** 实时（未定）结果，整段替换显示。 Interim text; replaces the previous interim. */
    fun onPartial(text: String)
    /** 一段最终结果，应上屏。 A final segment to commit. */
    fun onFinal(text: String)
    /** 插件事后把已上屏的 [old] 修正为 [new]（如智能整理）。 Post-hoc correction of committed text. */
    fun onReplace(old: String, new: String) {}
    fun onError(message: String)
    /** 会话结束（无论成功与否）。 Session ended. */
    fun onEnd()
    /** 输入音量 0..1，用于波形动画。 Input level for the waveform. */
    fun onLevel(level: Float) {}
}

/** 语音插件管理。 Voice plugin management. */
interface VoiceEngines {
    fun list(): List<VoicePlugin>
    /** 当前选用的插件 id（单选）。 Currently selected plugin. */
    var activeId: String?
    fun active(): VoicePlugin? = list().firstOrNull { it.id == activeId } ?: list().firstOrNull()
    /** 读取 .xipk 的信息但不安装（导入前确认）。 Read a package without installing it. */
    fun inspect(xipkPath: String): Result<VoicePlugin> = Result.failure(UnsupportedOperationException())
    /** 从 .xipk 文件导入。 Import a .xipk package. */
    fun install(xipkPath: String): Result<VoicePlugin>
    fun uninstall(id: String): Result<Unit>
    fun getConfig(id: String, key: String): String?
    fun setConfig(id: String, key: String, value: String)
}

/** 一次录音识别。 One recording/recognition session. */
interface VoiceRecognizer {
    val isRunning: Boolean
    /** 开始录音识别；缺少麦克风权限时返回 false 并回调 onError。 */
    fun start(listener: VoiceListener): Boolean
    /** 停止录音，等待最终结果（随后回调 onFinal/onEnd）。 Stop and wait for the final result. */
    fun stop()
    /** 立即取消，不再回调结果。 Cancel immediately. */
    fun cancel()
    /** 打开语音面板时调用：预热本地模型等。 Called when the voice panel opens (warms local models). */
    fun warmUp() {}
}

/** 语音能力入口。 Entry point. */
object VoiceHub {
    @Volatile private var impl: Pair<VoiceEngines, VoiceRecognizer>? = null

    /** 在后台线程预热（扫描插件、解包内置插件）。 Warm up on a background thread. */
    fun preload(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ runCatching { get(app) } }, "weave-voice-init").start()
    }

    fun engines(ctx: Context): VoiceEngines = get(ctx).first
    fun recognizer(ctx: Context): VoiceRecognizer = get(ctx).second

    @Synchronized
    private fun get(ctx: Context): Pair<VoiceEngines, VoiceRecognizer> =
        impl ?: VoiceBackend.create(ctx.applicationContext).also { impl = it }
}
