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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    val updateText = when (val s = update) {
        AppUpgrade.State.Idle -> "从 GitHub 检查最新版本（支持镜像）"
        AppUpgrade.State.Checking -> "正在检查 GitHub Release…"
        is AppUpgrade.State.UpToDate -> "已是最新版 v${s.current}"
        is AppUpgrade.State.Available -> "发现 ${s.tag} · 点击下载并安装"
        is AppUpgrade.State.Downloading -> if (s.total > 0) "正在下载 ${(s.done * 100 / s.total).coerceIn(0, 100)}% · ${s.mirror}" else "正在连接镜像…"
        AppUpgrade.State.Verifying -> "正在校验安装包…"
        is AppUpgrade.State.Ready -> "已下载 ${s.tag} · 点击安装"
        is AppUpgrade.State.Failed -> s.message
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
            SettingRow("在线检查更新", updateText, icon = R.drawable.ic_update, subtitleMaxLines = 2, onClick = {
                when (val s = AppUpgrade.state) {
                    is AppUpgrade.State.Available -> AppUpgrade.download(ctx, s)
                    is AppUpgrade.State.Ready -> AppUpgrade.install(ctx, s)
                    is AppUpgrade.State.Downloading, AppUpgrade.State.Checking, AppUpgrade.State.Verifying -> AppUpgrade.cancel()
                    else -> AppUpgrade.check(ctx)
                }
            }) { Chevron() }
            if (ISSUES_URL.isNotEmpty()) {
                RowDivider(false)
                SettingRow("反馈问题", onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL))) }) {
                    Icon(painterResource(R.drawable.ic_open_external), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            "本机处理" to "拼音、五笔、联想与用户词学习全部在本机完成，织文不收集、不上传你的输入内容。",
            "语音输入" to "默认使用本机离线模型。选用联网插件时，音频会发送到该插件对应的服务，插件只能访问其清单允许的网络地址。",
            "GitHub 插件仓库" to "公开仓库可直接读取，私有仓库使用你提供的访问令牌。令牌通过 Android Keystore 加密保存在本机，不交给插件，不参与互联同步；退出 GitHub 登录后清除本机授权。",
            "剪贴板" to "剪贴板历史只保存在本机私有目录；来自密码框或标记为敏感的内容不会记录，未固定的记录 24 小时后自动删除。可在「外观与手感」中关闭记录。",
            "密码框" to "在密码框中输入时不学习用户词，也不记录剪贴板。",
            "云端热词" to "默认关闭。开启后每天从公开的织文热词库下载一次词表（带签名校验），只下载、不上传，你的输入不会因此离开手机。",
            "专业词库" to "按需从织文的 GitHub 发布页下载，下载时只请求词库文件本身。",
            "织文互联" to "默认关闭。文件与你配对过的设备端到端加密直传，不经过中转。跨网直传由你主动生成连接码，地址探测服务只获取公网映射，不接收文件内容。部分网络不能直连时会提示失败。",
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
