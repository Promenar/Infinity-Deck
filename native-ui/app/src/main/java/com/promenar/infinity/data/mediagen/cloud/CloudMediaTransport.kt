package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.remote.protocol.readBoundedModelListBody
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.cancel
import java.io.IOException
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 不携带凭证的模型快照；请求执行时由传输层读取现有 SecretStore。 */
data class CloudMediaModel(val providerId: String, val modelId: String, val baseUrl: String, val settings: MediaModelSettings)
fun interface CloudMediaModelSource { fun find(channelId: String): CloudMediaModel? }

class ProviderCloudMediaModelSource(private val providers: ProviderManager) : CloudMediaModelSource {
    override fun find(channelId: String): CloudMediaModel? {
        val model = providers.providerModels.value.firstOrNull { "cloud:${it.id}" == channelId } ?: return null
        val provider = providers.providers.value.firstOrNull { it.id == model.providerId } ?: return null
        val settings = model.effectiveMediaSettings() ?: return null
        return CloudMediaModel(provider.id, model.remoteModelId, settings.options.baseUrl ?: provider.baseUrl, settings)
    }
}

fun interface CloudMediaHttp {
    suspend fun request(providerId: String, url: String, body: JsonObject?): JsonObject
}

class CloudHttpException(val status: Int) : IOException("媒体接口返回 HTTP $status")
class CloudApiException(val code: Int) : IOException("媒体接口返回错误代码 $code")

/** 复用模型引擎的连接池、系统代理、内置 Tailnet 与用户配置的媒体超时。 */
class KtorCloudMediaHttp(
    private val apiKey: (String) -> String,
    client: HttpClient = ModelHttp.client { followRedirects = false; expectSuccess = false },
) : CloudMediaHttp {
    private val client = client.config { followRedirects = false; expectSuccess = false }.also { configured ->
        client.coroutineContext.job.invokeOnCompletion { configured.close() }
    }.withModelTimeoutSupport()
    constructor(providers: ProviderManager) : this({ id -> providers.getProviderConfig(id)?.apiKey.orEmpty() })

    override suspend fun request(providerId: String, url: String, body: JsonObject?): JsonObject = withContext(Dispatchers.IO) {
        val endpoint = java.net.URI(url)
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null && endpoint.rawUserInfo == null && endpoint.rawFragment == null)
        val key = apiKey(providerId)
        require(key.isNotBlank()) { "提供商尚未配置密钥" }
        // 不跟随带鉴权请求的重定向；错误正文不进入日志或异常。
        client.prepareRequest(url) {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $key")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
            applyModelTimeout(ModelTimeoutKind.IMAGE)
        }.execute { response ->
            if (response.status.value !in 200..299) {
                response.bodyAsChannel().cancel(null)
                throw CloudHttpException(response.status.value)
            }
            val text = response.readBoundedModelListBody() ?: throw IOException("媒体接口响应过大或无效")
            val result = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw IOException("媒体接口响应格式无效")
            (result["base_resp"] as? JsonObject)?.get("status_code")?.jsonPrimitive?.intOrNull?.let {
                if (it != 0) throw CloudApiException(it)
            }
            result
        }
    }
}

/** 兼容提供商基础 URL 已经包含 /api、/api/v3 或 /v1 的配置。 */
internal fun cloudApiUrl(base: String, path: String): String {
    val uri = java.net.URI(base)
    require(uri.scheme in setOf("https", "http") && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "媒体基础地址无效" }
    require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\') && !path.contains('#'))
    var basePath = uri.rawPath.orEmpty().trimEnd('/')
    if (path.startsWith("/v") || path.startsWith("/api/v")) {
        basePath = basePath.removeSuffix("/chat/completions").removeSuffix("/responses")
        if (path.startsWith("/v")) basePath = basePath.replace(Regex("/v[0-9]+$"), "")
    }
    val pathOnly = path.substringBefore('?')
    val parts = pathOnly.split('/').filter(String::isNotEmpty)
    val overlap = (parts.size downTo 1).firstOrNull { basePath.endsWith("/" + parts.take(it).joinToString("/")) } ?: 0
    return "${uri.scheme}://${uri.rawAuthority}$basePath" + "/" + parts.drop(overlap).joinToString("/") + path.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
}

internal fun safeTaskId(id: String): String = id.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "远端任务 ID 格式无效" } }
internal fun queryPath(template: String, taskId: String): String {
    safeTaskId(taskId)
    return if ("{task_id}" in template) template.replace("{task_id}", taskId)
    else template + (if ('?' in template) "&" else "?") + "task_id=$taskId"
}
