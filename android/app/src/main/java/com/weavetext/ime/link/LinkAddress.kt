package com.weavetext.ime.link

object LinkAddress {
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
