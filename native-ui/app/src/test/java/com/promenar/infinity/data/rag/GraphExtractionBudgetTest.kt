package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.AbnormalFinishException
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class GraphExtractionBudgetTest {
    @Test
    fun `默认文档调用预算停止于64次并返回部分完成`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var requests = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            requests++
            PromptResponse(GRAPH, usage = ProtocolUsage(10, 1))
        }
        val extractor = testGraphExtractor(protocol, mockk(relaxed = true), chunkSize = 10, chunkOverlap = 0)

        val result = extractor.extractCandidate("a".repeat(650))

        assertThat(requests).isEqualTo(64)
        assertThat(result.status.name).isEqualTo("PARTIAL")
        assertThat(result.edges).hasSize(1)
    }

    @Test
    fun `每次KG请求都声明输出token上限`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var sent: PromptRequest? = null
        coEvery { protocol.sendPromptSync(any()) } answers {
            sent = firstArg()
            PromptResponse(GRAPH)
        }

        val result = testKgClient(protocol).extract("owner::model", "提取实体", "甲与乙认识", 1000)

        assertThat(result.isSuccess).isTrue()
        assertThat(sent!!.maxTokens).isEqualTo(16384)
    }

    @Test
    fun `剩余输出预算收紧下一请求且实际用量耗尽后不再调用`() = runTest {
        val protocol = mockk<LlmProtocol>()
        val limits = mutableListOf<Int?>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            limits += firstArg<PromptRequest>().maxTokens
            PromptResponse(GRAPH, usage = ProtocolUsage(input = 11, output = if (limits.size == 1) 3 else 2))
        }
        val extractor = extractor(protocol, KgExtractionBudget(maxCalls = 10, maxOutputTokens = 5, requestMaxOutputTokens = 4))

        val result = extractor.extractCandidate("a".repeat(30))

        assertThat(limits).containsExactly(4, 2).inOrder()
        assertThat(result.progress!!.processedChunks).isEqualTo(2)
        assertThat(result.progress!!.totalChunks).isEqualTo(3)
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.OUTPUT_TOKENS)
        assertThat(result.usage).isEqualTo(KgTokenUsage(22, 5))
    }

    @Test
    fun `总时长耗尽会取消在途请求并提交此前完成分块`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var requests = 0
        coEvery { protocol.sendPromptSync(any()) } coAnswers {
            requests++
            delay(700)
            PromptResponse(GRAPH, usage = ProtocolUsage(10, 2))
        }

        val result = extractor(protocol, KgExtractionBudget(maxDurationMs = 1_000)).extractCandidate("a".repeat(30))

        assertThat(testScheduler.currentTime).isEqualTo(1_000)
        assertThat(requests).isEqualTo(2)
        assertThat(result.status).isEqualTo(KgExtractionStatus.PARTIAL)
        assertThat(result.progress!!.processedChunks).isEqualTo(1)
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.DURATION)
        assertThat(result.edges).hasSize(1)
    }

    @Test
    fun `文档版本断点保留进度与预算且上调预算后只处理未完成分块`() = runTest {
        val root = Files.createTempDirectory("kg-budget-checkpoint").toFile()
        try {
            val protocol = mockk<LlmProtocol>()
            var requests = 0
            coEvery { protocol.sendPromptSync(any()) } answers {
                requests++
                PromptResponse(GRAPH, usage = ProtocolUsage(5, 2))
            }
            val text = "a".repeat(30)
            val limited = KgExtractionBudget(maxCalls = 1)

            val first = extractor(protocol, limited, root).extractCandidate(text, "document", runId = "run-a")
            val sameBudget = extractor(protocol, limited, root).extractCandidate(text, "document", runId = "run-a")
            assertThat(first.progress!!.processedChunks).isEqualTo(1)
            assertThat(sameBudget.progress!!.processedChunks).isEqualTo(1)
            assertThat(requests).isEqualTo(1)

            val completed = extractor(protocol, limited.copy(maxCalls = 3), root).extractCandidate(text, "document")
            assertThat(requests).isEqualTo(3)
            assertThat(completed.status).isEqualTo(KgExtractionStatus.SUCCESS)
            assertThat(completed.progress!!.processedChunks).isEqualTo(3)
            assertThat(root.listFiles().orEmpty()).isEmpty()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `请求取消的已耗时会占用恢复后的同文档总时长预算`() = runTest {
        val root = Files.createTempDirectory("kg-budget-cancel").toFile()
        try {
            val protocol = mockk<LlmProtocol>()
            val identity = GraphCheckpointIdentity(kgSha256("a".repeat(10)), kgSha256("a".repeat(10)),
                "owner::model", kgSha256(GraphExtractor.DEFAULT_KG_PROMPT), 10, 0)
            var reservedElapsed: Long? = null
            coEvery { protocol.sendPromptSync(any()) } coAnswers {
                if (reservedElapsed == null) reservedElapsed = root.walkTopDown().firstOrNull { it.name.startsWith("budget_") }?.readText()?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["state"]?.jsonObject?.get("elapsedMs")?.jsonPrimitive?.content?.toLong() }
                delay(1_000)
                PromptResponse(GRAPH, usage = ProtocolUsage(5, 2))
            }
            val budget = KgExtractionBudget(maxDurationMs = 1_000)
            val first = launch { extractor(protocol, budget, root).extractCandidate("a".repeat(10), "document", runId = "run-a") }
            runCurrent()
            assertThat(reservedElapsed).isEqualTo(1_000)
            advanceTimeBy(500)
            first.cancelAndJoin()

            val resumed = extractor(protocol, budget, root).extractCandidate("a".repeat(10), "document", runId = "run-a")

            assertThat(testScheduler.currentTime).isEqualTo(500)
            assertThat(resumed.status).isEqualTo(KgExtractionStatus.PARTIAL)
            assertThat(resumed.progress!!.reason).isEqualTo(KgExtractionStopReason.DURATION)
            assertThat(resumed.progress!!.processedChunks).isEqualTo(0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `切换模型或窗口后新运行重新获得调用预算`() = runTest {
        for ((model, chunk) in listOf("other::model" to 10, "owner::model" to 5)) {
            val root = Files.createTempDirectory("kg-budget-configuration").toFile()
            try {
                val protocol = mockk<LlmProtocol>()
                var requests = 0
                coEvery { protocol.sendPromptSync(any()) } answers {
                    requests++
                    PromptResponse(GRAPH, usage = ProtocolUsage(5, 2))
                }
                val budget = KgExtractionBudget(maxCalls = 1)
                extractor(protocol, budget, root).extractCandidate("a".repeat(20), "document")

                val result = extractor(protocol, budget, root, model, chunk).extractCandidate("a".repeat(20), "document")

                assertThat(requests).isEqualTo(2)
                assertThat(result.progress!!.processedChunks).isEqualTo(1)
                assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.CALLS)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `坏JSON的已返回实际用量仍记入KG分类`() = runTest {
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse("invalid", usage = ProtocolUsage(17, 7))
        val usageStore = TestKgUsageStore()
        val router = testKgRouter()
        val client = KgRequestClient(router, { protocol }, StandardTestDispatcher(testScheduler), usageStore)

        val result = client.extract("owner::model", "提取实体", "甲与乙认识", 1000)

        assertThat(result.status).isEqualTo(KgExtractionStatus.PARSE_FAILURE)
        assertThat(result.usage).isEqualTo(KgTokenUsage(17, 7))
        assertThat(usageStore.records.single().modelId).isEqualTo("owner::model")
        assertThat(usageStore.records.single().usage).isEqualTo(KgTokenUsage(17, 7))
    }

    @Test
    fun `输出截断的请求返回并记录同一估算用量`() = runTest {
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } answers { throw outputLimit() }
        val usageStore = TestKgUsageStore()
        val client = KgRequestClient(testKgRouter(), { protocol }, StandardTestDispatcher(testScheduler), usageStore)

        val result = client.extract(
            "owner::model", "abcd", "efghij", 1000, maxOutputTokens = 4,
            historyMessages = listOf(ProtocolMessage("user", "klmn")),
        )

        assertThat(result.status).isEqualTo(KgExtractionStatus.PARSE_FAILURE)
        assertThat(result.usage).isEqualTo(KgTokenUsage(7, 4, estimated = true))
        assertThat(usageStore.records.single().usage).isEqualTo(result.usage)
    }

    @Test
    fun `单次截断按请求上限计入预算并停止`() = runTest {
        val protocol = mockk<LlmProtocol>()
        val limits = mutableListOf<Int?>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            limits += firstArg<PromptRequest>().maxTokens
            throw outputLimit()
        }

        val result = extractor(protocol, KgExtractionBudget(maxOutputTokens = 4, requestMaxOutputTokens = 4))
            .extractCandidate("abcdefgh")

        assertThat(limits).containsExactly(4)
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.OUTPUT_TOKENS)
        assertThat(result.usage.outputTokens).isEqualTo(4)
        assertThat(result.usage.estimated).isTrue()
    }

    @Test
    fun `连续截断拆块重抽累计输出预算直至耗尽`() = runTest {
        val protocol = mockk<LlmProtocol>()
        val limits = mutableListOf<Int?>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            limits += firstArg<PromptRequest>().maxTokens
            throw outputLimit()
        }

        val result = extractor(protocol, KgExtractionBudget(maxOutputTokens = 8, requestMaxOutputTokens = 4))
            .extractCandidate("abcdefgh")

        assertThat(limits).containsExactly(4, 4).inOrder()
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.OUTPUT_TOKENS)
        assertThat(result.progress!!.calls).isEqualTo(2)
        assertThat(result.usage.outputTokens).isEqualTo(8)
    }

    @Test
    fun `估算按窗口与追加轮计算确认阈值`() {
        val budget = KgExtractionBudget()
        assertThat(budget.estimate("a".repeat(128_000)).requiresConfirmation).isFalse()
        assertThat(budget.estimate("a".repeat(128_001)).requiresConfirmation).isTrue()
        val large = budget.estimate("a".repeat(1_000_000))
        assertThat(large.windows).isEqualTo(63)
        assertThat(large.calls).isEqualTo(126)
        assertThat(large.requiresConfirmation).isTrue()
        assertThat(large.budgetedCalls).isEqualTo(64)
        assertThat(large.outputTokenLimit).isEqualTo(126L * 16384)
    }

    private fun TestScope.extractor(
        protocol: LlmProtocol, budget: KgExtractionBudget, root: File? = null,
        modelId: String = "owner::model", chunkSize: Int = 10,
    ) = GraphExtractor(
        testKgClient(protocol), mockk(relaxed = true), modelId, chunkSize = chunkSize, chunkOverlap = 0,
        checkpointRoot = root, dispatcher = StandardTestDispatcher(testScheduler), budget = budget,
        nowMs = { testScheduler.currentTime }, concurrency = 1, gleaningPasses = 0,
    )

    private fun outputLimit() = AbnormalFinishException(StreamChunk.Error(code = GenerationFailureCode.OUTPUT_LIMIT))

    companion object {
        private const val GRAPH = """{"nodes":[{"name":"甲","type":"person"},{"name":"乙","type":"person"}],"edges":[{"source":"甲","target":"乙","relation":"认识"}]}"""
    }
}
