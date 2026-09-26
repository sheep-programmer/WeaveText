package com.weavetext.ime.models

/**
 * 轻量版的「离线语音包」：识别运行库 + 「实时识别 · 小」，一次下载两样，合并显示进度。
 * 两样都装好后调用 [start] 时传入的 onReady（用来自动选中本地离线识别）。终稿与标点模型仍在「离线模型」里按需下载。
 *
 * The lite build's offline voice pack: the speech runtime plus the small streaming model, downloaded
 * together with one combined progress. Once both are installed the onReady given to [start] runs (used to
 * select the local engine). Final-pass and punctuation models stay optional downloads on the models page.
 */
class VoicePack(private val repo: ModelRepository) {
    sealed interface State {
        /** 尚未下载；[bytes] 为还需下载的大小。 Not downloaded; [bytes] still to download. */
        data class Idle(val bytes: Long) : State
        data class Downloading(val done: Long, val total: Long, val bytesPerSecond: Long, val mirror: String) : State
        /** 正在解压与校验。 Extracting and verifying. */
        data object Installing : State
        data object Ready : State
        data class Failed(val message: String, val bytes: Long) : State
    }

    /** 语音包的组成（按目录顺序）。 The pack's parts. */
    val parts: List<ModelSpec> = PART_IDS.mapNotNull { repo.catalog.find(it) }

    /** 目录里两样都有（运行库只在轻量版、且架构相符时才列出）。 Both parts listed for this build and ABI. */
    val supported: Boolean get() = parts.size == PART_IDS.size

    fun state(): State {
        val states = parts.map { repo.state(it.id) }
        if (states.all { it.isReady }) return State.Ready
        val remaining = parts.zip(states).filter { !it.second.isReady }.sumOf { it.first.archiveSize }
        val active = states.filter { it == ModelState.Waiting || it == ModelState.Extracting || it is ModelState.Downloading }
        if (active.isNotEmpty()) {
            if (active.all { it == ModelState.Extracting } && states.none { it is ModelState.Failed || it == ModelState.NotInstalled }) {
                return State.Installing
            }
            var done = 0L
            var total = 0L
            var speed = 0L
            var mirror = ""
            for ((m, s) in parts.zip(states)) {
                when {
                    s.isReady || s == ModelState.Extracting -> { done += m.archiveSize; total += m.archiveSize }
                    s is ModelState.Downloading -> {
                        val p = s.progress
                        // 实际总量以下载器报告为准（换了来源或走逐文件时与目录里的压缩包大小不同）。
                        // Prefer the downloader's total: another source or per-file route differs from the archive size.
                        val t = if (p.total > 0) p.total else m.archiveSize
                        done += p.downloaded.coerceAtMost(t)
                        total += t
                        speed += p.bytesPerSecond
                        if (mirror.isEmpty() || p.bytesPerSecond > 0) mirror = p.mirror
                    }
                    else -> total += m.archiveSize
                }
            }
            return State.Downloading(done, total, speed, mirror)
        }
        states.firstOrNull { it is ModelState.Failed }?.let { return State.Failed(friendly((it as ModelState.Failed).message), remaining) }
        return State.Idle(remaining)
    }

    /**
     * 下载还没装好的部分；两样都就绪后在主线程调用一次 [onReady]（界面关掉也会执行）。
     * Download the missing parts; [onReady] runs once on the main thread when both are ready, even if the
     * page has been closed.
     */
    fun start(allowMetered: Boolean, onReady: () -> Unit = {}) {
        if (!supported) return
        if (state() == State.Ready) { onReady(); return }
        val watcher = object : () -> Unit {
            override fun invoke() {
                when (state()) {
                    State.Ready -> { repo.removeListener(this); onReady() }
                    is State.Idle, is State.Failed -> repo.removeListener(this)
                    else -> {}
                }
            }
        }
        repo.addListener(watcher)
        for (m in parts) {
            val s = repo.state(m.id)
            if (s == ModelState.NotInstalled || s is ModelState.Failed) repo.download(m.id, allowMetered)
        }
    }

    fun cancel() {
        for (m in parts) repo.cancel(m.id)
    }

    companion object {
        /** 运行库在前：它小，先装好。 Runtime first: it is small. */
        val PART_IDS = listOf(AsrRuntime.ID, "asr-stream-small")

        /** 下载失败的原因换成用户能懂的话；已是中文的原样保留。 Readable failure text; Chinese messages pass through. */
        fun friendly(raw: String): String = when {
            raw.isNotEmpty() && raw.first().code > 0x2E80 -> raw
            raw.contains("sha256", true) -> "下载的文件校验失败，请重试"
            else -> "下载失败，请检查网络后重试（可在「离线模型」里换下载源）"
        }
    }
}
