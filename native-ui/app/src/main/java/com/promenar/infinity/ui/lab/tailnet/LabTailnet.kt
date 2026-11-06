package com.promenar.infinity.ui.lab.tailnet

import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetStatusSummary

/**
 * 实验室共用的虚构 tailnet：手机 infinity-fold 接入 tail-demo.ts.net，家里的 gpu-box 提供模型网关，
 * web-01 是运维脚本里的服务器。名称、地址与 MagicDNS 后缀全部虚构。
 */
internal object LabTailnet {
    const val NAME = "tail-demo.ts.net"
    const val SELF = "infinity-fold"
    const val GPU_BOX = "gpu-box"
    const val GPU_BOX_HOST = "gpu-box.tail-demo.ts.net"
    const val GATEWAY_URL = "https://gpu-box.tail-demo.ts.net/v1"

    val summary = TailnetStatusSummary(
        backendState = TailnetStatusSummary.STATE_RUNNING,
        selfName = SELF,
        selfDnsName = "$SELF.$NAME",
        selfIps = listOf("100.101.7.5", "fd7a:115c:a1e0::5"),
        online = true,
        magicDnsSuffix = NAME,
        tailnetName = NAME,
    )

    /** 设置页的设备列表；[gpuBoxPath] 是 gpu-box 当前的连接路径（打洞成功前经 DERP 中继）。 */
    fun peers(gpuBoxPath: String = TailnetPaths.DIRECT): List<TailnetPeer> = listOf(
        TailnetPeer(
            GPU_BOX, GPU_BOX_HOST, listOf("100.101.7.12"), "linux", online = true, path = gpuBoxPath,
            relayRegion = "tok".takeIf { gpuBoxPath == TailnetPaths.DERP },
            relayRegionName = "Tokyo".takeIf { gpuBoxPath == TailnetPaths.DERP },
        ),
        TailnetPeer("web-01", "web-01.$NAME", listOf("100.101.7.20"), "linux", online = true, path = TailnetPaths.DIRECT),
        TailnetPeer(
            "macbook", "macbook.$NAME", listOf("100.101.7.31"), "macOS", online = true,
            path = TailnetPaths.DERP, relayRegion = "tok", relayRegionName = "Tokyo",
        ),
        TailnetPeer("nas", "nas.$NAME", listOf("100.101.7.40"), "linux", online = false),
    )

    /** 提供商表单用的设备候选：MagicDNS 全名带结尾的点，与 tailscale status 的返回一致。 */
    fun magicDnsPeers(): List<TailnetPeer> = peers().map { peer -> peer.copy(dnsName = peer.dnsName?.let { "$it." }) }
}
