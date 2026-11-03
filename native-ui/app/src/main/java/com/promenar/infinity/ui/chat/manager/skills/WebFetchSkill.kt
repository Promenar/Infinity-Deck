package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URI
import com.promenar.infinity.data.network.AgentHttpRouting
import com.promenar.infinity.data.network.awaitAgentResponse
import com.promenar.infinity.data.tailnet.InstalledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import com.promenar.infinity.ui.chat.manager.registry.intArgument
import com.promenar.infinity.ui.chat.manager.registry.stringArgument

class WebFetchSkill(
    private val httpClient: OkHttpClient,
    networkPolicy: AgentNetworkPolicy? = null,
    private val tailnetRouting: TailnetRouting = InstalledTailnetRouting.routing,
    private val addressResolver: suspend (String) -> Array<InetAddress> = { host ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host) }
    },
) : SkillDefinition {
    private val networkPolicy = networkPolicy ?: AgentNetworkPolicy(resolver = addressResolver)
    override val id = "web_fetch"
    override val name = "web_fetch"
    override val description = "获取网页并提取正文，适合阅读搜索结果或指定网页。支持 HTTP/HTTPS，公网、局域网与本机直连，tailnet 经已连接的内置 Tailscale。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The exact URL of the webpage to fetch content from"
                },
                "startLine": {
                    "type": "integer",
                    "description": "The starting line number to read (1-indexed). Defaults to 1."
                },
                "lineCount": {
                    "type": "integer",
                    "description": "The number of lines (paragraphs) to read in this chunk. Defaults to 80."
                }
            },
            "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(
        args: JsonObject,
        context: SkillExecutionContext
    ): ToolResult {
        val rawUrl = args.stringArgument("url") ?: return ToolResult(id = "err", content = "缺少必填参数 url", status = "error")
        val url = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else {
            rawUrl
        }

        // 解析并校验 startLine 和 lineCount 分页参数
        val rawStartLine = args.intArgument("startLine") ?: 1
        val rawLineCount = args.intArgument("lineCount") ?: 80

        val startLine = rawStartLine.coerceAtLeast(1)
        val lineCount = rawLineCount.coerceAtLeast(1).coerceAtMost(500) // 最大单次读取 500 行，防爆

        return try {
            val page = fetchPage(url)
            // 纯文本、JSON、Markdown 等非 HTML 内容按原有换行逐行返回，不经 HTML 解析和断句。
            val plainText = page.contentType?.let { !it.contains("html") && !it.contains("xml") } == true
            val doc = Jsoup.parse(if (plainText) "" else page.body, page.url)

            // 1. 获取网页 Title
            val title = doc.title().trim()
            
            // 2. 移除所有广告、样式、脚本、导航以及底部噪音元素进行深度清洗
            doc.select("script, style, iframe, header, footer, nav, aside, noscript, svg, form, .ads, #ads, .footer, #footer").remove()
            
            // 3. 优先尝试定位主要文章主体，避免杂波
            val mainElement = doc.select("article, main, #content, .content, .post, .article").firstOrNull() 
                ?: doc.body()

            // 4. 精准提取段落、标题、列表等有意义的排版行
            val elements = mainElement.select("p, h1, h2, h3, h4, h5, h6, li, table, pre")
            val rawLines = mutableListOf<String>()
            
            elements.forEach { el ->
                val cleanText = el.text().trim()
                if (cleanText.length > 8) {
                    rawLines.add(cleanText)
                }
            }

            // 5. 兜底提取：若精准提取得到的行数过少，则对 body 的纯文本进行按句断行切割
            var lines: List<String> = rawLines
            if (plainText) {
                lines = page.body.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            } else if (lines.size < 5) {
                val fallbackText = mainElement.text().replace("\\s+".toRegex(), " ").trim()
                if (fallbackText.isNotEmpty()) {
                    lines = fallbackText.split(SENTENCE_BOUNDARY)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
            }

            val totalLines = lines.size

            if (totalLines == 0) {
                return ToolResult(
                    id = "fetch_${System.currentTimeMillis()}",
                    content = "Webpage fetched successfully but no readable text content could be extracted.",
                    status = "error"
                )
            }

            val fromIndex = startLine - 1
            if (fromIndex >= totalLines) {
                return ToolResult(
                    id = "fetch_${System.currentTimeMillis()}",
                    content = buildString {
                        append("Webpage Title: ").append(title).append("\n")
                        append("URL: ").append(url).append("\n\n")
                        append("--- Metadata ---\n")
                        append("Total Lines: ").append(totalLines).append("\n")
                        append("Requested StartLine: ").append(startLine).append("\n")
                        append("Status: Out of bounds. You have reached the end of the document.\n")
                    },
                    status = "success"
                )
            }

            val toIndex = (fromIndex + lineCount).coerceAtMost(totalLines)
            val chunkLines = lines.subList(fromIndex, toIndex)

            val formattedContent = buildString {
                append("Webpage Title: ").append(title).append("\n")
                append("URL: ").append(url).append("\n\n")
                append("--- Metadata ---\n")
                append("Total Lines: ").append(totalLines).append("\n")
                append("Current Chunk: Lines ").append(startLine).append(" to ").append(toIndex).append("\n")
                
                if (toIndex < totalLines) {
                    append("Notice: There are more lines remaining. You can call 'web_fetch' again with startLine=").append(toIndex + 1).append(" to read the next segment.\n")
                } else {
                    append("Notice: This is the end of the webpage content.\n")
                }
                append("\n--- Content ---\n")
                chunkLines.forEachIndexed { idx, line ->
                    append("[").append(fromIndex + idx + 1).append("] ").append(line).append("\n\n")
                }
            }

            ToolResult(
                id = "fetch_${System.currentTimeMillis()}",
                content = formattedContent,
                status = "success"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NetworkAccessDenied) {
            ToolResult(id = "err", content = "Blocked URL [${failure.code}]：${failure.message}", status = "error")
        } catch (e: Exception) {
            ToolResult(
                id = "fetch_${System.currentTimeMillis()}",
                content = "网页获取或解析失败：${e.localizedMessage ?: e.message}",
                status = "error"
            )
        }
    }

    private class FetchedPage(val url: String, val body: String, val contentType: String?)

    private suspend fun fetchPage(initialUrl: String): FetchedPage = withContext(Dispatchers.IO) {
        var url = initialUrl
        repeat(6) { hop ->
            val endpoint = networkPolicy.validate(url, AgentNetworkTool.WEB_FETCH)
            val tailnet = endpoint.target == NetworkTargetClass.TAILNET
            val route = AgentHttpRouting.route(httpClient, endpoint, tailnetRouting)
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
                .get().build()
            val call = route.client.newCall(route.request(request))
            val result = try {
                coroutineScope {
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.awaitAgentResponse().use { response ->
                            FetchResponse(
                                code = response.code,
                                location = response.header("Location"),
                                body = if (response.code == 200) response.body?.string().orEmpty() else "",
                                contentType = response.header("Content-Type")?.lowercase(),
                            )
                        }
                    } finally { cancellation.cancel() }
                }
            } catch (failure: java.io.IOException) {
                if (tailnet) throw com.promenar.infinity.data.network.AgentTailnetProxy.failure(failure)
                throw failure
            }
            if (result.code in setOf(301, 302, 303, 307, 308)) {
                if (hop == 5) throw NetworkAccessDenied("REDIRECT_LIMIT", "网页重定向超过 5 跳")
                val location = result.location ?: throw NetworkAccessDenied("URL_POLICY", "重定向缺少目标")
                val reference = runCatching { URI(location) }.getOrNull()
                if (reference == null || reference.rawUserInfo != null || location.startsWith("///")) throw NetworkAccessDenied("URL_POLICY", "重定向 URL 无效或包含用户信息")
                url = url.toHttpUrl().resolve(location)?.toString() ?: throw NetworkAccessDenied("URL_POLICY", "重定向 URL 无效")
            } else {
                // 由通用错误输出报告站点返回的 HTTP 状态。
                if (result.code != 200) throw java.io.IOException("HTTP ${result.code}")
                return@withContext FetchedPage(url, result.body, result.contentType)
            }
        }
        throw NetworkAccessDenied("REDIRECT_LIMIT", "网页重定向超过 5 跳")
    }

    private class FetchResponse(val code: Int, val location: String?, val body: String, val contentType: String?)

    private companion object {
        // 中文句末标点直接断句；英文句末标点后须跟空白，避免拆开小数、版本号与网址。
        val SENTENCE_BOUNDARY = Regex("(?<=[。！？；])|(?<=[.!?;])(?=\\s)")
    }
}
