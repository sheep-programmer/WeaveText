package com.weavetext.ime.settings

import com.weavetext.ime.extensions.Extensions
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R

/** 织文标（ic_logo 放在主色圆底上）。 Brand mark. */
@Composable
fun BrandLogo(size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_logo), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size * 0.62f).dp))
    }
}

// ------------------------------------------------------------------ onboarding (03 §3)

@Composable
fun OnboardingScreen(statusVersion: Int) {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val status = remember(statusVersion) { deps.status() }
    var micAsked by rememberSaveable { mutableIntStateOf(0) }
    var skippedMic by rememberSaveable { mutableStateOf(deps.prefs.getBoolean(WeavePrefs.MIC_SKIPPED, false)) }
    var micGranted by remember(statusVersion) { mutableStateOf(status.micGranted) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micGranted = ok; micAsked++ }
    val step1 = status.enabled
    val step2 = status.isDefault
    val step3 = micGranted || skippedMic
    val current = when { !step1 -> 1; !step2 -> 2; !step3 -> 3; else -> 0 }
    val finish = {
        deps.prefs.edit().putBoolean(WeavePrefs.ONBOARDING_DONE, true).apply()
        nav.replaceAll(Route.Home)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).contentWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                BrandLogo(56)
                Column {
                    Text("织文输入法", style = MaterialTheme.typography.headlineSmall)
                    Text("三步开始使用 · Get started", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            StepCard(
                1, "启用织文输入法", "在系统设置中打开「织文输入法」的开关。",
                "系统会提示输入法可能收集输入内容。织文所有输入均在本机处理，仅语音会发送至你选择的语音服务商。",
                done = step1, active = current == 1, action = "去启用",
            ) { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            StepCard(
                2, "切换为默认输入法", "选择「织文输入法」。", null,
                done = step2, active = current == 2, action = "选择输入法",
            ) { ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
            val permanentlyDenied = micAsked > 0 && !micGranted &&
                (ctx as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            StepCard(
                3, "允许使用麦克风（可选）", "用于语音输入。", null,
                done = micGranted, active = current == 3, action = if (permanentlyDenied) "去系统设置" else "允许",
                secondary = "跳过", onSecondary = { skippedMic = true; deps.prefs.edit().putBoolean(WeavePrefs.MIC_SKIPPED, true).apply() },
            ) {
                if (permanentlyDenied) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            if (step2) {
                var t by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), placeholder = { Text("在这里试试输入…") }, shape = RoundedCornerShape(12.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = finish) { Text("跳过") }
                Button(onClick = finish, enabled = step1 && step2) { Text("完成") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepCard(
    n: Int, title: String, desc: String, note: String?, done: Boolean, active: Boolean, action: String,
    secondary: String? = null, onSecondary: () -> Unit = {}, onAction: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val success = LocalSuccess.current
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        border = if (active) BorderStroke(1.5.dp, cs.primary.copy(alpha = 0.45f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(
                        when { done -> success; active -> cs.primary; else -> cs.surfaceContainerHigh },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
                    else Text("$n", color = if (active) cs.onPrimary else cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium,
                        color = if (done || active) cs.onSurface else cs.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (!done) Text(desc, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            if (active) {
                if (note != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 42.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary) }
                    Button(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ home (03 §4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(statusVersion: Int) {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val p by rememberLivePrefs(deps.prefs)
    val status = remember(statusVersion) { deps.status() }
    val cs = MaterialTheme.colorScheme
    Scaffold(containerColor = cs.background, topBar = {
        androidx.compose.material3.TopAppBar(title = { Text("织文") }, actions = {
            TextButton({ nav.push(Route.About) }) { Text("关于") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).contentWidth().imePadding().verticalScroll(rememberScrollState())) {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = cs.primaryContainer) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BrandLogo(48)
                        Column {
                            Text("让输入顺手一点", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(if (status.enabled && status.isDefault) "已就绪 · 当前默认输入法" else "完成设置，开始使用织文", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!status.enabled || !status.isDefault) {
                        FilledTonalButton({
                            if (!status.enabled) ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            else ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                        }) { Text(if (!status.enabled) "启用输入法" else "切换到织文") }
                    }
                    var text by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("在这里试试输入…") }, shape = RoundedCornerShape(16.dp), maxLines = 3)
                }
            }
            GroupTitle("你的键盘")
            GroupCard {
                SettingRow("输入方案", schemeSummary(p), R.drawable.ic_keyboard, onClick = { nav.push(Route.Schemes) }) { Chevron() }
                RowDivider()
                SettingRow("外观与手感", "主题、布局与按键反馈", R.drawable.ic_theme, onClick = { nav.push(Route.Look) }) { Chevron() }
                RowDivider()
                SettingRow("词库与资料", "用户词、学习记录与备份", R.drawable.ic_book, onClick = { nav.push(Route.Dictionary) }) { Chevron() }
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp), color = cs.secondaryContainer,
            ) {
                SettingRow("插件市场", "添加功能，搭配主题与布局", R.drawable.ic_toolbox, onClick = { nav.push(Route.Market()) }) { Chevron() }
            }
            val voice = Extensions.feature(p, "voice")
            val translate = Extensions.feature(p, "translate")
            val stickers = Extensions.feature(p, "stickers")
            val link = Extensions.feature(p, "link")
            if (voice || translate || stickers || link) {
                GroupTitle("已添加的工具")
                GroupCard {
                    if (voice) SettingRow("语音输入", "引擎与识别设置", R.drawable.ic_waveform, onClick = { nav.push(Route.Voice) }) { Chevron() }
                    if (translate) SettingRow("翻译", "服务与离线翻译插件", R.drawable.ic_globe, onClick = { nav.push(Route.Translation) }) { Chevron() }
                    if (stickers) SettingRow("表情收纳袋", "收藏与整理图片表情", R.drawable.ic_sticker_bag, onClick = { ctx.startActivity(Intent(ctx, com.weavetext.ime.stickers.StickerActivity::class.java)) }) { Chevron() }
                    if (link) SettingRow("织文互联", "与电脑互传文字和文件", R.drawable.ic_devices, onClick = { nav.push(Route.Link) }) { Chevron() }
                }
            }
            Text("按你的习惯，组合自己的键盘", Modifier.padding(24.dp).fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
