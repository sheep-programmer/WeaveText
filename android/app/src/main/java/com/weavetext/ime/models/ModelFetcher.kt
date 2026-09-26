package com.weavetext.ime.models

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 取回一个模型的全部文件，两条路线择优：
 * 1. HuggingFace 逐文件（经 hf-mirror 等镜像），无需解压；
 * 2. GitHub Releases 压缩包（经多个 GitHub 加速镜像），下载后解压。
 * 先对两条路线测速，快的先试，失败自动换另一条；无论走哪条，每个文件都按目录里的 SHA-256 校验。
 *
 * Fetch every file of a model via the faster of two routes — per-file from HuggingFace mirrors, or the
 * GitHub release archive via GitHub mirrors — falling back to the other on failure. Every file is
 * verified against the catalog's SHA-256 whichever route delivered it. Android-free for desktop tests.
 */
class ModelFetcher(
    private val githubMirrors: List<Mirror>,
    private val hfMirrors: List<Mirror>,
    private val workDir: File,
    /** 解压函数：(压缩包, 目标目录, 要保留的文件名) → 错误信息或 null。 Extractor returning an error or null. */
    private val extract: (File, File, List<String>) -> String?,
) {
    private enum class Route { HF, ARCHIVE }

    /**
     * 下载到 [dest]（调用方保证是空的临时目录）。 Download into [dest], an empty staging dir.
     * @param preferred 用户指定的镜像 id；属于哪条路线就先试哪条。 User-chosen mirror id.
     */
    fun fetch(
        spec: ModelSpec,
        dest: File,
        cancel: AtomicBoolean = AtomicBoolean(false),
        preferred: String? = null,
        onProgress: (Progress) -> Unit = {},
    ) {
        dest.mkdirs()
        val hf = Downloader(hfMirrors)
        val gh = Downloader(githubMirrors)
        val hfProbe = spec.hfRepo?.let { repo -> hf.probeAll(hfPath(repo, spec.files.first().name)) }.orEmpty()
        val ghProbe = gh.probeAll(spec.archiveUrl)
        val best = { p: List<Pair<Mirror, Long?>> -> p.firstOrNull()?.second ?: Long.MAX_VALUE }
        val routes = buildList {
            if (hfProbe.isNotEmpty()) add(Route.HF to best(hfProbe))
            add(Route.ARCHIVE to best(ghProbe))
        }.sortedWith(compareBy({ route ->
            when {
                preferred != null && route.first == Route.HF && hfMirrors.any { it.id == preferred } -> 0
                preferred != null && route.first == Route.ARCHIVE && githubMirrors.any { it.id == preferred } -> 0
                else -> 1
            }
        }, { it.second })).map { it.first }
        val errors = mutableListOf<String>()
        for (route in routes) {
            if (cancel.get()) throw IOException("cancelled")
            try {
                when (route) {
                    Route.HF -> perFile(spec, dest, hf, hfProbe.map { it.first }, cancel, preferred, onProgress)
                    Route.ARCHIVE -> archive(spec, dest, gh, ghProbe.map { it.first }, cancel, preferred, onProgress)
                }
                verify(spec, dest)
                return
            } catch (e: IOException) {
                if (cancel.get()) throw IOException("cancelled")
                errors += "${route.name}: ${e.message}"
            }
        }
        throw IOException(errors.joinToString("\n"))
    }

    private fun hfPath(repo: String, file: String) = "$repo/resolve/main/$file"

    private fun perFile(
        spec: ModelSpec, dest: File, dl: Downloader, order: List<Mirror>, cancel: AtomicBoolean,
        preferred: String?, onProgress: (Progress) -> Unit,
    ) {
        val repo = spec.hfRepo ?: throw IOException("no hf repo")
        val total = spec.files.sumOf { it.size }
        var before = 0L
        for (f in spec.files) {
            val base = before
            dl.download(hfPath(repo, f.name), f.sha256, File(dest, f.name), cancel, preferred, order, f.size) { p ->
                onProgress(p.copy(downloaded = base + p.downloaded, total = total))
            }
            before += f.size
        }
    }

    /**
     * 按目录顺序尝试每个压缩包来源（例如先试小的自建包，404 或失败再试官方大包）；第一个用预先测好的镜像顺序。
     * Try each archive source in catalog order (e.g. our small pack, then the big upstream one on 404 or
     * failure); the first reuses the pre-probed mirror order.
     */
    private fun archive(
        spec: ModelSpec, dest: File, dl: Downloader, order: List<Mirror>, cancel: AtomicBoolean,
        preferred: String?, onProgress: (Progress) -> Unit,
    ) {
        workDir.mkdirs()
        val errors = mutableListOf<String>()
        spec.archives.forEachIndexed { i, a ->
            if (cancel.get()) throw IOException("cancelled")
            val file = File(workDir, a.url.substringAfterLast('/'))
            try {
                val ranked = if (i == 0) order else dl.probeAll(a.url).map { it.first }
                dl.download(a.url, a.sha256, file, cancel, preferred, ranked, a.size, onProgress)
                val err = extract(file, dest, spec.fileNames())
                file.delete()
                if (err != null) throw IOException("解压失败：$err")
                // 在这里校验，内容不对时还能换下一个来源。 Verify here so a bad pack falls through to the next source.
                verify(spec, dest)
                return
            } catch (e: IOException) {
                if (cancel.get()) throw e
                errors += "${a.url.substringAfterLast('/')}: ${e.message}"
            }
        }
        throw IOException(errors.joinToString("\n"))
    }

    /** 逐个文件核对大小与 SHA-256。 Check each file's size and SHA-256. */
    private fun verify(spec: ModelSpec, dir: File) {
        for (f in spec.files) {
            val file = File(dir, f.name)
            if (!file.isFile) throw IOException("missing ${f.name}")
            if (f.size > 0 && file.length() != f.size) throw IOException("${f.name}: size ${file.length()} != ${f.size}")
            val got = Downloader.sha256Of(file)
            if (got != f.sha256) {
                file.delete()
                throw IOException("${f.name}: sha256 mismatch")
            }
        }
    }
}
