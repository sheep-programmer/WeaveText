package com.weavetext.ime.models

import android.content.Context
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 模型当前状态。 A model's current state. */
sealed interface ModelState {
    /** 随 APK 内置，随时可用。 Bundled in the APK. */
    data object Builtin : ModelState
    data object NotInstalled : ModelState
    data object Waiting : ModelState
    data class Downloading(val progress: Progress) : ModelState
    data object Extracting : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/** 可以直接使用（内置或已安装）。 Usable right now (built in or installed). */
val ModelState.isReady: Boolean get() = this == ModelState.Builtin || this == ModelState.Installed

/** 模型文件位置：内置模型在 APK assets 中，下载的在私有目录。 Where a model's files live. */
data class ModelLocation(val assets: AssetManager?, val dir: String) {
    fun path(file: String) = "$dir/$file"
}

/**
 * 端侧模型管理：内置 + 可下载，多镜像加速下载，断点续传，SHA-256 校验，原子安装。
 * On-device model management: built-in and downloadable models, multi-mirror downloads with resume,
 * SHA-256 verification and atomic installs.
 */
class ModelManager private constructor(private val ctx: Context) : ModelRepository {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "weave-models").apply { isDaemon = true } }
    private val prefs = ctx.getSharedPreferences("weave_models", Context.MODE_PRIVATE)
    private val root = File(ctx.filesDir, "models").apply { mkdirs() }
    private val downloads = File(root, ".downloads").apply { mkdirs() }
    private val states = ConcurrentHashMap<String, ModelState>()
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val listeners = mutableListOf<() -> Unit>()
    /** 删除/替换模型前调用，让正在使用它的引擎先释放。 Called before a model is deleted or replaced. */
    private val releaseHooks = mutableListOf<(String) -> Unit>()

    /** 按本次构建调整过的目录（离线语音版不列运行库，轻量版没有内置模型）。 Catalog adjusted for this build. */
    override val catalog: ModelCatalog = ModelCatalog.parse(ctx.assets.open("models/catalog.json").bufferedReader().use { it.readText() })
        .forBuild(AsrRuntime.bundled, android.os.Build.SUPPORTED_ABIS.firstOrNull())

    init {
        for (m in catalog.models) states[m.id] = computeState(m)
    }

    private fun computeState(m: ModelSpec): ModelState = when {
        m.builtin && builtinPresent(m) -> ModelState.Builtin
        installedDir(m).let { d -> d.isDirectory && m.files.all { File(d, it.name).isFile } } -> ModelState.Installed
        else -> ModelState.NotInstalled
    }

    private fun builtinPresent(m: ModelSpec): Boolean =
        runCatching { ctx.assets.list("models/${m.id}")?.toSet().orEmpty().containsAll(m.fileNames()) }.getOrDefault(false)

    private fun installedDir(m: ModelSpec) = File(root, m.id)

    // ------------------------------------------------------------ queries

    override fun state(id: String): ModelState = states[id] ?: ModelState.NotInstalled

    fun isAvailable(id: String) = state(id).isReady

    /** 可用模型的位置；不可用返回 null。 Location of an available model. */
    fun location(id: String): ModelLocation? {
        val m = catalog.find(id) ?: return null
        return when (state(id)) {
            ModelState.Builtin -> ModelLocation(ctx.assets, "models/${m.id}")
            ModelState.Installed -> ModelLocation(null, installedDir(m).absolutePath)
            else -> null
        }
    }

    fun available(kind: ModelKind): List<ModelSpec> = catalog.models.filter { it.kind == kind && isAvailable(it.id) }

    /**
     * 已下载运行库所在目录（含两个 .so）；运行库随包或未下载时为 null。
     * Directory holding the downloaded runtime libraries; null when bundled or not downloaded.
     */
    fun runtimeDir(): File? {
        val m = catalog.find(AsrRuntime.ID) ?: return null
        if (state(m.id) != ModelState.Installed) return null
        val sub = m.files.first().name.substringBeforeLast('/', "")
        return File(installedDir(m), sub)
    }

    fun addReleaseHook(h: (String) -> Unit) = synchronized(releaseHooks) { releaseHooks += h }

    private fun releaseUsers(id: String) = synchronized(releaseHooks) { releaseHooks.toList() }.forEach { runCatching { it(id) } }


    override fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    override fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    private fun set(id: String, s: ModelState) {
        states[id] = s
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }

    // ------------------------------------------------------------ mirrors

    /** 下载源偏好："auto"（测速选最快）、某个镜像 id，或自定义模板。 Mirror preference. */
    override var mirrorPreference: String
        get() = prefs.getString("mirror", "auto") ?: "auto"
        set(v) { prefs.edit().putString("mirror", v).apply() }

    /** 自定义镜像模板，形如 `https://example.com/{url}`。 Custom mirror template. */
    override var customMirror: String?
        get() = prefs.getString("custom_mirror", null)?.takeIf { it.contains("{url}") }
        set(v) { prefs.edit().putString("custom_mirror", v?.trim()).apply() }

    /** 仅在不计流量的网络（Wi-Fi）下下载。 Only download on unmetered networks. */
    override var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) { prefs.edit().putBoolean("wifi_only", v).apply() }

    /** GitHub 路线的镜像（含自定义）。 GitHub-route mirrors, including a custom one. */
    fun mirrors(): List<Mirror> {
        val custom = customMirror?.let { Mirror("custom", "自定义", it) }
        return listOfNotNull(custom) + catalog.mirrors
    }

    /** 可供用户选择的全部下载源（GitHub 镜像 + HuggingFace 镜像）。 All selectable sources. */
    override fun allSources(): List<Mirror> = mirrors() + catalog.hfMirrors

    override fun isMetered(): Boolean =
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    // ------------------------------------------------------------ actions

    /**
     * 开始下载并安装；[allowMetered] 为 false 且当前是计流量网络时直接失败（界面应先征得同意）。
     * Start downloading; fails right away on a metered network unless [allowMetered].
     */
    override fun download(id: String, allowMetered: Boolean) {
        val m = catalog.find(id) ?: return
        val s = state(id)
        if (s == ModelState.Builtin || s == ModelState.Installed || s is ModelState.Downloading || s == ModelState.Waiting) return
        if (!allowMetered && isMetered()) {
            set(id, ModelState.Failed("当前为移动网络，已按设置暂停下载"))
            return
        }
        // 需要：下载（压缩包或逐文件）+ 安装后的空间，留 10% 余量。 Download + installed size, 10% headroom.
        // 按最大的压缩包来源估算（首选来源失败时会退到更大的官方包）。 Budget for the largest archive source.
        val need = (maxOf(m.archives.maxOf { it.size }, m.installedSize) + m.installedSize) * 11 / 10
        if (root.usableSpace < need) {
            set(id, ModelState.Failed("存储空间不足，需要约 ${need / 1_000_000} MB"))
            return
        }
        val cancel = AtomicBoolean(false)
        cancels[id] = cancel
        set(id, ModelState.Waiting)
        io.execute {
            // 失败时保留暂存目录与 .part，重试可以续传；取消时才清掉。
            // Keep staging and partial files on failure so a retry resumes; clear them only on cancel.
            val staging = File(root, ".${m.id}.staging")
            try {
                val fetcher = ModelFetcher(mirrors(), catalog.hfMirrors, downloads) { archive, dest, keep ->
                    set(id, ModelState.Extracting)
                    NativeArchive.nativeExtractTarBz2(archive.absolutePath, dest.absolutePath, keep.joinToString("\n"))
                }
                val pref = mirrorPreference.takeIf { it != "auto" }
                fetcher.fetch(m, staging, cancel, pref) { p -> set(id, ModelState.Downloading(p)) }
                install(m, staging)
                set(id, ModelState.Installed)
            } catch (t: Throwable) {
                Log.w(TAG, "download $id failed", t)
                if (cancel.get()) {
                    staging.deleteRecursively()
                    val names = m.archives.map { it.url.substringAfterLast('/') }
                    downloads.listFiles()?.filter { f -> names.any { f.name.startsWith(it) } }?.forEach { it.delete() }
                }
                set(id, if (cancel.get()) ModelState.NotInstalled else ModelState.Failed(t.message?.lineSequence()?.firstOrNull() ?: "下载失败"))
            } finally {
                cancels.remove(id)
            }
        }
    }

    override fun cancel(id: String) {
        cancels[id]?.set(true)
    }

    /** 删除已下载的模型（内置模型不能删除）。 Delete a downloaded model. */
    override fun delete(id: String): Boolean {
        val m = catalog.find(id) ?: return false
        if (m.builtin || state(id) != ModelState.Installed) return false
        releaseUsers(id)
        installedDir(m).deleteRecursively()
        set(id, ModelState.NotInstalled)
        return true
    }

    /** 已下载模型占用的空间（字节）。 Bytes used by downloaded models. */
    override fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * 校验通过的暂存目录换成正式目录：旧目录先改名备份，新目录就位后再删备份，失败则回滚。
     * Swap a verified staging dir into place: back up the old dir, move the new one in, then drop the
     * backup; roll back on failure.
     */
    private fun install(m: ModelSpec, staging: File) {
        if (m.kind == ModelKind.ASR_RUNTIME) AsrRuntime.makeReadOnly(staging)
        File(staging, "installed.json").writeText(JSONObject().put("id", m.id).put("archive", m.archiveSha256).toString())
        val dest = installedDir(m)
        val backup = File(root, ".${m.id}.old")
        backup.deleteRecursively()
        if (dest.exists()) {
            releaseUsers(m.id)
            if (!dest.renameTo(backup)) throw IllegalStateException("安装失败：无法替换旧版本")
        }
        if (!staging.renameTo(dest)) {
            backup.renameTo(dest)
            throw IllegalStateException("安装失败")
        }
        backup.deleteRecursively()
    }

    companion object {
        private const val TAG = "WeaveModels"
        @Volatile private var instance: ModelManager? = null

        fun get(ctx: Context): ModelManager =
            instance ?: synchronized(this) { instance ?: ModelManager(ctx.applicationContext).also { instance = it } }
    }
}
