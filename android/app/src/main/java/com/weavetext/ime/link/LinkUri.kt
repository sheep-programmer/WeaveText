package com.weavetext.ime.link

import java.net.URLDecoder
import java.net.URI

/** 配对二维码：`weavelink://pair?v=1&id=…&n=名字&p=mac&a=ip:port,ip:port&c=123456`。 The pairing QR payload. */
object LinkUri {
    fun parse(uri: String): PendingPair? {
        if (uri.length > 16_384) return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (parsed.scheme != "weavelink" || parsed.host != "pair" || parsed.rawUserInfo != null || parsed.port != -1 || !parsed.path.isNullOrEmpty()) return null
        val q = parsed.rawQuery.orEmpty()
        val m = q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to runCatching { URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()
        if (m["v"] != null && m["v"] != "1") return null
        val code = m["c"]?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } } ?: return null
        val addrs = m["a"].orEmpty().split(',').map { it.trim() }
        if (addrs.isEmpty() || addrs.size > 16 || addrs.any { !LinkAddress.valid(it) }) return null
        return PendingPair(m["n"].orEmpty().ifEmpty { "电脑" }.take(40), addrs, code)
    }
}
