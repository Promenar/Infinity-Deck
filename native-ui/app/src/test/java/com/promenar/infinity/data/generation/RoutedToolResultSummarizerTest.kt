package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.ProtocolToolChoice
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.protocol.adaptedTo
import com.promenar.infinity.data.remote.protocol.putChatReasoning
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationFailureCode
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class RoutedToolResultSummarizerTest {
    @Test
    fun `深度思考摘要关闭可关闭的思考且不设置输出限额`() = runTest {
        val protocol = RecordingProtocol(successfulStream("摘要"))
        val remote = "deepseek-v4.1-flash"
        val stableId = "current::$remote"
        val router = RecordingRouter(mapOf(stableId to endpoint("current", remote, protocol)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不使用本地模型") }
            .summarizeWithUsageOutcome("待摘要数据", stableId)

        assertThat(result.getOrNull()?.content).isEqualTo("摘要")
        val request = protocol.requests.single()
        assertThat(request.maxTokens).isNull()
        assertThat(request.reasoningEffort).isEqualTo(ReasoningEffort.NONE)
        val wire = buildJsonObject { putChatReasoning(request.adaptedTo(protocol.protocolType, remote)) }
        assertThat(wire["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content).isEqualTo("disabled")
        assertThat(wire.keys).doesNotContain("reasoning_effort")
    }

    @Test
    fun `必需思考的模型使用最低档位且未知兼容模型不序列化思考字段`() = runTest {
        val protocol = RecordingProtocol(successfulStream("摘要"))
        val models = listOf("o3", "unknown-vendor-model")
        val router = RecordingRouter(models.associate { "current::$it" to endpoint("current", it, protocol) })
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不使用本地模型") }

        models.forEach { summarizer.summarize("待摘要数据", "current::$it") }

        val (required, unknown) = protocol.requests
        assertThat(required.reasoningEffort).isEqualTo(ReasoningEffort.LOW)
        val requiredWire = buildJsonObject { putChatReasoning(required.adaptedTo(protocol.protocolType, required.model)) }
        val unknownWire = buildJsonObject { putChatReasoning(unknown.adaptedTo(protocol.protocolType, unknown.model)) }
        assertThat(requiredWire["reasoning_effort"]?.jsonPrimitive?.content).isEqualTo("low")
        assertThat(unknownWire).isEmpty()
    }

    @Test
    fun `结构化保留终态失败码且丢弃部分正文`() = runTest {
        val reasons = mapOf(
            GenerationFailureCode.OUTPUT_LIMIT to ContextCompactionFailureReason.OUTPUT_LIMIT,
            GenerationFailureCode.TIMEOUT to ContextCompactionFailureReason.TIMEOUT,
            GenerationFailureCode.NETWORK to ContextCompactionFailureReason.NETWORK,
            GenerationFailureCode.CONTEXT_LIMIT to ContextCompactionFailureReason.CONTEXT_LIMIT,
            GenerationFailureCode.RATE_LIMIT to ContextCompactionFailureReason.RATE_LIMITED,
            GenerationFailureCode.AUTH to ContextCompactionFailureReason.AUTH,
            GenerationFailureCode.SERVER to ContextCompactionFailureReason.PROVIDER_ERROR,
        )
        for ((code, reason) in reasons) {
            val protocol = RecordingProtocol(flowOf(StreamChunk.TextDelta("部分正文"), StreamChunk.Error(code)))
            val result = summarizer(protocol).summarizeWithUsageOutcome("数据", "current::remote")
            assertThat(result).isEqualTo(ContextCompactionOutcome.Failure(reason))
        }
    }

    @Test
    fun `历史摘要透传路由和空正文失败原因`() = runTest {
        val protocol = RecordingProtocol(successfulStream(" "))
        val manager = summarizer(protocol).historySummaryManager(LlmProvider(protocol))
        val history = listOf(com.promenar.infinity.data.model.Message(
            "old", com.promenar.infinity.data.model.MessageRole.USER, "旧消息",
        ))

        val usage = mutableListOf<com.promenar.infinity.data.model.TokenUsage>()
        assertThat(manager.summarizeOutcome(null, history, "missing::remote", onUsage = usage::add))
            .isEqualTo(ContextCompactionOutcome.Failure(ContextCompactionFailureReason.ROUTE_UNAVAILABLE))
        assertThat(usage).isEmpty()
        assertThat(manager.summarizeOutcome(null, history, "current::remote"))
            .isEqualTo(ContextCompactionOutcome.Failure(ContextCompactionFailureReason.EMPTY_OUTPUT))
        assertThat(protocol.requests.single().maxTokens).isNull()
    }

    @Test
    fun `失败摘要仍通过用量回调保留提供商实报值`() = runTest {
        val outputs = listOf(
            flowOf(
                StreamChunk.Usage(ProtocolUsage(4000, 1000, 5000)),
                StreamChunk.Completed(CompletionReason.END_TURN),
            ),
            flowOf(
                StreamChunk.TextDelta("未完成摘要"),
                StreamChunk.Usage(ProtocolUsage(4000, 1000, 5000)),
            ),
            flowOf(
                StreamChunk.TextDelta("失败摘要"),
                StreamChunk.Usage(ProtocolUsage(4000, 1000, 5000)),
                StreamChunk.Error(GenerationFailureCode.SERVER),
                StreamChunk.Completed(CompletionReason.END_TURN),
            ),
        )
        outputs.forEach { output ->
            val protocol = RecordingProtocol(output)
            var reported: com.promenar.infinity.data.model.TokenUsage? = null

            val result = summarizer(protocol).summarizeWithUsage("data", "current::remote") { reported = it }

            assertThat(result).isNull()
            assertThat(reported?.total).isEqualTo(5000)
            assertThat(reported?.estimated).isFalse()
            assertThat(protocol.requests).hasSize(1)
        }
    }

    @Test
    fun `工具摘要仅按会话provider路由且不调用快速模型或默认模型`() = runTest {
        val summaryProtocol = RecordingProtocol(successfulStream("错误的快速模型"))
        val currentProtocol = RecordingProtocol(successfulStream("摘要", "正文"))
        val defaultProtocol = RecordingProtocol(successfulStream("错误的默认模型"))
        val router = RecordingRouter(
            mapOf(
                "summary-provider::summary-remote" to endpoint("summary-provider", "summary-remote", summaryProtocol),
                "current-provider::current-remote" to endpoint("current-provider", "current-remote", currentProtocol),
                "default-provider::default-remote" to endpoint("default-provider", "default-remote", defaultProtocol),
            ),
        )
        val localModels = mutableListOf<String>()
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { model ->
            localModels += model
            LlmProvider(defaultProtocol)
        }
        val input = "工具结果正文：成功读取 7 个文件。"

        val result = summarizer.summarize(input, "current-provider::current-remote")

        assertThat(result).isEqualTo("摘要正文")
        assertThat(router.resolvedIds).containsExactly("current-provider::current-remote")
        assertThat(currentProtocol.requests).hasSize(1)
        val request = currentProtocol.requests.single()
        assertThat(request.model).isEqualTo("current-remote")
        assertThat(request.maxTokens).isNull()
        assertThat(request.stream).isTrue()
        assertThat(request.toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(request.tools.orEmpty()).isEmpty()
        assertThat(request.webSearch).isNotEqualTo(true)
        assertThat(request.enableGeminiSearch).isNotEqualTo(true)
        assertThat(request.messages.single { it.role == "user" }.content).isEqualTo(input)
        assertThat(request.messages.map { it.role }).containsExactly("system", "user").inOrder()
        assertThat(currentProtocol.requestDispatchers).containsExactly(Dispatchers.IO)
        assertThat(summaryProtocol.requests).isEmpty()
        assertThat(defaultProtocol.requests).isEmpty()
        assertThat(localModels).isEmpty()
    }

    @Test
    fun `空白会话模型失败关闭且不解析其它模型`() = runTest {
        listOf("", "  ").forEach { currentModel ->
            val protocol = RecordingProtocol(successfulStream("不应发送"))
            val router = RecordingRouter(mapOf("current::remote" to endpoint("current", "remote", protocol)))
            val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用本地提供商") }

            assertThat(summarizer.summarize("待摘要数据", currentModel)).isNull()
            assertThat(router.resolvedIds).isEmpty()
            assertThat(protocol.requests).isEmpty()
        }
    }

    @Test
    fun `会话模型路由失败时返回空值且不向快速模型或默认provider发送数据`() = runTest {
        val quickProtocol = RecordingProtocol(successfulStream("不应发送"))
        val defaultProtocol = RecordingProtocol(successfulStream("不应发送"))
        val router = RecordingRouter(mapOf(
            "quick::remote" to endpoint("quick", "remote", quickProtocol),
            "default::remote" to endpoint("default", "remote", defaultProtocol),
        ))
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认本地提供商") }

        assertThat(summarizer.summarize("私有工具数据", "disabled::current")).isNull()
        assertThat(router.resolvedIds).containsExactly("disabled::current")
        assertThat(quickProtocol.requests).isEmpty()
        assertThat(defaultProtocol.requests).isEmpty()
    }

    @Test
    fun `路由解析抛出异常时失败关闭且不创建客户端`() = runTest {
        var clientCreated = false
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = throw IOException("route unavailable")
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient {
                clientCreated = true
                error("路由失败不应创建客户端")
            }
        }

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认提供商") }
            .summarize("数据", "current::remote")

        assertThat(result).isNull()
        assertThat(clientCreated).isFalse()
    }

    @Test
    fun `provider配置为空时返回空值且不调用协议工厂`() = runTest {
        var protocolCreated = false
        val resolved = endpoint("current", "remote", RecordingProtocol(successfulStream("不应发送"))).resolved
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String) = ProviderResolution.Success(resolved)
            override fun createClient(resolved: ResolvedProviderModel) = UnifiedLlmClient(
                providerConfigResolver = { null },
                protocolFactory = {
                    protocolCreated = true
                    error("缺少配置不应创建协议")
                },
            )
        }

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认提供商") }
            .summarize("数据", "current::remote")

        assertThat(result).isNull()
        assertThat(protocolCreated).isFalse()
    }

    @Test
    fun `缺失远端模型ID的路由不发送请求`() = runTest {
        val protocol = RecordingProtocol(successfulStream("不应发送"))
        val router = RecordingRouter(mapOf("current::" to endpoint("current", "", protocol)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用本地提供商") }
            .summarize("数据", "current::")

        assertThat(result).isNull()
        assertThat(protocol.requests).isEmpty()
    }

    @Test
    fun `EOF缺少显式完成事件时丢弃部分摘要`() = runTest {
        val protocol = RecordingProtocol(flowOf(StreamChunk.TextDelta("不完整摘要")))

        assertThat(summarizer(protocol).summarize("数据", "current::remote")).isNull()
    }

    @Test
    fun `显式错误后的文本不能冒充有效摘要`() = runTest {
        val protocol = RecordingProtocol(flowOf(
            StreamChunk.TextDelta("部分摘要"),
            StreamChunk.Error(GenerationFailureCode.SERVER),
            StreamChunk.Completed(CompletionReason.END_TURN),
        ))

        assertThat(summarizer(protocol).summarize("数据", "current::remote")).isNull()
    }

    @Test
    fun `完成后追加文本或重复完成事件不能冒充有效摘要`() = runTest {
        val outputs = listOf(
            flowOf(
                StreamChunk.TextDelta("摘要"),
                StreamChunk.Completed(CompletionReason.END_TURN),
                StreamChunk.TextDelta("完成后追加内容"),
            ),
            flowOf(
                StreamChunk.TextDelta("摘要"),
                StreamChunk.Completed(CompletionReason.END_TURN),
                StreamChunk.Completed(CompletionReason.END_TURN),
            ),
        )

        outputs.forEach { output ->
            assertThat(summarizer(RecordingProtocol(output)).summarize("数据", "current::remote")).isNull()
        }
    }

    @Test
    fun `意外工具调用与工具结束原因均拒绝采用`() = runTest {
        val protocol = RecordingProtocol(flowOf(
            StreamChunk.TextDelta("尝试执行工具"),
            StreamChunk.ToolCallDelta("call", "delete_file", "{}", 0),
            StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ))

        assertThat(summarizer(protocol).summarize("数据", "current::remote")).isNull()
    }

    @Test
    fun `只有结束事件或空白输出时不返回空摘要`() = runTest {
        val protocol = RecordingProtocol(successfulStream(" \n "))

        assertThat(summarizer(protocol).summarize("数据", "current::remote")).isNull()
    }

    @Test
    fun `流传输取消必须透传取消异常`() = runTest {
        val cancelled = CancellationException("summary cancelled")
        val protocol = RecordingProtocol(flow { throw cancelled })

        val failure = runCatching {
            summarizer(protocol).summarize("数据", "current::remote")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `流取消前的实报用量仍通过回调保留`() = runTest {
        val protocol = RecordingProtocol(flow {
            emit(StreamChunk.Usage(ProtocolUsage(4000, 1000, 5000)))
            throw CancellationException("summary cancelled")
        })
        var reported: com.promenar.infinity.data.model.TokenUsage? = null

        val failure = runCatching {
            summarizer(protocol).summarizeWithUsage("数据", "current::remote") { reported = it }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(reported).isEqualTo(com.promenar.infinity.data.model.TokenUsage(4000, 1000, 5000))
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `路由取消必须透传且不降级到默认provider`() = runTest {
        val cancelled = CancellationException("route cancelled")
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = throw cancelled
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("不应创建客户端")
        }

        val failure = runCatching {
            RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认提供商") }
                .summarize("数据", "current::remote")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `本地摘要使用路由远端ID同步推理且禁用工具搜索`() = runTest {
        val localProtocol = RecordingProtocol(successfulStream("不应走流式"), PromptResponse("本地摘要"))
        val router = RecordingRouter(mapOf("local::gguf-model" to endpoint("local", "gguf-model", localProtocol, local = true)))
        val localModels = mutableListOf<String>()
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { model ->
            localModels += model
            LlmProvider(localProtocol)
        }

        assertThat(summarizer.summarize("数据", "local::gguf-model")).isEqualTo("本地摘要")
        assertThat(localModels).containsExactly("gguf-model")
        assertThat(router.createdClients).isEmpty()
        assertThat(localProtocol.requests).isEmpty()
        val request = localProtocol.syncRequests.single()
        assertThat(request.model).isEqualTo("gguf-model")
        assertThat(request.maxTokens).isNull()
        assertThat(request.stream).isFalse()
        assertThat(request.toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(request.tools.orEmpty()).isEmpty()
        assertThat(request.webSearch).isNotEqualTo(true)
        assertThat(request.enableGeminiSearch).isNotEqualTo(true)
        assertThat(localProtocol.requestDispatchers).containsExactly(Dispatchers.IO)
    }

    @Test
    fun `本地响应包含工具调用时拒绝采用`() = runTest {
        val protocol = RecordingProtocol(
            successfulStream("不应走流式"),
            PromptResponse("不安全摘要", toolCalls = listOf(ProtocolToolCall("call", "write_file", "{}"))),
        )
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
            .summarize("数据", "local::model")

        assertThat(result).isNull()
    }

    @Test
    fun `本地推理取消必须透传`() = runTest {
        val protocol = RecordingProtocol(
            successfulStream("不应走流式"),
            syncFailure = CancellationException("local cancelled"),
        )
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val failure = runCatching {
            RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
                .summarize("数据", "local::model")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `多条累计Usage按单请求最大计量而非逐事件相加`() = runTest {
        val cases = listOf(
            listOf(ProtocolUsage(10, 1, 11), ProtocolUsage(10, 5, 9), ProtocolUsage(8, 2, 10)) to 15,
            listOf(ProtocolUsage(10, 1, 40), ProtocolUsage(10, 5, 15)) to 40,
        )
        cases.forEach { (usages, expectedTotal) ->
            val protocol = RecordingProtocol(flow {
                emit(StreamChunk.TextDelta("完整摘要"))
                usages.forEach { emit(StreamChunk.Usage(it)) }
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            })

            val result = summarizer(protocol).summarizeWithUsage("工具数据", "current::remote")

            assertThat(result?.content).isEqualTo("完整摘要")
            assertThat(result?.usage?.input).isEqualTo(10)
            assertThat(result?.usage?.output).isEqualTo(5)
            assertThat(result?.usage?.total).isEqualTo(expectedTotal)
            assertThat(result?.usage?.estimated).isFalse()
            assertThat(protocol.requests).hasSize(1)
        }
    }

    @Test
    fun `无Usage时计入完整提示和输出估算且不额外发送请求`() = runTest {
        val protocol = RecordingProtocol(successfulStream("摘要"))

        val result = summarizer(protocol).summarizeWithUsage("abcdefgh", "current::remote")

        assertThat(result?.content).isEqualTo("摘要")
        val usage = checkNotNull(result).usage
        assertThat(usage.estimated).isTrue()
        assertThat(usage.input).isGreaterThan(8)
        assertThat(usage.output).isEqualTo(2)
        assertThat(usage.total).isEqualTo(usage.input + 2)
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `本地同步响应采用实际Usage并补足不一致总数`() = runTest {
        val protocol = RecordingProtocol(
            successfulStream("不应走流式"),
            PromptResponse("本地摘要", usage = ProtocolUsage(11, 7, 15)),
        )
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
            .summarizeWithUsage("工具数据", "local::model")

        val usage = checkNotNull(result).usage
        assertThat(result.content).isEqualTo("本地摘要")
        assertThat(usage.input).isEqualTo(11)
        assertThat(usage.output).isEqualTo(7)
        assertThat(usage.total).isEqualTo(18)
        assertThat(usage.estimated).isFalse()
        assertThat(protocol.syncRequests).hasSize(1)
        assertThat(protocol.requests).isEmpty()
    }

    @Test
    fun `本地缺少Usage时估算提示和输出`() = runTest {
        val protocol = RecordingProtocol(successfulStream("不应走流式"), PromptResponse("本地摘要"))
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
            .summarizeWithUsage("abcdefgh", "local::model")

        val usage = checkNotNull(result).usage
        assertThat(usage.estimated).isTrue()
        assertThat(usage.input).isGreaterThan(8)
        assertThat(usage.output).isEqualTo(4)
        assertThat(usage.total).isEqualTo(usage.input + 4)
        assertThat(protocol.syncRequests).hasSize(1)
    }

    @Test
    fun `用量总和超过Int上限时饱和而不溢出为负数`() = runTest {
        val protocol = RecordingProtocol(flowOf(
            StreamChunk.TextDelta("摘要"),
            StreamChunk.Usage(ProtocolUsage(1_500_000_000, 1_000_000_000, 0)),
            StreamChunk.Completed(CompletionReason.END_TURN),
        ))

        val result = summarizer(protocol).summarizeWithUsage("工具数据", "current::remote")

        assertThat(result?.usage?.input).isEqualTo(1_500_000_000)
        assertThat(result?.usage?.output).isEqualTo(1_000_000_000)
        assertThat(result?.usage?.total).isEqualTo(Int.MAX_VALUE)
        assertThat(result?.usage?.estimated).isFalse()
    }

    private fun summarizer(protocol: RecordingProtocol): RoutedToolResultSummarizer {
        val router = RecordingRouter(mapOf("current::remote" to endpoint("current", "remote", protocol)))
        return RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用本地提供商") }
    }

    private fun endpoint(
        providerId: String,
        remoteModelId: String,
        protocol: RecordingProtocol,
        local: Boolean = false,
    ): Endpoint = Endpoint(
        ResolvedProviderModel(
            modelId = "$providerId::$remoteModelId",
            remoteModelId = remoteModelId,
            providerId = providerId,
            providerName = providerId,
            config = UnifiedProviderConfig(
                protocolType = if (local) ProtocolType.Local else ProtocolType.OpenAI_ChatCompletions,
                baseUrl = if (local) "" else "https://$providerId.invalid",
                apiKey = "",
                defaultModel = remoteModelId,
            ),
        ),
        protocol,
    )

    private fun successfulStream(vararg chunks: String): Flow<StreamChunk> = flow {
        chunks.forEach { emit(StreamChunk.TextDelta(it)) }
        emit(StreamChunk.Completed(CompletionReason.END_TURN))
    }

    private data class Endpoint(val resolved: ResolvedProviderModel, val protocol: RecordingProtocol)

    private class RecordingRouter(private val endpoints: Map<String, Endpoint>) : ProviderRequestRouter {
        val resolvedIds = mutableListOf<String>()
        val createdClients = mutableListOf<String>()

        override fun resolve(modelId: String): ProviderResolution {
            resolvedIds += modelId
            return endpoints[modelId]?.let { ProviderResolution.Success(it.resolved) }
                ?: ProviderResolution.Failure(ProviderResolutionError.MODEL_NOT_FOUND, modelId)
        }

        override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient {
            createdClients += resolved.providerId
            return UnifiedLlmClient(
                providerConfigResolver = { resolved.config },
                protocolFactory = { endpoints.getValue(resolved.modelId).protocol },
            )
        }
    }

    private class RecordingProtocol(
        private val output: Flow<StreamChunk>,
        private val syncResponse: PromptResponse = PromptResponse(""),
        private val syncFailure: Exception? = null,
    ) : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        val requests = mutableListOf<PromptRequest>()
        val syncRequests = mutableListOf<PromptRequest>()
        val requestDispatchers = mutableListOf<ContinuationInterceptor?>()

        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
            requests += request
            requestDispatchers += currentCoroutineContext()[ContinuationInterceptor]
            return output
        }

        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
            syncRequests += request
            requestDispatchers += currentCoroutineContext()[ContinuationInterceptor]
            syncFailure?.let { throw it }
            return syncResponse
        }

        override fun cancel() = Unit
    }
}
