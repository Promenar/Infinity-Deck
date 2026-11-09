package com.promenar.infinity.data.browser

import com.promenar.infinity.data.tailnet.InstalledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetHosts
import java.util.Locale

/** 两个浏览器入口共用的缺省协议规则。 */
internal object BrowserUrlInput {
    private val schemePrefix = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\d)""")
    private val bareHost = Regex(
        """^(localhost|[a-zA-Z0-9-]+|\d{1,3}(\.\d{1,3}){3}|([\p{L}\p{N}-]+\.)+\p{L}{2,}|\[[0-9a-fA-F:.]+])(:\d{1,5})?([/?#]\S*)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun isBareHost(input: String): Boolean = bareHost.matches(input) || isUnbracketedIpv6(input)

    fun withDefaultScheme(input: String): String {
        val authority = input.substringBefore('/').substringBefore('?').substringBefore('#')
        if (isUnbracketedIpv6(input)) {
            return "http://[$authority]" + input.substring(authority.length)
        }
        if (schemePrefix.containsMatchIn(input)) return input
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        val local = normalized == "localhost" || normalized.endsWith(".localhost") ||
            TailnetHosts.parseAddressLiteral(host) != null || '.' !in normalized ||
            normalized.endsWith(".ts.net") || InstalledTailnetRouting.routing.isTailnetHost(host)
        return (if (local) "http://" else "https://") + input
    }

    private fun isUnbracketedIpv6(input: String): Boolean {
        val host = input.substringBefore('/').substringBefore('?').substringBefore('#')
        return ':' in host && !host.startsWith('[') && TailnetHosts.parseAddressLiteral(host) != null
    }
}
