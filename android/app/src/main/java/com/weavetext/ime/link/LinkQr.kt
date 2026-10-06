package com.weavetext.ime.link

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.util.Base64

/** Only recognized WeaveLink payloads leave the scanner; a scan never opens an arbitrary URL. */
sealed interface LinkQrPayload {
    data class Pairing(val request: PendingPair) : LinkQrPayload
    data class Direct(val ticket: String, val name: String) : LinkQrPayload

    companion object {
        fun parse(text: String, nowSeconds: Long = System.currentTimeMillis() / 1000): LinkQrPayload? {
            val value = text.trim()
            if (value.length > 16_384) return null
            LinkUri.parse(value)?.let { return Pairing(it) }
            if (!value.startsWith("weavelink://direct/")) return null
            return runCatching {
                val encoded = value.removePrefix("weavelink://direct/")
                if (encoded.isEmpty() || encoded.any { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" }) return null
                val o = JSONObject(String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
                val id = o.getString("id")
                val code = o.getString("code")
                val cert = o.getString("cert")
                val name = o.optString("name", "对方设备")
                val addrs = o.getJSONArray("addrs")
                val expires = o.getLong("expires")
                if (o.getInt("v") != 1 || id.length != 16 || id.any { it !in "0123456789abcdefABCDEF" }
                    || code.length != 6 || code.any { it !in '0'..'9' } || cert.isEmpty() || cert.length > 8192
                    || cert.length % 2 != 0 || cert.any { it !in "0123456789abcdefABCDEF" } || name.length > 256
                    || addrs.length() !in 1..16 || (0 until addrs.length()).any { !LinkAddress.valid(addrs.getString(it)) }
                    || expires < nowSeconds || expires > nowSeconds + 360
                ) return null
                Direct(value, name.ifBlank { "对方设备" })
            }.getOrNull()
        }
    }
}

object LinkQrImage {
    /** Integer-sized modules and a quiet zone keep dense connection codes readable. */
    fun bitmap(text: String, pixels: Int = 720): Bitmap? = runCatching {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        ))
        val scale = (pixels / bits.width).coerceAtLeast(1)
        val size = bits.width * scale
        val colors = IntArray(size * size) { i -> if (bits[i % size / scale, i / size / scale]) 0xff000000.toInt() else 0xffffffff.toInt() }
        Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
