package com.weavetext.ime.link

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.ClipHistory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkContentTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun reset() {
        ClipHistory.awaitIo(); ClipHistory.resetShared()
        // Robolectric gives each test a new data directory, unlike the real application.
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        cache.clear()
        File(app.filesDir, "clipboard").deleteRecursively()
    }
    @Test fun ownedMediaSurvivesNativePruningAndHistoryRestart() {
        val source = File(app.filesDir, "link/clip/test.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(9000) { (it % 253).toByte() }) }
        val body = source.readBytes()
        val item = LinkContent.importFile(app, source, "application/pdf", "report.pdf")
        source.delete()
        assertArrayEquals(body, app.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readBytes() })
        ClipHistory.awaitIo(); ClipHistory.resetShared()
        val restored = LinkContent.history(app).list(System.currentTimeMillis()).single()
        assertEquals("report.pdf", restored.text); assertEquals("application/pdf", restored.mime)
        assertArrayEquals(body, app.contentResolver.openInputStream(Uri.parse(restored.uri))!!.use { it.readBytes() })
        LinkContent.history(app).clearAll(); ClipHistory.awaitIo()
        assertEquals(0, File(app.filesDir, "clipboard/media").listFiles()?.size ?: 0)
    }
    @Test fun currentClipboardWithoutConsentDoesNotCreateHistory() {
        val source = File(app.filesDir, "link/clip/photo.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val item = LinkContent.importFile(app, source, "image/png", "photo.png", record = false)
        ClipHistory.awaitIo()
        assertFalse(File(app.filesDir, "clipboard/history.json").exists())
        assertEquals(0, LinkContent.history(app).size)
        assertArrayEquals(source.readBytes(), app.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readBytes() })
    }
    @Test fun failedDestinationKeepsSourceAndClipDataOnlySharesParse() {
        val source = File(app.cacheDir, "link-inbox/test.bin").apply { parentFile!!.mkdirs(); writeText("keep me") }
        assertTrue(runCatching { LinkFiles.save(app, source, "test.bin", "application/octet-stream", "content://invalid/tree/no") }.isFailure)
        assertEquals("keep me", source.readText())
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", source)
        val intent = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newUri(app.contentResolver, "file", uri) }
        assertEquals(Outgoing.Files(listOf(uri)), Outgoing.from(intent))
        assertEquals("[2001:db8::1]:47811", LinkAddress.normalize("2001:db8::1"))
    }
}
