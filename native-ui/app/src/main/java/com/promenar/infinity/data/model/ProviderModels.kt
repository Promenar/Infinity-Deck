package com.promenar.infinity.data.model

import com.promenar.infinity.data.remote.protocol.ProtocolType

sealed interface CredentialUpdate {
    data object Preserve : CredentialUpdate
    data object Clear : CredentialUpdate
    data class Replace(val value: String) : CredentialUpdate {
        init {
            require(value.isNotBlank()) { "凭证不能为空；请使用 Preserve 或 Clear" }
        }
    }
}

fun String.toCredentialUpdate(): CredentialUpdate =
    takeIf { it.isNotBlank() }
        ?.let(CredentialUpdate::Replace)
        ?: CredentialUpdate.Preserve

/**
 * 提供商列表项 — 从 SettingsViewModel 提取为共享数据模型。
 * 只暴露凭证存在性，完整凭证必须按 providerId 从 SecretStore 获取。
 */
data class ProviderListItem(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val protocolType: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
    val hasApiKey: Boolean = false,
    val hasVertexCredentials: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 未知持久化协议的只读列表投影。
 *
 * 不携带 URL、模型或秘密值，也不伪造成任何可执行 [ProtocolType]。调用方只能展示并删除，
 * 如需迁移必须通过显式替换配置完成。
 */
data class UnsupportedProviderListItem(
    val id: String,
    val name: String,
    val rawProtocolId: String,
    val enabled: Boolean,
    val hasApiKey: Boolean,
    val hasVertexCredentials: Boolean,
)

/** 未知协议配置的显式修复投影；不把原协议伪装成可执行协议，也不暴露秘密值。 */
data class UnsupportedProviderSummary(
    val id: String,
    val name: String,
    val rawProtocolId: String,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val hasVertexCredentials: Boolean,
)

data class ProviderSummary(
    val id: String,
    val name: String,
    val protocolType: ProtocolType,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val hasVertexCredentials: Boolean,
)

/**
 * 提供商完整配置信息，用于编辑回填和持久化。
 */
data class ProviderConfig(
    val protocolType: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val name: String? = null,
    val vertexServiceAccountJson: String = "",
    val sessionHeadersEnabled: Boolean = false,
    val sessionHeaderName: String = SessionHeaderPolicy.DEFAULT_NAME,
)
