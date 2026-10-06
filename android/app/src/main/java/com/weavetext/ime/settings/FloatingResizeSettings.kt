package com.weavetext.ime.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.weavetext.ime.ui.keyboard.FloatingResizeCorner
import com.weavetext.ime.ui.keyboard.FloatingResizePolicy

/**
 * Owns only this new key in the supplied SharedPreferences (normally WeavePrefs.of(context)).
 * The IME listener can match KEY_CORNERS and read() without modifying WeavePrefs. No geometry,
 * orientation-specific size/position, floating-mode or unrelated settings are touched.
 */
object FloatingResizeSettings {
    const val KEY_CORNERS = "floating_resize_corners"
    const val DEFAULT_ALL = FloatingResizePolicy.DEFAULT_ALL

    /** Missing/empty/corrupt data falls back to all four corners; unknown future ids are ignored. */
    fun read(prefs: SharedPreferences): FloatingResizePolicy {
        val ids = try { prefs.getStringSet(KEY_CORNERS, null) } catch (_: ClassCastException) { null }
        val corners = FloatingResizeCorner.entries.filter { it.id in ids.orEmpty() }.toSet()
        return if (corners.isEmpty()) FloatingResizePolicy() else FloatingResizePolicy(corners)
    }

    fun write(prefs: SharedPreferences, policy: FloatingResizePolicy) {
        prefs.edit().putStringSet(KEY_CORNERS, policy.corners.map { it.id }.toSet()).apply()
    }

    /** Convenience overload also rejects an empty selection before changing preferences. */
    fun write(prefs: SharedPreferences, corners: Set<FloatingResizeCorner>) = write(prefs, FloatingResizePolicy(corners))

    fun cornerMask(prefs: SharedPreferences) = read(prefs).cornerMask
    fun writeMask(prefs: SharedPreferences, mask: Int) = write(prefs, FloatingResizePolicy.fromMask(mask))

    fun reset(prefs: SharedPreferences) { prefs.edit().remove(KEY_CORNERS).apply() }
}

/**
 * Standalone insertion in LookScreen: FloatingResizeSettingsCard(p). Each row is a single checkbox
 * accessibility node, >=56dp high; the last selected row is disabled. Reads its own live prefs so
 * toggles and external preference changes update immediately, without reopening the settings page.
 * UI integration: use R.drawable.ic_close with FloatingResizeAccessibility.CLOSE_DESCRIPTION for
 * a separate close button, and FloatingGeometry.MIN_TOUCH_TARGET_DP (48dp) for corner/close targets.
 */
@Composable
fun FloatingResizeSettingsCard(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    val live by rememberLivePrefs(prefs)
    val policy = FloatingResizeSettings.read(live)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupTitle("悬浮键盘缩放")
        Text(
            "默认四角都能调整大小；可选择任意角，至少保留一个。",
            modifier = Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GroupCard {
            FloatingResizeCorner.entries.forEachIndexed { index, corner ->
                val checked = policy.allows(corner)
                val enabled = !checked || policy.corners.size > 1
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(
                            value = checked, enabled = enabled, role = Role.Checkbox,
                            onValueChange = { selected ->
                                // Read the latest value, even if another settings writer changed it.
                                FloatingResizeSettings.write(prefs, FloatingResizeSettings.read(prefs).withCorner(corner, selected))
                            },
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
                    Text(corner.label, style = MaterialTheme.typography.bodyLarge)
                }
                if (index < FloatingResizeCorner.entries.lastIndex) RowDivider(false)
            }
        }
    }
}
