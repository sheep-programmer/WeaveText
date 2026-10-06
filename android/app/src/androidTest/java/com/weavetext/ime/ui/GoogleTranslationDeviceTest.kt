package com.weavetext.ime.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weavetext.ime.translate.*
import com.weavetext.ime.settings.TranslationSettings
import com.weavetext.translation.contract.ITranslationCallback
import com.weavetext.translation.contract.ITranslationPlugin
import com.weavetext.translation.contract.TranslationPluginContract as Contract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual companion APK, real Binder, explicit official model download and model deletion. */
@RunWith(AndroidJUnit4::class)
class GoogleTranslationDeviceTest {
    @Test fun independentPluginDownloadsTranslatesAndDeletesWithoutSdkInHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        assertEquals(OfflineTranslationPlugin.State.READY, OfflineTranslationPlugin.status(ctx).state)
        val connected = CountDownLatch(1)
        var remote: ITranslationPlugin? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = ITranslationPlugin.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        var bound = false
        instrumentation.runOnMainSync {
            bound = ctx.bindService(Intent().setComponent(ComponentName(Contract.PLUGIN_PACKAGE, Contract.PLUGIN_SERVICE)), connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(bound)
        try {
            assertTrue("Plugin failed to bind", connected.await(15, TimeUnit.SECONDS))
            val plugin = remote!!
            assertEquals(Contract.VERSION, plugin.protocolVersion())
            assertEquals("deleted", command(plugin, JSONObject().put("op", "delete").put("language", "zh")).optString("type"))
            val missing = command(plugin, JSONObject().put("op", "translate").put("source", "zh").put("target", "en").put("text", "你好世界"))
            assertEquals("MODEL_MISSING", missing.optString("code"))
            val inventory = command(plugin, JSONObject().put("op", "languages")).getJSONArray("languages")
            val chineseBefore = (0 until inventory.length()).map { inventory.getJSONObject(it) }.first { it.getString("code") == "zh" }
            assertFalse("Translation must not implicitly download a missing model", chineseBefore.getBoolean("installed"))
            val downloaded = command(plugin, JSONObject().put("op", "download").put("language", "zh").put("wifiOnly", false), timeoutSeconds = 300)
            assertEquals(downloaded.toString(), "downloaded", downloaded.optString("type"))
            val prefsName = "translation-plugin-device-" + UUID.randomUUID()
            val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            try {
                TranslationSettings.setProtocol(prefs, TranslationProtocol.GOOGLE_DEVICE)
                val service = TranslationServices.from(ctx, prefs)
                val english = translate(service, TranslationRequest("zh", "en", "你好世界"))
                assertTrue(english, english.lowercase().contains("hello") && english.lowercase().contains("world"))
                val chinese = translate(service, TranslationRequest("auto", "zh", "Hello world"))
                assertTrue(chinese, chinese.contains("你好") && chinese.contains("世界"))
            } finally { prefs.edit().clear().commit(); ctx.deleteSharedPreferences(prefsName) }
            assertEquals("deleted", command(plugin, JSONObject().put("op", "delete").put("language", "zh")).optString("type"))
            val deleted = command(plugin, JSONObject().put("op", "translate").put("source", "zh").put("target", "en").put("text", "你好世界"))
            assertEquals("MODEL_MISSING", deleted.optString("code"))
        } finally { instrumentation.runOnMainSync { if (bound) ctx.unbindService(connection) } }
    }
    private fun command(plugin: ITranslationPlugin, json: JSONObject, timeoutSeconds: Long = 65): JSONObject {
        val id = UUID.randomUUID().toString()
        val queue = LinkedBlockingQueue<JSONObject>()
        plugin.request(id, json.toString(), object : ITranslationCallback.Stub() {
            override fun onEvent(requestId: String?, value: String?) {
                if (requestId == id && value != null) {
                    val event = JSONObject(value)
                    if (event.optString("type") != "progress") queue.offer(event)
                }
            }
        })
        try { return queue.poll(timeoutSeconds, TimeUnit.SECONDS) ?: throw AssertionError("Plugin command timed out: " + json.optString("op")) }
        finally { plugin.cancel(id) }
    }
    private fun translate(service: TranslationService, request: TranslationRequest): String {
        val queue = LinkedBlockingQueue<TranslationResult>()
        val call = service.translate(request) { queue.offer(it) }
        try {
            val result = queue.poll(65, TimeUnit.SECONDS)
            assertNotNull("Plugin translation timed out", result)
            assertTrue("Expected downloaded-model translation, got $result", result is TranslationResult.Success)
            return (result as TranslationResult.Success).text
        } finally { call.cancel() }
    }
}
