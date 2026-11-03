package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.tailnet.TailnetPeer
import java.util.Locale

/** 设备行图标对应的设备类别。 */
internal enum class TailscalePeerKind { DESKTOP, LAPTOP, PHONE, SERVER, OTHER }

/** 设备列表顺序：在线、离线；同组按名称（忽略大小写）排序。 */
internal fun List<TailnetPeer>.sortedForDisplay(): List<TailnetPeer> = sortedWith(
    compareBy<TailnetPeer>(
        { if (it.online) 0 else 1 },
        { it.name.lowercase(Locale.ROOT) },
    ),
)

/** Tailscale 上报的系统标识（如 `windows`、`linux`、`macOS`）转成显示名；未知值首字母大写。 */
internal fun tailscaleOsLabel(os: String?): String? {
    val value = os?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (value.lowercase(Locale.ROOT)) {
        "windows" -> "Windows"
        "linux" -> "Linux"
        "android" -> "Android"
        "macos", "darwin" -> "macOS"
        "ios" -> "iOS"
        "ipados" -> "iPadOS"
        "tvos" -> "tvOS"
        "freebsd" -> "FreeBSD"
        "openbsd" -> "OpenBSD"
        "js" -> "Web"
        else -> value.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

internal fun tailscalePeerKind(os: String?): TailscalePeerKind = when (os?.trim()?.lowercase(Locale.ROOT)) {
    "windows" -> TailscalePeerKind.DESKTOP
    "macos", "darwin" -> TailscalePeerKind.LAPTOP
    "android", "ios", "ipados" -> TailscalePeerKind.PHONE
    "linux", "freebsd", "openbsd" -> TailscalePeerKind.SERVER
    else -> TailscalePeerKind.OTHER
}

/** 本机地址优先显示 IPv4；没有 IPv4 时取第一个地址。 */
internal fun preferredTailnetAddress(ips: List<String>): String? =
    ips.firstOrNull { it.contains('.') && !it.contains(':') } ?: ips.firstOrNull()
