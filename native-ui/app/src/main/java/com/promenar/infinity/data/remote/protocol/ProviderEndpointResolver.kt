package com.promenar.infinity.data.remote.protocol

import java.net.URI

const val VERTEX_DEFAULT_LOCATION: String = "us-central1"

enum class ProviderEndpointOperation {
    INFERENCE,
    MODELS,
    CONNECTION_PROBE,
}

sealed interface ProviderEndpointTarget {
    data class Standard(val operation: ProviderEndpointOperation) : ProviderEndpointTarget

    data class VertexInference(
        val projectId: String,
        val location: String,
        val model: String,
        val streaming: Boolean,
    ) : ProviderEndpointTarget

    data object VertexOAuthToken : ProviderEndpointTarget

    /** Gemini API：`{base}/{version}/models/{model}:generateContent`，流式追加 `alt=sse`。 */
    data class GeminiInference(
        val model: String,
        val streaming: Boolean,
    ) : ProviderEndpointTarget
}

class UnsupportedProviderProtocolException(
    val protocol: ProtocolType,
) : UnsupportedOperationException("Provider protocol is not supported") {
    override fun toString(): String = "UnsupportedProviderProtocolException(protocol=${protocol.persistedId})"
}

class UnsupportedProviderOperationException(
    val protocol: ProtocolType,
    val operation: ProviderEndpointOperation,
) : UnsupportedOperationException("Provider operation is not supported") {
    override fun toString(): String =
        "UnsupportedProviderOperationException(protocol=${protocol.persistedId}, operation=$operation)"
}

/**
 * Provider 网络目标的唯一解析器。
 *
 * 持久化层保留用户输入原值；仅在真正发起请求前由这里补齐协议路径。协议实现不得再拼接路径。
 */
object ProviderEndpointResolver {
    private val unsupportedProtocols = setOf(
        ProtocolType.Cohere_Chat,
        ProtocolType.Yi_ZeroOne,
    )

    private val dynamicModelProtocols = setOf(
        ProtocolType.OpenAI_ChatCompletions,
        ProtocolType.OpenAI_Responses,
        ProtocolType.Anthropic_Messages,
        ProtocolType.DeepSeek,
        ProtocolType.Mistral_Chat,
        ProtocolType.Generic_OpenAI_Compat,
        ProtocolType.Google_Gemini,
    )

    fun resolve(
        protocol: ProtocolType,
        configuredBaseUrl: String,
        operation: ProviderEndpointOperation,
    ): String = resolve(protocol, configuredBaseUrl, ProviderEndpointTarget.Standard(operation))

    fun resolve(
        protocol: ProtocolType,
        configuredBaseUrl: String,
        target: ProviderEndpointTarget,
    ): String {
        if (protocol in unsupportedProtocols) {
            throw UnsupportedProviderProtocolException(protocol)
        }

        return when (target) {
            is ProviderEndpointTarget.Standard -> resolveStandard(
                protocol = protocol,
                configuredBaseUrl = configuredBaseUrl,
                operation = target.operation,
            )
            is ProviderEndpointTarget.VertexInference -> resolveVertexInference(
                protocol = protocol,
                configuredBaseUrl = configuredBaseUrl,
                target = target,
            )
            is ProviderEndpointTarget.GeminiInference -> {
                require(protocol == ProtocolType.Google_Gemini) {
                    "Gemini inference target requires the Gemini protocol"
                }
                val model = requireSafeVertexSegment("model", target.model.trim().removePrefix("models/"))
                val operation = if (target.streaming) "streamGenerateContent" else "generateContent"
                geminiUrl(configuredBaseUrl, "/models/$model:$operation", if (target.streaming) "alt=sse" else null)
            }
            ProviderEndpointTarget.VertexOAuthToken -> {
                require(protocol == ProtocolType.Google_VertexAI) {
                    "Vertex OAuth target requires the Vertex protocol"
                }
                "https://oauth2.googleapis.com/token"
            }
        }
    }

