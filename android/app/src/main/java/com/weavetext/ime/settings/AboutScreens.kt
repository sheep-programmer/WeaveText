package com.weavetext.ime.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.models.DownloadPhase
import com.weavetext.ime.models.Progress
import com.weavetext.ime.voice.AppUpgrade

/** 问题反馈地址；仓库公开前为空，此时隐藏该入口。 Issue tracker URL; the row is hidden while empty. */
const val ISSUES_URL = ""

/** 关于（03 §9）。 About page. */
@Composable
fun AboutScreen() {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var update by remember { mutableStateOf(AppUpgrade.state) }
    DisposableEffect(Unit) {
        val listener: (AppUpgrade.State) -> Unit = { update = it }
        AppUpgrade.addListener(listener)
        update = AppUpgrade.state
        onDispose { AppUpgrade.removeListener(listener) }
    }
    SubPage("关于") {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo(72)
            Spacer(Modifier.height(16.dp))
            Text("织文输入法", style = MaterialTheme.typography.headlineSmall)
            Text(
                "WeaveText · v${deps.versionName} (${deps.versionCode})",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GroupCard {
            SettingRow("使用帮助", onClick = { nav.push(Route.Help) }) { Chevron() }
            RowDivider(false)
            SettingRow("隐私说明", onClick = { nav.push(Route.Privacy) }) { Chevron() }
            RowDivider(false)
            SettingRow("开源许可", onClick = { nav.push(Route.Licenses) }) { Chevron() }
            RowDivider(false)
            AppUpdateContent(update, onAction = {
                when (val s = update) {
                    is AppUpgrade.State.Available -> AppUpgrade.download(ctx, s)
                    is AppUpgrade.State.Ready -> AppUpgrade.install(ctx, s)
                    is AppUpgrade.State.Failed, is AppUpgrade.State.Cancelled -> AppUpgrade.retry(ctx)
                    else -> AppUpgrade.check(ctx)
                }
            }, onCancel = { AppUpgrade.cancel() })
            if (ISSUES_URL.isNotEmpty()) {
                RowDivider(false)
                SettingRow("反馈问题", onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL))) }) {
                    Icon(painterResource(R.drawable.ic_open_external), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Explicit actions and progress; opening the About page never starts a download. */
@Composable
internal fun AppUpdateContent(state: AppUpgrade.State, onAction: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用更新", style = MaterialTheme.typography.titleSmall)
        val progress = when (state) {
            is AppUpgrade.State.Connecting -> Progress(state.done, state.total, 0, state.mirror, DownloadPhase.CONNECTING)
            is AppUpgrade.State.Downloading -> Progress(state.done, state.total, state.speed, state.mirror)
            else -> null
        }
        if (progress != null) {
            if (state is AppUpgrade.State.Connecting && state.label != "正在连接下载源…") Text(state.label, style = MaterialTheme.typography.bodySmall)
            DownloadProgressContent(progress)
        } else {
            val label = when (state) {
                AppUpgrade.State.Idle -> "从 GitHub 检查最新版本（支持已配置的镜像）"
                AppUpgrade.State.Checking -> "正在检查 GitHub Release…"
                is AppUpgrade.State.UpToDate -> "已是最新版 v${state.current}"
                is AppUpgrade.State.Available -> "发现 ${state.tag} · " + if (state.size > 0) "下载 ${formatSize(state.size)}" else "下载大小待确认"
                AppUpgrade.State.Verifying -> "正在校验安装包与签名…"
                AppUpgrade.State.Cancelling -> "正在取消…"
                is AppUpgrade.State.Cancelled -> if (state.available != null) "已取消，可继续下载" else "已取消检查"
                is AppUpgrade.State.Ready -> "已下载 ${state.tag} · 校验通过"
                is AppUpgrade.State.Failed -> state.message
                else -> ""
            }
            if (state == AppUpgrade.State.Checking || state == AppUpgrade.State.Verifying || state == AppUpgrade.State.Cancelling) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (state is AppUpgrade.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state is AppUpgrade.State.Available && state.notes.isNotBlank()) Text(state.notes, style = MaterialTheme.typography.bodySmall)
        }
        val active = progress != null || state == AppUpgrade.State.Checking || state == AppUpgrade.State.Verifying
        if (active) TextButton(onClick = onCancel) { Text("取消") }
        else if (state != AppUpgrade.State.Cancelling) {
            FilledTonalButton(onClick = onAction) {
                Text(when (state) {
                    is AppUpgrade.State.Available -> "下载更新"
                    is AppUpgrade.State.Ready -> "安装更新"
                    is AppUpgrade.State.Failed -> "重试"
                    is AppUpgrade.State.Cancelled -> if (state.available != null) "继续下载" else "重新检查"
                    else -> "检查更新"
                })
            }
        }
    }
}

@Composable
private fun Paragraphs(items: List<Pair<String, String>>) {
    GroupCard(Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((title, body) in items) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 使用帮助：手势说明。 Gesture help. */
@Composable
fun HelpScreen() = SubPage("使用帮助") {
    Paragraphs(
        listOf(
            "上滑输入副字符" to "字母键上滑输入键上方的数字或符号；长按弹出更多候选，左右滑动选择后松手输入。",
            "空格左右滑动" to "按住空格左右拖动可移动光标，滑得越快移动越快。",
            "删除键左滑清空" to "按住删除键向左滑，出现「松手清空」后松手即可清空光标前的内容，3 秒内可点「撤销」。长按删除会加速连删。",
            "按住说话" to "长按空格或顶栏话筒直接说话，松手上屏，上滑取消。",
            "切换键盘" to "长按「中/英」键或点顶栏键盘图标选择全拼、九键、双拼、五笔或英文。",
            "候选展开" to "点候选栏右侧箭头展开更多候选；长按学习到的词可以删除它。",
            "悬浮键盘" to "在工具箱点「悬浮键盘」，键盘变成可拖动的小卡片，不遮挡整个 App。拖动顶部横条移动位置，双击横条或点右侧图标恢复。",
            "收起键盘" to "点顶栏最右侧箭头，或在空闲的工具栏上向下拖动。",
        ),
    )
}

/** 隐私说明。 Privacy notes. */
@Composable
fun PrivacyScreen() = SubPage("隐私说明") {
    Paragraphs(
        listOf(
            "本机处理" to "拼音、五笔、联想与用户词学习在本机完成，这些输入处理不需要上传文字。",
            "语音输入" to "本机离线模型在设备上识别。选用已安装并配置好的联网语音插件并开始录音时，音频会发送到所选提供方；联网地址由插件清单声明。",
            "翻译" to "只有主动点击翻译，选中或粘贴的原文才会发送到已配置的联网服务；网页翻译会把原文带到打开的官方网页。离线翻译插件在设备上处理原文。",
            "市场与下载" to "刷新官方市场、安装扩展、下载语音模型或检查更新时，会向官方仓库、模型来源或已配置的镜像请求目录与所需文件，不发送输入文字或录音。",
            "GitHub 插件仓库" to "公开仓库可直接读取，私有仓库使用你提供的访问令牌。令牌通过 Android Keystore 加密保存在本机，不交给插件，不参与互联同步；退出 GitHub 登录后清除本机授权。",
            "剪贴板" to "历史默认不记录；开启后保存在本机私有目录，未固定内容默认 24 小时后删除。来自密码框或标记为敏感的内容不会记录。可在「外观与手感」中开关记录。",
            "密码框" to "在密码框中输入时不学习用户词，也不记录剪贴板。",
            "云端热词" to "默认关闭。开启后每天从公开的织文热词库下载一次词表（带签名校验），只下载、不上传，你的输入不会因此离开手机。",
            "专业词库" to "按需从织文的 GitHub 发布页下载，下载时只请求词库文件本身。",
            "织文互联" to "默认关闭。启用互联及剪贴板同步后，剪贴板会发送到已配对且已连接的设备；文字、图片与文件端到端加密直传。跨网连接由你主动生成连接码，地址探测服务只获取公网映射，不接收传输内容。",
            "二维码扫码" to "仅在主动打开扫码页面时使用相机，二维码在本机识别。相机图像不会录制或上传，离开扫码页面后停止使用相机。",
        ),
    )
}

/** 开源许可（与 docs/THIRD_PARTY.md 对应）。 Open-source licenses, mirroring docs/THIRD_PARTY.md. */
@Composable
fun LicensesScreen() = SubPage("开源许可") {
    GroupTitle("词库数据")
    LicenseCard(
        listOf(
            Triple("万象拼音词库", "CC BY 4.0", "amzxyz/rime_wanxiang：拼音字词、词频、英文词频、表情联想"),
            Triple("OpenCC 简繁转换表", "Apache-2.0", "BYVoid/OpenCC（经 rime_wanxiang 整理）"),
            Triple("五笔 86 码表", "LGPL-3.0", "rime/rime-wubi：作为独立可替换的数据文件分发"),
            Triple("手写识别模板", "Arphic Public License", "skishore/makemeahanzi 的笔画数据（源自文鼎 PL 字体），作为独立数据文件分发"),
            Triple("专业词库（可选下载）", "CC BY 4.0 · MIT", "万象拼音的领域词表与 THUOCL 清华开放中文词库（thunlp/THUOCL），按需下载的独立数据文件"),
        ),
    )
    GroupTitle("内核（Rust）")
    LicenseCard(
        listOf(
            Triple("Lua 5.4（mlua / lua-src）", "MIT", "插件运行时"),
            Triple("rustls、ring、webpki-roots", "Apache-2.0 / ISC / CDLA-Permissive-2.0", "插件网络 TLS"),
            Triple("snow、spake2、mdns-sd、ed25519-dalek 等", "MIT OR Apache-2.0 / BSD-3-Clause", "织文互联的加密、配对与发现；云端热词签名校验"),
            Triple("quinn、rustls、rcgen、tokio", "MIT OR Apache-2.0 / MIT", "跨网 UDP 直传、临时证书与网络运行时"),
            Triple("memmap2、jni、serde_json、zip、tungstenite、ureq 等", "MIT OR Apache-2.0", "见仓库 docs/THIRD_PARTY.md"),
        ),
    )
    GroupTitle("Android")
    LicenseCard(listOf(
        Triple("AndroidX、Jetpack Compose、Material 3", "Apache-2.0", "界面与基础组件"),
        Triple("ZXing、zxing-android-embedded", "Apache-2.0", "本机二维码生成与相机扫码"),
    ))
    Text(
        "完整清单见源码仓库 docs/THIRD_PARTY.md。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun LicenseCard(items: List<Triple<String, String, String>>) {
    GroupCard {
        items.forEachIndexed { i, (name, license, note) ->
            if (i > 0) RowDivider(false)
            SettingRow(name, note, subtitleMaxLines = 2) {
                Text(license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
