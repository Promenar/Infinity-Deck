package com.promenar.infinity.ui.renderer

import java.net.InetAddress
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object RichContentRequestPolicy {
    const val BASE_URL = "https://appassets.androidplatform.net/assets/"
    private const val ASSET_HOST = "appassets.androidplatform.net"
    enum class Kind { LOCAL_ASSET, INLINE_DATA, PUBLIC_HTTPS, BLOCKED }

    fun classify(url: String): Kind {
        if (richContentInlineMimeType(url) != null) return Kind.INLINE_DATA
        val uri = parse(url) ?: return Kind.BLOCKED
        val parsed = url.toHttpUrlOrNull() ?: return Kind.BLOCKED
        val host = parsed.host.trimEnd('.')
        // 虚拟媒体来源只由绑定会话的专用处理器响应，任何未绑定客户端均不得落到网络。
        if (host == HtmlWorkspaceMediaSource.HOST) return Kind.BLOCKED
        if (host == ASSET_HOST) return if (assetPath(url) != null) Kind.LOCAL_ASSET else Kind.BLOCKED
        if (!host.contains('.') && !host.contains(':')) return Kind.BLOCKED
        if (listOf("localhost", "local", "internal", "lan", "home", "invalid", "test").any {
                host == it || host.endsWith(".$it")
            }) return Kind.BLOCKED
        if (host.startsWith("0x", ignoreCase = true)) return Kind.BLOCKED
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 1 && it.startsWith('0') || it.toIntOrNull() !in 0..255 }) {
                return Kind.BLOCKED
            }
            if (!isPublicAddress(InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()))) return Kind.BLOCKED
        } else if (host.contains(':')) {
            if (uri.host.contains('%') || !runCatching { isPublicAddress(InetAddress.getByName(host)) }.getOrDefault(false)) return Kind.BLOCKED
        }
        return Kind.PUBLIC_HTTPS
    }

    fun assetPath(url: String): String? {
        val uri = parse(url) ?: return null
        if (uri.host != ASSET_HOST || uri.port !in listOf(-1, 443)) return null
        val path = uri.rawPath.removePrefix("/assets/")
        if (!uri.rawPath.startsWith("/assets/") || !path.matches(Regex("[A-Za-z0-9_./-]+"))) return null
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return path.takeIf {
            it == "kg_template.html" || listOf("katex/", "mermaid/", "echarts/").any(it::startsWith)
        }
    }

    private fun parse(url: String): URI? {
        if (url.any { it <= ' ' || it == '\\' }) return null
        return runCatching { URI(url) }.getOrNull()?.takeIf {
            it.scheme.equals("https", ignoreCase = true) && it.host != null && it.rawUserInfo == null
        }
    }

    fun arePublicAddresses(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all(::isPublicAddress)

    private fun isPublicAddress(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val (a, b, c) = bytes
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                a == 100 && b in 64..127 || a == 169 && b == 254 ||
                a == 172 && b in 16..31 || a == 192 && (b == 168 || b == 0 && c in listOf(0, 2) || b == 88 && c == 99) ||
                a == 198 && (b in 18..19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
        }
        if (bytes.size != 16 || bytes[0] !in 0x20..0x3f) return false
        // 拒绝文档、Teredo、6to4 等可能嵌入非公网 IPv4 的特殊前缀。
        return !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
            (bytes[2] <= 1 || bytes[2] == 0x0d && bytes[3] == 0xb8) ||
            bytes[0] == 0x20 && bytes[1] == 0x02 ||
            bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10)
    }
}
