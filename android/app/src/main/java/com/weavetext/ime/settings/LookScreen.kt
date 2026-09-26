package com.weavetext.ime.settings

import android.content.SharedPreferences
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.weavetext.ime.R
import com.weavetext.ime.ime.ClipHistory
import com.weavetext.ime.ui.keyboard.ClipboardRepo
import com.weavetext.ime.ui.keyboard.Feedback
import com.weavetext.ime.ui.keyboard.Icons
import com.weavetext.ime.ui.keyboard.KeyboardView
import com.weavetext.ime.ui.keyboard.Layouts
import com.weavetext.ime.style.StyleRepository
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** 外观与手感（03 §7）：6 个控件 + 实时预览。 Look & feel: six controls plus a live preview. */
@Composable
fun LookScreen() {
    val deps = LocalDeps.current
    val p by rememberLivePrefs(deps.prefs)
    val ctx = LocalContext.current
    val view = LocalView.current
    val nav = LocalNav.current
    val feedback = remember { Feedback(ctx) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    var confirmClear by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    SubPage("外观与手感", snackbar = snack) {
        KeyboardPreview(p)
        Spacer(Modifier.height(12.dp))
        GroupCard {
            val repo = StyleRepository.get(ctx)
            val theme = WeavePrefs.styleTheme(p)
            val layout = repo.layout(WeavePrefs.styleLayout(p))
            val themeName = if (theme == StyleRepository.AUTO) repo.theme(layout.theme).name else repo.theme(theme).name
            SettingRow("键盘风格", "布局、配色、微调与导入导出", icon = R.drawable.ic_theme, onClick = { nav.push(Route.Styles) }) {
                ValueChevron("${layout.name} · $themeName")
            }
        }
        GroupTitle("深浅色")
        val themes = listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统")
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp)) {
            themes.forEachIndexed { i, (key, name) ->
                SegmentedButton(
                    selected = WeavePrefs.theme(p) == key,
                    onClick = { p.edit().putString(WeavePrefs.THEME, key).apply() },
                    shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                ) { Text(name) }
            }
        }
        GroupTitle("键盘高度")
        StepSlider(WeavePrefs.heightLevel(p), listOf("紧凑", "", "适中", "较高", "高")) {
            p.edit().putInt(WeavePrefs.HEIGHT_LEVEL, it).apply()
        }
        GroupTitle("振动")
        StepSlider(WeavePrefs.vibration(p), listOf("关", "系统", "轻", "中", "强")) {
            p.edit().putInt(WeavePrefs.VIBRATION, it).apply()
            // 拖动时即时振动一次预览。 Preview the new strength.
            feedback.vibration = it
            feedback.haptic(view)
        }
        GroupTitle("按键音")
        StepSlider(WeavePrefs.sound(p), listOf("关", "1", "2", "3", "4")) {
            p.edit().putInt(WeavePrefs.SOUND, it).apply()
            if (it > 0) audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, floatArrayOf(0f, 0.15f, 0.3f, 0.5f, 0.8f)[it])
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(GroupGap))
        GroupCard {
            SwitchRow("按键气泡", "按下时放大显示字符", WeavePrefs.keyPreview(p)) { p.edit().putBoolean(WeavePrefs.KEY_PREVIEW, it).apply() }
            RowDivider(false)
            SwitchRow("记录剪贴板", "在本机保存 24 小时，密码框中不记录；关闭后只显示当前剪贴板", WeavePrefs.clipboardRecord(p), subtitleMaxLines = 2) {
                p.edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, it).apply()
            }
            RowDivider(false)
            SettingRow("清空剪贴板历史", "包括已固定的内容，常用语不受影响", onClick = { confirmClear = true })
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空剪贴板历史？") },
            text = { Text("已记录的剪贴板内容（包括已固定的）将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    ClipHistory(File(ctx.filesDir, ClipboardRepo.HISTORY_FILE)).clearAll()
                    // 通知正在运行的键盘清空内存中的历史。 Tell a running keyboard to drop its in-memory copy.
                    p.edit().putLong(WeavePrefs.CLIPBOARD_CLEARED, System.currentTimeMillis()).apply()
                    scope.launch { snack.showSnackbar("已清空剪贴板历史") }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 离散滑块 + 刻度文字。 Discrete slider with tick labels. */
@Composable
private fun StepSlider(value: Int, labels: List<String>, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Slider(
            value = value.toFloat(), onValueChange = { val v = it.roundToInt(); if (v != value) onChange(v) },
            valueRange = 0f..(labels.size - 1).toFloat(), steps = labels.size - 2,
        )
        // 刻度文字与滑块档位对齐。 Tick labels aligned under the slider stops.
        Box(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, style = MaterialTheme.typography.labelMedium,
                    color = if (i == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(BiasAlignment(-1f + 2f * i / (labels.size - 1), 0f)),
                )
            }
        }
    }
}

/**
 * 实时预览：真实 [KeyboardView]（不可交互），按 0.6 缩放，随主题与键高即时变化。
 * Live preview: the real, non-interactive KeyboardView scaled to 0.6.
 */
@Composable
private fun KeyboardPreview(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    val style = StyleRepository.get(ctx).resolve(ctx, prefs)
    val palette = style.palette
    val metrics = style.metrics
    val icons = remember(palette) { Icons(ctx) }
    val density = LocalDensity.current
    val scale = 0.6f
    GroupCard(Modifier.padding(top = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val fullW = maxWidth / scale
            val fullH = with(density) { metrics.mainHeight.toDp() }
            Box(
                Modifier.fillMaxWidth().height(fullH * scale).clip(RoundedCornerShape(12.dp)).background(Color(palette.background)),
            ) {
                AndroidView(
                    factory = { c -> KeyboardView(c, null) },
                    update = { v ->
                        v.applyStyle(style, icons)
                        v.setQwerty(Layouts.qwerty(english = false, style.layout.qwerty, style.layout.labels))
                        v.chinese = true
                        v.requestLayout()
                    },
                    modifier = Modifier.requiredSize(fullW, fullH).graphicsLayer {
                        scaleX = scale; scaleY = scale
                        // requiredSize 超出父布局时居中放置，因此绕中心缩放。 Oversized child is centred; scale about the centre.
                        transformOrigin = TransformOrigin.Center
                    },
                )
            }
        }
    }
}
