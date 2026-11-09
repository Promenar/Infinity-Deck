package com.promenar.infinity.data.keychain

import java.net.IDN
import java.net.URI
import java.util.Locale

/** 网站凭据只匹配规范化后的完整主机及显式端口。 */
object KeychainHost {
    fun normalize(host: String, port: Int?): String? {
        if (port != null && port !in 1..65535) return null
        val value = host.trim().trimEnd('.')
        if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() } ||
            value.any { it in "/:@?#[]\\" }) return null
        val ascii = try { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
        catch (_: IllegalArgumentException) { return null }
        if (ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return if (port == null) ascii else "$ascii:$port"
    }

    fun fromUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val uri = try { URI(if ("://" in text) text else "https://$text") }
        catch (_: Exception) { return null }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val authority = uri.rawAuthority ?: return null
        if (authority.count { it == ':' } > 1) return null
        val host = authority.substringBefore(':')
        val port = if (':' in authority) authority.substringAfter(':').toIntOrNull() ?: return null else null
        return normalize(host, port)
    }
}
