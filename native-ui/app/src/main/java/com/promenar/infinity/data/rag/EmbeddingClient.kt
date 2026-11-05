package com.promenar.infinity.data.rag

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelRequestTimeouts
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
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

internal open class EmbeddingBatchException(
    message: String,
    val retryable: Boolean,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
) : IllegalStateException(message)

internal class EmbeddingTimeoutException : EmbeddingBatchException("向量请求超时，请检查服务响应时间", true)

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
    private var floatOnlySpaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun resolvedClient(resolver: (HttpClient) -> EmbeddingClient): EmbeddingClient =
        resolver(httpClient).also { it.floatOnlySpaces = floatOnlySpaces }

    private fun shareEncodingState(client: EmbeddingClient): EmbeddingClient =
        client.also { it.floatOnlySpaces = floatOnlySpaces }

    /** 固定当前远端路由，避免一次检索中的多个请求跨向量空间。 */
    internal fun frozen(): EmbeddingClient {
        clientResolver?.let { return resolvedClient(it).frozen() }
        val resolved = modelRouteResolver?.invoke() ?: return this
        return shareEncodingState(EmbeddingClient(baseUrl, apiKey, model, httpClient, localEngine,
            modelRouteResolver = { resolved }, allowLocalFallback = allowLocalFallback))
    }

    /** 索引候选的每批必须处于同一个向量空间。 */
    internal fun frozenForIndex(): EmbeddingClient {
        clientResolver?.let { return resolvedClient(it).frozenForIndex() }
        val route = modelRouteResolver?.invoke()
        val fallback = route == null && !isConfigured && allowLocalFallback
        return shareEncodingState(EmbeddingClient(baseUrl, apiKey, model, httpClient, localEngine,
            modelRouteResolver = route?.let { fixed -> { fixed } }, allowLocalFallback = fallback))
    }

    /** 仅输出摘要；端点和本地路径不会写入缓存键。 */
    internal fun vectorSpaceKey(): String? {
        clientResolver?.let { return it(httpClient).vectorSpaceKey() }
        val route = modelRouteResolver?.let { runCatching { it() }.getOrNull() ?: return null }
        val parts = if (route?.config?.protocolType == ProtocolType.Local ||
            (route == null && !isConfigured && allowLocalFallback)) {
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
        return if (route?.config?.protocolType == ProtocolType.Local ||
            (route == null && !isConfigured && allowLocalFallback)) localEngine?.embeddingModelGeneration else null
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

    suspend fun embedDocuments(texts: List<String>): EmbeddingResult = embedDocuments(texts, isQuery = false)

    private suspend fun embedDocuments(texts: List<String>, isQuery: Boolean): EmbeddingResult {
        if (texts.isEmpty()) throw IllegalArgumentException("No texts provided for embedding")
        clientResolver?.let { return resolvedClient(it).embedDocuments(texts, isQuery) }
        modelRouteResolver?.let { resolve ->
            val route = resolve()
            return if (route.config.protocolType == ProtocolType.Local) {
                requireNotNull(localEngine).embedBatch(texts).getOrThrow().let { EmbeddingResult(it, null) }
            } else {
                embedViaRemote(texts, route.requireOpenAiModelRoute(), isQuery)
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
            embedViaRemote(texts, isQuery = isQuery)
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

    private suspend fun embedViaRemote(texts: List<String>, route: ResolvedProviderModel? = null, isQuery: Boolean): EmbeddingResult {
        val allEmbeddings = mutableListOf<FloatArray>()
        var totalTokens = 0

        for (range in embeddingBatchRanges(texts)) {
            val batch = texts.slice(range)
            val result = embedBatch(batch, route, isQuery)
            allEmbeddings.addAll(result.embeddings)
            totalTokens += result.usage?.totalTokens ?: 0
        }

        return EmbeddingResult(
            embeddings = allEmbeddings,
            usage = if (totalTokens > 0) EmbeddingUsage(totalTokens) else null
        )
    }

    suspend fun embedQuery(text: String): Pair<FloatArray, EmbeddingUsage?> {
        clientResolver?.let { return resolvedClient(it).embedQuery(text) }
        if (modelRouteResolver != null) {
            val result = embedDocuments(listOf(text), isQuery = true)
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
            val result = embedDocuments(listOf(text), isQuery = true)
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

    private suspend fun embedBatch(texts: List<String>, route: ResolvedProviderModel? = null, isQuery: Boolean): EmbeddingResult {
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

        val encodingKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$cleanBase\u0000$model".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        var useBase64 = encodingKey !in floatOnlySpaces
        while (true) {
            val requestBody = buildJsonObject {
                put("model", model)
                put("input", buildJsonArray { texts.forEach { add(it) } })
                if (useBase64) put("encoding_format", "base64")
            }
            val (response, responseText) = try {
                val response = httpClient.post(endpoint) {
                    expectSuccess = false
                    if (isQuery) applyModelTimeout(ModelTimeoutKind.EMBEDDING) else {
                        val idle = ModelRequestTimeouts.socketMillis(ModelTimeoutKind.EMBEDDING)
                        timeout {
                            socketTimeoutMillis = idle
                            requestTimeoutMillis = maxOf(120_000L, idle * 4)
                        }
                    }
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $cleanApiKey")
                    setBody(json.encodeToString(JsonObject.serializer(), requestBody))
                }
                response to response.bodyAsText()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (failure is java.net.SocketTimeoutException || failure is HttpRequestTimeoutException) {
                    throw EmbeddingTimeoutException()
                }
                throw EmbeddingBatchException("向量请求网络错误，请检查连接", true)
            }

            if (!response.status.isSuccess()) {
                val errObj = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
                val detail = errObj?.get("error") as? JsonObject
                val serverError = (detail?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (errObj?.get("message") as? JsonPrimitive)?.contentOrNull
                val errorParameter = (detail?.get("param") as? JsonPrimitive)?.contentOrNull
                val errorCode = (detail?.get("code") as? JsonPrimitive)?.contentOrNull
                val status = response.status.value
                if (useBase64 && status in 400..499 && status != 429 &&
                    unsupportedEncodingFormat(serverError, errorParameter, errorCode)) {
                    floatOnlySpaces.add(encodingKey)
                    useBase64 = false
                    continue
                }
                throw EmbeddingBatchException(
                    "HTTP $status：${summarizeEmbeddingError(serverError, status)}",
                    status == 429 || status in 500..599, status,
                    retryAfterMillis = parseEmbeddingRetryAfter(response.headers[HttpHeaders.RetryAfter]),
                )
            }
            val jsonResponse = try { json.parseToJsonElement(responseText).jsonObject }
                catch (_: IllegalArgumentException) { throw IllegalStateException("Embedding 响应格式无效") }
            val data = jsonResponse["data"] as? JsonArray ?: throw IllegalStateException(
                runCatching {
                    jsonResponse["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        ?: jsonResponse["message"]?.jsonPrimitive?.content
                }.getOrNull()?.let { summarizeEmbeddingError(it, null) } ?: "Missing data array in embedding response"
            )
            val (embeddings, returnedFloats) = parseEmbeddingData(data, texts.size)
            if (useBase64 && returnedFloats) floatOnlySpaces.add(encodingKey)
            val usage = (jsonResponse["usage"] as? JsonObject)?.let { usageObj ->
                EmbeddingUsage((usageObj["total_tokens"] as? JsonPrimitive)?.intOrNull ?: 0)
            }
            return EmbeddingResult(embeddings = embeddings, usage = usage)
        }
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

private fun unsupportedEncodingFormat(message: String?, parameter: String?, code: String?): Boolean {
    val text = listOfNotNull(message, code).joinToString(" ").lowercase(Locale.ROOT)
    return (parameter?.equals("encoding_format", ignoreCase = true) == true || "encoding_format" in text) && listOf(
        "unsupported", "not support", "not supported", "unknown", "unrecognized", "unrecognised",
        "not allowed", "not permitted", "extra", "invalid", "not implemented", "不支持", "未知",
    ).any { it in text }
}

/** 上游可能回显正文或凭据，只把可识别的错误类别映射为固定文案。 */
private fun summarizeEmbeddingError(message: String?, status: Int?): String {
    val text = message?.lowercase(Locale.ROOT).orEmpty()
    return when {
        status == 429 || "rate limit" in text || "限流" in text -> "服务限流"
        status == 401 || status == 403 -> "服务认证或访问权限不足"
        listOf("too long", "context length", "maximum context", "token limit", "输入过长", "超出长度").any { it in text } -> "输入过长或超过模型上下文限制"
        listOf("quota", "insufficient balance", "额度不足", "余额不足").any { it in text } -> "服务额度不足"
        listOf("model not found", "unknown model", "invalid model", "模型不存在").any { it in text } -> "模型不可用或不存在"
        listOf("overload", "server busy", "服务繁忙").any { it in text } -> "服务繁忙"
        status != null && status >= 500 -> "上游服务发生错误"
        else -> "上游拒绝请求"
    }
}

private fun parseEmbeddingData(data: JsonArray, expectedCount: Int): Pair<List<FloatArray>, Boolean> {
    check(data.size == expectedCount) { "Embedding 数量与切块不一致" }
    val items = data.map { it as? JsonObject ?: throw IllegalStateException("Embedding 数据格式无效") }
    val indexes = items.map { item -> (item["index"] as? JsonPrimitive)?.intOrNull }
    val ordered = if (items.any { it.containsKey("index") }) {
        check(indexes.all { it != null } && indexes.filterNotNull().sorted() == items.indices.toList()) {
            "Embedding 响应索引无效"
        }
        items.zip(indexes).sortedBy { it.second }.map { it.first }
    } else items
    var returnedFloats = false
    val embeddings = ordered.map { item ->
        val vector = when (val value = item["embedding"]) {
            is JsonArray -> {
                returnedFloats = true
                check(value.size in 1..65_536) { "Embedding 向量维度无效" }
                FloatArray(value.size) { i ->
                    val element = value[i] as? JsonPrimitive
                    check(element != null && !element.isString) { "Embedding 数值无效" }
                    element.floatOrNull ?: throw IllegalStateException("Embedding 数值无效")
                }
            }
            is JsonPrimitive -> {
                check(value.isString) { "Embedding 响应格式无效" }
                val bytes = try { Base64.getDecoder().decode(value.content) }
                    catch (_: IllegalArgumentException) { throw IllegalStateException("Embedding base64 响应无效") }
                check(bytes.isNotEmpty() && bytes.size <= 65_536 * Float.SIZE_BYTES && bytes.size % Float.SIZE_BYTES == 0) {
                    "Embedding base64 字节长度无效"
                }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
            }
            else -> throw IllegalStateException("Missing embedding array")
        }
        check(vector.all(Float::isFinite)) { "Embedding 数值无效" }
        vector
    }
    return embeddings to returnedFloats
}

private fun parseEmbeddingRetryAfter(value: String?): Long? {
    val input = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    input.toLongOrNull()?.let { seconds ->
        if (seconds < 0 || seconds > Long.MAX_VALUE / 1_000) return null
        return seconds * 1_000
    }
    return runCatching {
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        (checkNotNull(parser.parse(input)).time - System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}
