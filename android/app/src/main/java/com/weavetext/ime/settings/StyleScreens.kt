package com.weavetext.ime.settings

import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.weavetext.ime.R
import com.weavetext.ime.style.KeyboardStyle
import com.weavetext.ime.style.StagedImport
import com.weavetext.ime.style.StyleException
import com.weavetext.ime.style.StyleOverrides
import com.weavetext.ime.style.StylePack
import com.weavetext.ime.style.StyleRepository
import com.weavetext.ime.ui.keyboard.KeyShadow
import com.weavetext.ime.ui.keyboard.StylePreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 真实渲染的键盘缩略图：按可用宽度等比缩放整块键盘（顶栏 + 26 键）。
 * Real-render keyboard thumbnail scaled to the available width (top bar + QWERTY).
 */
@Composable
fun StyleThumb(style: KeyboardStyle, modifier: Modifier = Modifier, composing: Boolean = true, corner: Dp = 10.dp) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val screenW = with(density) { ctx.resources.displayMetrics.widthPixels.toDp() }
    val fullH = with(density) { style.metrics.kbHeight.toDp() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = maxWidth / screenW
        Box(Modifier.fillMaxWidth().height(fullH * scale).clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> StylePreviewView(c) },
                update = { v -> v.bind(style, composing) },
                modifier = Modifier.requiredSize(screenW, fullH).graphicsLayer {
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin.Center
                },
            )
        }
    }
}

private fun editOverrides(repo: StyleRepository, p: SharedPreferences, f: (StyleOverrides) -> StyleOverrides) {
    p.edit().putString(WeavePrefs.STYLE_OVERRIDES, f(repo.overrides(p)).toJson().toString()).apply()
}

