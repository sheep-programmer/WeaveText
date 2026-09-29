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
    /**
     * 引擎已开始收音。[selfEnd] 为 true 时引擎自己判断话说完了，界面不必按音量自动结束。
     * The engine is listening. With [selfEnd] the engine detects the end of speech itself, so the UI should not
     * auto-stop on silence.
     */
    fun onReady(selfEnd: Boolean) {}
    /**
     * 给用户的一行提示，不算错误（如系统识别用不了、已自动改用本地识别）。会话继续进行。
     * A one-line note for the user, not an error (e.g. the system engine failed and local recognition took
     * over). The session goes on.
     */
    fun onNotice(message: String) {}
}

/**
 * 多引擎会话的回调（06 §6）。多于一个引擎时，[VoiceRecognizer.start] 先回调 [onEngines]，
 * 之后每个引擎的结果走 onEngine*，不再回调 [onPartial] / [onFinal]；音量、会话级错误与
 * [onEnd]（全部引擎结束）照常回调。
 * Callbacks of a multi-engine session. With more than one engine, [onEngines] comes first and each
 * engine reports through onEngine* instead of onPartial/onFinal; level, session errors and [onEnd]
 * (all engines finished) work as usual.
 */
interface MultiVoiceListener : VoiceListener {
    /** 本次会话使用的引擎，主引擎在前。 Engines of this session, primary first. */
    fun onEngines(engines: List<VoicePlugin>) {}
    fun onEnginePartial(id: String, text: String) {}
    fun onEngineFinal(id: String, text: String) {}
    fun onEngineReplace(id: String, old: String, new: String) {}
    fun onEngineError(id: String, message: String) {}
    fun onEngineEnd(id: String) {}
}

/** 语音插件管理。 Voice plugin management. */
interface VoiceEngines {
    fun list(): List<VoicePlugin>
    /** 主引擎 id：实时显示它的中间结果，结果列表默认选中它。 Primary engine. */
    var activeId: String?
    fun active(): VoicePlugin? = list().firstOrNull { it.id == activeId } ?: list().firstOrNull()

    /** 与主引擎「同时使用」的其它引擎 id（06 §6）。 Engines used together with the primary one. */
    var extraIds: Set<String>
        get() = emptySet()
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /**
     * 能否与其它引擎共用一次录音。系统识别服务自己占用麦克风，只能单独使用。
     * Whether the engine can share one recording; the platform recognizer owns the mic.
     */
    fun canCombine(id: String): Boolean = id != SYSTEM_ENGINE_ID

    /** 本次录音要用的引擎，主引擎在前。 Engines for the next recording, primary first. */
    fun selection(): List<VoicePlugin> {
        val primary = active() ?: return emptyList()
        if (!canCombine(primary.id)) return listOf(primary)
        val extra = extraIds
        return listOf(primary) + list().filter { it.id != primary.id && it.id in extra && canCombine(it.id) }
    }
    /**
     * 停用系统语音识别：它是内置引擎不能卸载，停用后不再出现在列表里（可随时恢复）。
     * Disable the platform recognizer: built in, so it can't be uninstalled; disabled it leaves the list.
     */
    var systemDisabled: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /** 手机上有系统语音识别服务（不论是否停用）。 A platform recognition service exists, disabled or not. */
    fun systemPresent(): Boolean = false

    /**
     * 重新检查系统语音服务：用户可能刚装好或启用了它。变了才重建列表，开销很小。
     * Re-check the platform service, which may have just been installed or enabled; rebuilds the list only on a
     * change, so it is cheap.
     */
    fun recheck() {}

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
    /** 是否有可用引擎；没有时界面给出安装引导而不是报错。 Whether any engine is usable; else the UI shows guidance. */
    fun hasEngine(): Boolean = true
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
