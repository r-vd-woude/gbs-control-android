package com.gbscontrol.app

import java.net.URI

object HostAddress {
    fun normalize(input: String): String {
        var value = input.trim()
        require(value.isNotEmpty()) { "Device address cannot be empty" }

        if (value.contains("://")) {
            val uri = URI(value)
            value = uri.host ?: throw IllegalArgumentException("Invalid device address")
        } else {
            value = value.substringBefore('/').trim()
            if (value.startsWith('[') && value.endsWith(']')) {
                value = value.substring(1, value.length - 1)
            }
        }

        require(value.isNotBlank() && !value.any(Char::isWhitespace)) {
            "Invalid device address"
        }
        return value.trimEnd('.')
    }

    fun authority(host: String): String {
        val normalized = normalize(host)
        return if (':' in normalized) "[${normalized.replace("%", "%25")}]" else normalized
    }

    fun httpUrl(host: String, path: String = "/"): String {
        val safePath = if (path.startsWith('/')) path else "/$path"
        return "http://${authority(host)}$safePath"
    }

    fun webSocketUrl(host: String): String = "ws://${authority(host)}/ws"
}
