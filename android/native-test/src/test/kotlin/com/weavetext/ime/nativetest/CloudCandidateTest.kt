package com.weavetext.ime.nativetest

import com.weavetext.ime.core.NativeEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudCandidateTest {
    @Test fun signedCloudWordsKeepTheirBadgeAcrossSnapshotPagingAndLearning() {
        val data=File(System.getProperty("weave.data"))
        val fixtures=data.parentFile.parentFile.resolve("core/weave-engine/tests/fixtures")
        val user=java.nio.file.Files.createTempDirectory("weave-cloud-candidate-").toFile()
        try {NativeEngine.create(data.path,user.path)!!.use {e ->
            assertEquals(2,e.loadHotwords(fixtures.resolve("hotwords.tsv").path,fixtures.resolve("hotwords.tsv.sig").path))
            "zhiwenshurufa".forEach {assertTrue(e.inputChar(it.code))}
            val c=e.snapshot().candidates.first {it.text=="织文输入法"}
            assertTrue(c.isCloud);assertFalse(c.isUser)
            val all=e.candidates(0,800);val i=all.indexOfFirst {it.text==c.text}
            assertTrue(all[i].isCloud);assertFalse(all[i].isUser)
            assertTrue(e.select(i));assertEquals("织文输入法",e.snapshot().commit)
            "zhiwenshurufa".forEach {e.inputChar(it.code)}
            assertTrue(e.snapshot().candidates.any {it.text==c.text && it.isCloud && it.isUser})
            e.unloadPack("cloud");e.clear()
            "zhiwenshurufa".forEach {e.inputChar(it.code)}
            assertTrue(e.snapshot().candidates.any {it.text==c.text && !it.isCloud && it.isUser})
        }} finally {user.deleteRecursively()}
    }
}
