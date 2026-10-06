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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
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
 * 表情收纳袋管理页（Compose）：与设置页同一套配色、圆角与控件。
 * 顶栏只放常用动作，其余收进「更多」；添加入口是右下角的悬浮按钮；整理模式底部出现操作条。
 * The sticker manager in Compose, sharing the settings app's palette and shapes. The top bar holds only the common
 * actions, the rest sit in "more"; adding lives on the floating button; the selection mode shows a bottom bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerManagerScreen(onBack: () -> Unit, editId: String? = null, onEditShown: () -> Unit = {}) {
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
    var grouping by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: () -> Unit = { tick++ }
        repository.observe(listener)
        onDispose { repository.unobserve(listener) }
    }
    fun notify(text: String) = scope.launch { snackbar.showSnackbar(text) }
    fun leaveSelection() { selecting = false; selected = emptySet() }

    val all = remember(tick) { repository.store.list() }
    LaunchedEffect(editId) {
        if (editId != null) {
            editing = all.firstOrNull { it.id == editId }
            onEditShown()
        }
    }
    val items = remember(tick, query, filter) { repository.store.list(query, filter) }
    val groups = remember(tick) { repository.store.groups() }
    val filters = remember(tick) {
        listOf(StickerFilter("all", "全部"), StickerFilter("recent", "最近"), StickerFilter("favorites", "收藏"), StickerFilter("ungrouped", "未分组")) +
            groups.map { StickerFilter("group:$it", it) }
    }
    // 分组被删光后筛选项消失，回到全部。 A filter whose group vanished falls back to "all".
    LaunchedEffect(filters) { if (filters.none { it.id == filter }) filter = "all" }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { collect(it, repository, ::notify) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { collect(it, repository, ::notify) }
    val importArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repositoryImport(uri, repository, ctx, scope, ::notify)
    }
    val exportArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) repositoryExport(uri, repository, ctx, scope, ::notify)
    }
    fun openFloating() {
        if (android.provider.Settings.canDrawOverlays(ctx)) StickerOverlayService.start(ctx)
        else {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            notify("允许悬浮窗权限后，再点一次悬浮即可打开")
        }
    }
    val animated = all.count { it.animated }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selecting) "已选 ${selected.size} 张" else "表情收纳袋")
                        if (!selecting && all.isNotEmpty()) {
                            Text(
                                if (animated > 0) "${all.size} 张 · ${animated} 个动图" else "${all.size} 张",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) leaveSelection() else onBack() }) {
                        Icon(painterResource(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back), if (selecting) "退出整理" else "返回")
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = {
                            selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet()
                        }) { Text(if (selected.size == items.size && items.isNotEmpty()) "取消全选" else "全选") }
                    } else {
                        IconButton(onClick = ::openFloating) { Icon(painterResource(R.drawable.ic_float), "悬浮窗") }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(painterResource(R.drawable.ic_more), "更多") }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("整理") }, enabled = all.isNotEmpty(),
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_select_all), null, Modifier.size(20.dp)) },
                                    onClick = { moreMenu = false; selecting = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("导出备份") }, enabled = all.isNotEmpty(),
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_export), null, Modifier.size(20.dp)) },
                                    onClick = { moreMenu = false; exportArchive.launch("织文表情备份.zip") },
                                )
                                DropdownMenuItem(
                                    text = { Text("导入备份") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_import), null, Modifier.size(20.dp)) },
                                    onClick = { moreMenu = false; importArchive.launch(arrayOf("application/zip", "application/octet-stream")) },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectionAction(R.drawable.ic_edit, "分组", selected.isNotEmpty(), Modifier.weight(1f)) { grouping = true }
                        SelectionAction(R.drawable.ic_delete, "删除", selected.isNotEmpty(), Modifier.weight(1f), danger = true) { pendingDelete = selected.toSet() }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selecting && all.isNotEmpty()) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(painterResource(R.drawable.ic_plus), null) },
                        text = { Text("添加") },
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("从相册选择") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_sticker_bag), null, Modifier.size(20.dp)) },
                            onClick = { addMenu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        )
                        DropdownMenuItem(
                            text = { Text("从文件选择") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_import), null, Modifier.size(20.dp)) },
                            onClick = { addMenu = false; files.launch(arrayOf("image/*", "application/octet-stream")) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (!selecting && all.isNotEmpty()) {
                SearchField(query) { query = it }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems(filters, key = { it.id }) { f ->
                        FilterChip(
                            selected = filter == f.id, onClick = { filter = f.id }, label = { Text(f.label) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
            if (items.isEmpty()) {
                EmptyState(
                    loadError = repository.store.loadError, nothingStored = all.isEmpty(),
                    onPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onFiles = { files.launch(arrayOf("image/*", "application/octet-stream")) },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        StickerTile(
                            item = item,
                            repository = repository,
                            selecting = selecting,
                            checked = item.id in selected,
                            onToggleSelect = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                            onOpen = { editing = item },
                            onSelect = { selecting = true; selected = selected + item.id },
                            onEdit = { editing = item },
                            onShare = { StickerSending.share(ctx, item); notify("已打开分享") },
                            onFavorite = { repository.edit(item.id, item.name, item.group, item.tags, !item.favorite) { notify(it) } },
                            onDelete = { pendingDelete = setOf(item.id) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { item ->
        StickerEditorDialog(
            item = item, groups = groups,
            onDismiss = { editing = null },
            onSave = { name, group, tags ->
                repository.edit(item.id, name, group, tags, item.favorite) { notify(it) }
                editing = null
            },
        )
    }
    if (grouping) {
        GroupDialog(
            count = selected.size, groups = groups, onDismiss = { grouping = false },
            onSave = { group ->
                val ids = selected
                StickerRepository.io.execute {
                    runCatching { repository.store.group(ids, group) }
                    repository.changed()
                }
                grouping = false; leaveSelection(); notify(if (group.isBlank()) "已移出分组" else "已移入「$group」")
            },
        )
    }
    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            icon = { Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (ids.size == 1) "删除这张表情？" else "删除 ${ids.size} 张表情？") },
            text = { Text("会删除收纳袋中的原图，无法撤销。原应用中的图片不受影响。") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    if (deleting || pendingDelete != ids) return@TextButton
                    deleting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { repository.store.delete(ids) } }
                        repository.changed(); deleting = false; pendingDelete = null
                        if (result.isSuccess) { selected = selected - ids; notify("已删除 ${ids.size} 张"); if (selected.isEmpty()) selecting = false }
                        else notify("删除失败，请重试")
                    }
                }) { Text(if (deleting) "删除中…" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onChange,
        placeholder = { Text("搜索名称、标签或分组") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(painterResource(R.drawable.ic_close), "清除", Modifier.size(18.dp)) }
        },
        singleLine = true, shape = RoundedCornerShape(26.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SelectionAction(icon: Int, label: String, enabled: Boolean, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(24.dp),
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@Composable
private fun EmptyState(loadError: String?, nothingStored: Boolean, onPhotos: () -> Unit, onFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_sticker_bag), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            loadError ?: if (nothingStored) "还没有收纳表情" else "没有找到表情",
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (loadError != null) "请检查存储空间后重新打开" else if (nothingStored) "在聊天软件里把表情分享到「收纳到织文」，\n或从相册、文件里添加。原图和动图都会原样保留。"
            else "换个关键词，或切换上面的分组看看。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (nothingStored && loadError == null) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPhotos, shape = RoundedCornerShape(24.dp)) { Text("从相册添加") }
                FilledTonalButton(onClick = onFiles, shape = RoundedCornerShape(24.dp)) { Text("从文件添加") }
            }
        }
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
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .clip(shape)
            .background(if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .then(if (checked) Modifier.border(2.dp, primary, shape) else Modifier)
            .combinedClickable(onClick = { if (selecting) onToggleSelect() else onOpen() }, onLongClick = { if (selecting) onToggleSelect() else menu = true })
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) { StickerThumbnail(repository, item) }
            // 角标与键盘面板一致：左上动图、右上收藏，整理时右上换成选中圈。
            // Badges match the keyboard panel: animation top-left, favourite top-right, a check ring while selecting.
            if (item.animated) {
                Text(
                    "GIF", style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (checked) primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .border(1.5.dp, if (checked) primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else if (item.favorite) {
                Icon(painterResource(R.drawable.ic_star_filled), "已收藏", Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp), tint = FAVORITE)
            }
        }
        Text(
            item.name, style = MaterialTheme.typography.labelLarge,
            color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text(if (item.favorite) "取消收藏" else "收藏") },
            leadingIcon = { Icon(painterResource(if (item.favorite) R.drawable.ic_star_filled else R.drawable.ic_star), null, Modifier.size(20.dp)) },
            onClick = { menu = false; onFavorite() },
        )
        DropdownMenuItem(
            text = { Text("编辑") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(20.dp)) },
            onClick = { menu = false; onEdit() },
        )
        DropdownMenuItem(
            text = { Text("分享原图") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_share), null, Modifier.size(20.dp)) },
            onClick = { menu = false; onShare() },
        )
        DropdownMenuItem(
            text = { Text("多选") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_select_all), null, Modifier.size(20.dp)) },
            onClick = { menu = false; onSelect() },
        )
        DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
            onClick = { menu = false; onDelete() },
        )
    }
}

