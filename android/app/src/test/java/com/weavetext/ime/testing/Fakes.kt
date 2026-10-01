package com.weavetext.ime.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.weavetext.ime.core.UserDictionary
import com.weavetext.ime.core.UserWord
import com.weavetext.ime.models.Mirror
import com.weavetext.ime.models.ModelCatalog
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.voice.ConfigField
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.VoiceListener
import com.weavetext.ime.voice.VoicePlugin
import com.weavetext.ime.voice.VoiceRecognizer
import java.io.ByteArrayOutputStream
import java.io.File

/** 测试用的通用示例插件（不指向任何真实服务）。 Generic sample plugins for tests. */
class FakeEngines(var plugins: List<VoicePlugin> = SAMPLE) : VoiceEngines {
    override var language = com.weavetext.ime.voice.VoiceLanguage.MIXED
    private val config = HashMap<String, String>()
    override fun list() = plugins
    override var activeId: String? = plugins.firstOrNull()?.id
    override var extraIds: Set<String> = emptySet()
    override fun install(xipkPath: String) = Result.failure<VoicePlugin>(IllegalArgumentException("不是有效的 .xipk 包"))
    override fun uninstall(id: String): Result<Unit> { plugins = plugins.filter { it.id != id }; return Result.success(Unit) }
    override fun getConfig(id: String, key: String) = config["$id/$key"]
    override fun setConfig(id: String, key: String, value: String) { config["$id/$key"] = value }

    companion object {
        /** 纯色圆角 PNG 图标，验证 iconPng 渲染。 A tiny PNG icon to exercise iconPng rendering. */
        fun pngIcon(color: Int): ByteArray {
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(color)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f }
            c.drawCircle(48f, 48f, 24f, p)
            return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }

        val SAMPLE = listOf(
            VoicePlugin("weave.local", "离线语音", "下载模型后在手机上识别。", "", null, emptyList()),
            VoicePlugin(
                "org.example.asr.cloud", "示例云端识别", "示例插件：WebSocket 流式识别，支持自动标点。", "1.0.4", pngIcon(Color.rgb(0x1B, 0x1E, 0x23)),
                listOf(
                    ConfigField("auto_punct", "自动标点", "switch", "识别", defaultValue = "true", helpText = "识别结果自动添加标点"),
                    ConfigField("language", "识别语言", "select", options = listOf("普通话", "粤语", "English"), defaultValue = "普通话", helpText = "普通话（默认）"),
                    ConfigField("filler", "过滤语气词", "select", options = listOf("开启", "关闭"), defaultValue = "开启"),
                    ConfigField("api_key", "API Key", "text", "账号", required = true, helpText = "在服务商控制台创建"),
                ),
                networkHosts = listOf("asr.example.org", "auth.example.org"),
            ),
            VoicePlugin(
                "org.example.asr.b", "示例插件 B", "实时流式识别，支持中英混说与自动标点。", "1.2.0", null,
                listOf(ConfigField("mixed", "中英混说", "switch", defaultValue = "true")),
            ),
            VoicePlugin("org.example.asr.c", "示例插件 C", "示例插件：低延迟流式识别。", "1.0.1", null, emptyList(), configured = false, unrestrictedNetwork = true),
        )
    }
}

/**
 * 可由测试驱动的识别器：记下监听者，按选中的引擎回调 onEngines。
 * A recognizer driven by the test: keeps the listener and reports the selected engines.
 */
class ScriptedRecognizer(private val engines: VoiceEngines) : VoiceRecognizer {
    var listener: VoiceListener? = null
    var stops = 0
    var cancels = 0
    override var isRunning = false
    override fun start(listener: VoiceListener): Boolean {
        this.listener = listener
        isRunning = true
        val sel = engines.selection()
        if (sel.size > 1) (listener as? com.weavetext.ime.voice.MultiVoiceListener)?.onEngines(sel)
        return true
    }
    override fun stop() { stops++ }
    override fun cancel() { cancels++; isRunning = false; listener?.onEnd() }
    override fun hasEngine() = engines.selection().isNotEmpty()
    val multi get() = listener as com.weavetext.ime.voice.MultiVoiceListener
    /** 模拟全部引擎结束。 All engines ended. */
    fun endAll() { isRunning = false; listener?.onEnd() }
}