    private fun resolveStandard(
        protocol: ProtocolType,
        configuredBaseUrl: String,
        operation: ProviderEndpointOperation,
    ): String {
        if (protocol == ProtocolType.Google_VertexAI) {
            throw UnsupportedProviderOperationException(protocol, operation)
        }

        if (protocol == ProtocolType.Google_Gemini) {
            return when (operation) {
                ProviderEndpointOperation.INFERENCE -> geminiUrl(configuredBaseUrl, "/models", null)
                ProviderEndpointOperation.MODELS -> geminiUrl(configuredBaseUrl, "/models", "pageSize=1000")
                ProviderEndpointOperation.CONNECTION_PROBE -> geminiUrl(configuredBaseUrl, "/models", "pageSize=1")
            }
        }

        val raw = configuredBaseUrl.trim().ifEmpty { protocol.defaultBaseUrl }
        if (protocol == ProtocolType.Local) return raw.trimEnd('/')
        val uri = validatedHttpUri(raw)
        var basePath = normalizePath(uri.rawPath.orEmpty())

        if (operation != ProviderEndpointOperation.INFERENCE && protocol !in dynamicModelProtocols) {
            throw UnsupportedProviderOperationException(protocol, operation)
        }
        if (protocol == ProtocolType.Generic_OpenAI_Compat) {
            validateGenericConfiguredPath(basePath)
        }

        val operationPath = when (operation) {
            ProviderEndpointOperation.INFERENCE -> inferencePath(protocol, basePath)
            ProviderEndpointOperation.MODELS -> modelPath(protocol, basePath)
            ProviderEndpointOperation.CONNECTION_PROBE -> {
                if (protocol == ProtocolType.Anthropic_Messages) "/v1/models" else modelPath(protocol, basePath)
            }
        }

        if (operation != ProviderEndpointOperation.INFERENCE) {
            basePath = stripInferenceResource(protocol, basePath)
        }

        val resolvedPath = if (
            operation == ProviderEndpointOperation.INFERENCE &&
            isCompleteInferencePath(protocol, basePath)
        ) {
            basePath
        } else {
            appendWithOverlap(basePath, operationPath)
        }
        return buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.rawAuthority)
            append(resolvedPath)
            when {
                operation == ProviderEndpointOperation.CONNECTION_PROBE &&
                    protocol == ProtocolType.Anthropic_Messages -> append("?limit=1")
                uri.rawQuery != null -> append('?').append(uri.rawQuery)
            }
        }
    }

    private fun resolveVertexInference(
        protocol: ProtocolType,
        configuredBaseUrl: String,
        target: ProviderEndpointTarget.VertexInference,
    ): String {
        require(protocol == ProtocolType.Google_VertexAI) {
            "Vertex inference target requires the Vertex protocol"
        }
        val projectId = requireSafeVertexSegment("project", target.projectId)
        val location = requireSafeVertexSegment("location", target.location)
        val model = requireSafeVertexSegment("model", target.model)
        val raw = configuredBaseUrl.trim().ifEmpty {
            if (location == "global") {
                "https://aiplatform.googleapis.com"
            } else {
                "https://$location-aiplatform.googleapis.com"
            }
        }
        val uri = validatedVertexOrigin(raw, location)
        val operation = if (target.streaming) "streamGenerateContent" else "generateContent"
        val path = appendWithOverlap(
            normalizePath(uri.rawPath.orEmpty()),
            "/v1/projects/$projectId/locations/$location/publishers/google/models/$model:$operation",
        )
        return buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.rawAuthority)
            append(path)
            if (target.streaming) append("?alt=sse")
        }
    }

    /**
     * Gemini API 地址：保留用户配置中的版本段（v1、v1beta、v1alpha 等），没有版本段时使用 `/v1beta`；
     * 用户粘贴的 `/models` 或完整 `:generateContent` 路径会被还原到版本段。API Key 只经请求头发送，不进入查询串。
     */
    private fun geminiUrl(configuredBaseUrl: String, resourcePath: String, query: String?): String {
        val uri = validatedHttpUri(configuredBaseUrl.trim().ifEmpty { ProtocolType.Google_Gemini.defaultBaseUrl })
        require(uri.rawQuery == null) { "Gemini endpoint 不支持查询参数" }
        val segments = normalizePath(uri.rawPath.orEmpty()).split('/').filter(String::isNotEmpty)
        val versionIndex = segments.indexOfLast { it.matches(GEMINI_VERSION) }
        val basePath = if (versionIndex >= 0) {
            "/" + segments.take(versionIndex + 1).joinToString("/")
        } else {
            normalizePath((segments + "v1beta").joinToString("/"))
        }
        return buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.rawAuthority)
            append(basePath)
            append(resourcePath)
            query?.let { append('?').append(it) }
        }
    }

    private val GEMINI_VERSION = Regex("v\\d+(?:alpha|beta)?\\d*")

    private fun validatedHttpUri(raw: String): URI {
        require(raw.isNotEmpty()) { "Provider base URL 不能为空" }
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("Provider base URL 无效")
        }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Provider base URL 必须使用 HTTP(S)"
        }
        require(!uri.rawAuthority.isNullOrBlank()) { "Provider base URL 缺少 host" }
        require(uri.rawFragment == null) { "Provider base URL 不支持 fragment" }
        return uri
    }

    private fun validatedVertexOrigin(raw: String, location: String): URI {
        val uri = validatedHttpUri(raw)
        val expectedHost = if (location == "global") {
            "aiplatform.googleapis.com"
        } else {
            "$location-aiplatform.googleapis.com"
        }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Vertex endpoint 必须使用 HTTPS"
        }
        require(uri.host?.equals(expectedHost, ignoreCase = true) == true) {
            "Vertex endpoint 与 location 不匹配"
        }
        require(uri.rawUserInfo == null && uri.port == -1) {
            "Vertex endpoint 必须使用 Google 官方 origin"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "Vertex endpoint 必须使用 Google 官方 origin"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Vertex endpoint 必须使用 Google 官方 origin"
        }
        return uri
    }

    private fun validateGenericConfiguredPath(path: String) {
        val segments = normalizePath(path).split('/').filter(String::isNotEmpty)
        val isCompleteInference = segments.takeLast(2) == listOf("chat", "completions")
        val containsVersion = segments.any { it.matches(Regex("v\\d+")) }
        require(isCompleteInference || containsVersion) {
            "Generic OpenAI Compat 端点必须包含明确版本前缀或完整 chat/completions 路径"
        }
    }

    private fun inferencePath(protocol: ProtocolType, basePath: String): String {
        val defaultSegments = normalizePath(protocol.defaultPath)
            .split('/')
            .filter(String::isNotEmpty)
        if (defaultSegments.isEmpty()) return ""
        val existingHasVersion = normalizePath(basePath)
            .split('/')
            .filter(String::isNotEmpty)
            .any { it.matches(Regex("v\\d+")) }
        val targetSegments = if (
            existingHasVersion && defaultSegments.first().matches(Regex("v\\d+"))
        ) {
            defaultSegments.drop(1)
        } else {
            defaultSegments
        }
        return normalizePath(targetSegments.joinToString("/"))
    }

    private fun isCompleteInferencePath(protocol: ProtocolType, basePath: String): Boolean {
        val normalized = normalizePath(basePath)
        val resourcePath = when (protocol) {
            ProtocolType.OpenAI_Responses -> "/responses"
            ProtocolType.Anthropic_Messages -> "/messages"
            else -> "/chat/completions"
        }
        return normalized.endsWith(resourcePath)
    }

    private fun modelPath(protocol: ProtocolType, basePath: String): String = when (protocol) {
        ProtocolType.DeepSeek -> {
            val version = normalizePath(basePath).split('/').filter(String::isNotEmpty)
                .lastOrNull { it.matches(Regex("v\\d+")) }
            if (version == null) "/models" else "/$version/models"
        }
        ProtocolType.OpenAI_ChatCompletions,
        ProtocolType.OpenAI_Responses,
        ProtocolType.Anthropic_Messages,
        ProtocolType.Mistral_Chat,
        -> {
            val version = normalizePath(basePath).split('/').filter(String::isNotEmpty)
                .lastOrNull { it.matches(Regex("v\\d+")) }
                ?: normalizePath(protocol.defaultPath).split('/').firstOrNull { it.matches(Regex("v\\d+")) }
            if (version == null) "/models" else "/$version/models"
        }
        ProtocolType.Generic_OpenAI_Compat -> {
            val version = normalizePath(basePath).split('/').filter(String::isNotEmpty)
                .lastOrNull { it.matches(Regex("v\\d+")) }
                ?: throw IllegalArgumentException(
                    "Generic OpenAI Compat 模型列表端点缺少明确版本前缀",
                )
            "/$version/models"
        }
        else -> throw UnsupportedProviderOperationException(protocol, ProviderEndpointOperation.MODELS)
    }

    private fun stripInferenceResource(protocol: ProtocolType, basePath: String): String {
        val normalized = normalizePath(basePath)
        val defaultPath = normalizePath(protocol.defaultPath)
        if (defaultPath.isNotEmpty() && normalized.endsWith(defaultPath)) {
            return normalizePath(normalized.removeSuffix(defaultPath))
        }

        val resourcePath = when (protocol) {
            ProtocolType.OpenAI_Responses -> "/responses"
            ProtocolType.Anthropic_Messages -> "/messages"
            else -> "/chat/completions"
        }
        if (!normalized.endsWith(resourcePath)) return normalized

        val withoutResource = normalizePath(normalized.removeSuffix(resourcePath))
        if (protocol == ProtocolType.DeepSeek || protocol == ProtocolType.Generic_OpenAI_Compat) {
            return withoutResource
        }
        val last = withoutResource.split('/').lastOrNull(String::isNotEmpty)
        if (last?.matches(Regex("v\\d+")) == true) return withoutResource
        throw IllegalArgumentException(
            "Provider base URL 的完整推理路径无法无歧义转换为模型列表端点",
        )
    }

    private fun requireSafeVertexSegment(label: String, value: String): String {
        val trimmed = value.trim()
        require(trimmed.length in 1..128) { "Vertex $label 无效" }
        require(trimmed != "." && trimmed != "..") { "Vertex $label 无效" }
        require(trimmed.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"))) {
            "Vertex $label 无效"
        }
        return trimmed
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank() || path == "/") return ""
        return "/" + path.split('/').filter(String::isNotEmpty).joinToString("/")
    }

    private fun appendWithOverlap(basePath: String, targetPath: String): String {
        val base = normalizePath(basePath)
        val target = normalizePath(targetPath)
        if (target.isEmpty()) return base
        val baseSegments = base.split('/').filter(String::isNotEmpty)
        val targetSegments = target.split('/').filter(String::isNotEmpty)
        val overlap = (minOf(baseSegments.size, targetSegments.size) downTo 1)
            .firstOrNull { size -> baseSegments.takeLast(size) == targetSegments.take(size) }
            ?: 0
        return "/" + (baseSegments + targetSegments.drop(overlap)).joinToString("/")
    }
}
