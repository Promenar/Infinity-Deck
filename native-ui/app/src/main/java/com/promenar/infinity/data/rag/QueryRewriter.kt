package com.promenar.infinity.data.rag

import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.protocol.ProtocolFactory
import kotlinx.coroutines.CancellationException

class QueryRewriter(
    private val protocol: LlmProtocol,
    private val strategy: RewriteStrategy = RewriteStrategy.MULTI_QUERY,
    private val modelId: String? = null,
    private val modelRouteResolver: ((String?) -> ResolvedProviderModel)? = null,
    private val protocolFactory: (ResolvedProviderModel) -> LlmProtocol = { route ->
        val config = route.config
        ProtocolFactory.create(config.protocolType, config.baseUrl, config.apiKey, route.remoteModelId,
            config.serviceAccountJson, config.projectId, config.location)
    },
) {
    suspend fun rewrite(
        query: String,
        count: Int = 3,
        strategyOverride: RewriteStrategy? = null,
        modelIdOverride: String? = null
    ): RewriteResult {
        val queries = linkedSetOf(query)
        var totalUsage: RewriteUsage? = null
        val route = modelRouteResolver?.invoke(modelIdOverride?.takeIf(String::isNotBlank) ?: modelId)

        try {
            val effectiveStrategy = strategyOverride ?: strategy
            val prompt = when (effectiveStrategy) {
                RewriteStrategy.HYDE -> hydePrompt(query)
                RewriteStrategy.MULTI_QUERY -> multiQueryPrompt(query, count)
                RewriteStrategy.EXPANSION -> expansionPrompt(query)
            }

            val request = PromptRequest(
                messages = listOf(ProtocolMessage(role = "user", content = prompt)),
                model = route?.remoteModelId ?: modelIdOverride ?: modelId ?: "default",
                temperature = 0.7,
                stream = false,
                sessionId = "rag-query-rewrite",
            )

            val response = (route?.let(protocolFactory) ?: protocol).sendPromptSync(request)

            val content = response.content
            totalUsage = response.usage?.let {
                RewriteUsage(it.input, it.output, it.total)
            }

            if (content.isNotEmpty()) {
                when (effectiveStrategy) {
                    RewriteStrategy.MULTI_QUERY -> {
                        content.lines().forEach { line ->
                            val clean = line.replace(Regex("^\\d+[\\.、\\)]\\s*"), "").trim()
                            if (clean.isNotEmpty()) queries.add(clean)
                        }
                    }
                    RewriteStrategy.EXPANSION -> {
                        queries.add("$query $content")
                    }
                    RewriteStrategy.HYDE -> {
                        queries.add(content.trim())
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // 请求失败时保留原查询；路由失败在发出请求前向调用方报告。
        }

        return RewriteResult(
            variants = queries.toList().take(count + 1),
            usage = totalUsage
        )
    }

    private fun hydePrompt(query: String): String {
        return """Given the query: "$query"
Generate a hypothetical document that would be a perfect answer to this query.
The document should contain detailed, factual information that directly addresses the query.
Output only the hypothetical document text, nothing else."""
    }

    private fun multiQueryPrompt(query: String, count: Int): String {
        return """Given the query: "$query"
Generate $count alternative queries that capture the same information need from different angles.
Each query should be on a separate line, numbered.
Only output the queries, nothing else."""
    }

    private fun expansionPrompt(query: String): String {
        return """Given the query: "$query"
Generate additional keywords and phrases that would help find relevant documents for this query.
Output only the expanded keywords/phrases, separated by spaces."""
    }
}
