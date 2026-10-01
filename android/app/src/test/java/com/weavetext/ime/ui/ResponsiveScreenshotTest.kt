package com.weavetext.ime.ui

import com.weavetext.ime.core.Candidate
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.KeyCode
import android.Manifest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 小屏 / 小平板 / 横屏 / 大字号的键盘截图矩阵（ui-polish §1、§7）。输出 keyboard_<尺寸>_<场景>.png。
 * Keyboard snapshots at 320dp, 600dp, landscape 800×360dp and font scales 1.3 / 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ResponsiveScreenshotTest : KeyboardSnapshotSupport() {

    private fun main(tag: String) { keyboard(false); snap("${tag}_main") }

    private fun candidates(tag: String) {
        val (k, c) = keyboard(false)
        c.previewState(composing(cands = nihao + listOf("你好吗", "你好啊", "拟", "昵称", "泥土", "你们好").map { Candidate(it, "", false) }))
        snap("${tag}_composing")
        k.showPanel("grid")
        snap("${tag}_candidates_expanded")
    }

    private fun symbols(tag: String) { val (k, _) = keyboard(false); k.showPanel("symbol"); snap("${tag}_symbols") }

    private fun voice(tag: String) {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        snap("${tag}_voice")
    }

    private fun wubi(tag: String) {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "wubi86,english").putBoolean(WeavePrefs.WUBI_ROOT_HINTS, true) }
        c.previewState(ImeState(schema = "wubi86", engineReady = true))
        snap("${tag}_wubi")
    }

    private fun t9(tag: String) {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "t9,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9") }
        c.previewState(ImeState(schema = "t9", engineReady = true))
        snap("${tag}_t9")
    }

    private fun numpad(tag: String) {
        val (k, _) = keyboard(false)
        k.onKey(k.keyboardView.keyOf(KeyCode.NUMBER)!!)
        snap("${tag}_numpad")
    }

    private fun shuangpin(tag: String) {
        val (_, c) = keyboard(false) { putString(WeavePrefs.KEYBOARDS, "shuangpin,english") }
        c.previewState(ImeState(schema = "shuangpin:xiaohe", engineReady = true))
        snap("${tag}_shuangpin")
    }

    // ---------------------------------------------------------------- 320dp 手机 / narrow phone

    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun narrowMain() = main("w320")
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun narrowCandidates() = candidates("w320")
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun narrowSymbols() = symbols("w320")
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun narrowVoice() = voice("w320")
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun narrowShuangpin() = shuangpin("w320")

    /** 窄屏上没有引擎时三个办法放不下一行，折成两行而不是冲出屏幕。 On 320dp the three no-engine pills wrap instead of overflowing. */
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun narrowVoiceNoEngine() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        engines.plugins = emptyList()
        com.weavetext.ime.models.AsrRuntime.bundled = false
        com.weavetext.ime.voice.VoiceIme.finder = { listOf(com.weavetext.ime.voice.VoiceIme.Option("org.example.voice/.Ime", "示例语音输入", null, enabled = true)) }
        try {
            val (k, _) = keyboard(false)
            k.showPanel("voice")
            snap("w320_voice_no_engine")
        } finally {
            com.weavetext.ime.models.AsrRuntime.bundled = com.weavetext.ime.BuildConfig.LOCAL_ASR
            com.weavetext.ime.voice.VoiceIme.finder = { emptyList() }
        }
    }

    // ---------------------------------------------------------------- 600dp 小平板 / small tablet

    @Config(qualifiers = "w600dp-h960dp-port-xhdpi") @Test fun tabletMain() = main("w600")
    @Config(qualifiers = "w600dp-h960dp-port-xhdpi") @Test fun tabletCandidates() = candidates("w600")
    @Config(qualifiers = "w600dp-h960dp-port-xhdpi") @Test fun tabletSymbols() = symbols("w600")
    @Config(qualifiers = "w600dp-h960dp-port-xhdpi") @Test fun tabletVoice() = voice("w600")

    // ---------------------------------------------------------------- 横屏 800×360dp / landscape

    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun landMain() = main("land")
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun landCandidates() = candidates("land")
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun landSymbols() = symbols("land")
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun landVoice() = voice("land")
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun landVoiceListening() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        (k.panel as com.weavetext.ime.ui.keyboard.VoicePanel).session.preview(
            com.weavetext.ime.ui.keyboard.VoiceSession.State.LISTENING, "中文为主，讨论 GitHub API and English words", "", 0.6f)
        snap("land_voice_listening")
    }
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun landT9() = t9("land")

    // ---------------------------------------------------------------- 宽屏分体 / wide split

    @Config(qualifiers = "w840dp-h1200dp-port-xhdpi") @Test fun wideSplitMain() = main("w840_split")
    @Config(qualifiers = "w840dp-h1200dp-port-xhdpi") @Test fun wideSplitEnglishDark() {
        val (_, c) = keyboard(true)
        c.previewState(ImeState(chinese = false, engineReady = true))
        snap("w840_split_english_dark")
    }
    @Config(qualifiers = "w840dp-h1200dp-port-xhdpi") @Test fun wideSplitOff() {
        keyboard(false) { putBoolean(WeavePrefs.SPLIT_WIDE, false) }
        snap("w840_unsplit")
    }
    @Config(qualifiers = "w840dp-h1200dp-port-xhdpi") @Test fun wideSplitCellsLeaveNoDeadZone() {
        val (k, _) = keyboard(false)
        val kv = k.keyboardView
        val t = kv.keyOf('t'.code)!!
        val y = kv.keyOf('y'.code)!!
        org.junit.Assert.assertTrue("gap between T and Y", y.rect.left - t.rect.right > kv.width * 0.15f)
        org.junit.Assert.assertEquals(t.cell.right, y.cell.left, 0.5f)
    }

    // ---------------------------------------------------------------- 字号 1.3 / font scale 1.3

    @Test fun font13Main() { fontScale(1.3f); main("font13") }
    @Test fun font13Candidates() { fontScale(1.3f); candidates("font13") }
    @Test fun font13Symbols() { fontScale(1.3f); symbols("font13") }
    @Test fun font13Voice() { fontScale(1.3f); voice("font13") }
    @Test fun font13Wubi() { fontScale(1.3f); wubi("font13") }
    @Test fun font13T9() { fontScale(1.3f); t9("font13") }
    @Test fun font13Numpad() { fontScale(1.3f); numpad("font13") }

    // ---------------------------------------------------------------- 字号 2.0 / font scale 2.0

    @Test fun font20Main() { fontScale(2f); main("font20") }
    @Test fun font20Candidates() { fontScale(2f); candidates("font20") }
    @Test fun font20Wubi() { fontScale(2f); wubi("font20") }
    @Test fun font20T9() { fontScale(2f); t9("font20") }
    @Test fun font20Numpad() { fontScale(2f); numpad("font20") }
    @Test fun font20Shuangpin() { fontScale(2f); shuangpin("font20") }
}
