package com.weavetext.ime.link

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.ClipHistory
import com.weavetext.ime.settings.WeavePrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class LinkDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private fun start(name: String): NativeLink {
        val dir = File(ctx.cacheDir, "link-device-${UUID.randomUUID()}")
        return NativeLink().also { assertTrue(it.start(JSONObject().put("name",name).put("platform","android").put("port",1).put("mdns",false)
            .put("stateDir",File(dir,"state").path).put("inboxDir",File(dir,"inbox").path).toString())) }
    }
    private fun info(link: NativeLink) = JSONObject(link.call("""{"op":"info"}"""))
    private fun await(link: NativeLink, type: String): JSONObject {
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) {
            val e = JSONObject(link.poll(250) ?: error("core stopped"))
            if (e.optString("type") == type) return e
            if (e.optString("type") == "pairFailed") error(e.toString())
        }
        error("No $type event")
    }
    @Test fun systemNsdDiscoversRealNativePeer() {
        val a = start("Device A"); val b = start("Device B")
        val ai = info(a); val bi = info(b)
        val found = CountDownLatch(2)
        val aFound = AtomicBoolean(false); val bFound = AtomicBoolean(false)
        lateinit var ad: LinkDiscovery; lateinit var bd: LinkDiscovery
        instrumentation.runOnMainSync {
            ad = LinkDiscovery(ctx, { command -> if (command.optString("op") == "discovered" && JSONObject(a.call(command.toString())).optBoolean("ok") && command.optString("id") == bi.getString("id") && aFound.compareAndSet(false, true)) found.countDown() }, { _, _ -> })
            bd = LinkDiscovery(ctx, { command -> if (command.optString("op") == "discovered" && JSONObject(b.call(command.toString())).optBoolean("ok") && command.optString("id") == ai.getString("id") && bFound.compareAndSet(false, true)) found.countDown() }, { _, _ -> })
            ad.start(ai); bd.start(bi)
        }
        try {
            assertTrue("System NSD did not discover the peer", found.await(25, TimeUnit.SECONDS))
            assertTrue(JSONObject(a.call("""{"op":"peers"}""")).getJSONArray("nearby").length() > 0)
            assertTrue(JSONObject(b.call("""{"op":"peers"}""")).getJSONArray("nearby").length() > 0)
        } finally {
            instrumentation.runOnMainSync { ad.stop(); bd.stop() }
            a.stop(); a.destroy(); b.stop(); b.destroy()
        }
    }
    @Test fun macToPhoneImageFileClipboardDownloadsAndPhoneReturn() {
        val args = InstrumentationRegistry.getArguments()
        val directTicket = args.getString("hostTicket")
        org.junit.Assume.assumeTrue("Requires tools/link-device-check.py", args.getString("hostAddress") != null || directTicket != null)
        ActivityScenario.launch(SmokeActivity::class.java).use {
            val phone = start("WeaveLink test phone")
            try {
                WeavePrefs.of(ctx).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
                if (directTicket != null) {
                    assertTrue(JSONObject(phone.call(JSONObject().put("op", "openDirect").put("stun", JSONArray(listOf(args.getString("stunAddress")!!))).toString())).optBoolean("ok"))
                    val local = await(phone, "directReady")
                    assertTrue("The STUN mapping through the emulator NAT is required", local.getBoolean("public"))
                    instrumentation.sendStatus(1, android.os.Bundle().apply { putString("directTicket", local.getString("ticket")) })
                    assertTrue(JSONObject(phone.call(JSONObject().put("op", "joinDirect").put("ticket", directTicket).toString())).optBoolean("ok"))
                } else {
                    val address = args.getString("hostAddress")!!
                    val code = args.getString("hostCode") ?: error("hostCode missing")
                    assertTrue(JSONObject(phone.call(JSONObject().put("op","pair").put("addrs",JSONArray(listOf(address))).put("code",code).toString())).optBoolean("ok"))
                }
                await(phone,"paired")
                if (directTicket != null) assertEquals("direct-udp", await(phone, "connected").getString("transport"))
                phone.call("""{"op":"sendText","text":"device-ready","clip":false}""")
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                val sink = AndroidLinkSink(ctx)
                val expected = ByteArray(90_000) { (it % 253).toByte() }
                var returned = false
                var personalReturn: String? = null
                var personalReturned = false
                repeat(4) {
                    var e = await(phone,"fileDone")
                    while (!e.optBoolean("incoming")) {
                        if(e.optString("id")==personalReturn)personalReturned=true
                        e = await(phone,"fileDone")
                    }
                    val file = File(e.getString("path")); val mime = e.getString("mime")
                    if(mime=="application/x-weavetext-personal") {
                        assertEquals("personal-inbox",file.parentFile!!.name)
                        val user=File(ctx.cacheDir,"profile-peer-${UUID.randomUUID()}")
                        com.weavetext.ime.core.NativeEngine.createFromSpec(com.weavetext.ime.core.DataInstaller.sourceSpec(ctx),user.path,com.weavetext.ime.core.DataInstaller.cacheKb(ctx))!!.use {engine ->
                            val command=JSONObject().put("op","importPersonal").put("data",file.readText()).toString()
                            repeat(2) {assertTrue(JSONObject(engine.features(command)).getBoolean("ok"))}
                            "shi".forEach {engine.inputChar(it.code)}
                            assertEquals("嗜",engine.snapshot().candidates.first().text);engine.clear()
                            assertTrue(JSONObject(engine.features("""{"op":"setSnippet","code":"phone","text":"来自手机"}""")).getBoolean("ok"))
                            val data=JSONObject(engine.features("""{"op":"exportPersonal"}""")).getString("data")
                            val back=File(user,"phone-personal.weaveprofile").apply {writeText(data)}
                            val sent=JSONObject(phone.call(JSONObject().put("op","sendFile").put("path",back.path).put("name",back.name).put("mime",mime).toString()))
                            assertTrue(sent.optBoolean("ok"));personalReturn=sent.getString("id")
                        }
                        user.deleteRecursively()
                        return@repeat
                    }
                    val complete = CountDownLatch(1)
                    var destination: String? = null; var failure: String? = null
                    instrumentation.runOnMainSync {
                        val done: (String?,String?) -> Unit = { uri,error -> destination=uri; failure=error; complete.countDown() }
                        if (e.optBoolean("clip")) sink.setClipboardFileAt(file,mime,e.getString("name"),done)
                        else sink.saveReceivedAt(file,e.getString("name"),mime,"Mac","",done)
                    }
                    assertTrue(complete.await(20, TimeUnit.SECONDS)); assertNull(failure)
                    val uri = Uri.parse(destination!!)
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (mime == "image/png") {
                        assertNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                        instrumentation.runOnMainSync { assertEquals(uri,cm.primaryClip!!.getItemAt(0).uri) }
                    } else {
                        assertArrayEquals(expected,bytes)
                        if (e.optBoolean("clip")) {
                            instrumentation.runOnMainSync { assertEquals(uri,cm.primaryClip!!.getItemAt(0).uri) }
                            if (!returned) {
                                val fd = ctx.contentResolver.openFileDescriptor(uri,"r")!!.detachFd()
                                assertTrue(JSONObject(phone.call(JSONObject().put("op","sendFile").put("fd",fd).put("name","phone-return.bin").put("mime",mime).toString())).optBoolean("ok"))
                                returned = true
                            }
                        }
                    }
                }
                while(!personalReturned) {val event=await(phone,"fileDone");if(event.optString("id")==personalReturn)personalReturned=true}
                ClipHistory.awaitIo(); ClipHistory.resetShared()
                val history = LinkContent.history(ctx).list(System.currentTimeMillis())
                assertTrue(history.any { it.mime == "image/png" }); assertTrue(history.any { it.mime == "application/octet-stream" })
                phone.call("""{"op":"sendText","text":"device-finished","clip":false}""")
            } finally { phone.stop(); phone.destroy() }
        }
    }
}