/** 收藏星标的颜色，与键盘面板一致。 Favourite star colour, shared with the keyboard panel. */
private val FAVORITE = androidx.compose.ui.graphics.Color(0xFFF5A623)

@Composable
private fun StickerThumbnail(repository: StickerRepository, item: Sticker) {
    var image by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) {
        val file = runCatching { repository.store.file(item) }.getOrNull() ?: return@LaunchedEffect
        image = StickerThumbs.load(file, item.id, 256)
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
private fun StickerEditorDialog(item: Sticker, groups: List<String>, onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var group by remember { mutableStateOf(item.group) }
    var tags by remember { mutableStateOf(item.tags.joinToString("，")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("编辑表情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(group, { group = it }, label = { Text("分组") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                GroupSuggestions(groups, group) { group = it }
                OutlinedTextField(tags, { tags = it }, label = { Text("标签，用逗号分隔") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, group, tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 批量分组：可以点已有分组，也可以输入新名称；留空表示移出分组。 Pick an existing group or type a new one; blank clears. */
@Composable
private fun GroupDialog(count: Int, groups: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var group by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("$count 张表情移入分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    group, { group = it }, label = { Text("分组名称，留空则移出分组") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                )
                GroupSuggestions(groups, group) { group = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(group.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSuggestions(groups: List<String>, current: String, onPick: (String) -> Unit) {
    if (groups.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { g ->
            FilterChip(selected = current == g, onClick = { onPick(g) }, label = { Text(g) }, shape = RoundedCornerShape(16.dp))
        }
    }
}

private fun collect(uris: List<Uri>, repository: StickerRepository, notify: (String) -> Unit) {
    if (uris.isNotEmpty()) repository.importInBackground(uris, notify)
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
