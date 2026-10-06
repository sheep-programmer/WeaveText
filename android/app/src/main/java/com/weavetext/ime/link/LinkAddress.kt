package com.weavetext.ime.link

object LinkAddress {
    /** Numeric endpoints only, matching the native core; parsing never performs DNS lookups. */
    fun valid(value: String): Boolean {
        val port = value.substringAfterLast(':', "").toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        val host = value.substringBeforeLast(':', "")
        if (host.startsWith('[') && host.endsWith(']')) {
            val ip = host.substring(1, host.lastIndex)
            return ip.count { it == ':' } >= 2 && ip.all { it in "0123456789abcdefABCDEF:." }
        }
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && part.toInt() in 0..255
        }
    }
    fun normalize(input: String): String {
        val value = input.trim()
        return when {
            value.startsWith("[") -> if (value.endsWith("]")) "$value:${LinkPorts.DEFAULT}" else value
            value.count { it == ':' } > 1 -> "[$value]:${LinkPorts.DEFAULT}"
            ':' in value -> value
            else -> "$value:${LinkPorts.DEFAULT}"
        }
    }
}
