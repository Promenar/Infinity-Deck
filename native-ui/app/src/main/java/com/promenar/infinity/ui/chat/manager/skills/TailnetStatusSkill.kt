package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetFailure
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 只读查询内置 Tailscale：本机节点名、地址与在线状态，以及 tailnet 节点列表。
 * 不输出登录链接、auth key、代理端口与凭据；登录与设置只能由用户在「设置 > Tailscale」完成。
 */
class TailnetStatusSkill(private val controller: TailnetController) : SkillDefinition {
    override val id = "tailnet_status"
    override val name = "tailnet_status"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "查询内置 Tailscale：本机节点名、tailnet IP、是否在线，以及 tailnet 节点（名称、IP、系统、在线状态）。" +
        "在线时 tailnet IP 与 MagicDNS 名称可直接用于 SSH 主机、shell_exec/python_exec 中的 git 与 HTTP(S)；web_fetch 只访问公网。"
    override val parametersSchema = """{"type":"object","properties":{},"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        ToolResult(id = "tailnet_${UUID.randomUUID()}", content = render(controller.refresh()).toString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ToolResult(
            id = "tailnet_${UUID.randomUUID()}",
            content = buildJsonObject {
                put("error_code", "TAILNET_STATUS_FAILED")
                put("guidance", "读取内置 Tailscale 状态失败；请稍后重试，或让用户在「设置 > Tailscale」查看。")
            }.toString(),
            status = "error",
        )
    }

    companion object {
        const val MAX_PEERS = 200

        fun render(state: TailnetState): JsonObject = buildJsonObject {
            put("state", state.phase.name.lowercase())
            val summary = state.summary
            if (state.phase == TailnetPhase.RUNNING && summary != null) {
                put(
                    "self",
                    buildJsonObject {
                        summary.selfName?.let { put("name", it) }
                        summary.selfDnsName?.let { put("dns_name", it) }
                        put("ips", JsonArray(summary.selfIps.map(::JsonPrimitive)))
                        put("online", summary.online)
                    },
                )
                summary.tailnetName?.let { put("tailnet", it) }
                summary.magicDnsSuffix?.let { put("magic_dns_suffix", it) }
                put(
                    "peers",
                    JsonArray(
                        state.peers.take(MAX_PEERS).map { peer ->
                            buildJsonObject {
                                put("name", peer.name)
                                peer.dnsName?.let { put("dns_name", it) }
                                put("ips", JsonArray(peer.ips.map(::JsonPrimitive)))
                                peer.os?.takeIf { it.isNotBlank() }?.let { put("os", it) }
                                put("online", peer.online)
                            }
                        },
                    ),
                )
                if (state.peers.size > MAX_PEERS) put("peers_truncated", true)
            }
            if (!summary?.health.isNullOrEmpty()) put("health", JsonArray(summary!!.health.take(10).map(::JsonPrimitive)))
            put("guidance", guidance(state))
        }

        private fun guidance(state: TailnetState): String = when (state.phase) {
            TailnetPhase.UNAVAILABLE -> "当前安装包未包含内置 Tailscale，无法访问 tailnet。"
            TailnetPhase.DISABLED -> "内置 Tailscale 未启用；请用户在「设置 > Tailscale」开启并登录后再访问 tailnet 设备。"
            TailnetPhase.NEEDS_LOGIN -> "内置 Tailscale 尚未登录；请用户在「设置 > Tailscale」点击登录（浏览器授权）或填写 auth key。工具无法代为登录。"
            TailnetPhase.STARTING, TailnetPhase.STOPPED -> "内置 Tailscale 正在连接，稍后再查询。"
            TailnetPhase.FAILED -> when (state.failure) {
                TailnetFailure.EXITED -> "内置 Tailscale 进程已退出；下次访问 tailnet 时会自动重启，或请用户在「设置 > Tailscale」重试。"
                else -> "内置 Tailscale 启动失败；请用户在「设置 > Tailscale」查看原因并重试（检查控制服务器地址与网络）。"
            }
            TailnetPhase.RUNNING -> "已在线。tailnet IP 或 MagicDNS 名称可直接用于 SSH 主机、shell_exec/python_exec 中的 git 与 HTTP(S)；" +
                "设备访问范围由 Tailscale 管理后台的 ACL 决定。"
        }
    }
}
