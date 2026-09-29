package com.weavetext.ime.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.weavetext.ime.R
import kotlin.math.roundToInt

/** 手写停笔判字的三档名称，与 [WeavePrefs.HAND_PAUSE_MS] 对应。 Names of the three pause levels. */
private val HAND_PAUSE_NAMES = listOf("快", "中", "慢")

private val ALL_KEYBOARDS = listOf("pinyin", "shuangpin", "t9", "t14", "hand", "wubi86", "english")

/** 输入方案（03 §5）。 Input schemes page. */
@Composable
fun SchemesScreen() {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val p by rememberLivePrefs(deps.prefs)
    var showScheme by remember { mutableStateOf(false) }
    var showHand by remember { mutableStateOf(false) }
    val enabled = WeavePrefs.keyboards(p)
    // 已启用的在前（按用户顺序），其余在后。 Enabled first (user order), then the rest.
    val order = enabled + ALL_KEYBOARDS.filter { it !in enabled }

    fun save(list: List<String>) {
        p.edit().putString(WeavePrefs.KEYBOARDS, list.joinToString(",")).apply()
    }

    SubPage("输入方案") {
        GroupTitle("启用的键盘（至少一个）")
        GroupCard {
            ReorderableKeyboards(order, enabled.toSet(), onToggle = { k, on ->
                val next = if (on) order.filter { it in enabled || it == k } else enabled.filter { it != k }
                if (next.isNotEmpty()) save(next)
            }, onMove = { from, to ->
                val list = order.toMutableList()
                list.add(to, list.removeAt(from))
                save(list.filter { it in enabled })
            })
        }
        Text(
            "长按右侧手柄拖动排序；顺序即「中/英」长按气泡中的顺序。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        GroupTitle("拼音")
        GroupCard {
            val scheme = WeavePrefs.SHUANGPIN_SCHEMES.firstOrNull { it.first == WeavePrefs.shuangpinScheme(p) }?.second ?: ""
            SettingRow("双拼方案", onClick = { showScheme = true }) { ValueChevron(scheme) }
            RowDivider(false)
            SwitchRow("显示双拼按键提示", checked = WeavePrefs.shuangpinHints(p)) { p.edit().putBoolean(WeavePrefs.SHUANGPIN_HINTS, it).apply() }
            RowDivider(false)
            val n = WeavePrefs.fuzzy(p).size
            SettingRow("模糊音", onClick = { nav.push(Route.Fuzzy) }) { ValueChevron(if (n == 0) "未开启" else "$n 项") }
            RowDivider(false)
            SwitchRow("自动纠错", "字母颠倒、漏打、多打时自动改正并标红", checked = WeavePrefs.autocorrect(p)) {
                p.edit().putBoolean(WeavePrefs.AUTOCORRECT, it).apply()
            }
            RowDivider(false)
            SwitchRow("联想词", "上屏后推荐下一个词，越用越懂你的搭配", checked = WeavePrefs.prediction(p)) {
                p.edit().putBoolean(WeavePrefs.PREDICTION, it).apply()
            }
            RowDivider(false)
            SwitchRow("成对符号", "输入“（《【时自动补上另一半", checked = WeavePrefs.autoPair(p)) {
                p.edit().putBoolean(WeavePrefs.AUTO_PAIR, it).apply()
            }
        }
        GroupTitle("手写")
        GroupCard {
            SettingRow("停笔判字", "停笔多久算写完一个字；写得慢选「慢」", onClick = { showHand = true }) {
                ValueChevron(HAND_PAUSE_NAMES[WeavePrefs.handPause(p)])
            }
        }
        GroupTitle("五笔")
        GroupCard {
            SwitchRow("显示字根提示", checked = WeavePrefs.wubiRootHints(p)) { p.edit().putBoolean(WeavePrefs.WUBI_ROOT_HINTS, it).apply() }
            RowDivider(false)
            SwitchRow("五笔拼音混输", "拼音结果带灰色编码提示", checked = WeavePrefs.wubiPinyinMix(p)) {
                p.edit().putBoolean(WeavePrefs.WUBI_PINYIN_MIX, it).apply()
            }
        }
    }

    if (showHand) {
        AlertDialog(
            onDismissRequest = { showHand = false },
            title = { Text("停笔判字") },
            text = {
                Column {
                    for (i in HAND_PAUSE_NAMES.indices) {
                        val sel = i == WeavePrefs.handPause(p)
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).selectable(sel) {
                                p.edit().putInt(WeavePrefs.HAND_PAUSE, i).apply(); showHand = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel, onClick = null)
                            Text("${HAND_PAUSE_NAMES[i]}（${"%.1f".format(java.util.Locale.ROOT, WeavePrefs.HAND_PAUSE_MS[i] / 1000f)} 秒）",
                                Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHand = false }) { Text("取消") } },
        )
    }

    if (showScheme) {
        AlertDialog(
            onDismissRequest = { showScheme = false },
            title = { Text("双拼方案") },
            text = {
                Column {
                    for ((key, name) in WeavePrefs.SHUANGPIN_SCHEMES) {
                        val sel = key == WeavePrefs.shuangpinScheme(p)
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).selectable(sel) {
                                p.edit().putString(WeavePrefs.SHUANGPIN_SCHEME, key).apply(); showScheme = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel, onClick = null)
                            Text(name, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showScheme = false }) { Text("取消") } },
        )
    }
}

/** 可拖动排序的键盘列表。 Reorderable keyboard list. */
@Composable
private fun ReorderableKeyboards(order: List<String>, enabled: Set<String>, onToggle: (String, Boolean) -> Unit, onMove: (Int, Int) -> Unit) {
    val rowPx = with(LocalDensity.current) { 56.dp.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    order.forEachIndexed { i, k ->
        val on = k in enabled
        if (i > 0) RowDivider(false)
        Row(
            Modifier.fillMaxWidth().height(56.dp)
                .zIndex(if (i == dragging) 1f else 0f)
                .graphicsLayer { translationY = if (i == dragging) offset else 0f; shadowElevation = if (i == dragging) 8f else 0f }
                .selectable(on) { onToggle(k, !on) }
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = { onToggle(k, it) })
            Text(WeavePrefs.KEYBOARD_NAMES[k] ?: k, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodyLarge)
            if (on) {
                Icon(
                    painterResource(R.drawable.ic_drag_handle), "拖动排序", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(order) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = i; offset = 0f },
                            onDragEnd = {
                                val to = (dragging + (offset / rowPx).roundToInt()).coerceIn(0, enabled.size - 1)
                                if (to != dragging && dragging >= 0) onMove(dragging, to)
                                dragging = -1; offset = 0f
                            },
                            onDragCancel = { dragging = -1; offset = 0f },
                        ) { change, drag -> change.consume(); offset += drag.y }
                    },
                )
            }
        }
    }
}

/** 模糊音子页（03 §5）。 Fuzzy pinyin sub-page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuzzyScreen() {
    val p by rememberLivePrefs(LocalDeps.current.prefs)
    val on = WeavePrefs.fuzzy(p)
    SubPage("模糊音") {
        Text(
            "开启后，这些读音会互相匹配。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        GroupCard {
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, label) in WeavePrefs.FUZZY_PAIRS) {
                    val sel = key in on
                    FilterChip(
                        selected = sel,
                        onClick = { p.edit().putStringSet(WeavePrefs.FUZZY, if (sel) on - key else on + key).apply() },
                        label = { Text(label) },
                        leadingIcon = if (sel) ({ Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) }) else null,
                    )
                }
            }
        }
    }
}
