package com.weavetext.ime.nativetest

import com.weavetext.ime.link.NativeLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 经 JNI 驱动真实互联内核：两个实例配对并互发文字。 Two real WeaveLink instances through JNI: pair and exchange text. */
class LinkJniTest {
    private fun start(name: String, platform: String): NativeLink {
        val dir = Files.createTempDirectory("weave-link-$name").toFile()
        val cfg = JSONObject().put("name", name).put("platform", platform)
            .put("stateDir", dir.resolve("state").path).put("inboxDir", dir.resolve("inbox").path)
            .put("port", 1).put("mdns", false)
        return NativeLink().also { assertTrue(it.start(cfg.toString())) }
    }

    private fun NativeLink.await(type: String): JSONObject {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val e = JSONObject(poll(200) ?: break)
            if (e.optString("type") == type) return e
        }
        error("no $type")
    }

    @Test
    fun pairAndSendText() {
        val mac = start("mac", "mac")
        val phone = start("phone", "android")
        try {
            val port = JSONObject(mac.call("""{"op":"info"}""")).getInt("port")
            val code = JSONObject(mac.call("""{"op":"openPairing"}""")).getString("code")
            phone.call(JSONObject().put("op", "pair").put("addrs", org.json.JSONArray(listOf("127.0.0.1:$port"))).put("code", code).toString())
            assertEquals("mac", phone.await("paired").getString("name"))
            mac.await("paired")
            phone.call("""{"op":"sendText","text":"从手机来","clip":true}""")
            assertEquals("从手机来", mac.await("text").getString("text"))
        } finally {
            for (l in listOf(mac, phone)) { l.stop(); l.destroy() }
        }
        assertNull(mac.poll(10))
    }
}
