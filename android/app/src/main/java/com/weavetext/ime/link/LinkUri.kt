package com.weavetext.ime.link

import java.net.URLDecoder

/** 配对二维码：`weavelink://pair?v=1&id=…&n=名字&p=mac&a=ip:port,ip:port&c=123456`。 The pairing QR payload. */
object LinkUri {
    fun parse(uri: String): PendingPair? {
        if (!uri.startsWith("weavelink://pair")) return null
        val q = uri.substringAfter('?', "")
        val m = q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to runCatching { URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()
        val code = m["c"]?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
        val addrs = m["a"].orEmpty().split(',').map { it.trim() }.filter { it.contains(':') }
        if (addrs.isEmpty()) return null
        return PendingPair(m["n"].orEmpty().ifEmpty { "电脑" }, addrs, code)
    }
}
