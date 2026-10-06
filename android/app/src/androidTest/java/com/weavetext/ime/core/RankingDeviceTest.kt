package com.weavetext.ime.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Verifies ranking and commits through JNI using the dictionaries inside the APK. */
@RunWith(AndroidJUnit4::class)
class RankingDeviceTest {
    @Test fun commonTyposAndLiteralParticlesUseTheSharedRankingPolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val user = File(context.cacheDir, "ranking-device-${UUID.randomUUID()}")
        val engine = NativeEngine.createFromSpec(DataInstaller.sourceSpec(context), user.path, DataInstaller.cacheKb(context))!!
        try {
            engine.setLearning(false)
            engine.setOption("candidates.prediction", false)
            for ((input, expected) in listOf("xiuba" to "修吧", "mingtinajian" to "明天见", "shagnhai" to "上海", "jintina" to "今天")) {
                engine.clear(); engine.setContext(null)
                input.forEach { engine.inputChar(it.code) }
                assertEquals(input, expected, engine.snapshot().candidates.first().text)
                assertTrue(engine.select(0))
                assertEquals(expected, engine.snapshot().commit)
            }
            engine.clear(); engine.setContext(null)
            engine.setOption("input.autocorrect", false)
            "mingtinajian".forEach { engine.inputChar(it.code) }
            assertNotEquals("明天见", engine.snapshot().candidates.first().text)
        } finally {
            engine.close(); user.deleteRecursively()
        }
    }
}
