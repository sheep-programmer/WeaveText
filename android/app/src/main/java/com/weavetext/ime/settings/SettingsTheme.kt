package com.weavetext.ime.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R

/** 设置 App 配色（01 §2.3），不使用动态取色。 Fixed colour schemes; no dynamic colour. */
object SettingsColors {
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF2E6CF6), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE6FD), onPrimaryContainer = Color(0xFF0B2A6B),
        secondaryContainer = Color(0xFFDCE6FD), onSecondaryContainer = Color(0xFF0B2A6B),
        background = Color(0xFFF5F6F8), surface = Color(0xFFF5F6F8),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F2F5),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFECEFF3), surfaceContainerHighest = Color(0xFFE6E9EE),
        onSurface = Color(0xFF1B1E23), onBackground = Color(0xFF1B1E23), onSurfaceVariant = Color(0xFF5E6570),
        outline = Color(0xFFC4CAD3), outlineVariant = Color(0xFFE3E6EB),
        error = Color(0xFFD93A3F), errorContainer = Color(0xFFFDE3E3), onErrorContainer = Color(0xFF6B0F12),
    )
    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFF8AAEFF), onPrimary = Color(0xFF0A2566),
        primaryContainer = Color(0xFF1F3A7A), onPrimaryContainer = Color(0xFFDCE6FD),
        secondaryContainer = Color(0xFF1F3A7A), onSecondaryContainer = Color(0xFFDCE6FD),
        background = Color(0xFF121315), surface = Color(0xFF121315),
        surfaceContainerLowest = Color(0xFF0D0E10), surfaceContainerLow = Color(0xFF17181B),
        surfaceContainer = Color(0xFF1E2024), surfaceContainerHigh = Color(0xFF26282D), surfaceContainerHighest = Color(0xFF2D2F34),
        onSurface = Color(0xFFE9EBEF), onBackground = Color(0xFFE9EBEF), onSurfaceVariant = Color(0xFFA2A8B2),
        outline = Color(0xFF4A4E56), outlineVariant = Color(0xFF2A2C31),
        error = Color(0xFFFF8A8D), errorContainer = Color(0xFF4A1C1E), onErrorContainer = Color(0xFFFFDADA),
    )
    val SuccessLight = Color(0xFF1F9D55)
    val SuccessDark = Color(0xFF4CC38A)
}

/** 自定义「成功」色。 Custom success colour. */
val LocalSuccess = staticCompositionLocalOf { SettingsColors.SuccessLight }

@Composable
fun WeaveSettingsTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSuccess provides if (dark) SettingsColors.SuccessDark else SettingsColors.SuccessLight) {
        MaterialTheme(colorScheme = if (dark) SettingsColors.Dark else SettingsColors.Light, content = content)
    }
}

// ------------------------------------------------------------------ shared components (03 §2)

/** 宽屏（> 600dp）时内容列居中、最宽 640dp。 Centre a 640dp max content column on wide screens. */
fun Modifier.contentWidth(): Modifier = wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()

/** 组与组之间（无标题时）的固定间距。 Fixed gap between groups without a title. */
val GroupGap = 24.dp

/**
 * 组标题：左缩进 24dp（卡片左缘 +8dp），上 20dp、下 8dp。
 * Group title: 24dp indent (card edge + 8dp), 20dp above, 8dp below.
 */
@Composable
fun GroupTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** 分组卡片：surfaceContainer，圆角 16dp，左右 16dp。 Group card. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) { Column(content = content) }
}

/** 卡片内分割线（有图标时左缩进 56dp）。 Divider inside a card. */
@Composable
fun RowDivider(indent: Boolean = true) {
    HorizontalDivider(
        thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = if (indent) 56.dp else 16.dp),
    )
}

/**
 * 列表项：单行 56dp / 双行 72dp；副标题允许多行（插件、权限说明等）时最小 80dp。
 * leading 图标 24dp；trailing 为任意内容；标题与副标题间距 2dp。
 * List row: 56dp single-line / 72dp two-line / 80dp when the subtitle may wrap; 2dp title gap.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: Int = 0,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    subtitleMaxLines: Int = 1,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    // 0.7：禁用的副标题仍 ≥ 3:1。 Keeps disabled subtitles at ≥ 3:1.
    val alpha = if (enabled) 1f else 0.7f
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = if (subtitle == null) 56.dp else if (subtitleMaxLines > 1) 80.dp else 72.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != 0) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = (if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor).copy(alpha = alpha),
            )
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) Box(contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

@Composable
fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
}

@Composable
fun ValueChevron(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Chevron()
    }
}

@Composable
fun SwitchRow(
    title: String, subtitle: String? = null, checked: Boolean, icon: Int = 0, subtitleMaxLines: Int = 1, onChange: (Boolean) -> Unit,
) {
    SettingRow(title, subtitle, icon, subtitleMaxLines = subtitleMaxLines, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 字母头像（插件缺图标时）。 Letter avatar for plugins without an icon. */
@Composable
fun LetterAvatar(name: String, size: Int, radius: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(radius.dp), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull()?.toString() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold, style = if (size >= 56) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
        }
    }
}
