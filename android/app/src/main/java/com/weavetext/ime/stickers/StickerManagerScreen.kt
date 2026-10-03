package com.weavetext.ime.stickers

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 管理页的分组筛选项。 Filter entries of the manager. */
private data class StickerFilter(val id: String, val label: String)

/**
 * 表情收纳袋管理页（Compose）：与设置页同一套配色、圆角与控件，不再是系统自带样子的原生控件。
 * The sticker manager in Compose: same palette, corner radii and controls as the settings app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repository = remember { StickerRepository.get(ctx) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf<Sticker?>(null) }

    DisposableEffect(Unit) {
        val listener: () -> Unit = { tick++ }
        repository.observe(listener)
        onDispose { repository.unobserve(listener) }
    }
    fun notify(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val all = remember(tick) { repository.store.list() }
    val items = remember(tick, query, filter) { repository.store.list(query, filter) }
    val filters = remember(tick) {
        listOf(StickerFilter("all", "全部"), StickerFilter("recent", "最近"), StickerFilter("favorites", "收藏"), StickerFilter("ungrouped", "未分组")) +
            repository.store.groups().map { StickerFilter("group:$it", it) }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { collect(it, repository, ::notify) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { collect(it, repository, ::notify) }
    val importArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repositoryImport(uri, repository, ctx, scope, ::notify)
    }
    val exportArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) repositoryExport(uri, repository, ctx, scope, ::notify)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${selected.size} 张" else "表情收纳袋") },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) { selecting = false; selected = emptySet() } else onBack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { exportArchive.launch("织文表情备份.zip") }) {
                        Icon(painterResource(R.drawable.ic_export), "备份")
                    }
                    IconButton(onClick = {
                        if (android.provider.Settings.canDrawOverlays(ctx)) StickerOverlayService.start(ctx)
                        else {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            notify("允许悬浮窗权限后，再点一次悬浮即可打开")
                        }
                    }) { Icon(painterResource(R.drawable.ic_float), "悬浮窗") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { selected = items.map { it.id }.toSet() }) { Text("全选") }
                    FilledTonalButton(onClick = { groupDialog(ctx, repository, selected, ::notify, { selected = emptySet(); selecting = false }) }) { Text("分组") }
                    FilledTonalButton(onClick = {
                        val ids = selected
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { repository.store.delete(ids) } }
                            repository.changed(); notify("已删除 ${ids.size} 张"); selected = emptySet(); selecting = false
                        }
                    }) { Text("删除") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { selecting = false; selected = emptySet() }) { Text("完成") }
                }
            } else {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("搜索名称、标签或分组") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(painterResource(R.drawable.ic_import), null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("导入")
                    }
                    FilledTonalButton(onClick = { files.launch(arrayOf("image/*", "application/octet-stream")) }) {
                        Icon(painterResource(R.drawable.ic_clipboard), null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("文件")
                    }
                    FilledTonalButton(onClick = { if (all.isEmpty()) selecting = true else { selecting = true } }) {
                        Icon(painterResource(R.drawable.ic_select_all), null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("整理")
                    }
                }
                if (filters.size > 4) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filters.forEach { f ->
                            FilterChip(selected = filter == f.id, onClick = { filter = f.id }, label = { Text(f.label) })
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        repository.store.loadError ?: if (all.isEmpty()) "把图片分享到「收纳到织文」\n或点导入，收藏自己的表情"
                        else "没有找到表情",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        StickerTile(
                            item = item,
                            repository = repository,
                            selecting = selecting,
                            checked = item.id in selected,
                            onToggleSelect = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                            onOpen = {
                                selecting = true
                                selected = if (item.id in selected) selected - item.id else selected + item.id
                            },
                            onEdit = { editing = item },
                            onShare = { StickerSending.share(ctx, item); notify("已打开分享") },
                            onFavorite = { repository.edit(item.id, item.name, item.group, item.tags, !item.favorite) { notify(it) } },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { repository.store.delete(setOf(item.id)) } }
                                    repository.changed(); notify("已删除「${item.name}」")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { item ->
        StickerEditorDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { name, group, tags ->
                repository.edit(item.id, name, group, tags, item.favorite) { notify(it) }
                editing = null
            },
        )
    }
}

@Composable
private fun StickerTile(
    item: Sticker,
    repository: StickerRepository,
    selecting: Boolean,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(if (checked) 2.dp else 1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = { if (selecting) onToggleSelect() else onOpen() }, onLongClick = { menu = true })
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            StickerThumbnail(repository, item)
            if (checked) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (item.favorite) Text("★", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(item.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        androidx.compose.material3.DropdownMenuItem(text = { Text(if (item.favorite) "取消收藏" else "收藏") }, onClick = { menu = false; onFavorite() })
        androidx.compose.material3.DropdownMenuItem(text = { Text("编辑名称、标签与分组") }, onClick = { menu = false; onEdit() })
        androidx.compose.material3.DropdownMenuItem(text = { Text("分享原图") }, onClick = { menu = false; onShare() })
        androidx.compose.material3.DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
    }
}

@Composable
private fun StickerThumbnail(repository: StickerRepository, item: Sticker) {
    var image by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) {
        val file = runCatching { repository.store.file(item) }.getOrNull() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            StickerThumbs.load(file, item.id, 256) { bitmap -> image = bitmap }
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_sticker_bag), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StickerEditorDialog(item: Sticker, onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var group by remember { mutableStateOf(item.group) }
    var tags by remember { mutableStateOf(item.tags.joinToString("，")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑表情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(group, { group = it }, label = { Text("分组") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(tags, { tags = it }, label = { Text("标签，用逗号分隔") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, group, tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun collect(uris: List<Uri>, repository: StickerRepository, notify: (String) -> Unit) {
    if (uris.isNotEmpty()) repository.import(uris, notify)
}

private fun repositoryImport(
    uri: Uri, repository: StickerRepository, ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope, notify: (String) -> Unit,
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(uri)?.let { repository.store.importArchive(it) } ?: error("无法打开备份") }
        }
        repository.changed()
        notify(result.fold({ "已导入 ${it.first} 张，重复 ${it.second} 张" }, { it.message ?: "导入失败" }))
    }
}

private fun repositoryExport(
    uri: Uri, repository: StickerRepository, ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope, notify: (String) -> Unit,
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.let { repository.store.export(it) } ?: error("无法写入备份") }
        }
        notify(if (result.isSuccess) "表情备份已导出" else result.exceptionOrNull()?.message ?: "导出失败")
    }
}

private fun groupDialog(
    ctx: android.content.Context, repository: StickerRepository, ids: Set<String>,
    notify: (String) -> Unit, done: () -> Unit,
) {
    if (ids.isEmpty()) { notify("先选择要分组的表情"); return }
    val input = android.widget.EditText(ctx).apply { hint = "分组名称" }
    android.app.AlertDialog.Builder(ctx).setTitle("批量分组").setView(input)
        .setPositiveButton("保存") { _, _ ->
            val group = input.text.toString()
            StickerRepository.io.execute {
                runCatching { repository.store.group(ids, group) }
                repository.changed()
            }
            notify("已保存分组"); done()
        }
        .setNegativeButton("取消", null).show()
}
