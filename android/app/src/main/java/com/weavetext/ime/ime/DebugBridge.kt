package com.weavetext.ime.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.weavetext.ime.BuildConfig

/**
 * 仅调试版：让脚本通过 `adb shell am broadcast` 驱动输入法做真机/模拟器冒烟测试。
 * Debug builds only: lets scripts drive the IME through `adb shell am broadcast` for smoke tests.
 *
 * ```
 * adb shell am broadcast -a com.weavetext.ime.debug.KEYS --es keys "nihao{space}"
 * ```
 * 记号 / Tokens: `{space}` `{enter}` `{bs}` `{sel:N}` `{py:N}` `{schema:KEY}` `{opt:KEY=true}` `{toggle}`；其余字符逐个按下。
 * 组合串编辑 / Composition editing: `{left}` `{right}` `{home}` `{end}` `{del}`，以词取字 `{first:N}` `{last:N}`；按文字选词 `{pick:TEXT}`；
 * 排序策略 `{policy:TEXT:MODE}`（MODE 为 pin、down 或留空恢复）；
 * 笔画筛选键 `{stroke:h}`（h s p n z，与展开网格下的笔画键相同）。 Stroke filter keys `{stroke:h}`, like the grid's stroke row.
 * 语音回调 / Voice callbacks: `{voice-partial:TEXT}` `{voice-final:TEXT}` `{voice-replace:OLD|NEW}` `{voice-cancel}`。
 * 每批按键后在 logcat（tag `WeaveSmoke`）输出按键数、平均/最大单键耗时和当前候选前 5 个。
 */
class DebugBridge(
    private val controller: InputController,
    /** 可开面板的键盘（仅调试版）；为 null 时面板令牌被忽略。 The keyboard that can open panels (debug only); null ignores panel tokens. */
    private val ui: (() -> com.weavetext.ime.ui.keyboard.WeaveKeyboard?)? = null,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val keys = intent.getStringExtra("keys") ?: return
            run(keys)
        }
    }

    fun register(ctx: Context) {
        if (!BuildConfig.DEBUG) return
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
    }

    fun unregister(ctx: Context) {
        if (BuildConfig.DEBUG) runCatching { ctx.unregisterReceiver(receiver) }
    }

    private fun run(keys: String) {
        val times = mutableListOf<Long>()
        var i = 0
        while (i < keys.length) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            if (keys[i] == '{') {
                val end = keys.indexOf('}', i)
                if (end < 0) break
                token(keys.substring(i + 1, end))
                i = end + 1
            } else {
                controller.onChar(keys.codePointAt(i))
                i += Character.charCount(keys.codePointAt(i))
            }
            times += SystemClock.elapsedRealtimeNanos() - t0
        }
        val s = controller.state
        val avg = if (times.isEmpty()) 0.0 else times.average() / 1e6
        val max = (times.maxOrNull() ?: 0L) / 1e6
        Log.i(
            TAG,
            "keys=${times.size} avg_ms=${"%.2f".format(avg)} max_ms=${"%.2f".format(max)} " +
                "schema=${s.schema} chinese=${s.chinese} preedit=[${s.preedit}] caret=${s.preeditCaret} " +
                "cands=${s.candidates.take(5).joinToString("|") { it.text }}",
        )
    }

    private fun token(t: String) {
        when {
            t == "space" -> controller.onSpace()
            t == "enter" -> controller.onEnter()
            t == "bs" -> controller.onBackspace()
            t == "toggle" -> controller.toggleChinese()
            t == "left" -> controller.moveComposingCaret(-1)
            t == "right" -> controller.moveComposingCaret(1)
            t == "home" -> controller.placeComposingCaret(0)
            t == "end" -> controller.placeComposingCaret(Int.MAX_VALUE)
            t == "del" -> controller.deleteComposingForward()
            t.startsWith("stroke:") -> t.removePrefix("stroke:").firstOrNull()?.let { controller.onShapeStroke(it) }
            t.startsWith("first:") -> t.removePrefix("first:").toIntOrNull()?.let { controller.onCandidateChar(it, false) }
            t.startsWith("last:") -> t.removePrefix("last:").toIntOrNull()?.let { controller.onCandidateChar(it, true) }
            t == "voice-cancel" -> controller.voiceCancel()
            t.startsWith("voice-partial:") -> controller.voicePartial(t.removePrefix("voice-partial:"))
            t.startsWith("voice-final:") -> controller.voiceFinal(t.removePrefix("voice-final:"))
            t.startsWith("voice-replace:") -> {
                val parts = t.removePrefix("voice-replace:").split('|', limit = 2)
                if (parts.size == 2) controller.voiceReplace(parts[0], parts[1])
            }
            t.startsWith("pick:") -> controller.state.candidates.indexOfFirst { it.text == t.removePrefix("pick:") }
                .takeIf { it >= 0 }?.let(controller::onCandidate)
            // 排序策略：{policy:TEXT:pin|down|}，与长按候选菜单里的置顶、降低优先级相同。
            // Ranking policy: {policy:TEXT:pin|down|}, the same as pin and demote in the candidate menu.
            t.startsWith("policy:") -> {
                val parts = t.removePrefix("policy:").split(':', limit = 2)
                val index = controller.state.candidates.indexOfFirst { it.text == parts[0] }
                if (index >= 0) controller.candidatePolicy(index, parts[0], parts.getOrElse(1) { "" })
            }
            t.startsWith("sel:") -> t.removePrefix("sel:").toIntOrNull()?.let(controller::onCandidate)
            t.startsWith("py:") -> t.removePrefix("py:").toIntOrNull()?.let(controller::onPinyinOption)
            // 打开面板：`{panel:symbol}`、`{panel:cursor}`……，`{emoji}` 直接落在表情分类。
            // Open a panel; `{emoji}` lands on the emoji tab. Panels are the one place a script could not reach before.
            t.startsWith("panel:") -> {
                val name = t.removePrefix("panel:")
                ui?.invoke()?.let { kb -> if (name == "symbol") kb.showPanel("symbol") else kb.showPanel(name) }
            }
            t == "emoji" -> ui?.invoke()?.let { kb ->
                kb.showPanel("symbol")
                (kb.panelNamed("symbol") as? com.weavetext.ime.ui.keyboard.SymbolPanel)?.selectEmoji()
            }
            t == "close-panel" -> ui?.invoke()?.closePanel()
            // 展开面板里的搜索行（等同点一下它），冒烟脚本才能点到字母键。
            // Unfold the panel's search row, as tapping it would, so a script can reach the letter pad.
            t == "open-search" -> (ui?.invoke()?.panelNamed("symbol") as? com.weavetext.ime.ui.keyboard.SymbolPanel)?.openSearchForTest()
            // 与用户设置里选方案一样：中文状态才切换，密码框等英文框只记下。 Like choosing it in settings: applied only in Chinese mode.
            t.startsWith("schema:") -> controller.setPreferredSchema(t.removePrefix("schema:"))
            t.startsWith("opt:") -> {
                val (k, v) = t.removePrefix("opt:").split('=', limit = 2).let { it[0] to (it.getOrNull(1) == "true") }
                controller.setOption(k, v)
            }
            else -> Log.w(TAG, "unknown token {$t}")
        }
    }

    companion object {
        const val ACTION = "com.weavetext.ime.debug.KEYS"
        private const val TAG = "WeaveSmoke"
    }
}
