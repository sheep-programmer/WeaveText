package com.weavetext.ime.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfficialMarketTest {
    private fun item(id: String = "newtheme") = JSONObject()
        .put("id", id).put("kind", "theme").put("name", "新主题").put("summary", "测试")
        .put("platforms", JSONArray(listOf("android", "mac"))).put("source", "remote")
        .put("file", "themes/theme-$id.json").put("url", "${OfficialMarket.RAW}/themes/theme-$id.json")
        .put("sha256", "a".repeat(64)).put("bytes", 1024)
    private fun catalog(vararg items: JSONObject) = JSONObject().put("version", 1).put("items", JSONArray(items.toList())).toString().toByteArray()
    @Test fun catalogIsPlatformFilteredAndAllowsOnlyOfficialData() {
        val parsed = OfficialMarket.parse(catalog(item(), item("mactheme").put("platforms", JSONArray(listOf("mac")))))
        assertEquals(listOf("theme:newtheme"), parsed.map { it.key })
        assertEquals(1024L, parsed.single().bytes)
        assertEquals("a".repeat(64), parsed.single().sha256)
    }
    @Test fun remoteCatalogCannotReplaceBaseInputOrUseForeignURLs() {
        for (bad in listOf(item("fresh"), item().put("kind", "feature"), item().put("url", "https://example.org/theme.json"), item().put("bytes", 256 * 1024 + 1), item().put("sha256", "none"))) {
            assertThrows(IllegalArgumentException::class.java) { OfficialMarket.parse(catalog(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { OfficialMarket.parse(catalog(item(), item())) }
    }
    @Test fun githubEnvelopeAndRawJSONHaveTheSamePayload() {
        val raw = catalog(item())
        val wrapper = JSONObject().put("encoding", "base64").put("content", java.util.Base64.getEncoder().encodeToString(raw)).toString().toByteArray()
        assertArrayEquals(raw, OfficialMarket.unwrap(wrapper))
        assertArrayEquals(raw, OfficialMarket.unwrap(raw))
        assertEquals(OfficialMarket.digest(raw), OfficialMarket.digest(OfficialMarket.unwrap(wrapper)))
    }
}
