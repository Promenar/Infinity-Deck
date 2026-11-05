package com.promenar.infinity.data.rag

import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolFactory
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.promenar.infinity.utils.InfinityLogger

/** 所有 KG 请求先解析稳定模型身份；解析失败时禁止使用默认提供商。 */
class KgRequestClient(
    private val router: ProviderRequestRouter,
    private val protocolFactory: (ResolvedProviderModel) -> LlmProtocol = { resolved ->
        val config = resolved.config
        ProtocolFactory.create(
            type = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = resolved.remoteModelId,
            serviceAccountJson = config.serviceAccountJson,
            projectId = config.projectId,
            location = config.location,
        )
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usageStore: KgUsageStore = KgUsageStore.None,
    private val defaultMaxOutputTokens: Int = 4_096,
) {
    suspend fun extract(
        modelId: String?, systemPrompt: String, text: String, timeoutMs: Long,
        maxOutputTokens: Int = defaultMaxOutputTokens,
        sessionId: String? = null,
        responseParser: (String) -> ExtractionResult = KgGraphValidation::parse,
    ): ExtractionResult =
        withContext(dispatcher) {
            require(maxOutputTokens > 0) { "知识图谱请求输出上限必须大于 0" }
            var attemptedModelId: String? = null
            var reportedUsage: KgTokenUsage? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    val stableId = modelId?.takeIf { it.isNotBlank() }
                        ?: return@withTimeoutOrNull ExtractionResult.failure(
                            KgExtractionStatus.PROVIDER_ERROR, "未配置知识图谱模型",
                        )
                    val resolved = when (val resolution = router.resolve(stableId)) {
                        is ProviderResolution.Success -> resolution.value
                        is ProviderResolution.Failure -> return@withTimeoutOrNull ExtractionResult.failure(
                            KgExtractionStatus.PROVIDER_ERROR, "知识图谱模型解析失败：${resolution.reason}",
                        )
                    }
                    val protocol = protocolFactory(resolved)
                    attemptedModelId = stableId
                    val response = protocol.sendPromptSync(PromptRequest(
                        messages = listOf(
                            ProtocolMessage("system", systemPrompt),
                            ProtocolMessage("user", text),
                        ),
                        model = resolved.remoteModelId,
                        temperature = 0.0,
                        maxTokens = maxOutputTokens,
                        stream = false,
                        sessionId = sessionId,
                        sessionHeaders = sessionId?.let { router.sessionHeaders(resolved, it) },
                    ))
                    reportedUsage = response.usage?.let {
                        KgTokenUsage(it.input.coerceAtLeast(0).toLong(), it.output.coerceAtLeast(0).toLong())
                    } ?: KgTokenUsage(
                        inputTokens = (systemPrompt.length.toLong() + text.length + 1) / 2,
                        outputTokens = (response.content.length.toLong() + 1) / 2,
                        estimated = true,
                    )
                    responseParser(response.content).copy(usage = reportedUsage!!)
                } ?: ExtractionResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱抽取超时")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.net.SocketTimeoutException) {
                ExtractionResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱提供商请求超时")
            } catch (failure: Exception) {
                // 同步协议把传输超时归一为此类别前缀；不回显可能含请求信息的技术消息。
                if (failure.message?.let { it.startsWith("[TIMEOUT]") || Regex("^\\[HTTP \\d{3}\\]\\[TIMEOUT\\]").containsMatchIn(it) } == true) {
                    ExtractionResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱提供商请求超时")
                } else ExtractionResult.failure(KgExtractionStatus.PROVIDER_ERROR, "知识图谱提供商请求失败")
            } finally {
                attemptedModelId?.let { stableId ->
                    // 取消与坏 JSON 同样可能已产生费用；未知 token 记为待估，不伪造实际值。
                    withContext(NonCancellable + dispatcher) {
                        try {
                            usageStore.record(KgUsageRecord(stableId, reportedUsage ?: KgTokenUsage(estimated = true)))
                        } catch (_: Exception) {
                            InfinityLogger.log("[KG] 用量统计持久化失败")
                        }
                    }
                }
            }
        }
}

data class KgExtractionSettings(val modelId: String?, val systemPrompt: String) {
    companion object {
        fun resolve(config: RagConfiguration, summaryModel: String?, mainModel: String?) = KgExtractionSettings(
            modelId = config.kgExtractionModel?.takeIf { it.isNotBlank() }
                ?: summaryModel?.takeIf { it.isNotBlank() }
                ?: mainModel?.takeIf { it.isNotBlank() },
            systemPrompt = config.kgExtractionPrompt?.takeIf { it.isNotBlank() } ?: GraphExtractor.DEFAULT_KG_PROMPT,
        )
    }
}
