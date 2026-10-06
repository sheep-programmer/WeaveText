package com.weavetext.ime.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.weavetext.ime.plugins.GitHubCatalog
import com.weavetext.ime.plugins.GitHubPlugin
import com.weavetext.ime.plugins.GitHubRepository
import com.weavetext.ime.plugins.GitHubRepositorySummary
import com.weavetext.ime.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private data class RepositoryRowState(
    val summary: GitHubRepositorySummary? = null,
    val catalog: GitHubCatalog? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
fun PluginRepositoriesScreen() {
    val deps = LocalDeps.current
    val ctx = LocalContext.current
    val service = remember { deps.pluginRepositories() }
    val engines = remember { deps.engines() }
    val importer = rememberPluginImporter(engines) {}
    val scope = rememberCoroutineScope()
    val slots = remember { Semaphore(2) }
    var account by remember { mutableStateOf(service.credentials.login) }
    var repos by remember { mutableStateOf(service.saved()) }
    val rows = remember { mutableStateMapOf<String, RepositoryRowState>() }
    var accountGeneration by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") } // Never saved to a Bundle or ordinary preferences.
    var repoInput by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var repositoryError by remember { mutableStateOf<String?>(null) }

    fun load(repo: GitHubRepository) {
        val previous = rows[repo.key] ?: RepositoryRowState(service.summary(repo))
        if (previous.loading) return
        rows[repo.key] = previous.copy(loading = true, error = null)
        val generation = accountGeneration
        scope.launch {
            try {
                val result = slots.withPermit { withContext(Dispatchers.IO) { service.catalog(repo) } }
                if (generation == accountGeneration && repos.any { it.key == repo.key }) rows[repo.key] = RepositoryRowState(
                    GitHubRepositorySummary(result.plugins.size, result.isPrivate), result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == accountGeneration && repos.any { it.key == repo.key }) rows[repo.key] = previous.copy(error = e.message ?: "读取仓库失败")
            }
        }
    }

    LaunchedEffect(service, account) {
        accountGeneration++
        rows.clear()
        repos.forEach { repo -> rows[repo.key] = RepositoryRowState(service.summary(repo)); load(repo) }
    }

    // Credentials stay out of screenshots and the app switcher while the authorization dialog is open.
    DisposableEffect(signingIn) {
        val activity = activity(ctx)
        val alreadySecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } == true
        if (signingIn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (signingIn && !alreadySecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    SubPage("插件仓库") {
        GroupCard(Modifier.padding(top = 12.dp)) {
            SettingRow(account?.let { "GitHub · $it" } ?: "登录 GitHub",
                if (account == null) "连接账号以访问私有仓库" else "已授权 · 凭据加密保存在本机",
                icon = R.drawable.ic_lock, onClick = { if (busy == null) signingIn = true }) {
                if (account == null) Chevron() else TextButton(enabled = busy == null, onClick = {
                    runCatching { service.logout() }.onSuccess { account = null }.onFailure { error = "退出登录失败，请重试" }
                }) { Text("退出") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("我的仓库", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("${repos.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = busy == null, onClick = { repoInput = ""; branch = ""; path = ""; repositoryError = null; adding = true }) {
                Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp)); Text("添加仓库")
            }
        }
        if (repos.isEmpty()) GroupCard {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_repository), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Text("还没有插件仓库", style = MaterialTheme.typography.titleMedium)
                Text("添加 GitHub 仓库，展开查看并导入插件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { repoInput = ""; branch = ""; path = ""; repositoryError = null; adding = true }) { Text("加入第一个仓库") }
            }
        }
        repos.forEach { repo -> key(repo.key) {
            val state = rows[repo.key] ?: RepositoryRowState(service.summary(repo))
            RepositoryCard(repo, state, repo.key in expanded, busy == null,
                onToggle = {
                    expanded = if (repo.key in expanded) expanded - repo.key else expanded + repo.key
                    if (repo.key in expanded && state.catalog == null && !state.loading) load(repo)
                }, onRefresh = { load(repo) }, onRemove = {
                    service.remove(repo); repos = service.saved(); rows.remove(repo.key); expanded = expanded - repo.key
                }, onOpen = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${repo.fullName}"))) },
                onImport = { plugin -> state.catalog?.let { c ->
                    if (busy == null) {
                        busy = "正在下载 ${pluginTitle(plugin, repo)}…"; error = null
                        scope.launch {
                            var file: File? = null
                            try {
                                val downloaded = withContext(Dispatchers.IO) { service.download(c, plugin).also { file = it } }
                                importer.importFile(downloaded)
                                file = null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "插件下载失败" }
                            finally { file?.delete(); busy = null }
                        }
                    }
                } })
            Spacer(Modifier.height(12.dp))
        } }
        busy?.let {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        Text("按内容识别插件压缩包和源码目录，不限制文件后缀。公开仓库可直接查看，私有仓库需 GitHub 授权。",
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("加入 GitHub 仓库") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(repoInput, { repoInput = it; repositoryError = null }, label = { Text("GitHub 仓库地址") },
                placeholder = { Text("https://github.com/owner/repository") },
                supportingText = { Text("粘贴完整仓库、目录或插件包链接，也支持 owner/repository") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = repositoryError != null,
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_repository_url"))
            OutlinedTextField(branch, { branch = it; repositoryError = null }, label = { Text("分支或标签（可选）") },
                supportingText = { Text("留空时从链接识别，仓库首页使用默认分支") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_repository_ref"))
            OutlinedTextField(path, { path = it; repositoryError = null }, label = { Text("插件目录或文件路径（可选）") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_repository_path"))
            val parsed = remember(repoInput, branch, path) { runCatching { GitHubRepository.parse(repoInput, branch, path) }.getOrNull() }
            if (parsed != null) {
                Text("仓库：${parsed.fullName}", style = MaterialTheme.typography.bodySmall)
                Text("分支：${parsed.ref.ifEmpty { "默认分支" }} · 目录：${parsed.path.ifEmpty { "仓库根目录" }}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            repositoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = repoInput.isNotBlank(), onClick = {
        runCatching {
            val repo = GitHubRepository.parse(repoInput, branch, path)
            service.save(repo); repos = service.saved()
            expanded = (expanded + repo.key).distinct()
            adding = false; error = null; load(repo)
        }.onFailure { repositoryError = it.message ?: "仓库地址无效，请检查后重试" }
    }) { Text("加入") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })

    if (signingIn) AlertDialog(onDismissRequest = { if (busy == null) { signingIn = false; token = "" } }, title = { Text("登录 GitHub") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("创建 Fine-grained 访问令牌，只选择需要导入的仓库，授予 Contents: Read-only 权限。")
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new"))) }) { Text("在 GitHub 创建只读令牌") }
            OutlinedTextField(token, { token = it }, label = { Text("GitHub 访问令牌") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Text("授权信息加密保存在本机，退出登录会清除。")
            if (busy != null) CircularProgressIndicator()
        }
    }, confirmButton = { TextButton(enabled = token.isNotBlank() && busy == null, onClick = {
        val input = token
        busy = "正在验证 GitHub 授权…"; error = null
        scope.launch {
            try {
                account = withContext(Dispatchers.IO) { service.login(input) }
                token = ""; signingIn = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "GitHub 授权失败"; signingIn = false; token = "" }
            finally { busy = null }
        }
    }) { Text("登录") } }, dismissButton = { TextButton(enabled = busy == null, onClick = { signingIn = false; token = "" }) { Text("取消") } })
    importer.Sheet()
}

@Composable
private fun RepositoryCard(repo: GitHubRepository, state: RepositoryRowState, expanded: Boolean, enabled: Boolean,
    onToggle: () -> Unit, onRefresh: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit,
    onImport: (GitHubPlugin) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "repository chevron")
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer,
        border = if (expanded) BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f)) else null,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().animateContentSize()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                onClickLabel = if (expanded) "收起仓库" else "展开仓库", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = colors.primaryContainer.copy(alpha = 0.6f), modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_repository), null, tint = colors.primary, modifier = Modifier.size(23.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(repo.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${repo.owner} · ${repo.ref.ifEmpty { "默认分支" }}", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(8.dp), color = colors.primaryContainer) {
                        Text(state.summary?.let { "${it.pluginCount} 项插件" } ?: if (state.loading) "读取中" else "待读取",
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                    }
                    if (state.error != null) Text("读取失败", style = MaterialTheme.typography.labelSmall, color = colors.error)
                    else state.summary?.let { Text(if (it.isPrivate) "私有" else "公开", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                }
                Icon(painterResource(R.drawable.ic_chevron_down), null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(angle))
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = colors.outlineVariant)
                if (repo.path.isNotEmpty()) Text("目录 · ${repo.path}", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (state.loading) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在更新插件列表…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (state.error != null) Text(state.error, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = colors.error)
                val catalog = state.catalog
                if (catalog != null) {
                    if (catalog.plugins.isEmpty()) Text("这个仓库还没有可导入的插件", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    catalog.plugins.forEachIndexed { i, plugin ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp), thickness = 0.5.dp, color = colors.outlineVariant)
                        RepositoryPluginRow(repo, plugin, enabled && !state.loading, onImport)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !state.loading, onClick = onRefresh) {
                        Icon(painterResource(R.drawable.ic_update), null, Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp)); Text(if (state.error != null) "重试" else "刷新")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), "管理 ${repo.fullName}", tint = colors.onSurfaceVariant) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("在 GitHub 中打开") }, onClick = { menu = false; onOpen() })
                            DropdownMenuItem(text = { Text("移除仓库", color = colors.error) }, enabled = enabled, onClick = { menu = false; onRemove() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryPluginRow(repo: GitHubRepository, plugin: GitHubPlugin, enabled: Boolean, onImport: (GitHubPlugin) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHigh, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_waveform), null, tint = colors.primary, modifier = Modifier.size(18.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pluginTitle(plugin, repo), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when (plugin) {
                is GitHubPlugin.Release -> "发布包 · ${plugin.tag}"
                is GitHubPlugin.Package -> "插件包 · ${plugin.blob.path}"
                is GitHubPlugin.Source -> "源码目录 · ${plugin.prefix.ifEmpty { "仓库根目录" }}"
            }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(enabled = enabled, onClick = { onImport(plugin) }) { Text("导入", style = MaterialTheme.typography.labelLarge) }
    }
}

private fun pluginTitle(plugin: GitHubPlugin, repo: GitHubRepository): String = when (plugin) {
    is GitHubPlugin.Release -> plugin.name.substringBeforeLast('.', plugin.name)
    is GitHubPlugin.Package -> plugin.blob.path.substringAfterLast('/').substringBeforeLast('.')
    is GitHubPlugin.Source -> plugin.prefix.substringAfterLast('/').ifEmpty { repo.name }
}

private fun activity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> activity(context.baseContext)
    else -> null
}
