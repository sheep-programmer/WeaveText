package com.weavetext.translate.google

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

internal interface PluginIdentifier : AutoCloseable { fun identify(text: String): PluginTask<String> }
internal interface PluginTranslator : AutoCloseable { fun translate(text: String): PluginTask<String> }

internal interface PluginBackend {
    fun languageCode(tag: String): String?
    fun supportedLanguages(): List<String>
    fun installedLanguages(): PluginTask<Set<String>>
    fun identifier(): PluginIdentifier
    fun translator(source: String, target: String): PluginTranslator
    fun download(language: String, wifiOnly: Boolean): PluginTask<Unit>
    fun delete(language: String): PluginTask<Unit>
}

/** SDK initialization is provided by MlKitInitProvider in this APK. */
internal class MlKitBackend : PluginBackend {
    override fun languageCode(tag: String): String? = TranslateLanguage.fromLanguageTag(tag)
    override fun supportedLanguages(): List<String> = TranslateLanguage.getAllLanguages()
    override fun installedLanguages(): PluginTask<Set<String>> = adapt(
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
    ) { models -> requireNotNull(models).map { it.language }.toSet() }

    override fun identifier(): PluginIdentifier {
        val client = LanguageIdentification.getClient()
        return object : PluginIdentifier {
            override fun identify(text: String) = adapt(client.identifyLanguage(text)) { requireNotNull(it) }
            override fun close() = client.close()
        }
    }

    override fun translator(source: String, target: String): PluginTranslator {
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        return object : PluginTranslator {
            // Model availability is checked by Engine first. This path never downloads a model.
            override fun translate(text: String) = adapt(client.translate(text)) { requireNotNull(it) }
            override fun close() = client.close()
        }
    }

    override fun download(language: String, wifiOnly: Boolean): PluginTask<Unit> {
        require(language != TranslateLanguage.ENGLISH)
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        return adapt(RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(language).build(), conditions)) { Unit }
    }

    override fun delete(language: String): PluginTask<Unit> {
        require(language != TranslateLanguage.ENGLISH)
        return adapt(RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(language).build())) { Unit }
    }

    private fun <S, T> adapt(task: Task<S>, transform: (S?) -> T) = PluginTask<T> { callback ->
        task.addOnCompleteListener(Executor { it.run() }) { completed ->
            callback(when {
                completed.isCanceled -> Result.failure(CancellationException())
                !completed.isSuccessful -> Result.failure(completed.exception ?: IllegalStateException("SDK operation failed"))
                else -> runCatching { transform(completed.result) }
            })
        }
    }
}

internal fun languageLabel(code: String): String = Locale.forLanguageTag(code).getDisplayName(Locale.SIMPLIFIED_CHINESE)
    .takeIf { it.isNotBlank() } ?: code
