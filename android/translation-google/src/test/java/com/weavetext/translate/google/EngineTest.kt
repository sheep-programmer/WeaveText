package com.weavetext.translate.google

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineTest {
    private lateinit var backend: FakeBackend
    private lateinit var engine: Engine
    private val events = mutableListOf<JSONObject>()
    private val source = "Hello world\n🌏"

    @Before fun setup() { backend = FakeBackend(); engine = Engine(backend, DirectWorker()); events.clear() }
    @After fun stop() { engine.close() }
    private fun request(op: String, text: String = source, sourceCode: String = "en", target: String = "zh", language: String = "zh", wifi: Boolean? = null): PluginCall {
        val json = JSONObject().put("op", op).put("source", sourceCode).put("target", target).put("text", text).put("language", language)
        if (wifi != null) json.put("wifiOnly", wifi)
        return engine.request(json.toString()) { events += JSONObject(it.encode()) }
    }
    private fun finalEvent(): JSONObject = events.last().also { assertTrue(it.getString("type") != "progress") }
    private fun error(code: String) { assertEquals("error", finalEvent().getString("type")); assertEquals(code, finalEvent().getString("code")) }

    @Test fun missingModelReturnsManagementGuidanceWithoutCreatingTranslatorOrDownloading() {
        request("translate")
        error("MODEL_MISSING")
        assertTrue(finalEvent().getString("message").contains("管理页"))
        assertTrue(backend.translators.isEmpty())
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun bothNonEnglishPacksAreRequiredButEnglishNeverNeedsARemoteModel() {
        backend.installed = setOf("zh")
        request("translate", sourceCode = "fr", target = "zh")
        error("MODEL_MISSING")
        assertTrue(finalEvent().getString("message").contains("fr"))
        events.clear()
        request("translate")
        assertEquals("en" to "zh", backend.translators.single().languages)
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun installedModelsTranslateUsingExactTextAndCloseBeforeDeliveringResult() {
        backend.installed = setOf("zh")
        request("translate")
        val translator = backend.translators.single()
        assertEquals(source, translator.input)
        translator.task.success("你好世界\n🌏")
        assertEquals("result", finalEvent().getString("type"))
        assertEquals("你好世界\n🌏", finalEvent().getString("text"))
        assertTrue(finalEvent().isNull("detected_language"))
        assertEquals(1, translator.closes)
        assertTrue(backend.downloads.isEmpty())
        assertEquals(listOf("CHECKING_MODELS", "TRANSLATING"), events.filter { it.getString("type") == "progress" }.map { it.getString("stage") })
    }

    @Test fun automaticSourceUsesBundledIdentificationThenChecksPacksAndReturnsDetectedCode() {
        backend.installed = setOf("zh")
        request("translate", sourceCode = "auto", target = "en")
        val identifier = backend.identifiers.single()
        assertEquals(source, identifier.input)
        identifier.task.success("zh-CN")
        assertEquals(1, identifier.closes)
        val translator = backend.translators.single()
        assertEquals("zh" to "en", translator.languages)
        translator.task.success("Real result")
        assertEquals("zh", finalEvent().getString("detected_language"))
        assertEquals(1, translator.closes)
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun undAndUnsupportedDetectedLanguageAreErrorsNotGuesses() {
        request("translate", sourceCode = "auto")
        backend.identifiers.single().task.success("und")
        error("LANGUAGE_UNDETERMINED")
        assertEquals(1, backend.identifiers.single().closes)
        events.clear()
        request("translate", sourceCode = "auto")
        backend.identifiers.last().task.success("zz")
        error("UNSUPPORTED_LANGUAGE")
        assertEquals(1, backend.identifiers.last().closes)
        assertTrue(backend.translators.isEmpty())
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun unsupportedTargetIsRejectedBeforeAnyIdentifier() {
        request("translate", sourceCode = "auto", target = "zz")
        error("UNSUPPORTED_LANGUAGE")
        assertTrue(backend.identifiers.isEmpty())
        assertTrue(backend.translators.isEmpty())
    }

    @Test fun sameLanguageReturnsOriginalWithoutInventoryOrModelOperations() {
        request("translate", text = "  Bonjour\n", sourceCode = "fr-FR", target = "fr")
        assertEquals("  Bonjour\n", finalEvent().getString("text"))
        assertEquals(0, backend.inventoryCalls)
        assertTrue(backend.translators.isEmpty())
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun languagesIncludeEnglishInstalledBuiltinAndTheSdkInventory() {
        backend.installed = setOf("zh")
        request("languages")
        val list = finalEvent().getJSONArray("languages")
        val items = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("code") }
        assertTrue(items.getValue("en").getBoolean("installed"))
        assertTrue(items.getValue("en").getBoolean("builtin"))
        assertTrue(items.getValue("zh").getBoolean("installed"))
        assertFalse(items.getValue("zh").getBoolean("builtin"))
        assertFalse(items.getValue("fr").getBoolean("installed"))
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun explicitDownloadDefaultsToWifiAndThenRefreshShowsInstalled() {
        request("download")
        assertEquals(listOf("zh" to true), backend.downloads)
        assertEquals("DOWNLOADING_MODEL", events.single().getString("stage"))
        backend.downloadTask.success(Unit)
        assertEquals("downloaded", finalEvent().getString("type"))
        assertEquals("zh", finalEvent().getString("language"))
        backend.installed = setOf("zh")
        events.clear()
        request("languages")
        assertTrue(finalEvent().getJSONArray("languages").toString().contains("\"installed\":true"))
    }

    @Test fun explicitDownloadCanAllowMobileNetwork() {
        request("download", wifi = false)
        assertEquals(listOf("zh" to false), backend.downloads)
    }

    @Test fun englishIsAlreadyAvailableAndCannotBeDeletedOrPassedToRemoteManager() {
        request("download", language = "en")
        assertEquals("downloaded", finalEvent().getString("type"))
        events.clear()
        request("delete", language = "en")
        error("BUILTIN_MODEL")
        assertTrue(backend.downloads.isEmpty())
        assertTrue(backend.deletes.isEmpty())
    }

    @Test fun deleteOnlyUsesExplicitDeleteOperation() {
        request("delete")
        assertEquals(listOf("zh"), backend.deletes)
        backend.deleteTask.success(Unit)
        assertEquals("deleted", finalEvent().getString("type"))
        assertEquals("zh", finalEvent().getString("language"))
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun errorsNeverEchoOriginalTextOrRawSdkException() {
        request("translate", sourceCode = "auto")
        backend.identifiers.single().task.failure(IllegalStateException(source))
        error("IDENTIFICATION_FAILED")
        assertFalse(events.last().toString().contains(source))
        assertEquals(1, backend.identifiers.single().closes)
        events.clear()
        backend.installed = setOf("zh")
        request("translate")
        backend.translators.single().task.failure(IllegalStateException(source))
        error("TRANSLATION_FAILED")
        assertFalse(finalEvent().toString().contains(source))
        assertEquals(1, backend.translators.single().closes)
    }

    @Test fun failedInventoryDownloadAndDeleteHaveSpecificErrors() {
        backend.inventoryError = true
        request("translate"); error("MODEL_QUERY_FAILED")
        assertTrue(backend.translators.isEmpty())
        events.clear()
        request("download"); backend.downloadTask.failure(IllegalStateException(source)); error("DOWNLOAD_FAILED")
        events.clear()
        request("delete"); backend.deleteTask.failure(IllegalStateException(source)); error("DELETE_FAILED")
        assertFalse(finalEvent().toString().contains(source))
    }

    @Test fun emptyAndOversizedSdkResultsFailAndCloseTheTranslator() {
        backend.installed = setOf("zh")
        for (output in listOf(" ", "x".repeat(32_001))) {
            events.clear()
            request("translate")
            val translator = backend.translators.last()
            translator.task.success(output)
            error(if (output.isBlank()) "INVALID_RESULT" else "RESULT_TOO_LARGE")
            assertEquals(1, translator.closes)
        }
    }

    @Test fun cancellationDuringIdentificationSuppressesLateLanguageAndClosesOnce() {
        val call = request("translate", sourceCode = "auto")
        val identifier = backend.identifiers.single()
        val before = events.size
        call.cancel(); call.cancel(); identifier.task.success("en")
        assertEquals(before, events.size)
        assertEquals(1, identifier.closes)
        assertTrue(backend.translators.isEmpty())
    }

    @Test fun cancellationWhileCheckingModelsDoesNotAllocateTranslatorOrDownload() {
        backend.deferredInventory = true
        val call = request("translate")
        val before = events.size
        call.cancel()
        backend.inventoryTask.success(setOf("zh"))
        assertEquals(before, events.size)
        assertTrue(backend.translators.isEmpty())
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun cancelledSdkTranslationCannotDeliverLateSuccessOrFailure() {
        backend.installed = setOf("zh")
        val call = request("translate")
        val translator = backend.translators.single()
        val before = events.size
        call.cancel()
        translator.task.success("late"); translator.task.failure(IllegalStateException("late"))
        assertEquals(before, events.size)
        assertEquals(1, translator.closes)
    }

    @Test fun cancelledManagementTaskMayFinishInSdkButEmitsNoOldEvent() {
        val call = request("download")
        val before = events.size
        call.cancel()
        backend.downloadTask.success(Unit)
        assertEquals(before, events.size)
    }

    @Test fun duplicateSdkCallbacksCannotTranslateTwiceOrCloseTwice() {
        backend.installed = setOf("zh")
        backend.deferredInventory = true
        request("translate")
        backend.inventoryTask.success(setOf("zh")); backend.inventoryTask.success(setOf("zh"))
        assertEquals(1, backend.translators.size)
        val translator = backend.translators.single()
        translator.task.success("one"); translator.task.success("two")
        assertEquals(1, events.count { it.getString("type") == "result" })
        assertEquals(1, translator.closes)
    }

    @Test fun shutdownCancelsResourcesAndRefusesNewRequests() {
        backend.installed = setOf("zh")
        request("translate")
        val translator = backend.translators.single()
        engine.close()
        val before = events.size
        translator.task.success("late")
        assertEquals(before, events.size)
        assertEquals(1, translator.closes)
        request("languages"); error("PLUGIN_STOPPED")
    }

    @Test fun thirtyTwoThousandCharactersAreBoundedBeforeClientAllocation() {
        request("translate", text = "x".repeat(32_001)); error("INPUT_TOO_LARGE")
        assertTrue(backend.translators.isEmpty())
        events.clear()
        request("translate", text = "x".repeat(32_000), target = "en")
        assertEquals(32_000, finalEvent().getString("text").length)
    }

    @Test fun queuedCancellationAndRejectedWorkerDoNotTouchSdk() {
        val pending = ArrayDeque<Runnable>()
        val queued = Engine(backend, DirectWorker { pending.add(it) })
        val call = queued.request("{\"op\":\"languages\"}") { events += JSONObject(it.encode()) }
        call.cancel(); pending.removeFirst().run()
        assertEquals(0, backend.inventoryCalls)
        queued.close()
        val rejected = Engine(backend, DirectWorker { throw RejectedExecutionException() })
        rejected.request("{\"op\":\"languages\"}") { events += JSONObject(it.encode()) }
        error("BUSY"); rejected.close()
    }

    internal class ControlledTask<T> : PluginTask<T> {
        private var listener: ((Result<T>) -> Unit)? = null
        override fun onComplete(callback: (Result<T>) -> Unit) { listener = callback }
        fun success(value: T) { checkNotNull(listener).invoke(Result.success(value)) }
        fun failure(error: Exception) { checkNotNull(listener).invoke(Result.failure(error)) }
    }
    internal class FakeIdentifier : PluginIdentifier {
        val task = ControlledTask<String>()
        var input: String? = null
        var closes = 0
        override fun identify(text: String): PluginTask<String> { input = text; return task }
        override fun close() { closes++ }
    }
    internal class FakeTranslator(val languages: Pair<String, String>) : PluginTranslator {
        val task = ControlledTask<String>()
        var input: String? = null
        var closes = 0
        override fun translate(text: String): PluginTask<String> { input = text; return task }
        override fun close() { closes++ }
    }
    internal class FakeBackend : PluginBackend {
        var installed = emptySet<String>()
        var inventoryCalls = 0
        var inventoryError = false
        var deferredInventory = false
        val inventoryTask = ControlledTask<Set<String>>()
        val downloadTask = ControlledTask<Unit>()
        val deleteTask = ControlledTask<Unit>()
        val identifiers = mutableListOf<FakeIdentifier>()
        val translators = mutableListOf<FakeTranslator>()
        val downloads = mutableListOf<Pair<String, Boolean>>()
        val deletes = mutableListOf<String>()
        override fun languageCode(tag: String) = tag.substringBefore('-').takeIf { it in supportedLanguages() }
        override fun supportedLanguages() = listOf("en", "zh", "fr")
        override fun installedLanguages(): PluginTask<Set<String>> {
            inventoryCalls++
            if (deferredInventory) return inventoryTask
            return PluginTask { it(if (inventoryError) Result.failure(IllegalStateException("private SDK details")) else Result.success(installed)) }
        }
        override fun identifier(): PluginIdentifier = FakeIdentifier().also { identifiers += it }
        override fun translator(source: String, target: String): PluginTranslator = FakeTranslator(source to target).also { translators += it }
        override fun download(language: String, wifiOnly: Boolean): PluginTask<Unit> { downloads += language to wifiOnly; return downloadTask }
        override fun delete(language: String): PluginTask<Unit> { deletes += language; return deleteTask }
    }
    internal class DirectWorker(private val run: (Runnable) -> Unit = { it.run() }) : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) { if (stopped) throw RejectedExecutionException(); run(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
