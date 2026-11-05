package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class KgWindowExtractionTest {
    private val empty = """{"nodes":[],"edges":[]}"""
    private val base = """{"nodes":[{"name":"甲","type":"person"},{"name":"乙","type":"person"}],"edges":[{"source":"甲","target":"乙","relation":"认识"}]}"""
    private val extra = """{"nodes":[{"name":"丙","type":"person"}],"edges":[{"source":"甲","target":"丙","relation":"认识"}]}"""

    @Test fun `并发窗口按原文定位并保留关系出现`() = runTest {
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            val input = firstArg<PromptRequest>().messages[1].content
            val quote = if (input.startsWith("a")) "甲乙结盟" else "不存在的引文"
            PromptResponse("""{"nodes":[{"name":"甲","type":"person","evidence":"甲乙"},{"name":"乙","type":"person"}],"edges":[{"source":"甲","target":"乙","relation":"盟友","kind":"state","family":"relationship","evidence":"$quote"}]}""")
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            chunkSize = 10, concurrency = 2, gleaningPasses = 0, dispatcher = StandardTestDispatcher(testScheduler))
        val result = extractor.extractCandidate("aaaa甲乙结盟xxbbbb甲乙反目yy")
        val appearances = result.edges.sortedBy { it.windowIndex }
        assertThat(appearances.map { it.windowIndex }).containsExactly(0, 1).inOrder()
        assertThat(appearances[0].charStart).isEqualTo(4)
        assertThat(appearances[0].charEnd).isEqualTo(8)
        assertThat(appearances[0].positionCoarse).isFalse()
        assertThat(appearances[1].charStart).isEqualTo(0)
        assertThat(appearances[1].positionCoarse).isTrue()
    }

    @Test fun `追加请求携带规范化首轮历史并合并结果`() = runTest {
        val protocol = mockk<LlmProtocol>()
        val sent = mutableListOf<PromptRequest>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            sent += firstArg<PromptRequest>()
            PromptResponse(if (sent.size == 1) "```json\n$base\n```" else extra, usage = ProtocolUsage(10, 3))
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 1)
        val result = extractor.extractCandidate("正文")
        assertThat(sent).hasSize(2)
        assertThat(sent[1].messages.map { it.role }).containsExactly("system", "user", "assistant", "user").inOrder()
        assertThat(sent[1].messages[2].content).startsWith("{\"nodes\"")
        assertThat(sent[1].messages[2].content).doesNotContain("```")
        assertThat(result.nodes.map { it.name }).containsExactly("甲", "乙", "丙")
        assertThat(result.edges).hasSize(2)
    }

    @Test fun `追加失败与关闭追加均不影响首轮窗口成功`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            if (calls == 2) throw IllegalStateException("fixture")
            PromptResponse(base, usage = ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 1)
        assertThat(extractor.extractCandidate("正文").status).isEqualTo(KgExtractionStatus.SUCCESS)
        val noExtra = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 0)
        assertThat(noExtra.extractCandidate("正文").status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(calls).isEqualTo(3)
    }

    @Test fun `追加轮预算不足仍完成窗口`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            PromptResponse(base, usage = ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 1, budget = KgExtractionBudget(maxCalls = 1))
        val result = extractor.extractCandidate("正文")
        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(result.progress!!.processedChunks).isEqualTo(1)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `并发不超限制且完成顺序不改变合并结果`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var active = 0
        var peak = 0
        var reverse = false
        coEvery { protocol.sendPromptSync(any()) } coAnswers {
            active++
            peak = maxOf(peak, active)
            val input = firstArg<PromptRequest>().messages[1].content
            delay(if ((input.first() == 'a') xor reverse) 300 else 100)
            active--
            val id = input.first()
            PromptResponse("""{"nodes":[{"name":"root"},{"name":"$id"}],"edges":[{"source":"root","target":"$id","relation":"联系"}]}""",
                usage = ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            chunkSize = 2000, concurrency = 2, gleaningPasses = 0, dispatcher = StandardTestDispatcher(testScheduler))
        val input = "a".repeat(2000) + "b".repeat(2000) + "c".repeat(2000)
        val result = extractor.extractCandidate(input)
        reverse = true
        val reversed = extractor.extractCandidate(input)
        assertThat(peak).isEqualTo(2)
        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(result.progress!!.processedChunks).isEqualTo(3)
        assertThat(result.nodes).isEqualTo(reversed.nodes)
        assertThat(result.edges).isEqualTo(reversed.edges)
    }

    @Test fun `默认构建器和运行抽取器使用相同的估算`() = runTest {
        val config = RagConfiguration(kgWindowChars = 2000, kgGleaningPasses = 2, kgRequestMaxOutputTokens = 1024)
        val builder = KnowledgeGraphCandidateBuilder { _, _, _ -> ExtractionResult(emptyList(), emptyList()) }
        val extractor = GraphExtractor(testKgClient(mockk<LlmProtocol>()), mockk(relaxed = true), "owner::model",
            chunkSize = config.kgWindowChars, gleaningPasses = config.kgGleaningPasses,
            budget = KgExtractionBudget.from(config), dispatcher = StandardTestDispatcher(testScheduler))
        val text = "x".repeat(4001)
        assertThat(builder.estimate(text, config)).isEqualTo(extractor.estimate(text))
    }

    @Test fun `限流只重试一次并计入运行调用数`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            if (calls == 1) throw IllegalStateException("[HTTP 429] fixture")
            PromptResponse(empty, usage = ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 0)
        val result = extractor.extractCandidate("正文")
        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(result.progress!!.calls).isEqualTo(2)
        assertThat(testScheduler.currentTime).isEqualTo(2000)
    }

    @Test fun `相同运行沿用预算新运行复用断点并重置预算`() = runTest {
        val root = Files.createTempDirectory("kg-runs").toFile()
        try {
            val protocol = mockk<LlmProtocol>()
            var calls = 0
            coEvery { protocol.sendPromptSync(any()) } answers { calls++; PromptResponse(empty, usage = ProtocolUsage(2, 1)) }
            val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
                chunkSize = 2000, concurrency = 1, gleaningPasses = 0, checkpointRoot = root,
                dispatcher = StandardTestDispatcher(testScheduler), budget = KgExtractionBudget(maxCalls = 1))
            val text = "a".repeat(4000)
            assertThat(extractor.extractCandidate(text, "doc", runId = "run-a").status).isEqualTo(KgExtractionStatus.PARTIAL)
            assertThat(extractor.extractCandidate(text, "doc", runId = "run-a").status).isEqualTo(KgExtractionStatus.PARTIAL)
            assertThat(calls).isEqualTo(1)
            val resumed = extractor.extractCandidate(text, "doc", runId = "run-b")
            assertThat(resumed.status).isEqualTo(KgExtractionStatus.SUCCESS)
            assertThat(resumed.progress!!.calls).isEqualTo(1)
            assertThat(calls).isEqualTo(2)
        } finally { root.deleteRecursively() }
    }

    @Test fun `失败停止启动新窗口并保存已经在途的结果`() = runTest {
        val root = Files.createTempDirectory("kg-failure-windows").toFile()
        try {
            val protocol = mockk<LlmProtocol>()
            val gate = CompletableDeferred<Unit>()
            val started = mutableListOf<Char>()
            var fail = true
            coEvery { protocol.sendPromptSync(any()) } coAnswers {
                val id = firstArg<PromptRequest>().messages[1].content.first()
                started += id
                if (id == 'a' && fail) gate.await()
                if (id == 'b' && fail) throw IllegalStateException("fixture")
                PromptResponse(empty, usage = ProtocolUsage(2, 1))
            }
            val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
                chunkSize = 2000, concurrency = 2, gleaningPasses = 0, checkpointRoot = root,
                dispatcher = StandardTestDispatcher(testScheduler))
            val text = "a".repeat(2000) + "b".repeat(2000) + "c".repeat(2000)
            val pending = async { extractor.extractCandidate(text, "doc", runId = "first") }
            runCurrent()
            assertThat(started).containsExactly('a', 'b')
            gate.complete(Unit)
            assertThat(pending.await().status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
            fail = false
            assertThat(extractor.extractCandidate(text, "doc", runId = "second").status).isEqualTo(KgExtractionStatus.SUCCESS)
            assertThat(started.count { it == 'a' }).isEqualTo(1)
            assertThat(started.count { it == 'c' }).isEqualTo(1)
        } finally { root.deleteRecursively() }
    }

    @Test fun `恢复时首轮和成功追加轮不重复请求`() = runTest {
        val root = Files.createTempDirectory("kg-gleaning-resume").toFile()
        try {
            val protocol = mockk<LlmProtocol>()
            val requested = mutableListOf<Char>()
            var fail = true
            coEvery { protocol.sendPromptSync(any()) } answers {
                val id = firstArg<PromptRequest>().messages[1].content.first()
                requested += id
                if (id == 'b' && fail) throw IllegalStateException("fixture")
                PromptResponse(empty, usage = ProtocolUsage(2, 1))
            }
            val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
                chunkSize = 2000, concurrency = 1, gleaningPasses = 1, checkpointRoot = root,
                dispatcher = StandardTestDispatcher(testScheduler))
            val text = "a".repeat(2000) + "b".repeat(2000)
            assertThat(extractor.extractCandidate(text, "doc", runId = "first").status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
            fail = false
            assertThat(extractor.extractCandidate(text, "doc", runId = "second").status).isEqualTo(KgExtractionStatus.SUCCESS)
            assertThat(requested.count { it == 'a' }).isEqualTo(2)
        } finally { root.deleteRecursively() }
    }

    @Test fun `模型输出能力限制每次请求而未知能力使用设置值`() = runTest {
        val protocol = mockk<LlmProtocol>()
        val limits = mutableListOf<Int?>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            limits += firstArg<PromptRequest>().maxTokens
            PromptResponse(empty, usage = ProtocolUsage(2, 1))
        }
        for (model in listOf(KgModelLimits(2048, null), KgModelLimits(0, null))) {
            GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
                dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 0,
                budget = KgExtractionBudget(requestMaxOutputTokens = 8192), modelLimits = { model })
                .extractCandidate("正文")
        }
        assertThat(limits).containsExactly(2048, 8192).inOrder()
    }

    @Test fun `普通提供商错误不重试`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers { calls++; throw IllegalStateException("[HTTP 400] fixture") }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 0)
        assertThat(extractor.extractCandidate("正文").status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `503 限流按相同规则重试`() = runTest {
        val protocol = mockk<LlmProtocol>()
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            if (calls == 1) throw IllegalStateException("[HTTP 503][TIMEOUT] fixture")
            PromptResponse(empty, usage = ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), mockk(relaxed = true), "owner::model",
            dispatcher = StandardTestDispatcher(testScheduler), gleaningPasses = 0)
        val result = extractor.extractCandidate("正文")
        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(result.progress!!.calls).isEqualTo(2)
    }

    @Test fun `旧格式预算文件被忽略`() {
        val root = Files.createTempDirectory("kg-old-ledger").toFile()
        try {
            val store = GraphExtractionCheckpoints(root)
            val identity = GraphCheckpointIdentity("content", "text", "owner::model", "prompt", 2000, 0)
            val directory = store.directory("doc").apply { mkdirs() }
            val file = java.io.File(directory, "budget_${kgSha256(identity.contentHash)}_${kgSha256("run")}.json")
            file.writeText("""{"contentHash":"content","state":{"calls":4}}""")
            assertThat(store.loadBudget("doc", identity, "run")).isNull()
            assertThat(file.exists()).isFalse()
        } finally { root.deleteRecursively() }
    }
}
