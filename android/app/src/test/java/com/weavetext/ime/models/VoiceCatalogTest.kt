package com.weavetext.ime.models

import com.weavetext.ime.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 语音目录与档位的一致性：推荐里不能出现已下线的模型，新增模型的文件必须能对上压缩包。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCatalogTest {
    private val catalog = ModelCatalog.parse(File("src/main/assets/models/catalog.json").readText())

    @Test fun retiredModelsAreGone() {
        for (id in listOf("asr-stream-mixed-medium", "asr-stream-mixed-high", "asr-whisper-base")) {
            assertNull("$id should no longer be offered", catalog.find(id))
        }
    }

    @Test fun xAsrStreamingModelIsInstallableAndSelfContained() {
        val model = catalog.find("asr-x-asr-960") ?: error("missing the X-ASR streaming entry")
        assertEquals(ModelKind.ASR_STREAMING, model.kind)
        assertEquals("zipformer-transducer", model.arch)
        assertEquals(listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"), model.fileNames())
        assertEquals(model.files.sumOf { it.size }, model.installedSize)
        assertTrue(model.license, model.license.contains("Apache"))
        assertTrue(model.description, model.description.contains("标点"))
    }

    @Test fun everyProfileInstallsOnlyModelsThatExist() {
        for (profile in VoiceProfiles.ALL) {
            for (id in profile.modelIds) assertNotNull("profile ${profile.name} lists $id", catalog.find(id))
        }
    }

    @Test fun defaultProfileStreamsMixedSpeechAndEnglishUsesWhisper() {
        val standard = VoiceProfiles.ALL.first { it.name == "中英标准" }
        assertEquals(listOf("asr-x-asr-960"), standard.modelIds)
        assertEquals(VoiceLanguage.MIXED, standard.language)
        val english = VoiceProfiles.ALL.first { it.language == VoiceLanguage.ENGLISH }
        assertEquals(listOf("asr-whisper-small"), english.modelIds)
    }
}
