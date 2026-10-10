package com.weavetext.ime.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.weavetext.ime.models.DownloadPhase
import com.weavetext.ime.models.Progress
import com.weavetext.ime.settings.AppUpdateContent
import com.weavetext.ime.settings.DownloadProgressContent
import com.weavetext.ime.settings.WeaveSettingsTheme
import com.weavetext.ime.voice.AppUpgrade
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h914dp-port-420dpi")
class DownloadProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private fun bar() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    @Test fun unknownLengthDownloadShowsTransferredBytesAndSpeedWithIndeterminateProgress() {
        compose.setContent { WeaveSettingsTheme(false) { DownloadProgressContent(Progress(1_000_000, 0, 250_000, "mirror")) } }
        compose.onNodeWithText("正在下载…", substring = true).assertExists()
        compose.onNodeWithText("1.0\u00a0MB / 大小未知 · 250\u00a0KB/s", substring = true).assertExists()
        compose.onNodeWithText("正在连接", substring = true).assertDoesNotExist()
        assertEquals(ProgressBarRangeInfo.Indeterminate, bar())
    }

    @Test fun resumedConnectingAndVerificationAreIndeterminateWithKnownSizes() {
        val progress = mutableStateOf(Progress(1_000_000, 4_000_000, 0, "mirror", DownloadPhase.CONNECTING))
        compose.setContent { WeaveSettingsTheme(false) { DownloadProgressContent(progress.value) } }
        compose.onNodeWithText("1.0\u00a0MB / 4.0\u00a0MB", substring = true).assertExists()
        assertEquals(ProgressBarRangeInfo.Indeterminate, bar())
        compose.runOnIdle { progress.value = progress.value.copy(phase = DownloadPhase.DOWNLOADING, bytesPerSecond = 250_000) }
        assertEquals(0.25f, bar().current)
        compose.runOnIdle { progress.value = progress.value.copy(downloaded = 4_000_000, phase = DownloadPhase.VERIFYING) }
        compose.onNodeWithText("正在校验…", substring = true).assertExists()
        assertEquals(ProgressBarRangeInfo.Indeterminate, bar())
    }

    @Test fun aboutDownloadExposesCancelAndResumeAndFailureExposesRetry() {
        val available = AppUpgrade.State.Available("v0.1.0-beta.21", "update.apk", 4_000_000, "")
        val state = mutableStateOf<AppUpgrade.State>(AppUpgrade.State.Downloading(1_000_000, 4_000_000, 250_000, "mirror"))
        var cancelled = 0
        var resumed = 0
        compose.setContent {
            WeaveSettingsTheme(false) {
                AppUpdateContent(state.value, onAction = { resumed++ }, onCancel = { cancelled++; state.value = AppUpgrade.State.Cancelled(available) })
            }
        }
        compose.onNodeWithText("1.0\u00a0MB / 4.0\u00a0MB · 250\u00a0KB/s", substring = true).assertExists()
        assertEquals(0.25f, bar().current)
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, cancelled)
        compose.onNodeWithText("继续下载").performClick()
        assertEquals(1, resumed)
        compose.runOnIdle { state.value = AppUpgrade.State.Failed("安装包校验失败", available) }
        compose.onNodeWithText("重试").performClick()
        assertEquals(2, resumed)
    }

    @Test fun aboutDoesNotOfferInstallationUntilVerificationCompletes() {
        compose.setContent { WeaveSettingsTheme(false) { AppUpdateContent(AppUpgrade.State.Verifying, {}, {}) } }
        compose.onNodeWithText("正在校验安装包与签名…").assertExists()
        compose.onNodeWithText("安装更新").assertDoesNotExist()
        assertEquals(ProgressBarRangeInfo.Indeterminate, bar())
    }
}
