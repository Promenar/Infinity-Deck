package com.promenar.infinity.data.tailnet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 与 libnxtailnet.so 之间的 stdin/stdout JSON 行协议（定义见 app/tailnet/protocol.go）。
 * 请求中的 auth key 与代理口令只经 stdin 传入；本端的 toString 一律遮蔽，避免进入日志与异常信息。
 */
object TailnetOps {
    const val START = "start"
    const val STATUS = "status"
    const val PEERS = "peers"
    const val PING = "ping"
    const val LOGIN = "login"
    const val LOGOUT = "logout"
    const val SHUTDOWN = "shutdown"
}

/** 子进程错误码。 */
object TailnetErrors {
    const val BAD_REQUEST = "bad_request"
    const val NOT_STARTED = "not_started"
    const val ALREADY_STARTED = "already_started"
    const val UNSUPPORTED = "unsupported_op"
    const val START_FAILED = "start_failed"
    const val NOT_RUNNING = "tailnet_not_running"
    const val INTERNAL = "internal"
}

@Serializable
class TailnetStartOptions(
    val stateDir: String,
    val hostname: String,
    val controlUrl: String? = null,
    val authKey: String? = null,
    val ephemeral: Boolean = false,
    val proxyUser: String,
    val proxyPassword: String,
) {
    override fun toString(): String =
        "TailnetStartOptions(hostname=$hostname, controlUrl=${controlUrl.orEmpty()}, ephemeral=$ephemeral, " +
            "authKey=${if (authKey.isNullOrEmpty()) "无" else "已遮蔽"})"
}

@Serializable
class TailnetRequest(
    val id: Long,
    val op: String,
    val start: TailnetStartOptions? = null,
    val ping: TailnetPingOptions? = null,
) {
    override fun toString(): String = "TailnetRequest(id=$id, op=$op)"
}

@Serializable
data class TailnetPingOptions(val target: String)

object TailnetPaths {
    const val DIRECT = "direct"
    const val PEER_RELAY = "peer_relay"
    const val DERP = "derp"
}

@Serializable
data class TailnetStatusSummary(
    val backendState: String = STATE_NO_STATE,
    val authUrl: String? = null,
    val selfName: String? = null,
    val selfDnsName: String? = null,
    val selfIps: List<String> = emptyList(),
    val online: Boolean = false,
    val magicDnsSuffix: String? = null,
    val tailnetName: String? = null,
    val health: List<String> = emptyList(),
    val error: String? = null,
) {
    val running: Boolean get() = backendState == STATE_RUNNING
    val needsLogin: Boolean get() = backendState == STATE_NEEDS_LOGIN || backendState == STATE_NEEDS_MACHINE_AUTH

    /** 登录链接属于一次性凭据，不出现在 toString。 */
    override fun toString(): String =
        "TailnetStatusSummary(backendState=$backendState, selfName=$selfName, online=$online, authUrl=${if (authUrl.isNullOrEmpty()) "无" else "已遮蔽"})"

    companion object {
        const val STATE_NO_STATE = "NoState"
        const val STATE_NEEDS_LOGIN = "NeedsLogin"
        const val STATE_NEEDS_MACHINE_AUTH = "NeedsMachineAuth"
        const val STATE_STARTING = "Starting"
        const val STATE_STOPPED = "Stopped"
        const val STATE_RUNNING = "Running"
    }
}

@Serializable
data class TailnetPeer(
    val name: String,
    val dnsName: String? = null,
    val ips: List<String> = emptyList(),
    val os: String? = null,
    val online: Boolean = false,
    val path: String? = null,
    val directLan: Boolean = false,
    val relayRegion: String? = null,
    val relayRegionName: String? = null,
)

@Serializable
data class TailnetPingResult(
    val path: String? = null,
    val directLan: Boolean = false,
    val relayRegion: String? = null,
    val relayRegionName: String? = null,
    val latencyMs: Double? = null,
    val timedOut: Boolean = false,
    val error: String? = null,
)

@Serializable
data class TailnetStartResult(val proxyPort: Int)

@Serializable
data class TailnetPeersResult(val peers: List<TailnetPeer> = emptyList())

/** 子进程输出的一行。 */
sealed interface TailnetMessage {
    data class Response(
        val id: Long,
        val ok: Boolean,
        val result: JsonElement?,
        val error: String?,
        val message: String?,
    ) : TailnetMessage

    data class State(val state: TailnetStatusSummary) : TailnetMessage

    /** 无法识别的行；忽略，不记录原文。 */
    data object Unknown : TailnetMessage
}

/** 子进程报告的命令失败；[message] 来自子进程，不含秘密。 */
class TailnetCommandException(val code: String, message: String?) : Exception(message ?: code)

object TailnetCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(request: TailnetRequest): String = json.encodeToString(TailnetRequest.serializer(), request)

    fun decode(line: String): TailnetMessage {
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return TailnetMessage.Unknown
        return runCatching {
            val event = element["event"]?.jsonPrimitive?.contentOrNull
            when {
                event == "state" -> element["state"]?.let { TailnetMessage.State(json.decodeFromJsonElement<TailnetStatusSummary>(it)) }
                element.containsKey("id") && element.containsKey("ok") -> TailnetMessage.Response(
                    id = element["id"]?.jsonPrimitive?.longOrNull ?: return@runCatching TailnetMessage.Unknown,
                    ok = element["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                    result = element["result"],
                    error = element["error"]?.jsonPrimitive?.contentOrNull,
                    message = element["message"]?.jsonPrimitive?.contentOrNull,
                )
                else -> null
            } ?: TailnetMessage.Unknown
        }.getOrDefault(TailnetMessage.Unknown)
    }
}
