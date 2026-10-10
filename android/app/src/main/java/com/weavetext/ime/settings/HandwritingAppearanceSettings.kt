package com.weavetext.ime.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import com.weavetext.ime.style.StyleRepository
import com.weavetext.ime.ui.keyboard.HandInkAppearance
import com.weavetext.ime.ui.keyboard.HandInkPrefs
import com.weavetext.ime.ui.keyboard.HandInkStyle
import com.weavetext.ime.ui.keyboard.HandPad
import com.weavetext.ime.ui.keyboard.HandwritingAreaMode
import kotlin.math.roundToInt

/** Insert in an existing settings page. Every scope that reads prefs owns its live subscription. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandwritingAppearanceSettings(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    val live by rememberLivePrefs(prefs)
    val appearance = HandInkPrefs.read(live)
    Column(modifier) {
        GroupTitle("书写区域")
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("区域高度", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HandwritingAreaMode.entries.forEach { mode ->
                        FilterChip(
                            selected = WeavePrefs.handAreaMode(live) == mode,
                            onClick = { prefs.edit().putString(WeavePrefs.HAND_AREA_MODE, mode.key).apply() },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag("handwriting_area_${mode.key}"),
                        )
                    }
                }
                Text(
                    "半屏和全屏按当前窗口的可用区域扩展书写区，避开状态栏与导航按钮。切换输入方案后恢复普通高度；悬浮键盘、实体键盘和私密输入框不扩展。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GroupTitle("手写笔迹")
        GroupCard {
            HandwritingInkPreview(prefs, Modifier.padding(16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("笔锋", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HandInkStyle.entries.forEach { style ->
                        FilterChip(
                            selected = appearance.style == style,
                            onClick = { HandInkPrefs.setStyle(prefs, style) },
                            label = { Text(style.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("宽度", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${appearance.widthDp.roundToInt()} dp", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = appearance.widthDp,
                    onValueChange = { HandInkPrefs.setWidthDp(prefs, it.roundToInt().toFloat()) },
                    valueRange = HandInkPrefs.MIN_WIDTH_DP..HandInkPrefs.MAX_WIDTH_DP,
                    steps = 9,
                    modifier = Modifier.testTag("handwriting_ink_width").semantics { contentDescription = "笔迹宽度" },
                )
                Text("颜色", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HandInkPrefs.colors.forEach { color ->
                        FilterChip(
                            selected = appearance.color == color.value,
                            onClick = { HandInkPrefs.setColor(prefs, color.value) },
                            label = { Text(color.label) },
                            leadingIcon = if (color.value == HandInkPrefs.THEME_COLOR) null else ({
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(16.dp).background(Color(HandInkPrefs.parseColor(color.value)!!), CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                )
                            }),
                        )
                    }
                }
                var custom by rememberSaveable(appearance.color) {
                    mutableStateOf(if (appearance.color == HandInkPrefs.THEME_COLOR) "" else appearance.color)
                }
                val invalid = custom.isNotEmpty() && HandInkPrefs.parseColor(custom) == null
                OutlinedTextField(
                    value = custom,
                    onValueChange = {
                        custom = it
                        // A complete valid RGB value applies immediately; partial/invalid edits keep the old ink.
                        if (HandInkPrefs.parseColor(it) != null) HandInkPrefs.setColor(prefs, it)
                    },
                    label = { Text("自定义颜色") },
                    placeholder = { Text("#3366CC") },
                    supportingText = { Text(if (invalid) "请输入 #RRGGBB 格式（6 位十六进制）" else "#RRGGBB，例如 #3366CC") },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("handwriting_ink_custom_color"),
                )
            }
        }
    }
}

/** Real HandPad outlines, with the current keyboard palette, rather than a font/approximation. */
@Composable
fun HandwritingInkPreview(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    val live by rememberLivePrefs(prefs)
    val appearance = HandInkPrefs.read(live)
    val palette = StyleRepository.get(LocalContext.current).resolve(LocalContext.current, live).palette
    val density = LocalDensity.current.density
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("真实笔迹预览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(
            factory = { HandwritingPreviewView(it) },
            update = { it.configure(appearance, density, palette.label, palette.key, palette.divider) },
            modifier = Modifier.fillMaxWidth().height(144.dp).testTag("handwriting_ink_preview")
                .semantics {
                    contentDescription = "手写笔迹预览"
                    stateDescription = "${appearance.style.label} · ${appearance.widthDp.roundToInt()} dp · ${HandInkPrefs.colorLabel(appearance.color)}"
                },
        )
    }
}

private class HandwritingPreviewView(context: Context) : View(context) {
    private val pad = HandPad()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG)
    private var themeInk = 0
    private var paper = 0
    private var guideColor = 0

    fun configure(appearance: HandInkAppearance, density: Float, ink: Int, background: Int, divider: Int) {
        val densityChanged = pad.density != density
        pad.configure(appearance, density)
        themeInk = ink
        paper = background
        guideColor = divider
        if (densityChanged && width > 0 && height > 0) seed()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { seed() }

    private fun seed() {
        pad.reset()
        val margin = 16f * pad.density
        val size = minOf(width - 2f * margin, height - 2f * margin).coerceAtLeast(1f)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        pad.rect.set(left, top, left + size, top + size)
        // Four recorded geometric strokes for 文. Interpolation adds genuine samples before rendering.
        for (stroke in EXAMPLE) {
            var time = 0L
            pad.begin(stroke[0] * size, stroke[1] * size, time)
            for (i in 2 until stroke.size step 2) {
                for (sample in 1..8) {
                    val fraction = sample / 8f
                    val x = stroke[i - 2] + (stroke[i] - stroke[i - 2]) * fraction
                    val y = stroke[i - 1] + (stroke[i + 1] - stroke[i - 1]) * fraction
                    time += 5L
                    pad.add(x * size, y * size, time)
                }
            }
            pad.end(time + 5L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paper)
        guide.color = guideColor
        guide.strokeWidth = pad.density
        val r = pad.rect
        canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), guide)
        canvas.drawLine(r.centerX(), r.top, r.centerX(), r.bottom, guide)
        canvas.save()
        canvas.translate(r.left, r.top)
        pad.drawInk(canvas, paint, themeInk)
        canvas.restore()
    }

    companion object {
        private val EXAMPLE = arrayOf(
            floatArrayOf(0.45f, 0.10f, 0.53f, 0.19f),
            floatArrayOf(0.20f, 0.32f, 0.50f, 0.31f, 0.85f, 0.30f),
            floatArrayOf(0.68f, 0.32f, 0.64f, 0.50f, 0.54f, 0.64f, 0.36f, 0.80f, 0.20f, 0.86f),
            floatArrayOf(0.36f, 0.41f, 0.47f, 0.61f, 0.68f, 0.80f, 0.84f, 0.87f),
        )
    }
}
