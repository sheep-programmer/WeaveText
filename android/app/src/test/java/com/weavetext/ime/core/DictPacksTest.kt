package com.weavetext.ime.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 专业词库目录解析：缺字段、坏校验值的条目被丢弃。 Catalog parsing drops entries without an id or a valid hash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictPacksTest {
    @Test fun parsesTheCatalog() {
        val o = JSONObject(
            """{"version":1,"base":"https://x/","packs":[
              {"id":"med","name":"医学","description":"d","license":"MIT","source":"s","words":10,"bytes":20,"sha256":"${"a".repeat(64)}"},
              {"id":"bad","sha256":"123"},{"name":"no id","sha256":"${"b".repeat(64)}"}]}""",
        )
        val p = DictPacks.parse(o)
        assertEquals(listOf("med"), p.map { it.id })
        assertEquals(20L, p[0].bytes)
        assertEquals(emptyList<DictPack>(), DictPacks.parse(null))
    }
}
