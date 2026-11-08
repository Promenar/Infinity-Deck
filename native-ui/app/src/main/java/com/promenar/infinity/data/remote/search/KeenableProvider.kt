package com.promenar.infinity.data.remote.search

import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.ui.chat.manager.WebSearchProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

class KeenableProvider(
    private val httpClient: HttpClient,
    private val apiKey: String = "",
    maxResults: Int = 5,
    includeDomains: List<String> = emptyList(),
    excludeDomains: List<String> = emptyList(),
) : WebSearchProvider {
    private val resultLimit = maxResults.coerceIn(1, 50)
    private val allowedDomains = includeDomains.mapNotNull(::domainHost)
    private val deniedDomains = excludeDomains.mapNotNull(::domainHost)

    override suspend fun search(query: String): Pair<String, List<Citation>> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Keenable 搜索词不能为空。" }
        val key = apiKey.trim()
        val endpoint = if (key.isEmpty()) "search/public" else "search"
        val response = try {
            httpClient.post("https://api.keenable.ai/v1/$endpoint") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                header("X-Keenable-Title", "Infinity Deck")
                if (key.isNotEmpty()) header("X-API-Key", key)
                setBody(buildJsonObject {
                    put("query", query)
                    put("max_results", resultLimit)
                    // 官方 site 参数只接受单个站点，多域名并集在返回结果中筛选。
                    allowedDomains.singleOrNull()?.let { put("site", it) }
                }.toString())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("Keenable 搜索连接失败，请检查网络连接。")
        }
        if (!response.status.isSuccess()) {
            val hint = when (response.status.value) {
                401, 403 -> "请检查已配置的 API Key。"
                402 -> "账户额度不足，请检查 Keenable 账户。"
                429 -> "请求已限流，请稍后重试。"
                else -> "请稍后重试。"
            }
            // 不转发可能包含凭据、查询或内部信息的服务端错误正文。
            throw IllegalStateException("Keenable 搜索失败（HTTP ${response.status.value}）。$hint")
        }
        val results = try {
            val root = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            root?.get("results") as? JsonArray ?: error("invalid_results")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("Keenable 返回了无效的搜索响应。")
        }
        val citations = results.asSequence().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val title = row.text("title").trim()
            val url = row.text("url").trim()
            val parsed = runCatching { Url(url) }.getOrNull() ?: return@mapNotNull null
            if (title.isEmpty() || parsed.host.isEmpty() ||
                parsed.protocol !in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)
            ) return@mapNotNull null
            val host = parsed.host.trimEnd('.').lowercase(Locale.ROOT)
            if (allowedDomains.isNotEmpty() && allowedDomains.none { matchesDomain(host, it) }) {
                return@mapNotNull null
            }
            if (deniedDomains.any { matchesDomain(host, it) }) return@mapNotNull null
            Citation(
                title = title,
                url = url,
                source = "Keenable",
                snippet = row.text("snippet").ifBlank { row.text("description") },
            )
        }.take(resultLimit).toList()
        val content = citations.mapIndexed { index, citation ->
            "[${index + 1}] ${citation.title}\n${citation.url}\n${citation.snippet.orEmpty()}"
        }.joinToString("\n\n").ifEmpty { "未找到匹配的网页。" }
        content to citations
    }

    private fun JsonObject.text(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun matchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun domainHost(value: String): String? {
        val domain = value.trim().removePrefix("*.")
        if (domain.isBlank()) return null
        return runCatching {
            Url(if ("://" in domain) domain else "https://$domain")
                .host.trimEnd('.').lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
