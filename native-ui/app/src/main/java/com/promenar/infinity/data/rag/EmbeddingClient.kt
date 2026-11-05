package com.promenar.infinity.data.rag

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.requireOpenAiModelRoute
import com.promenar.infinity.data.remote.protocol.ProtocolType
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class EmbeddingClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "text-embedding-3-small",
    httpClient: HttpClient = ModelHttp.client {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            // 显式给出读超时，避免沿用 OkHttp 默认 10 秒，批量请求排队时被提前切断。
            socketTimeoutMillis = 30_000
        }
    },
    private val localEngine: LocalInferenceEngine? = null,
    private val modelRouteResolver: (() -> ResolvedProviderModel)? = null,
    /** 长期持有的应用入口按次取得当前配置，子客户端共享 HTTP 传输。 */
    private val clientResolver: ((HttpClient) -> EmbeddingClient)? = null,
    /** 显式禁用手动远程配置的本地降级，确保同一检索使用同一向量空间。 */
    private val allowLocalFallback: Boolean = true,
) {
    private val httpClient = httpClient.withModelTimeoutSupport()
    private val json = Json { ignoreUnknownKeys = true }

    /** 固定当前远端路由，避免一次检索中的多个请求跨向量空间。 */
    internal fun frozen(): EmbeddingClient {
        clientResolver?.let { return it(httpClient).frozen() }
        val resolved = modelRouteResolver?.invoke() ?: return this
        return EmbeddingClient(baseUrl, apiKey, model, httpClient, localEngine,
            modelRouteResolver = { resolved }, allowLocalFallback = allowLocalFallback)
    }

    /** 仅输出摘要；端点和本地路径不会写入缓存键。 */
    internal fun vectorSpaceKey(): String? {
        clientResolver?.let { return it(httpClient).vectorSpaceKey() }
        val route = modelRouteResolver?.let { runCatching { it() }.getOrNull() ?: return null }
        val parts = if (route?.config?.protocolType == ProtocolType.Local) {
            val slot = localEngine?.embeddingSlot?.value ?: return null
            val path = slot.modelPath?.takeIf { slot.isLoaded && !slot.isLoading } ?: return null
            val file = java.io.File(path)
            listOf("local", path, file.length().toString(), file.lastModified().toString(), slot.backendType.toString())
        } else if (route != null) {
            val usable = runCatching { route.requireOpenAiModelRoute() }.getOrNull() ?: return null
            listOf("remote", usable.providerId, usable.remoteModelId, usable.config.baseUrl.trim().trimEnd('/'), usable.config.protocolType.persistedId)
        } else {
            if (!isConfigured || model.isBlank()) return null
            listOf("manual", model, baseUrl.trim().trimEnd('/'))
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 本地模型装卸的单调代次用于发现一次检索期间的往返切换。 */
    internal fun localModelGeneration(): Long? {
        clientResolver?.let { return it(httpClient).localModelGeneration() }
        val route = modelRouteResolver?.let { runCatching { it() }.getOrNull() }
        return if (route?.config?.protocolType == ProtocolType.Local) localEngine?.embeddingModelGeneration else null
    }

    /** 远程 Embedding 是否已配置（baseUrl 和 apiKey 均非空） */
    val isConfigured: Boolean
        get() = clientResolver?.invoke(httpClient)?.isConfigured ?: modelRouteResolver?.let { resolve -> runCatching {
            val route = resolve()
            route.config.protocolType != ProtocolType.Local && route.requireOpenAiModelRoute().config.baseUrl.isNotBlank()
        }.getOrDefault(false) }
            ?: (baseUrl.isNotBlank() && apiKey.isNotBlank())

    /** 是否有本地引擎可用作降级 */
    val hasLocalFallback: Boolean
        get() {
            clientResolver?.let { return it(httpClient).hasLocalFallback }
            if (!allowLocalFallback && modelRouteResolver == null) return false
            modelRouteResolver?.let { resolve ->
                if (!runCatching { resolve().config.protocolType == ProtocolType.Local }.getOrDefault(false)) return false
            }
            return localEngine?.embeddingSlot?.value?.isLoaded == true
        }

    /** 诊断信息：用于向用户解释当前配置状态 */
    fun diagnosticMessage(): String = buildString {
        clientResolver?.let { return it(httpClient).diagnosticMessage() }
        modelRouteResolver?.let { resolve ->
            runCatching { resolve() }.exceptionOrNull()?.message?.let { return it }
        }
        if (baseUrl.isBlank()) {
            append("Embedding 服务未配置")
            if (model.isNotBlank()) append("（当前已选模型: \"$model\"）")
            append("\n\n可能的原因：\n")
            append("1. 未在 设置 → 通用 中选择向量模型。\n")
            append("2. 所选模型属于一个无效或未配置地址的提供商。\n")
            append("3. 主提供商配置已失效且未设置备选 Embedding 模型。\n")
            append("\n请前往 设置 检查提供商配置及模型选择。")
        } else if (apiKey.isBlank()) {
            append("Embedding API Key 未配置（Base URL: $baseUrl）")
            append("\n请检查对应提供商的 API Key 设置。")
        } else if (model.isBlank()) {
            append("Embedding 模型未指定（Base URL: $baseUrl）")
            append("\n请前往 设置 → 通用 选择一个向量模型。")
        }
    }

    suspend fun embedDocuments(texts: List<String>): EmbeddingResult {
        if (texts.isEmpty()) throw IllegalArgumentException("No texts provided for embedding")
        clientResolver?.let { return it(httpClient).embedDocuments(texts) }
        modelRouteResolver?.let { resolve ->
            val route = resolve()
            return if (route.config.protocolType == ProtocolType.Local) {
                requireNotNull(localEngine).embedBatch(texts).getOrThrow().let { EmbeddingResult(it, null) }
            } else {
                embedViaRemote(texts, route.requireOpenAiModelRoute())
            }
        }
        // 如果远程未配置但有本地引擎，直接走本地通道
        if (!isConfigured && localEngine != null && allowLocalFallback) {
            return localEngine.embedBatch(texts).fold(
                onSuccess = { EmbeddingResult(embeddings = it, usage = null) },
                onFailure = { throw IllegalStateException(diagnosticMessage()) }
            )
        }
        return try {
            embedViaRemote(texts)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (localEngine != null && allowLocalFallback) {
                localEngine.embedBatch(texts).fold(
                    onSuccess = { EmbeddingResult(embeddings = it, usage = null) },
                    onFailure = { throw e }
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun embedViaRemote(texts: List<String>, route: ResolvedProviderModel? = null): EmbeddingResult {
        val batchSize = 50
        val allEmbeddings = mutableListOf<FloatArray>()
        var totalTokens = 0

        for (i in texts.indices step batchSize) {
            val batch = texts.subList(i, minOf(i + batchSize, texts.size))
            val result = embedBatch(batch, route)
            allEmbeddings.addAll(result.embeddings)
            totalTokens += result.usage?.totalTokens ?: 0
        }

        return EmbeddingResult(
            embeddings = allEmbeddings,
            usage = if (totalTokens > 0) EmbeddingUsage(totalTokens) else null
        )
    }

    suspend fun embedQuery(text: String): Pair<FloatArray, EmbeddingUsage?> {
        clientResolver?.let { return it(httpClient).embedQuery(text) }
        if (modelRouteResolver != null) {
            val result = embedDocuments(listOf(text))
            return result.embeddings.first() to result.usage
        }
        // 如果远程未配置但有本地引擎，直接走本地通道
        if (!isConfigured && localEngine != null && allowLocalFallback) {
            return localEngine.embed(text).fold(
                onSuccess = { Pair(it, null) },
                onFailure = { throw IllegalStateException(diagnosticMessage()) }
            )
        }
        return try {
            val result = embedDocuments(listOf(text))
            Pair(result.embeddings.first(), result.usage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (localEngine != null && allowLocalFallback) {
                localEngine.embed(text).fold(
                    onSuccess = { Pair(it, null) },
                    onFailure = { throw e }
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun embedBatch(texts: List<String>, route: ResolvedProviderModel? = null): EmbeddingResult {
        val baseUrl = route?.config?.baseUrl ?: baseUrl
        val apiKey = route?.config?.apiKey ?: apiKey
        val model = route?.remoteModelId ?: model
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) throw IllegalStateException(diagnosticMessage())

        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanApiKey = apiKey.trim()
        val endpoint = if (cleanBase.endsWith("/v1")) {
            "$cleanBase/embeddings"
        } else {
            "$cleanBase/v1/embeddings"
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray { texts.forEach { add(it) } })
        }

        val response: HttpResponse = httpClient.post(endpoint) {
            applyModelTimeout(ModelTimeoutKind.EMBEDDING)
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $cleanApiKey")
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val serverError = runCatching {
                val errObj = json.parseToJsonElement(responseText).jsonObject
                errObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: errObj["message"]?.jsonPrimitive?.content
            }.getOrNull()
            throw IllegalStateException(serverError ?: "Embedding 请求失败 (${response.status.value}): ${responseText.take(120)}")
        }
        val jsonResponse = json.parseToJsonElement(responseText).jsonObject

        val data = jsonResponse["data"]?.jsonArray ?: throw IllegalStateException(
            runCatching {
                jsonResponse["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: jsonResponse["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "Missing data array in embedding response"
        )
        val embeddings = data.map { item ->
            val embeddingArray = item.jsonObject["embedding"]?.jsonArray
                ?: throw IllegalStateException("Missing embedding array")
            FloatArray(embeddingArray.size) { i -> embeddingArray[i].jsonPrimitive.float }
        }

        val usage = jsonResponse["usage"]?.jsonObject?.let { usageObj ->
            EmbeddingUsage(usageObj["total_tokens"]?.jsonPrimitive?.int ?: 0)
        }

        return EmbeddingResult(embeddings = embeddings, usage = usage)
    }

    suspend fun embedLocal(text: String): Result<FloatArray> {
        clientResolver?.let { return it(httpClient).embedLocal(text) }
        val engine = localEngine
            ?: return Result.failure(IllegalStateException("Local engine not configured"))
        return engine.embed(text)
    }

    suspend fun embedLocalBatch(texts: List<String>): Result<List<FloatArray>> {
        clientResolver?.let { return it(httpClient).embedLocalBatch(texts) }
        val engine = localEngine
            ?: return Result.failure(IllegalStateException("Local engine not configured"))
        return engine.embedBatch(texts)
    }
}
