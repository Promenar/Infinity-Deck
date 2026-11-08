package com.promenar.infinity.data.remote

import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.model.SessionHeaderPolicy
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders

/** 仅存在于传输层，不参与 PromptRequest 的序列化。 */
class SessionRequestHeaders(val name: String, val identifier: String, val userAgent: String) {
    init {
        require(SessionHeaderPolicy.isValidName(name)) { "会话请求头名称无效" }
        require(identifier.matches(Regex("[0-9a-f]{32}"))) { "会话标识格式无效" }
        require(userAgent.startsWith("Infinity-Deck/") && userAgent.all { it.code in 0x21..0x7e }) { "客户端标识格式无效" }
    }
    override fun toString(): String = "SessionRequestHeaders(configured=true)"
}

internal suspend fun resolveSessionRequestHeaders(
    name: String?,
    sessionId: String?,
    identifier: suspend (String) -> String?,
    versionName: String = BuildConfig.VERSION_NAME,
): SessionRequestHeaders? {
    if (name == null || sessionId.isNullOrBlank()) return null
    val value = identifier(sessionId) ?: return null
    return SessionRequestHeaders(name, value, "Infinity-Deck/$versionName")
}

internal fun HttpRequestBuilder.applySessionRequestHeaders(value: SessionRequestHeaders?) {
    if (value == null) return
    headers[value.name] = value.identifier
    headers[HttpHeaders.UserAgent] = value.userAgent
}
