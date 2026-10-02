package com.weavetext.ime.stickers

import android.graphics.BitmapFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class StickerStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val root get()=generateSequence(File(checkNotNull(System.getProperty("user.dir")))) {it.parentFile}.first {File(it,"tests/fixtures/stickers").isDirectory}
    private fun fixture(name:String)=File(root,"tests/fixtures/stickers/$name").readBytes()
    private fun store(dir:File)=StickerStore(dir) {file ->
        val b=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,b);b.outWidth to b.outHeight
    }
    private fun rejects(block:()->Unit){try {block();fail("Expected invalid content to be rejected")}catch(e:IllegalArgumentException){assertFalse(e.message.isNullOrBlank())}catch(e:IllegalStateException){assertFalse(e.message.isNullOrBlank())}}
    private fun archive(catalog:String,assets:Map<String,ByteArray>):ByteArray {
        val out=ByteArrayOutputStream();ZipOutputStream(out).use {zip ->
            for((name,bytes) in mapOf("catalog.json" to catalog.toByteArray())+assets){zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
        };return out.toByteArray()
    }
    @Test fun originalsSurviveWrongExtensionDedupRestartAndClipboardRemoval() {
        val dir=temp.newFolder();val s=store(dir)
        for((name,mime,ext) in listOf(Triple("sample.png","image/png","png"),Triple("animated.gif","image/gif","gif"),Triple("animated.webp","image/webp","webp"))) {
            val bytes=fixture(name);val (item,new)=s.import(bytes.inputStream(),"wrong.txt")
            assertTrue(new);assertEquals(mime,item.mime);assertTrue(item.file.endsWith(".$ext"));assertEquals(80,item.width)
            assertArrayEquals(bytes,s.file(item).readBytes());assertFalse(s.import(bytes.inputStream(),"another.png").second)
            s.edit(item.id,"快乐", "日常",listOf("开心","happy","开心"),true);s.used(item.id)
        }
        File(dir.parentFile,"clipboard").apply {mkdirs();writeTextIfFile()}.deleteRecursively()
        val next=store(dir);assertEquals(3,next.list("HAPPY","favorites").size);assertEquals(3,next.list(filter="recent").size)
        assertEquals(listOf("日常"),next.groups());assertTrue(next.list().all {next.file(it).isFile})
        next.group(next.list().map {it.id}.toSet(),"工作");assertEquals(3,next.list(filter="group:工作").size)
        val removed=next.list().first();next.delete(setOf(removed.id));assertFalse(next.file(removed).exists());assertEquals(2,store(dir).list().size)
    }
    private fun File.writeTextIfFile(){File(this,"unrelated").writeText("temporary clip")}
    @Test fun portableBackupPreservesMetadataAndReadsMacArchive() {
        val dir=temp.newFolder();val s=store(dir);val (item,_)=s.import(fixture("animated.gif").inputStream(),"动画")
        s.edit(item.id,"晚安","日常",listOf("睡觉"),true);s.used(item.id)
        val bytes=ByteArrayOutputStream().also {s.export(it)}.toByteArray()
        val restored=store(temp.newFolder());assertEquals(1 to 0,restored.importArchive(bytes.inputStream()))
        assertEquals(s.list(),restored.list());assertArrayEquals(fixture("animated.gif"),restored.file(restored.list().single()).readBytes())
        assertEquals(0 to 1,restored.importArchive(bytes.inputStream()))
        val out=File(root,".ref/sticker-interop").apply {mkdirs()};File(out,"android.zip").writeBytes(bytes)
        val mac=File(out,"mac.zip")
        if(mac.isFile){assertEquals(2 to 1,restored.importArchive(mac.inputStream()));assertEquals(3,restored.list().size)}
    }
    @Test fun invalidImagesAndMalformedArchivesDoNotEscapeAssetDirectory() {
        val dir=temp.newFolder();val s=store(dir)
        rejects {s.import("not an image".byteInputStream(),"x.png")}
        rejects {s.import(ByteArray(10){if(it<4)"RIFF"[it].code.toByte()else 0}.inputStream(),"x.webp")}
        rejects {s.import(ByteArrayInputStream(ByteArray(StickerStore.MAX_BYTES.toInt()+1)),"huge.png")}
        val (item,_)=s.import(fixture("sample.png").inputStream(),"sample")
        val catalog=JSONObject(File(dir,"catalog.json").readText())
        val meta=catalog.getJSONArray("items").getJSONObject(0);meta.put("file","../../escape.png")
        rejects {store(temp.newFolder()).importArchive(archive(catalog.toString(),emptyMap()).inputStream())}
        assertFalse(File(dir.parentFile,"escape.png").exists())
        meta.put("file",item.file)
        rejects {store(temp.newFolder()).importArchive(archive(catalog.toString(),mapOf("originals/${item.file}" to fixture("animated.gif"))).inputStream())}
        rejects {store(temp.newFolder()).importArchive(archive(catalog.toString(),emptyMap()).inputStream())}
        assertEquals(1,s.list().size)
    }
    @Test fun corruptIndexFallsBackToLastValidCatalogAndCannotOverwriteSilently() {
        val dir=temp.newFolder();val s=store(dir);val (item,_)=s.import(fixture("sample.png").inputStream(),"图")
        s.edit(item.id,"编辑","组",emptyList(),false)
        File(dir,"catalog.json").writeText("corrupt")
        assertEquals("图",store(dir).list().single().name)
        File(dir,"catalog.json.bak").delete();val broken=store(dir);assertNotNull(broken.loadError)
        rejects {broken.import(fixture("animated.gif").inputStream(),"动画")}
        assertEquals("corrupt",File(dir,"catalog.json").readText())
    }
}
