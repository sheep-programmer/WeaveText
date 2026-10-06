package com.weavetext.ime.link

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkQrTest {
    private val now = System.currentTimeMillis() / 1000
    private fun directJson() = JSONObject().put("v", 1).put("id", "0123456789abcdef").put("name", "工作 Mac")
        .put("code", "482913").put("cert", "30" + "ab".repeat(400)).put("expires", now + 300)
        .put("addrs", JSONArray(listOf("203.0.113.7:47811", "[2001:db8::7]:47811")))
    private fun ticket(o: JSONObject) = "weavelink://direct/" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())

    @Test fun pairingQrAndNumericAddressesAreRecognized() {
        val result = LinkQrPayload.parse("weavelink://pair?v=1&n=Mac&a=192.168.1.8%3A47811&c=482913") as LinkQrPayload.Pairing
        assertEquals("Mac", result.request.name)
        assertEquals("482913", result.request.code)
        assertTrue(LinkAddress.valid("[2001:db8::1]:47811"))
        assertFalse(LinkAddress.valid("1.2.3.999:47811"))
        assertFalse(LinkAddress.valid("1.2.3.4:0"))
    }

    @Test fun unrelatedLinksAndLookalikePairingHostsAreRejected() {
        for (text in listOf("https://example.com", "weavelink://pair-fake?a=1.2.3.4:1&c=123456",
            "weavelink://pair?a=example.com:47811&c=123456", "weavelink://pair?a=1.2.3.4:1&c=１２３４５６")) {
            assertNull(text, LinkQrPayload.parse(text))
        }
    }

    @Test fun directQrPreservesTheWholeConnectionCode() {
        val text = ticket(directJson())
        val parsed = LinkQrPayload.parse(text, now) as LinkQrPayload.Direct
        assertEquals(text, parsed.ticket)
        assertEquals("工作 Mac", parsed.name)
    }

    @Test fun expiredMalformedOrOversizedDirectCodesAreRejected() {
        assertNull(LinkQrPayload.parse(ticket(directJson().put("expires", now - 1)), now))
        assertNull(LinkQrPayload.parse(ticket(directJson().put("v", 2)), now))
        assertNull(LinkQrPayload.parse(ticket(directJson().put("addrs", JSONArray())), now))
        assertNull(LinkQrPayload.parse("weavelink://direct/%%bad"))
        assertNull(LinkQrPayload.parse("x".repeat(16_385)))
    }

    @Test fun generatedDenseQrRoundTripsThroughTheCameraDecoder() {
        val text = ticket(directJson())
        val bitmap = LinkQrImage.bitmap(text, 720)!!
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(text, decoded.text)
        assertTrue(LinkQrPayload.parse(decoded.text, now) is LinkQrPayload.Direct)
        assertNull(LinkQrImage.bitmap("x".repeat(16_384)))
    }
}
