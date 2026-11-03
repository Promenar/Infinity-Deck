package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPingResult
import java.util.Locale
import kotlin.math.roundToInt

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

/** 设备行与设备面板显示的连接路径种类。 */
internal enum class TailscalePathKind { DIRECT, PEER_RELAY, DERP, IDLE, NO_REPLY }

/** 一条连接路径的显示信息；[latencyMs] 只在 Ping 过之后才有。 */
internal data class TailscalePathView(
    val kind: TailscalePathKind,
    val lan: Boolean = false,
    val region: String? = null,
    val regionName: String? = null,
    val latencyMs: Double? = null,
)

/** 一次 Ping 的结果：有路径时取其路径，超时或失败视为无响应。 */
internal fun TailnetPingResult.pathView(): TailscalePathView =
    pathView(path, directLan, relayRegion, relayRegionName, latencyMs) ?: TailscalePathView(TailscalePathKind.NO_REPLY)

/**
 * 设备行的路径：本次打开页面后 Ping 过且有路径时用那次结果（带延迟），否则用节点状态里的被动路径；
 * 离线设备返回 null，不显示路径。
 */
internal fun TailnetPeer.pathView(lastPing: TailnetPingResult? = null): TailscalePathView? {
    if (!online) return null
    lastPing?.takeIf { it.path != null }?.let { return it.pathView() }
    return pathView(path, directLan, relayRegion, relayRegionName, latencyMs = null) ?: TailscalePathView(TailscalePathKind.IDLE)
}

private fun pathView(path: String?, lan: Boolean, region: String?, regionName: String?, latencyMs: Double?): TailscalePathView? {
    val kind = when (path) {
        TailnetPaths.DIRECT -> TailscalePathKind.DIRECT
        TailnetPaths.PEER_RELAY -> TailscalePathKind.PEER_RELAY
        TailnetPaths.DERP -> TailscalePathKind.DERP
        else -> return null
    }
    return TailscalePathView(
        kind = kind,
        lan = kind == TailscalePathKind.DIRECT && lan,
        region = region?.takeIf { kind == TailscalePathKind.DERP && it.isNotBlank() },
        regionName = regionName?.takeIf { kind == TailscalePathKind.DERP && it.isNotBlank() },
        latencyMs = latencyMs?.takeIf { it >= 0 },
    )
}

/** 延迟毫秒数：10 ms 以下保留一位小数，便于分辨局域网里的细小差别。 */
internal fun tailscaleLatencyText(ms: Double): String =
    if (ms < 10) String.format(Locale.ROOT, "%.1f", ms) else ms.roundToInt().toString()

/** Tailscale 官方 DERP 区域代码对应的中文城市名；自建或新区域回退到 DERP map 的英文名或代码。 */
private val DerpRegionNamesZh = mapOf(
    "hkg" to "香港", "tok" to "东京", "sin" to "新加坡", "syd" to "悉尼", "blr" to "班加罗尔",
    "dbi" to "迪拜", "sfo" to "旧金山", "sea" to "西雅图", "lax" to "洛杉矶", "den" to "丹佛",
    "dfw" to "达拉斯", "ord" to "芝加哥", "nyc" to "纽约", "mia" to "迈阿密", "tor" to "多伦多",
    "hnl" to "檀香山", "sao" to "圣保罗", "lhr" to "伦敦", "ams" to "阿姆斯特丹", "fra" to "法兰克福",
    "par" to "巴黎", "mad" to "马德里", "waw" to "华沙", "jnb" to "约翰内斯堡", "nai" to "内罗毕",
)

/** 中转区域的显示名：中文界面优先中文城市名；其余用 DERP map 的名称，再退到大写代码。 */
internal fun tailscaleRegionLabel(region: String?, regionName: String?, chinese: Boolean): String? {
    val code = region?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    if (chinese) code?.let(DerpRegionNamesZh::get)?.let { return it }
    return regionName?.trim()?.takeIf { it.isNotEmpty() } ?: code?.uppercase(Locale.ROOT)
}
