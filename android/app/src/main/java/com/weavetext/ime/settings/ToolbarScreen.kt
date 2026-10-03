package com.weavetext.ime.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.style.StyleRepository

/** 工具栏按钮的名称、说明与图标。 Name, description and icon of a toolbar item. */
private data class ToolInfo(val name: String, val hint: String, val icon: Int)

private val TOOLS = mapOf(
    "keyboard" to ToolInfo("切换键盘", "中英切换，或在多个输入方案间选择", R.drawable.ic_keyboard),
    "voice" to ToolInfo("语音输入", "点按说话，长按按住说话", R.drawable.ic_mic),
    "emoji" to ToolInfo("表情符号", "Emoji 与颜文字", R.drawable.ic_emoji),
    "cursor" to ToolInfo("光标编辑", "移动光标、选择、复制粘贴", R.drawable.ic_cursor),
    "clipboard" to ToolInfo("剪贴板", "最近复制的文字、图片和常用语", R.drawable.ic_clipboard),
    "stickers" to ToolInfo("表情收纳袋", "收藏的图片与动图", R.drawable.ic_sticker_bag),
    "settings" to ToolInfo("设置", "打开织文设置", R.drawable.ic_settings),
    "hide" to ToolInfo("收起键盘", "", R.drawable.ic_chevron_down),
)

/** 当前风格自带的工具栏（去掉菜单，收纳袋默认不放）。 The style's own toolbar without the menu or the sticker bag. */
fun defaultToolbar(ctx: Context, p: SharedPreferences): List<String> =
    WeavePrefs.toolbarItems(p.let { DefaultsOnly(it) }, StyleRepository.get(ctx).layout(WeavePrefs.styleLayout(p)).toolbar.items).drop(1)

/** 只读的偏好视图：假装没自定义过，用来求默认工具栏。 Read-only view that hides the customisation, for the defaults. */
private class DefaultsOnly(private val p: SharedPreferences) : SharedPreferences by p {
    override fun getString(key: String?, defValue: String?): String? = if (key == WeavePrefs.TOOLBAR_ITEMS) defValue else p.getString(key, defValue)
}

/**
 * 工具栏设置：预览、挑选与排序候选栏上方的按钮；菜单按钮固定在最前（工具箱和设置都从这里进）。
 * Toolbar settings: preview, pick and order the buttons above the candidates; the menu button stays first
 * (it leads to the toolbox and settings).
 */
@Composable
fun ToolbarScreen() {
    val deps = LocalDeps.current
    val ctx = LocalContext.current
    val p by rememberLivePrefs(deps.prefs)
    val custom = WeavePrefs.toolbarCustom(p)
    val items = custom ?: defaultToolbar(ctx, p)
    fun save(next: List<String>) = WeavePrefs.setToolbar(deps.prefs, next)

    SubPage("工具栏", actions = {
        if (custom != null) TextButton(onClick = { WeavePrefs.setToolbar(deps.prefs, null) }) { Text("恢复默认") }
    }) {
        ToolbarPreview(items)
        Text(
            "最左边的织文按钮固定不动，点它打开工具箱，长按打开设置。下面挑选其余按钮，最多 ${WeavePrefs.TOOLBAR_MAX} 个。",
            Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GroupTitle("已显示 · ${items.size}/${WeavePrefs.TOOLBAR_MAX}")
        GroupCard {
            if (items.isEmpty()) SettingRow("只保留织文按钮", "从下面添加常用的按钮")
            items.forEachIndexed { i, id ->
                val t = TOOLS.getValue(id)
                if (i > 0) RowDivider()
                SettingRow(t.name, icon = t.icon) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { save(items.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                            Icon(painterResource(R.drawable.ic_chevron_up), "上移", Modifier.size(20.dp))
                        }
                        IconButton(onClick = { save(items.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < items.lastIndex) {
                            Icon(painterResource(R.drawable.ic_chevron_down), "下移", Modifier.size(20.dp))
                        }
                        IconButton(onClick = { save(items - id) }) {
                            Icon(painterResource(R.drawable.ic_close), "移除", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        val rest = WeavePrefs.TOOLBAR_CHOICES.filter { it !in items }
        if (rest.isNotEmpty()) {
            val full = items.size >= WeavePrefs.TOOLBAR_MAX
            GroupTitle(if (full) "可以添加 · 先移除一个" else "可以添加")
            GroupCard {
                rest.forEachIndexed { i, id ->
                    val t = TOOLS.getValue(id)
                    if (i > 0) RowDivider()
                    SettingRow(t.name, t.hint.ifEmpty { null }, icon = t.icon, enabled = !full, onClick = { save(items + id) }) {
                        Icon(painterResource(R.drawable.ic_plus), "添加", Modifier.size(22.dp),
                            tint = if (full) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 预览条：和键盘上一样排开。 Preview strip laid out like the keyboard's. */
@Composable
private fun ToolbarPreview(items: List<String>) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp).height(52.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            for (icon in listOf(R.drawable.ic_logo) + items.map { TOOLS.getValue(it).icon }) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (icon == R.drawable.ic_logo) cs.primary else cs.onSurfaceVariant)
                }
            }
        }
    }
}
