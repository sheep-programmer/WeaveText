package com.weavetext.ime.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.core.UserWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 词库（03 §8）。 Dictionary page. */
@Composable
fun DictionaryScreen() {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val dict = deps.dictionary
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var count by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { count = runCatching { dict.count() }.getOrNull() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
            if (text == null) { snack.showSnackbar("无法读取文件"); return@launch }
            val r = dict.importText(text)
            tick++
            snack.showSnackbar("导入 ${r.imported} 个" + if (r.skipped > 0) "，跳过 ${r.skipped} 个（格式错误）" else "")
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = dict.exportText()
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            }
            snack.showSnackbar(if (ok) "已导出 ${text.lineSequence().count { it.isNotBlank() }} 个用户词" else "导出失败")
        }
    }

    SubPage("词库", snackbar = snack) {
        GroupCard(Modifier.padding(top = 8.dp)) {
            SettingRow("用户词", onClick = { nav.push(Route.UserWords) }) { ValueChevron(count?.let { "%,d 个".format(it) } ?: "") }
            RowDivider(false)
            SettingRow("系统词库", null) {
                Text("随应用内置", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowDivider(false)
            val repo = remember { deps.packs() }
            val installed = repo.packs.filter { repo.state(it.id) == com.weavetext.ime.core.PackState.Installed }
            SettingRow(
                "专业词库", if (installed.isEmpty()) "医学、法律、IT、地名等，按需下载" else installed.joinToString("、") { it.name },
                onClick = { nav.push(Route.DictPacks) },
            ) { ValueChevron(if (installed.isEmpty()) "" else "${installed.size} 个") }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
        GroupCard {
            SettingRow("导入用户词", icon = R.drawable.ic_import, onClick = { importer.launch(arrayOf("text/plain", "*/*")) })
            RowDivider()
            SettingRow("导出用户词", icon = R.drawable.ic_export, onClick = {
                exporter.launch("weavetext-userdict-" + SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date()) + ".txt")
            })
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
        GroupCard {
            SettingRow("清除学习记录", titleColor = MaterialTheme.colorScheme.error, onClick = { confirmClear = true })
        }
        Text(
            "导入格式：每行「词语<Tab>拼音<Tab>词频」，拼音用空格分隔，词频可省略。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除学习记录？") },
            text = { Text("将删除全部用户词与学习到的词频，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { val ok = dict.clear(); tick++; snack.showSnackbar(if (ok) "已清除" else "清除失败") }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 用户词列表：搜索、左滑删除（可撤销）、添加。 User words: search, swipe-to-delete with undo, add. */
@Composable
fun UserWordsScreen() {
    val dict = LocalDeps.current.dictionary
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var words by remember { mutableStateOf<List<UserWord>?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(query, tick) { words = runCatching { dict.list(query.trim(), 0, 500) }.getOrDefault(emptyList()) }

    SubPage("用户词", snackbar = snack, scroll = false, actions = {
        TextButton(onClick = { adding = true }) {
            Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp)); Text(" 添加")
        }
    }) {
        TextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("搜索汉字或拼音首字母") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        )
        val list = words
        when {
            list == null -> {}
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isEmpty()) "还没有用户词，打字时会自动学习" else "没有匹配的词", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.text + "\t" + it.pinyin }) { w ->
                    WordRow(w) {
                        scope.launch {
                            dict.delete(w); tick++
                            val r = snack.showSnackbar("已删除「${w.text}」", "撤销", duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) { dict.add(w.text, w.pinyin); tick++ }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddWordDialog({ adding = false }) { text, py ->
        adding = false
        scope.launch { val ok = dict.add(text, py); tick++; snack.showSnackbar(if (ok) "已添加「$text」" else "添加失败：请检查拼音") }
    }
}

@Composable
private fun WordRow(w: UserWord, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart })
    LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete() }
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(painterResource(R.drawable.ic_delete), "删除", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).heightIn(min = 64.dp).padding(horizontal = 32.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(w.text, style = MaterialTheme.typography.bodyLarge)
            Text("${w.pinyin} · 使用 ${w.count} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddWordDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var py by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加用户词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("词语") }, singleLine = true)
                OutlinedTextField(py, { py = it }, label = { Text("拼音") }, placeholder = { Text("如 zhi wen") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text.trim(), py.trim()) }, enabled = text.isNotBlank() && py.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun packSize(n: Long) = if (n >= 1 shl 20) "%.1f MB".format(n / (1 shl 20).toDouble()) else "${(n + 1023) / 1024} KB"

private fun wordCount(n: Int) = if (n >= 10_000) "%.1f 万词".format(n / 10_000.0) else "$n 词"

/** 专业词库：逐个下载或删除，立即生效。 Domain dictionaries: download or remove each, effective at once. */
@Composable
fun DictPacksScreen() {
    val deps = LocalDeps.current
    val repo = remember { deps.packs() }
    var tick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(repo) {
        val l: () -> Unit = { tick++ }
        repo.addListener(l)
        onDispose { repo.removeListener(l) }
    }
    var removing by remember { mutableStateOf<com.weavetext.ime.core.DictPack?>(null) }
    SubPage("专业词库") {
        Text(
            "装上后，这些领域的词会出现在候选里，但不会排到常用词前面；选过一次后会自动靠前。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        GroupCard(Modifier.padding(top = 8.dp)) {
            tick.let { }
            repo.packs.forEachIndexed { i, p ->
                if (i > 0) RowDivider(false)
                val st = repo.state(p.id)
                val sub = when (st) {
                    is com.weavetext.ime.core.PackState.Downloading -> st.progress?.let { "下载中 · ${packSize(it.downloaded)} / ${packSize(p.bytes)}" } ?: "准备下载…"
                    is com.weavetext.ime.core.PackState.Failed -> st.message
                    else -> "${p.description} · ${wordCount(p.words)} · ${packSize(p.bytes)}"
                }
                SettingRow(p.name, sub, subtitleMaxLines = 2) {
                    when (st) {
                        com.weavetext.ime.core.PackState.Installed -> TextButton(onClick = { removing = p }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        is com.weavetext.ime.core.PackState.Downloading -> TextButton(onClick = { repo.cancel(p.id) }) { Text("取消") }
                        else -> TextButton(onClick = { repo.install(p.id) }) { Text(if (st is com.weavetext.ime.core.PackState.Failed) "重试" else "下载") }
                    }
                }
            }
        }
        Text(
            "词表来自万象拼音（CC BY 4.0）与 THUOCL 清华开放中文词库（MIT），详见「关于 › 开源许可」。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除「${p.name}」？") },
            text = { Text("删除后这些词不再出现在候选里；你选过的词仍保留在用户词里。") },
            confirmButton = { TextButton(onClick = { repo.remove(p.id); removing = null; tick++ }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}