/** 键盘风格（05 §7）：布局与主题分开选、微调入口、我的风格与导入导出。 Keyboard style page. */
@Composable
fun StylesScreen() {
    val deps = LocalDeps.current
    val p by rememberLivePrefs(deps.prefs)
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val repo = remember { StyleRepository.get(ctx) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dark = repo.isDark(ctx, p)
    val level = WeavePrefs.heightLevel(p)
    val layoutId = WeavePrefs.styleLayout(p)
    val themeId = WeavePrefs.styleTheme(p)
    val o = repo.overrides(p)
    val current = repo.resolve(ctx, p)
    var packsVersion by remember { mutableIntStateOf(0) }
    val packs = remember(packsVersion, p.getLong(WeavePrefs.STYLE_STAMP, 0)) { repo.packs() }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StylePack?>(null) }
    var staged by remember { mutableStateOf<StagedImport?>(null) }
    var exporting by remember { mutableStateOf<StylePack?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { repo.stage(it) } ?: throw StyleException("无法读取文件") }
            }
            r.onSuccess { staged = it }.onFailure { snack.showSnackbar("导入失败：${it.message}") }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val pack = exporting
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        if (pack != null) repo.exportPack(pack, out) else repo.exportCurrent(deps.prefs, "我的风格", out)
                    } ?: error("无法写入文件")
                }
            }
            snack.showSnackbar(if (r.isSuccess) "已导出" else "导出失败：${r.exceptionOrNull()?.message}")
        }
    }

    SubPage("键盘风格", snackbar = snack) {
        GroupCard { SettingRow("更多主题与布局", "在插件市场安装，再自由搭配", onClick = { nav.push(Route.Market("theme")) }) { Chevron() } }
        GroupCard(Modifier.padding(top = 8.dp)) {
            Box(Modifier.padding(12.dp)) { StyleThumb(current, corner = 12.dp) }
        }

        GroupTitle("布局风格")
        CardGrid(repo.layoutIds, columns = 2) { id ->
            val l = repo.layout(id)
            val s = repo.resolve(ctx, id, themeId, o, dark, level)
            PresetCard(l.name, l.description, selected = id == layoutId, onClick = { p.edit().putString(WeavePrefs.STYLE_LAYOUT, id).apply() }) {
                StyleThumb(s)
            }
        }

        GroupTitle("配色主题")
        CardGrid(listOf(StyleRepository.AUTO) + repo.themeIds, columns = 3) { id ->
            val s = repo.resolve(ctx, layoutId, id, o, dark, level)
            val name = if (id == StyleRepository.AUTO) "跟随布局" else repo.theme(id).name
            PresetCard(name, null, selected = id == themeId, onClick = { p.edit().putString(WeavePrefs.STYLE_THEME, id).apply() }) {
                StyleThumb(s, composing = false, corner = 6.dp)
            }
        }

        Spacer(Modifier.height(GroupGap))
        GroupCard {
            SettingRow("微调", tweakSummary(o), icon = R.drawable.ic_resize, onClick = { nav.push(Route.StyleTweak) }) { Chevron() }
        }

        GroupTitle("我的风格")
        GroupCard {
            if (packs.isEmpty()) {
                Text(
                    "把当前组合另存下来，或导入 .wvskin 风格包", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
            packs.forEachIndexed { i, pack ->
                if (i > 0) RowDivider(false)
                SettingRow(pack.name, packSummary(repo, pack), onClick = {
                    repo.apply(deps.prefs, pack)
                    scope.launch { snack.showSnackbar("已应用「${pack.name}」") }
                }) {
                    Row {
                        IconButton(onClick = { exporting = pack; exporter.launch("${pack.name}.${StyleRepository.EXTENSION}") }) {
                            Icon(painterResource(R.drawable.ic_export), "导出", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = pack }) {
                            Icon(painterResource(R.drawable.ic_delete), "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { saving = true }, modifier = Modifier.weight(1f)) { Text("另存为我的风格", maxLines = 1) }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }) { Text("导入") }
            OutlinedButton(onClick = { exporting = null; exporter.launch("织文风格.${StyleRepository.EXTENSION}") }) { Text("导出") }
        }
    }

    if (saving) SaveDialog(onDismiss = { saving = false }) { name ->
        saving = false
        repo.saveCurrent(deps.prefs, name)
        packsVersion++
        scope.launch { snack.showSnackbar("已保存「$name」") }
    }
    deleting?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${pack.name}」？") },
            text = { Text("正在使用时会退回它所基于的内置布局与主题。") },
            confirmButton = {
                TextButton(onClick = { deleting = null; repo.delete(deps.prefs, pack); packsVersion++ }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    staged?.let { s ->
        StyleImportSheet(
            s, repo.preview(ctx, s, dark, level),
            onCancel = { repo.discard(s); staged = null },
            onConfirm = { apply ->
                val pack = repo.commit(s)
                if (apply) repo.apply(deps.prefs, pack)
                staged = null
                packsVersion++
                scope.launch { snack.showSnackbar(if (apply) "已导入并应用「${pack.name}」" else "已导入「${pack.name}」") }
            },
        )
    }
}

private fun tweakSummary(o: StyleOverrides): String {
    if (o.isEmpty) return "强调色、圆角、键间距、字号、背景图"
    val parts = ArrayList<String>()
    if (o.accent != null) parts += "强调色"
    if (o.radius != null) parts += "圆角"
    if (o.gap != null) parts += "键间距"
    if (o.textScale != null) parts += "字号"
    if (o.hints != null) parts += "副标签"
    if (o.shadow != null) parts += "阴影"
    if (o.background != null) parts += "背景图"
    if (o.keyOpacity != null) parts += "透明度"
    return "已调整：" + parts.joinToString("、")
}

private fun packSummary(repo: StyleRepository, pack: StylePack): String {
    val l = runCatching {
        if (pack.ownLayout) "自定义布局" else repo.layout(pack.layoutJson.optString("extends", StyleRepository.DEFAULT_LAYOUT)).name
    }.getOrDefault("")
    val t = runCatching {
        if (pack.ownTheme) "自定义主题" else repo.theme(pack.themeJson.optString("extends", StyleRepository.DEFAULT_THEME)).name
    }.getOrDefault("")
    return "$l · $t"
}

/** 等宽网格（Column 里不用懒加载网格）。 Fixed-column grid inside the scrolling column. */
@Composable
private fun <T> CardGrid(items: List<T>, columns: Int, cell: @Composable (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in items.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (it in row) Box(Modifier.weight(1f)) { cell(it) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PresetCard(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, thumb: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(2.dp, cs.primary) else null,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = title },
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            thumb()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("我的风格") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("另存为我的风格") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(StyleRepository.MAX_NAME) }, singleLine = true, label = { Text("名称") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifEmpty { "我的风格" }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 导入确认弹层。 Import confirmation sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleImportSheet(s: StagedImport, preview: KeyboardStyle, onCancel: () -> Unit, onConfirm: (apply: Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StyleImportContent(s, preview, onCancel, onConfirm)
        }
    }
}

/** 导入确认内容（截图测试直接渲染）。 Import confirmation content, rendered directly in screenshot tests. */
@Composable
internal fun ColumnScope.StyleImportContent(s: StagedImport, preview: KeyboardStyle, onCancel: () -> Unit, onConfirm: (apply: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text("导入风格包", style = MaterialTheme.typography.titleLarge)
    StyleThumb(preview, corner = 12.dp)
    Text(s.name, style = MaterialTheme.typography.titleMedium)
    Text(
        "布局：${s.layoutName} · 主题：${s.themeName}" + if (s.hasImage) " · 含背景图" else "",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
    )
    Text("风格包只包含颜色、尺寸与图片，不含可执行内容。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text("取消") }
        OutlinedButton(onClick = { onConfirm(false) }) { Text("仅导入") }
        FilledTonalButton(onClick = { onConfirm(true) }) { Text("导入并应用") }
    }
}

// ------------------------------------------------------------------ tweaks

/** 强调色候选（均满足白字 ≥ 4.5:1）。 Accent choices (all ≥ 4.5:1 with white text). */
private val ACCENTS = listOf(0xFF2E6CF6, 0xFF0D7355, 0xFFC25100, 0xFFB8185A, 0xFF6A42C2, 0xFF00718A, 0xFFA3411E, 0xFF3A3A3A).map { it.toInt() }

/** 微调页（05 §5）。 Tweak page. */
@Composable
fun StyleTweakScreen() {
    val deps = LocalDeps.current
    val p by rememberLivePrefs(deps.prefs)
    val ctx = LocalContext.current
    val repo = remember { StyleRepository.get(ctx) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val o = repo.overrides(p)
    val style = repo.resolve(ctx, p)
    val base = repo.resolve(ctx, WeavePrefs.styleLayout(p), WeavePrefs.styleTheme(p), StyleOverrides.NONE, style.dark, WeavePrefs.heightLevel(p))
    val edit: ((StyleOverrides) -> StyleOverrides) -> Unit = { f -> editOverrides(repo, deps.prefs, f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { repo.setBackground(deps.prefs, it) } ?: error("无法读取图片") }
            }
            r.onFailure { snack.showSnackbar("设置背景失败：${it.message}") }
        }
    }

    SubPage("微调", snackbar = snack, actions = {
        TextButton(onClick = { edit { StyleOverrides.NONE } }, enabled = !o.isEmpty) { Text("恢复默认") }
    }) {
        GroupCard(Modifier.padding(top = 8.dp)) {
            Box(Modifier.padding(12.dp)) { StyleThumb(style, corner = 12.dp) }
        }
        GroupTitle("强调色")
        Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Swatch(Color(base.palette.keyAccent), selected = o.accent == null, label = "默认") { edit { it.copy(accent = null) } }
            for (c in ACCENTS.filter { it != base.palette.keyAccent }.take(7)) Swatch(Color(c), selected = o.accent == c, label = null) { edit { it.copy(accent = c) } }
        }
        GroupTitle("按键")
        TweakSlider("圆角", o.radius ?: base.layout.geometry.radius, 0f..16f, 16, { "${it.roundToInt()} dp" }) { v -> edit { it.copy(radius = v) } }
        TweakSlider("键间距", o.gap ?: 1f, 0.5f..1.6f, 10, { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(gap = v) } }
        TweakSlider("字号", o.textScale ?: 1f, 0.85f..1.2f, 6, { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(textScale = v) } }
        Spacer(Modifier.height(8.dp))
        GroupCard {
            SwitchRow("显示副标签", "字母键上的数字与符号提示", o.hints ?: base.layout.qwerty.showHints) { v -> edit { it.copy(hints = v) } }
            RowDivider(false)
            SwitchRow("按键阴影", null, o.shadow ?: (base.palette.shadow != KeyShadow.NONE)) { v -> edit { it.copy(shadow = v) } }
        }
        GroupTitle("背景图")
        GroupCard {
            val bg = o.background?.takeIf { it.type == "image" }
            SettingRow(if (bg == null) "选择图片" else "更换图片", "图片只保存在本机", icon = R.drawable.ic_import, onClick = { picker.launch(arrayOf("image/*")) })
            if (bg != null) {
                RowDivider(false)
                SettingRow("移除背景图", titleColor = MaterialTheme.colorScheme.error, onClick = { edit { it.copy(background = null, keyOpacity = null) } })
            }
        }
        val bg = o.background?.takeIf { it.type == "image" }
        if (bg != null) {
            TweakSlider("模糊", bg.blur, 0f..25f, 24, { "${it.roundToInt()}" }) { v -> edit { it.copy(background = copyBg(bg, blur = v)) } }
            TweakSlider("压暗", bg.dim, 0f..0.8f, 15, { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(background = copyBg(bg, dim = v)) } }
            TweakSlider("按键不透明度", o.keyOpacity ?: 1f, 0.3f..1f, 13, { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(keyOpacity = v) } }
        }
    }
}

private fun copyBg(b: com.weavetext.ime.style.BackgroundSpec, blur: Float = b.blur, dim: Float = b.dim) =
    com.weavetext.ime.style.BackgroundSpec(b.type, b.colors, b.angle, b.image, blur, dim)

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 0.dp, if (selected) cs.onSurface else Color.Transparent, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = label ?: "强调色" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TweakSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(fmt(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = { v ->
            // 对齐到刻度，避免写入 0.30000001 这类值。 Snap to the ticks.
            val step = (range.endInclusive - range.start) / (steps + 1)
            val snapped = range.start + ((v - range.start) / step).roundToInt() * step
            onChange((snapped * 100).roundToInt() / 100f)
        }, valueRange = range, steps = steps)
    }
}
