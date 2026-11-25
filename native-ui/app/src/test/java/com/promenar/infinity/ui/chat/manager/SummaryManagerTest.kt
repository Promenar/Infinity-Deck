package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.LlmProvider
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SummaryManagerTest {
    @Test
    fun `历史摘要只传递会话模型并在IO发送禁用搜索的同步请求`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("新的历史摘要"))
        val progress = mutableListOf<String>()

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull(
            oldSummary = null,
            overflowMessages = messages(),
            currentModelId = "current-provider::current-remote",
            onProgress = progress::add,
            sessionId = "session-id",
        )

        assertThat(result).isEqualTo("新的历史摘要")
        val request = protocol.requests.single()
        assertThat(request.sessionId).isEqualTo("session-id")
        assertThat(request.model).isEqualTo("current-provider::current-remote")
        assertThat(request.stream).isFalse()
        assertThat(request.maxTokens).isNull()
        assertThat(request.tools.orEmpty()).isEmpty()
        assertThat(request.webSearch).isFalse()
        assertThat(request.enableGeminiSearch).isFalse()
        assertThat(request.messages.single().role).isEqualTo("user")
        assertThat(protocol.dispatchers).containsExactly(Dispatchers.IO)
        assertThat(progress).containsExactly("context_summary_calling")
    }

    @Test
    fun `历史摘要保留长正文且不限制模型输出`() = runTest {
        val content = "已确认的历史事实。".repeat(800) + "末尾未决事项"
        val protocol = RecordingProtocol(PromptResponse(content))

        val result = SummaryManager(LlmProvider(protocol)).summarizeOutcome(null, messages(), "current::remote")

        assertThat(result.getOrNull()).isEqualTo(content)
        assertThat(protocol.requests.single().maxTokens).isNull()
    }

    @Test
    fun `旧摘要与新增历史按原顺序合并到摘要请求`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("合并结果"))
        val history = listOf(
            Message("m1", MessageRole.USER, "用户新事实"),
            Message("m2", MessageRole.ASSISTANT, "助手新事实"),
            Message("m3", MessageRole.TOOL, "工具新事实"),
        )

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull("既有事实", history, "current::remote")

        assertThat(result).isEqualTo("合并结果")
        val prompt = protocol.requests.single().messages.single().content
        assertThat(prompt).contains("既有事实")
        assertThat(prompt).contains("USER: 用户新事实")
        assertThat(prompt).contains("ASSISTANT: 助手新事实")
        assertThat(prompt).contains("TOOL: 工具新事实")
        assertThat(prompt.indexOf("既有事实")).isLessThan(prompt.indexOf("USER: 用户新事实"))
        assertThat(prompt.indexOf("USER: 用户新事实")).isLessThan(prompt.indexOf("ASSISTANT: 助手新事实"))
        assertThat(prompt.indexOf("ASSISTANT: 助手新事实")).isLessThan(prompt.indexOf("TOOL: 工具新事实"))
    }

    @Test
    fun `单条过长历史只取有界正文且不修改原消息`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("摘要"))
        val original = Message("long", MessageRole.TOOL, "甲".repeat(2000) + "不应进入摘要的尾部")

        SummaryManager(LlmProvider(protocol)).summarizeOrNull(null, listOf(original), "current::remote")

        val prompt = protocol.requests.single().messages.single().content
        assertThat(prompt).contains("甲".repeat(2000))
        assertThat(prompt).doesNotContain("不应进入摘要的尾部")
        assertThat(original.content).endsWith("不应进入摘要的尾部")
    }

    @Test
    fun `摘要请求异常返回空值而非旧摘要并报告估算用量`() = runTest {
        val protocol = RecordingProtocol(failure = IOException("summary unavailable"))
        val usage = mutableListOf<TokenUsage>()

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull(
            "旧摘要", messages(), "current::remote", onUsage = usage::add,
        )

        assertThat(result).isNull()
        assertThat(protocol.requests).hasSize(1)
        val recorded = usage.single()
        assertThat(recorded.estimated).isTrue()
        assertThat(recorded.input).isGreaterThan(0)
        assertThat(recorded.output).isEqualTo(0)
        assertThat(recorded.total).isEqualTo(recorded.input)
    }

    @Test
    fun `路由拒绝或空白响应返回空值而非沿用旧摘要`() = runTest {
        listOf(null, PromptResponse(" \n ")).forEach { response ->
            val fallback = RecordingProtocol(PromptResponse("不应走默认提供商"))
            val requests = mutableListOf<PromptRequest>()
            val manager = SummaryManager(LlmProvider(fallback)) { request, _ ->
                requests += request
                response
            }

            val result = manager.summarizeOrNull("旧摘要", messages(), "current::remote")

            assertThat(result).isNull()
            assertThat(requests.single().model).isEqualTo("current::remote")
            assertThat(fallback.requests).isEmpty()
        }
    }

    @Test
    fun `没有待摘要消息时保留已有摘要且不发请求或记账`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("不应发送"))
        val usage = mutableListOf<TokenUsage>()

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull(
            "旧摘要", emptyList(), "current::remote", onUsage = usage::add,
        )

        assertThat(result).isEqualTo("旧摘要")
        assertThat(protocol.requests).isEmpty()
        assertThat(usage).isEmpty()
    }

    @Test
    fun `空白会话模型不请求默认模型且不记账`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("不应发送"))
        val usage = mutableListOf<TokenUsage>()

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull(
            "旧摘要", messages(), "  ", onUsage = usage::add,
        )

        assertThat(result).isNull()
        assertThat(protocol.requests).isEmpty()
        assertThat(usage).isEmpty()
    }

    @Test
    fun `同步响应的实报用量只回调一次且保持非估算`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("摘要", usage = ProtocolUsage(31, 7, 38)))
        val usage = mutableListOf<TokenUsage>()

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull(
            null, messages(), "current::remote", onUsage = usage::add,
        )

        assertThat(result).isEqualTo("摘要")
        assertThat(usage).containsExactly(TokenUsage(31, 7, 38))
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `精确路由传入会话模型且采用最后一份累计实报用量`() = runTest {
        val fallback = RecordingProtocol(PromptResponse("不应走默认提供商"))
        val requests = mutableListOf<PromptRequest>()
        val usage = mutableListOf<TokenUsage>()
        val manager = SummaryManager(LlmProvider(fallback)) { request, reportUsage ->
            requests += request
            reportUsage?.invoke(TokenUsage(21, 4, 25))
            reportUsage?.invoke(TokenUsage(30, 7, 37))
            PromptResponse("路由摘要")
        }

        val result = manager.summarizeOrNull(null, messages(), "current::remote", onUsage = usage::add)

        assertThat(result).isEqualTo("路由摘要")
        assertThat(requests.single().model).isEqualTo("current::remote")
        assertThat(usage).containsExactly(TokenUsage(30, 7, 37))
        assertThat(fallback.requests).isEmpty()
    }

    @Test
    fun `没有实报用量时包含旧摘要提示及输出的估算值`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("新增摘要"))
        val usage = mutableListOf<TokenUsage>()

        val result = SummaryManager(LlmProvider(protocol)).summarizeOrNull(
            "旧".repeat(100), messages(), "current::remote", onUsage = usage::add,
        )

        assertThat(result).isEqualTo("新增摘要")
        val recorded = usage.single()
        assertThat(recorded.estimated).isTrue()
        assertThat(recorded.input).isGreaterThan(100)
        assertThat(recorded.output).isEqualTo(4)
        assertThat(recorded.total).isEqualTo(recorded.input + 4)
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `摘要失败前收到的路由实报用量不会被估算覆盖`() = runTest {
        val fallback = RecordingProtocol(PromptResponse("不应发送"))
        val usage = mutableListOf<TokenUsage>()
        val manager = SummaryManager(LlmProvider(fallback)) { _, reportUsage ->
            reportUsage?.invoke(TokenUsage(4000, 1000, 5000))
            throw IOException("response unavailable")
        }

        val result = manager.summarizeOrNull("旧摘要", messages(), "current::remote", onUsage = usage::add)

        assertThat(result).isNull()
        assertThat(usage).containsExactly(TokenUsage(4000, 1000, 5000))
        assertThat(fallback.requests).isEmpty()
    }

    @Test
    fun `摘要取消必须透传且没有实报时不虚构估算用量`() = runTest {
        val protocol = RecordingProtocol(failure = CancellationException("summary cancelled"))
        val usage = mutableListOf<TokenUsage>()

        val failure = runCatching {
            SummaryManager(LlmProvider(protocol)).summarizeOrNull(
                "旧摘要", messages(), "current::remote", onUsage = usage::add,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(usage).isEmpty()
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `摘要取消前收到的实报用量仍交给记账回调`() = runTest {
        val fallback = RecordingProtocol(PromptResponse("不应发送"))
        val usage = mutableListOf<TokenUsage>()
        val manager = SummaryManager(LlmProvider(fallback)) { _, reportUsage ->
            reportUsage?.invoke(TokenUsage(4000, 1000, 5000))
            throw CancellationException("summary cancelled")
        }

        val failure = runCatching {
            manager.summarizeOrNull("旧摘要", messages(), "current::remote", onUsage = usage::add)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(usage).containsExactly(TokenUsage(4000, 1000, 5000))
        assertThat(fallback.requests).isEmpty()
    }

    @Test
    fun `记账回调失败必须透传且不得重试摘要请求`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("摘要", usage = ProtocolUsage(10, 2, 12)))
        val accountingFailure = IOException("accounting unavailable")

        val failure = runCatching {
            SummaryManager(LlmProvider(protocol)).summarizeOrNull(
                null, messages(), "current::remote", onUsage = { throw accountingFailure },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure?.message).isEqualTo("accounting unavailable")
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `记账回调取消必须透传取消异常`() = runTest {
        val protocol = RecordingProtocol(PromptResponse("摘要", usage = ProtocolUsage(10, 2, 12)))

        val failure = runCatching {
            SummaryManager(LlmProvider(protocol)).summarizeOrNull(
                null, messages(), "current::remote", onUsage = { throw CancellationException("accounting cancelled") },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(protocol.requests).hasSize(1)
    }

    private fun messages() = listOf(Message("m1", MessageRole.USER, "待总结的新事实"))

    private class RecordingProtocol(
        private val response: PromptResponse = PromptResponse(""),
        private val failure: Exception? = null,
    ) : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        val requests = mutableListOf<PromptRequest>()
        val dispatchers = mutableListOf<ContinuationInterceptor?>()

        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> =
            error("历史摘要应通过同步请求发送")

        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
            requests += request
            dispatchers += currentCoroutineContext()[ContinuationInterceptor]
            failure?.let { throw it }
            return response
        }

        override fun cancel() = Unit
    }
}
