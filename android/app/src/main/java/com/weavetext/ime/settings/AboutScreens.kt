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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R

/** 问题反馈地址；仓库公开前为空，此时隐藏该入口。 Issue tracker URL; the row is hidden while empty. */
const val ISSUES_URL = ""

/** 关于（03 §9）。 About page. */
@Composable
fun AboutScreen() {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
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
            "语音输入" to "语音由你选择并导入的插件识别。音频会发送到该插件对应的服务，插件只能访问其清单中声明的域名。",
            "剪贴板" to "剪贴板历史只保存在本机私有目录；来自密码框或标记为敏感的内容不会记录，未固定的记录 24 小时后自动删除。可在「外观与手感」中关闭记录。",
            "密码框" to "在密码框中输入时不学习用户词，也不记录剪贴板。",
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
        ),
    )
    GroupTitle("内核（Rust）")
    LicenseCard(
        listOf(
            Triple("Lua 5.4（mlua / lua-src）", "MIT", "插件运行时"),
            Triple("rustls、ring、webpki-roots", "Apache-2.0 / ISC / CDLA-Permissive-2.0", "插件网络 TLS"),
            Triple("memmap2、jni、serde_json、zip、tungstenite、ureq 等", "MIT OR Apache-2.0", "见仓库 docs/THIRD_PARTY.md"),
        ),
    )
    GroupTitle("Android")
    LicenseCard(listOf(Triple("AndroidX、Jetpack Compose、Material 3", "Apache-2.0", "界面与基础组件")))
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
