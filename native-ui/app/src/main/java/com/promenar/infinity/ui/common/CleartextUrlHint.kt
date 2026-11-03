package com.promenar.infinity.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetHosts
import java.net.URI

/**
 * 地址是否以明文离开本机：http 且目标不是本机回环或 tailnet 地址。
 * tailnet 流量由 WireGuard 加密，回环不出设备，二者不提示。
 */
fun isCleartextRemoteUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val host = uri.host?.let(TailnetHosts::normalize)?.takeIf { it.isNotEmpty() } ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return false
    TailnetHosts.parseAddressLiteral(host)?.let { bytes ->
        if (bytes.size == 4 && bytes[0] == 127.toByte()) return false
        if (bytes.size == 16 && bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return false
        if (TailnetHosts.isTailnetAddress(bytes)) return false
        return true
    }
    return !TailnetHosts.isTailnetHost(host, null, emptySet())
}

/** 地址输入框的帮助行：明文地址时返回“未加密”提示，否则返回 [fallback]。 */
@Composable
fun cleartextUrlHelp(url: String, fallback: String? = null): String? =
    if (isCleartextRemoteUrl(url)) stringResource(R.string.common_cleartext_url_hint) else fallback
