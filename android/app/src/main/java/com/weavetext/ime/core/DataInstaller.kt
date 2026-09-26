package com.weavetext.ime.core

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 词库来源：APK 内的分块压缩文件（`assets/dict` 下的 `.wvz`，不压缩存放），内核按偏移直接读取、按需解压，
 * 手机上不再复制一份——装机占用约等于 APK 大小，首次启动也不用等拷贝。
 *
 * Dictionary sources: block-compressed files stored uncompressed in the APK (the `.wvz` files under `assets/dict`). The
 * core reads them by offset and decodes blocks on demand, so nothing is copied onto the device — the
 * footprint is about the APK size and first start doesn't wait for a copy.
 *
 * 万一某个资源在 APK 里被压缩了（无法按偏移读取），退回到拷贝到私有目录。
 * If an asset was compressed in the APK (no direct offset access), it falls back to a private copy.
 */
object DataInstaller {
    private const val TAG = "WeaveData"
    private const val ASSET_DIR = "dict"

    fun userDir(ctx: Context): File = File(ctx.filesDir, "user")
    private fun fallbackDir(ctx: Context): File = File(ctx.noBackupFilesDir, "dict")

    /**
     * 生成内核的资源描述 `key=path@offset+len;…`。同步执行，调用方负责放到后台线程。
     * Build the engine's resource spec. Blocking; call off the main thread.
     */
    @Synchronized
    fun sourceSpec(ctx: Context): String {
        userDir(ctx).mkdirs()
        val apk = ctx.applicationInfo.sourceDir
        val names = ctx.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".wvz") }
        val usedFallback = mutableSetOf<String>()
        val spec = names.joinToString(";") { name ->
            val key = name.removeSuffix(".wvz")
            val loc = try {
                ctx.assets.openFd("$ASSET_DIR/$name").use { fd -> "$apk@${fd.startOffset}+${fd.length}" }
            } catch (e: IOException) {
                Log.w(TAG, "$name is compressed in the APK, copying", e)
                usedFallback += name
                copyOut(ctx, name).absolutePath
            }
            "$key=$loc"
        }
        cleanUp(ctx, usedFallback)
        return spec
    }

    /** 每个分块压缩文件的解压缓存预算（KB）：低内存设备减半。 Per-file cache budget in KB; halved on low-RAM devices. */
    fun cacheKb(ctx: Context): Int {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val low = am == null || am.isLowRamDevice || am.memoryClass < 192
        return if (low) 6 * 1024 else 12 * 1024
    }

    private fun copyOut(ctx: Context, name: String): File {
        val dir = fallbackDir(ctx).apply { mkdirs() }
        val out = File(dir, name)
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val stamp = File(dir, "$name.stamp")
        val want = pkg.lastUpdateTime.toString()
        if (out.isFile && stamp.isFile && stamp.readText() == want) return out
        val tmp = File(dir, "$name.tmp")
        ctx.assets.open("$ASSET_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (!tmp.renameTo(out)) Log.w(TAG, "rename failed: $name")
        stamp.writeText(want)
        return out
    }

    /** 删除旧版本解压出来、现在用不到的词库副本。 Remove extracted copies older versions left behind. */
    private fun cleanUp(ctx: Context, keep: Set<String>) {
        val dir = fallbackDir(ctx)
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.removeSuffix(".stamp") !in keep) f.delete()
        }
        if (keep.isEmpty()) dir.delete()
    }
}
