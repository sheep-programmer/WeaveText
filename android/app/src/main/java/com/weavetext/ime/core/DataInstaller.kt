package com.weavetext.ime.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把 APK 里的词库拷到私有目录（只在安装/升级后做一次），供内核 mmap。
 * Copies bundled dictionaries to private storage once per install/upgrade so the core can mmap them.
 *
 * 写入先落到临时文件再原子改名，拷贝中途被杀不会留下半个词库。
 * Writes go to a temp file then atomically renamed, so a kill mid-copy never leaves a torn file.
 */
object DataInstaller {
    private const val TAG = "WeaveData"
    private const val ASSET_DIR = "dict"

    fun dataDir(ctx: Context): File = File(ctx.noBackupFilesDir, "dict")
    fun userDir(ctx: Context): File = File(ctx.filesDir, "user")

    /** 同步执行，调用方负责放到后台线程。 Blocking; call off the main thread. */
    @Synchronized
    fun ensureInstalled(ctx: Context): File {
        val dir = dataDir(ctx).apply { mkdirs() }
        userDir(ctx).mkdirs()
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val stamp = "${pkg.longVersionCodeCompat()}:${pkg.lastUpdateTime}"
        val stampFile = File(dir, ".stamp")
        if (stampFile.exists() && stampFile.readText() == stamp) return dir
        val names = ctx.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".wvl") }
        val started = System.nanoTime()
        for (name in names) {
            val tmp = File(dir, "$name.tmp")
            ctx.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
            if (!tmp.renameTo(File(dir, name))) Log.w(TAG, "rename failed: $name")
        }
        stampFile.writeText(stamp)
        Log.i(TAG, "installed ${names.size} dictionaries in ${(System.nanoTime() - started) / 1_000_000}ms")
        return dir
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}
