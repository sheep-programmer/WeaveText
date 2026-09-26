package com.weavetext.ime.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R

/** 设置页路由。 Settings routes. */
sealed class Route {
    data object Onboarding : Route()
    data object Home : Route()
    data object Schemes : Route()
    data object Fuzzy : Route()
    data object Voice : Route()
    data class VoiceDetail(val id: String) : Route()
    data object Models : Route()
    data object Look : Route()
    data object Styles : Route()
    data object StyleTweak : Route()
    data object Dictionary : Route()
    data object UserWords : Route()
    data object About : Route()
    data object Help : Route()
    data object Privacy : Route()
    data object Licenses : Route()

    companion object {
        /** 深链 weavetext://settings/<path>（03 §1）。 Deep link parsing. */
        fun fromPath(path: String?): List<Route> {
            val parts = path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
            return when (parts.firstOrNull()) {
                "voice" -> listOf(Voice) + (parts.getOrNull(1)?.let { listOf(VoiceDetail(it)) } ?: emptyList())
                "models" -> if (com.weavetext.ime.BuildConfig.LOCAL_ASR) listOf(Voice, Models) else listOf(Voice)
                "schemes" -> listOf(Schemes)
                "look" -> listOf(Look) + when (parts.getOrNull(1)) { "styles" -> listOf(Styles); else -> emptyList() }
                "dictionary" -> listOf(Dictionary)
                "about" -> listOf(About) + when (parts.getOrNull(1)) { "help" -> listOf(Help); "privacy" -> listOf(Privacy); else -> emptyList() }
                else -> emptyList()
            }
        }
    }
}

/** 简单的回退栈。 A tiny back stack. */
class Navigator(start: List<Route>) {
    val stack = mutableStateListOf<Route>().apply { addAll(start) }
    val current get() = stack.last()
    fun push(r: Route) { stack += r }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun replaceAll(r: Route) { stack.clear(); stack += r }
}

val LocalDeps = staticCompositionLocalOf<SettingsDeps> { error("SettingsDeps not provided") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/** 设置 App 根。 Settings app root. */
@Composable
fun SettingsApp(deps: SettingsDeps, nav: Navigator, statusVersion: Int = 0) {
    val p by rememberLivePrefs(deps.prefs)
    val theme = WeavePrefs.theme(p)
    val night = (deps.ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> night }
    WeaveSettingsTheme(dark) {
        CompositionLocalProvider(LocalDeps provides deps, LocalNav provides nav) {
            BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
            AnimatedContent(nav.current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { r ->
                when (r) {
                    Route.Onboarding -> OnboardingScreen(statusVersion)
                    Route.Home -> HomeScreen(statusVersion)
                    Route.Schemes -> SchemesScreen()
                    Route.Fuzzy -> FuzzyScreen()
                    Route.Voice -> VoiceListScreen()
                    is Route.VoiceDetail -> VoiceDetailScreen(r.id)
                    Route.Models -> ModelsScreen()
                    Route.Look -> LookScreen()
                    Route.Styles -> StylesScreen()
                    Route.StyleTweak -> StyleTweakScreen()
                    Route.Dictionary -> DictionaryScreen()
                    Route.UserWords -> UserWordsScreen()
                    Route.About -> AboutScreen()
                    Route.Help -> HelpScreen()
                    Route.Privacy -> PrivacyScreen()
                    Route.Licenses -> LicensesScreen()
                }
            }
        }
    }
}

/** 二级页骨架：TopAppBar + 返回 + 可滚动内容。 Sub-page scaffold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(
    title: String,
    snackbar: SnackbarHostState? = null,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) { Icon(painterResource(R.drawable.ic_arrow_back), "返回") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).contentWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }.padding(bottom = 24.dp),
            content = content,
        )
    }
}

/**
 * 设置 App 入口（03）：未完成启用时显示三步引导，否则首页；支持深链 weavetext://settings/...。
 * Settings entry: onboarding until the IME is enabled and default, then home; supports deep links.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var deps: SettingsDeps
    private lateinit var nav: Navigator
    private var statusVersion by mutableIntStateOf(0)
    private val imeChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { statusVersion++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deps = SettingsDeps(this)
        val st = deps.status()
        val done = WeavePrefs.of(this).getBoolean(WeavePrefs.ONBOARDING_DONE, false)
        val start = if (!done && !(st.enabled && st.isDefault)) listOf<Route>(Route.Onboarding) else listOf(Route.Home)
        nav = Navigator(start + Route.fromPath(intent?.data?.path))
        registerReceiver(imeChanged, IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED))
        setContent { SettingsApp(deps, nav, statusVersion) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val extra = Route.fromPath(intent.data?.path)
        if (extra.isNotEmpty()) { nav.replaceAll(Route.Home); extra.forEach(nav::push) }
    }

    override fun onResume() {
        super.onResume()
        statusVersion++
    }

    override fun onDestroy() {
        unregisterReceiver(imeChanged)
        super.onDestroy()
    }
}
