package com.weavetext.ime.link

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.settings.WeavePrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 互联管理器：内核事件 → 界面状态与剪贴板/文件副作用；剪贴板同步不回传。 Link manager logic with a scripted core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkManagerTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private class FakeBackend : LinkBackend {
        var config: JSONObject? = null
        val calls = ArrayList<JSONObject>()
        val events = LinkedBlockingQueue<String>()
        var peers = """{"trusted":[],"nearby":[]}"""
        @Volatile var stopped = false
        override fun start(config: String): Boolean { this.config = JSONObject(config); return true }
        override fun poll(timeoutMs: Int): String? = if (stopped) null else events.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: """{"type":"idle"}"""
        override fun call(command: String): String {
            val c = JSONObject(command)
            calls += c
            return when (c.getString("op")) {
                "info" -> """{"fingerprint":"AB12-CD34-EF56-7890"}"""
                "peers" -> peers
                else -> """{"ok":true}"""
            }
        }
        override fun stop() { stopped = true }
    }

    private class FakeSink : LinkSink {
        val log = ArrayList<String>()
        override fun setClipboardText(text: String) { log += "clip:$text" }
        override fun setClipboardImage(file: File, mime: String) { log += "image:${file.name}" }
        override fun saveReceived(file: File, name: String, mime: String, from: String) { log += "save:$name:$from" }
        override fun notifyText(from: String, text: String) { log += "notify:$from:$text" }
    }

    private lateinit var backend: FakeBackend
    private lateinit var sink: FakeSink
    private lateinit var m: LinkManager

    @Before fun setUp() {
        WeavePrefs.of(app).edit().clear().commit()
        backend = FakeBackend()
        sink = FakeSink()
        m = LinkManager(app, WeavePrefs.of(app), { backend }, sink)
    }

    private fun event(json: String) { m.onEvent(JSONObject(json)); ShadowLooper.idleMainLooper() }

    @Test fun offByDefaultAndStartsWhenEnabled() {
        m.ensureRunning()
        assertNull(backend.config)
        assertFalse(m.state.value.enabled)
        m.setEnabled(true)
        assertEquals("android", backend.config!!.getString("platform"))
        assertTrue(backend.config!!.getString("stateDir").endsWith("/link"))
        assertTrue(m.state.value.running)
        assertEquals("AB12-CD34-EF56-7890", m.state.value.fingerprint)
        m.setEnabled(false)
        assertTrue(backend.stopped)
        assertFalse(m.state.value.running)
        assertFalse(WeavePrefs.linkEnabled(WeavePrefs.of(app)))
    }

    @Test fun peersPairingAndTransfers() {
        m.setEnabled(true)
        backend.peers = """{"trusted":[{"id":"m1","name":"我的 Mac","platform":"mac","connected":true,"nearby":true,"addrs":["10.0.0.2:47811"]}],
            "nearby":[{"id":"x","name":"另一台","platform":"mac","addrs":["10.0.0.3:47811"]}]}"""
        m.pair(listOf("10.0.0.2:47811"), "123456")
        assertEquals(PairState.Working, m.state.value.pairing)
        assertEquals("123456", backend.calls.last { it.getString("op") == "pair" }.getString("code"))
        event("""{"type":"paired","id":"m1","name":"我的 Mac"}""")
        assertEquals(PairState.Done("我的 Mac"), m.state.value.pairing)
        assertEquals(listOf("我的 Mac"), m.state.value.connected.map { it.name })
        assertEquals("另一台", m.state.value.nearby.single().name)
        event("""{"type":"pairFailed","reason":"handshake rejected (wrong code?)"}""")
        assertEquals(PairState.Failed("配对码不对或已过期"), m.state.value.pairing)

        event("""{"type":"fileStart","id":"t1","name":"a.jpg","size":100,"incoming":true,"fromName":"我的 Mac"}""")
        event("""{"type":"fileProgress","id":"t1","done":40,"size":100,"incoming":true}""")
        assertEquals(0.4f, m.state.value.transfers.single().fraction, 0.001f)
        event("""{"type":"fileDone","id":"t1","name":"a.jpg","path":"/x/a.jpg","mime":"image/jpeg","incoming":true,"fromName":"我的 Mac"}""")
        assertEquals(LinkTransfer.State.DONE, m.state.value.transfers.single().state)
        event("""{"type":"fileDone","id":"t2","name":"c.png","path":"/x/c.png","mime":"image/png","incoming":true,"clip":true}""")
        assertEquals(listOf("save:a.jpg:我的 Mac", "image:c.png"), sink.log)
    }

    @Test fun clipboardSyncNeverEchoes() {
        m.setEnabled(true)
        backend.peers = """{"trusted":[{"id":"m1","name":"Mac","platform":"mac","connected":true}],"nearby":[]}"""
        event("""{"type":"connected","id":"m1"}""")
        m.onLocalClip("手机上复制的")
        assertEquals("手机上复制的", backend.calls.last().getString("text"))
        assertTrue(backend.calls.last().getBoolean("clip"))
        event("""{"type":"text","text":"电脑上复制的","clip":true,"fromName":"Mac"}""")
        assertEquals("clip:电脑上复制的", sink.log.last())
        val before = backend.calls.size
        m.onLocalClip("电脑上复制的") // 系统剪贴板回调：不回传。 The clipboard callback: not echoed.
        m.onLocalClip("手机上复制的") // 同一段不重复发。 The same text isn't resent.
        assertEquals(before, backend.calls.size)
        event("""{"type":"text","text":"直接发来的","clip":false,"fromName":"Mac"}""")
        assertEquals(listOf("clip:直接发来的", "notify:Mac:直接发来的"), sink.log.takeLast(2))
        m.setClipSync(false)
        m.onLocalClip("关了同步")
        assertEquals(before, backend.calls.size)
    }

    @Test fun qrAndShareIntentsParse() {
        val p = LinkUri.parse("weavelink://pair?v=1&id=ab&n=%E6%88%91%E7%9A%84%20Mac&p=mac&a=192.168.1.8%3A47811%2C10.0.0.2%3A47811&c=482913")!!
        assertEquals("我的 Mac", p.name)
        assertEquals(listOf("192.168.1.8:47811", "10.0.0.2:47811"), p.addrs)
        assertEquals("482913", p.code)
        assertNull(LinkUri.parse("weavelink://pair?a=1.2.3.4:1&c=12"))
        assertNull(LinkUri.parse("https://example.com"))

        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "一段话")
        assertEquals(Outgoing.Text("一段话"), Outgoing.from(text))
        val u = Uri.parse("content://x/1")
        val one = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u)
        assertEquals(Outgoing.Files(listOf(u)), Outgoing.from(one))
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(u, u))
        assertEquals(2, (Outgoing.from(many) as Outgoing.Files).uris.size)
        assertNull(Outgoing.from(Intent(Intent.ACTION_SEND)))
    }
}