/** 不做任何事的识别器。 A recognizer that never produces results. */
class FakeRecognizer : VoiceRecognizer {
    override var isRunning = false
    var warmUps = 0
    override fun warmUp() { warmUps++ }
    override fun start(listener: VoiceListener): Boolean { isRunning = true; return true }
    override fun stop() { isRunning = false }
    override fun cancel() { isRunning = false }
}

/** 内存词库。 In-memory user dictionary. */
class FakeUserDictionary(words: List<UserWord> = SAMPLE) : UserDictionary {
    val words = words.toMutableList()
    override suspend fun count() = words.size
    override suspend fun list(query: String, offset: Int, limit: Int) =
        words.filter { query.isEmpty() || query in it.text || it.pinyin.replace(" ", "").startsWith(query) }.drop(offset).take(limit)
    override suspend fun delete(word: UserWord): Boolean = words.remove(word)
    override suspend fun exportText() = words.joinToString("\n") { "${it.text}\t${it.pinyin}\t${it.count}" }
    override suspend fun importText(text: String): UserDictionary.ImportResult {
        val lines = text.lines().filter { it.isNotBlank() }
        val ok = lines.mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { UserWord(it[0], it[1], it.getOrNull(2)?.toIntOrNull() ?: 1) } }
        words += ok
        return UserDictionary.ImportResult(ok.size, lines.size - ok.size)
    }
    override suspend fun add(text: String, pinyin: String) = importText("$text\t$pinyin\t1").imported > 0
    override suspend fun clear(): Boolean { words.clear(); return true }

    companion object {
        val SAMPLE = listOf(
            UserWord("织文", "zhi wen", 32), UserWord("输入法", "shu ru fa", 18), UserWord("周末爬山", "zhou mo pa shan", 6),
            UserWord("地铁站见", "di tie zhan jian", 4), UserWord("设计稿", "she ji gao", 3), UserWord("暗色主题", "an se zhu ti", 2),
        )
    }
}

/**
 * 内存中的模型仓库：使用 APK 随附的真实目录，状态由测试指定。
 * In-memory model repository over the real bundled catalog; states are set by the test.
 */
class FakeModels(
    initial: Map<String, ModelState> = emptyMap(),
    var metered: Boolean = false,
    override val catalog: ModelCatalog = CATALOG,
) : ModelRepository {
    val states = HashMap(initial)
    val downloads = mutableListOf<Pair<String, Boolean>>()
    private val listeners = mutableListOf<() -> Unit>()

    override fun state(id: String) = states[id] ?: if (catalog.find(id)?.builtin == true) ModelState.Builtin else ModelState.NotInstalled
    private fun set(id: String, s: ModelState) { states[id] = s; listeners.toList().forEach { it() } }
    /** 模拟下载进展（通知监听者）。 Simulate progress, notifying listeners. */
    fun emit(id: String, s: ModelState) = set(id, s)
    val cancels = mutableListOf<String>()
    override fun download(id: String, allowMetered: Boolean) { downloads += id to allowMetered; set(id, ModelState.Waiting) }
    override fun cancel(id: String) { cancels += id; set(id, ModelState.NotInstalled) }
    override fun delete(id: String): Boolean { set(id, ModelState.NotInstalled); return true }
    override fun addListener(l: () -> Unit) { listeners += l }
    override fun removeListener(l: () -> Unit) { listeners -= l }
    override var mirrorPreference = "auto"
    override var customMirror: String? = null
    override var wifiOnly = true
    override fun allSources(): List<Mirror> = catalog.mirrors + catalog.hfMirrors
    override fun isMetered() = metered
    override fun usedBytes() = catalog.models.filter { state(it.id) == ModelState.Installed }.sumOf { it.installedSize }

    companion object {
        /** 单元测试的工作目录是 app/。 Unit tests run with app/ as the working directory. */
        private val RAW: ModelCatalog by lazy { ModelCatalog.parse(File("src/main/assets/models/catalog.json").readText()) }
        /** 离线语音版看到的目录（运行库随包，不列出）。 The offline-voice build's catalog. */
        val CATALOG: ModelCatalog by lazy { RAW.forBuild(bundledRuntime = true) }
        /** 轻量版（arm64）看到的目录：有运行库，没有内置模型。 The lite build's catalog on arm64. */
        val LITE: ModelCatalog by lazy { RAW.forBuild(bundledRuntime = false, abi = "arm64-v8a") }
        /** 轻量版在其它架构上：没有运行库。 Lite on another ABI: no runtime. */
        val LITE_X86: ModelCatalog by lazy { RAW.forBuild(bundledRuntime = false, abi = "x86_64") }
    }
}
