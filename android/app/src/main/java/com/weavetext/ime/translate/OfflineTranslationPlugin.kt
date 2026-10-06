package com.weavetext.ime.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.weavetext.translation.contract.TranslationPluginContract as Contract
import java.io.File
import java.util.UUID

/** Installation state is owned by Android; this contains neither an offline runtime nor models. */
object OfflineTranslationPlugin {
    enum class State { MISSING, READY, INCOMPATIBLE, UNTRUSTED }
    data class PluginStatus(val state: State, val versionName: String = "", val message: String = "")

    fun status(ctx: Context): PluginStatus {
        val pm = ctx.packageManager
        val info = try { pm.getPackageInfo(Contract.PLUGIN_PACKAGE, FLAGS) }
        catch (_: PackageManager.NameNotFoundException) { return PluginStatus(State.MISSING, message = "尚未安装离线翻译插件") }
        if (pm.checkSignatures(ctx.packageName, Contract.PLUGIN_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            return PluginStatus(State.UNTRUSTED, info.versionName.orEmpty(), "插件签名与输入法不匹配，请安装配套插件")
        }
        return inspectServices(info)
    }

    private fun inspectServices(info: PackageInfo): PluginStatus {
        val service = info.services?.firstOrNull { it.name == Contract.PLUGIN_SERVICE }
        val version = info.versionName.orEmpty()
        return if (info.applicationInfo?.enabled != true || service == null || !service.enabled || !service.exported ||
            service.permission != Contract.PERMISSION || service.metaData?.getInt("weave.translation.protocol") != Contract.VERSION) {
            PluginStatus(State.INCOMPATIBLE, version, "插件版本不兼容或已被停用")
        } else PluginStatus(State.READY, version, "已安装；语言包在插件中独立管理")
    }

    fun openManager(ctx: Context): Boolean {
        if (status(ctx).state != State.READY) return false
        return runCatching {
            ctx.startActivity(Intent().setComponent(ComponentName(Contract.PLUGIN_PACKAGE, Contract.PLUGIN_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    fun requestUninstall(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${Contract.PLUGIN_PACKAGE}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** Run on an IO worker. The installer, rather than this app, confirms the package installation. */
    fun installFromUri(ctx: Context, uri: Uri): Result<Unit> = runCatching {
        val root = File(ctx.cacheDir, "translation-plugins").apply { mkdirs() }
        // These are our previously selected installation files, not SDK model data.
        root.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
        val file = File(root, "${UUID.randomUUID()}.apk")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("无法读取插件文件")
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_APK_BYTES) { "插件安装包超过 96 MB" }
                    output.write(buffer, 0, n)
                }
            } }
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(file.path, FLAGS) ?: error("文件不是有效的 Android 插件安装包")
            require(info.packageName == Contract.PLUGIN_PACKAGE) { "请选择 Google 离线翻译插件安装包" }
            val ours = pm.getPackageInfo(ctx.packageName, signatureFlags)
            require(sameSigners(ours, info)) { "插件签名与输入法不匹配" }
            require(inspectServices(info).state == State.READY) { "离线翻译插件清单不兼容" }
            val shared = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(shared, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (error: Exception) { file.delete(); throw error }
    }

    internal fun sameSigners(left: PackageInfo, right: PackageInfo): Boolean {
        val a = signers(left)
        val b = signers(right)
        return a.isNotEmpty() && a == b
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<List<Byte>> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { it.toByteArray().toList() }.toSet()
    }

    @Suppress("DEPRECATION")
    private val signatureFlags: Int
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private const val MAX_APK_BYTES = 96L * 1024 * 1024
    private val FLAGS: Int
        get() = PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or signatureFlags
}
