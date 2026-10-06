package com.weavetext.ime.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.voice.local.LocalAsrChoice
import com.weavetext.ime.voice.local.OfflineModelSelection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 真正的模型选择策略 + 内存下载仓库，不联网/录音/加载原生模型。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceAutoDownloadTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val upgradePrefs get() = ctx.getSharedPreferences("voice-auto-test", 0)
    private val modelPrefs get() = ctx.getSharedPreferences(LocalAsrChoice.PREFS, 0)
    private val pluginPrefs get() = ctx.getSharedPreferences("voice-plugin-test", 0)
    private var bundled = false

    @Before fun setUp() {
        bundled = AsrRuntime.bundled
        AsrRuntime.bundled = true
        for (prefs in listOf(upgradePrefs, modelPrefs, pluginPrefs)) prefs.edit().clear().commit()
    }
    @After fun tearDown() { AsrRuntime.bundled = bundled }

    private class TestModels(val memory: FakeModels) : ModelRepository by memory {
        var deletions = 0
        var failDownloads = false
        override fun download(id: String, allowMetered: Boolean) {
            memory.download(id, allowMetered)
            if (failDownloads) memory.emit(id, ModelState.Failed("下载失败"))
        }
        override fun delete(id: String): Boolean { deletions++; return memory.delete(id) }
    }

    private class TestEngines(ctx: Context, models: ModelRepository) : VoiceEngines {
        val selection = OfflineModelSelection(ctx, models)
        val writes = mutableListOf<List<String>>()
        override var language: VoiceLanguage
            get() = selection.mode
            set(value) { selection.mode = value }
        override fun list() = selection.available().map { VoicePlugin(it.id, it.name, "", "", null, emptyList()) }
        override var activeId: String?
            get() = selection.ids().firstOrNull()
            set(value) { setSelection(listOfNotNull(value)) }
        override fun selection() = selection.ids().mapNotNull { id -> list().find { it.id == id } }
        override fun selectionSatisfiesMode() = selection.primaryOk()
        override fun setSelection(ids: List<String>) { writes += ids.toList(); selection.select(ids) }
        override fun getConfig(id: String, key: String): String? = null
        override fun setConfig(id: String, key: String, value: String) {}
        override fun install(xipkPath: String) = Result.failure<VoicePlugin>(UnsupportedOperationException())
        override fun uninstall(id: String) = Result.failure<Unit>(UnsupportedOperationException())
    }

    private data class Fixture(val models: TestModels, val engines: TestEngines, val preparation: VoiceModelPreparation) {
        val downloads get() = models.memory.downloads
        fun ids() = engines.selection().map { it.id }
        fun installed(id: String) { models.memory.emit(id, ModelState.Installed) }
    }

    private fun fixture(installed: List<String> = listOf("vad-silero", STREAM),
        mode: VoiceLanguage = VoiceLanguage.CHINESE, lite: Boolean = false): Fixture {
        if (lite) AsrRuntime.bundled = false
        val models = TestModels(FakeModels(installed.associateWith { ModelState.Installed },
            catalog = if (lite) FakeModels.LITE else FakeModels.CATALOG))
        val engines = TestEngines(ctx, models).apply { language = mode }
        ShadowLooper.idleMainLooper()
        return Fixture(models, engines, VoiceModelPreparation(models, engines, upgradePrefs, modelPrefs, pluginPrefs))
    }

    @Test fun chineseFirstInstallDownloadsBothButSelectsOnlyTheStreamForTwoPassFinals() {
        val f = fixture(installed = emptyList(), lite = true)
        assertTrue(f.preparation.ensure())
        assertEquals(setOf(AsrRuntime.ID, "vad-silero", STREAM, FINAL), f.downloads.map { it.first }.toSet())
        assertTrue(f.downloads.all { !it.second })
        for (id in listOf(AsrRuntime.ID, "vad-silero", STREAM, FINAL)) f.installed(id)

        assertEquals(VoiceAutoDownload.State.Ready, f.preparation.state)
        assertEquals(listOf(STREAM), f.ids())
        assertEquals(FINAL, f.engines.selection.finalCompanion(STREAM))
        // 用户手工多选两模型后不再使用隐藏终稿，进入现有多引擎结果流程。
        f.engines.setSelection(listOf(STREAM, FINAL))
        assertEquals(listOf(STREAM, FINAL), f.ids())
        assertNull(f.engines.selection.finalCompanion(STREAM))
    }

    @Test fun chineseLegacyUpgradeOnlyDownloadsFinalAndNeverWritesSelection() {
        val f = fixture()
        assertFalse(f.preparation.ensure())
        assertEquals(VoiceAutoDownload.State.Ready, f.preparation.state)
        assertEquals(listOf(FINAL to false), f.downloads)
        assertFalse(f.preparation.ensure())
        assertEquals(1, f.downloads.size)
        f.installed(FINAL)
        assertEquals(listOf(STREAM), f.ids())
        assertTrue(f.engines.writes.isEmpty())
        assertEquals(FINAL, f.engines.selection.finalCompanion(STREAM))
        assertTrue(upgradePrefs.getBoolean("chinese_final_upgrade_done", false))
    }

    @Test fun chineseUpgradeRespectsManualSelectionDuringDownload() {
        val f = fixture(listOf("vad-silero", STREAM, "asr-stream-large"))
        f.engines.setSelection(listOf(STREAM))
        f.preparation.ensure()
        f.engines.setSelection(listOf("asr-stream-large"))
        ShadowLooper.idleMainLooper()
        val writes = f.engines.writes.toList()
        f.installed(FINAL)
        assertEquals(listOf("asr-stream-large"), f.ids())
        assertEquals(writes, f.engines.writes)
        f.preparation.ensure()
        assertEquals(listOf("asr-stream-large"), f.ids())
    }

    @Test fun chineseUpgradeKeepsAManuallyCombinedSelectionOnCompletion() {
        val f = fixture()
        f.preparation.ensure()
        // 模型就绪事件发出前，用户已通过模型页把两项勾选写入偏好。
        modelPrefs.edit().putString("selected_models_zh", "[\"$STREAM\",\"$FINAL\"]").commit()
        ShadowLooper.idleMainLooper()
        f.installed(FINAL)
        assertEquals(listOf(STREAM, FINAL), f.ids())
        assertTrue(f.engines.writes.isEmpty())
        assertNull(f.engines.selection.finalCompanion(STREAM))
    }

    @Test fun otherChineseModelOrExplicitlyDisabledFinalIsNotUpgraded() {
        val f = fixture(listOf("vad-silero", STREAM, "asr-stream-large"))
        f.engines.setSelection(listOf("asr-stream-large"))
        f.preparation.ensure()
        assertTrue(f.downloads.isEmpty())
        f.engines.setSelection(listOf(STREAM))
        modelPrefs.edit().putString(LocalAsrChoice.KEY_FINAL, "none").commit()
        f.preparation.ensure()
        assertTrue(f.downloads.isEmpty())
    }

    @Test fun backgroundFailurePreservesInstalledModelsAndCanBeRetried() {
        val f = fixture()
        f.models.failDownloads = true
        f.preparation.ensure()
        assertEquals(VoiceAutoDownload.State.Ready, f.preparation.state)
        assertEquals(ModelState.Installed, f.models.state(STREAM))
        assertEquals(listOf(STREAM), f.ids())
        assertEquals(0, f.models.deletions)
        assertTrue(f.models.memory.cancels.isEmpty())
        assertFalse(upgradePrefs.getBoolean("chinese_final_upgrade_done", false))
        f.models.failDownloads = false
        f.preparation.ensure()
        assertEquals(2, f.downloads.size)
        f.installed(FINAL)
        assertEquals(FINAL, f.engines.selection.finalCompanion(STREAM))
    }

    @Test fun backgroundWifiOnlyWaitsForUnmeteredNetworkWithoutMarkingFailure() {
        val f = fixture()
        f.models.memory.metered = true
        assertFalse(f.preparation.ensure())
        assertTrue(f.downloads.isEmpty())
        assertEquals(VoiceAutoDownload.State.Ready, f.preparation.state)
        assertFalse(upgradePrefs.getBoolean("chinese_final_upgrade_done", false))
        f.models.memory.metered = false
        f.preparation.ensure()
        assertEquals(listOf(FINAL to false), f.downloads)
    }

    @Test fun backgroundMayUseMeteredNetworkOnlyWhenWifiOnlyIsOff() {
        val f = fixture()
        f.models.memory.metered = true
        f.models.memory.wifiOnly = false
        f.preparation.ensure()
        assertEquals(listOf(FINAL to true), f.downloads)
    }

    @Test fun alreadyInstalledChineseFinalDoesNotQueueDownloadsOrChangeSelection() {
        val f = fixture(listOf("vad-silero", STREAM, FINAL))
        f.preparation.ensure()
        assertTrue(f.downloads.isEmpty())
        assertTrue(f.engines.writes.isEmpty())
        assertEquals(listOf(STREAM), f.ids())
        assertEquals(FINAL, f.engines.selection.finalCompanion(STREAM))
    }

    @Test fun uninstallingTheSupplementAfterUpgradeDoesNotAutomaticallyInstallItAgain() {
        val f = fixture()
        f.preparation.ensure()
        f.installed(FINAL)
        f.models.memory.emit(FINAL, ModelState.NotInstalled)
        f.preparation.ensure()
        assertEquals(listOf(FINAL to false), f.downloads)
        assertEquals(listOf(STREAM), f.ids())
        assertNull(f.engines.selection.finalCompanion(STREAM))
    }

    @Test fun defaultPackCompletionDoesNotOverwriteASelectionMadeDuringDownload() {
        val f = fixture(installed = listOf("vad-silero"), lite = true)
        f.preparation.ensure()
        f.installed("asr-stream-large")
        f.engines.setSelection(listOf("asr-stream-large"))
        ShadowLooper.idleMainLooper()
        val writes = f.engines.writes.toList()
        for (id in listOf(AsrRuntime.ID, STREAM, FINAL)) f.installed(id)
        assertEquals(listOf("asr-stream-large"), f.ids())
        assertEquals(writes, f.engines.writes)
        assertEquals(VoiceAutoDownload.State.Ready, f.preparation.state)
    }

    @Test fun defaultPackDoesNotOverrideASelectionChangedAwayAndBackBeforeCompletion() {
        val f = fixture(installed = listOf("vad-silero"), lite = true)
        f.preparation.ensure()
        f.installed(STREAM)
        f.installed("asr-stream-large")
        f.engines.setSelection(listOf("asr-stream-large"))
        f.engines.setSelection(listOf(STREAM))
        ShadowLooper.idleMainLooper()
        val writes = f.engines.writes.toList()
        for (id in listOf(AsrRuntime.ID, FINAL)) f.installed(id)
        assertEquals(listOf(STREAM), f.ids())
        assertEquals(writes, f.engines.writes)
    }

    @Test fun changingLanguageDuringDefaultDownloadPreparesTheNewModeWithoutSwitchingBack() {
        val f = fixture(installed = listOf("vad-silero"), lite = true)
        f.preparation.ensure()
        f.engines.language = VoiceLanguage.ENGLISH
        ShadowLooper.idleMainLooper()
        for (id in listOf(AsrRuntime.ID, STREAM, FINAL)) f.installed(id)
        assertEquals(VoiceLanguage.ENGLISH, f.engines.language)
        assertTrue(f.downloads.any { it.first == "asr-sensevoice" })
        f.installed("asr-sensevoice")
        assertEquals(listOf("asr-sensevoice"), f.ids())
    }

    @Test fun bilingualMigrationStillAdoptsSenseVoiceOnceWhenTheChoiceIsUnchanged() {
        val f = fixture(listOf("vad-silero", "asr-stream-mixed-medium"), VoiceLanguage.MIXED)
        f.engines.setSelection(listOf("asr-stream-mixed-medium"))
        ShadowLooper.idleMainLooper()
        f.preparation.ensure()
        assertEquals(listOf("asr-sensevoice" to false), f.downloads)
        f.installed("asr-sensevoice")
        assertEquals(listOf("asr-sensevoice"), f.ids())
        f.engines.setSelection(listOf("asr-stream-mixed-medium"))
        f.preparation.ensure()
        assertEquals(listOf("asr-stream-mixed-medium"), f.ids())
    }

    @Test fun bilingualMigrationDoesNotStealAManualCombinationMadeDuringDownload() {
        val f = fixture(listOf("vad-silero", "asr-stream-mixed-medium", "asr-stream-mixed-high"), VoiceLanguage.MIXED)
        f.engines.setSelection(listOf("asr-stream-mixed-medium"))
        ShadowLooper.idleMainLooper()
        f.preparation.ensure()
        f.engines.setSelection(listOf("asr-stream-mixed-medium", "asr-stream-mixed-high"))
        ShadowLooper.idleMainLooper()
        f.installed("asr-sensevoice")
        assertEquals(listOf("asr-stream-mixed-medium", "asr-stream-mixed-high"), f.ids())
        f.preparation.ensure()
        assertEquals(listOf("asr-stream-mixed-medium", "asr-stream-mixed-high"), f.ids())
    }

    private companion object {
        const val STREAM = "asr-stream-small"
        const val FINAL = "asr-final-small"
    }
}
