package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class GraphExtractorTest {

    private lateinit var tempDir: File
    private lateinit var mockApp: InfinityApplication
    private val protocol: LlmProtocol = mockk()
    private val graphStore: GraphStore = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("infinity_test_cache").toFile()
        mockApp = mockk(relaxed = true)
        every { mockApp.cacheDir } returns tempDir
        every { mockApp.filesDir } returns File(tempDir, "durable").apply { mkdirs() }

        // 反射设置 InfinityApplication.instance
        val field = InfinityApplication::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, mockApp)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
        
        // 重置 instance
        val field = InfinityApplication::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `extractCandidate split text and extracts correctly without docId`() = runTest {
        val extractor = testGraphExtractor(
            protocol = protocol,
            graphStore = graphStore,
            chunkSize = 10,
            chunkOverlap = 0
        )

        // text 长度 25，按 chunkSize=10 会被切分成 3 个 chunks
        // "1234567890", "abcdefghij", "klmno"
        val text = "1234567890abcdefghijklmno"

        coEvery { protocol.sendPromptSync(any()) } returnsMany listOf(
            PromptResponse(
                content = """{
                    "nodes": [{"name": "NodeA", "type": "concept"}],
                    "edges": [{"source": "NodeA", "target": "NodeB", "relation": "test", "weight": 0.8}]
                }"""
            ),
            PromptResponse(
                content = """{
                    "nodes": [{"name": "NodeB", "type": "concept"}],
                    "edges": []
                }"""
            ),
            PromptResponse(
                content = """{"nodes": [], "edges": []}"""
            )
        )

        val result = extractor.extractCandidate(text)

        assertThat(result.error).isNull()
        assertThat(result.nodes).hasSize(2)
        assertThat(result.nodes.map { it.name }).containsExactly("NodeA", "NodeB")
        
        // 验证没有 docId 时没有创建任何 checkpoint 文件夹
        val checkpointRoot = File(tempDir, "kg_extraction_checkpoint")
        assertThat(checkpointRoot.exists()).isFalse()
    }

    @Test
    fun `extractCandidate saves progressive checkpoints and resumes after interruption`() = runTest {
        val docId = "test-doc-123"
        val extractor = testGraphExtractor(
            protocol = protocol,
            graphStore = graphStore,
            chunkSize = 10,
            chunkOverlap = 0
        )

        // "1234567890", "abcdefghij" (2 chunks)
        val text = "1234567890abcdefghij"

        // 第一次尝试：第 1 个 chunk 成功，第 2 个 chunk 超时/返回 Error
        coEvery { protocol.sendPromptSync(any()) } coAnswers {
            val req = firstArg<PromptRequest>()
            // 我们通过判断提示词里包含的内容来区分 chunk 0 和 chunk 1
            val isChunk0 = req.messages.any { it.content.contains("1234567890") }
            if (isChunk0) {
                PromptResponse(
                    content = """{
                        "nodes": [{"name": "NodeA", "type": "concept"}],
                        "edges": [{"source": "NodeA", "target": "NodeB", "relation": "link", "weight": 0.9}]
                    }"""
                )
            } else {
                throw RuntimeException("Timeout exception")
            }
        }

        val result1 = extractor.extractCandidate(text, docId = docId)

        // 验证第一次返回的是中断错误，且没有 upsert 进数据库（原子写保护）
        assertThat(result1.status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
        coVerify(exactly = 0) { graphStore.clearGraphForDoc(any()) }
        coVerify(exactly = 0) { graphStore.upsertNode(any(), any(), any(), any(), collectionId = any()) }

        // 验证 checkpoint 文件已成功落盘
        val chunk0File = File(tempDir, "kg_extraction_checkpoint").walkTopDown().first { it.name == "chunk_0.json" }
        val chunk1File = File(chunk0File.parentFile, "chunk_1.json")
        assertThat(chunk0File.exists()).isTrue()
        assertThat(chunk1File.exists()).isFalse()

        // 第二次尝试：续传！
        // 清理 mock 的协议调用计数
        clearMocks(protocol)

        // 此时由于 chunk_0.json 已经存在，应该只向 LLM 发送 chunk 1 的请求
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(
            content = """{
                "nodes": [{"name": "NodeB", "type": "concept"}],
                "edges": []
            }"""
        )

        val result2 = extractor.extractCandidate(text, docId = docId)

        // 候选完成后清理断点；实际写库由索引事务负责。
        assertThat(result2.error).isNull()
        assertThat(result2.nodes.map { it.name }).containsExactly("NodeA", "NodeB")

        // 验证只请求了 1 次（只请求了未完成的 chunk 1，chunk 0 则是秒级从 checkpoint 缓存中加载）
        coVerify(exactly = 1) { protocol.sendPromptSync(any()) }

        // 候选构建不修改旧图谱。
        coVerify(exactly = 0) { graphStore.clearGraphForDoc(docId) }
        coVerify(exactly = 0) { graphStore.upsertNode(any(), any(), any(), any(), collectionId = any()) }

        // 候选完成后清理缓存。
        val checkpointDir = File(tempDir, "kg_extraction_checkpoint/${kgSha256(docId)}")
        assertThat(checkpointDir.exists()).isFalse()
    }

    @Test
    fun `无效响应按段落分半抽取并合并图谱`() = runTest {
        val received = mutableListOf<String>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            val text = firstArg<PromptRequest>().messages.last().content
            received += text
            val body = when (received.size) {
                1 -> "{\"nodes\":["
                2 -> """{"nodes":[{"name":"甲"},{"name":"乙"}],"edges":[{"source":"甲","target":"乙","relation":"关联"}]}"""
                3 -> """{"nodes":[{"name":"丙"},{"name":"丁"}],"edges":[{"source":"丙","target":"丁","relation":"关联"}]}"""
                else -> """{"nodes":[{"name":"戊"},{"name":"己"}],"edges":[{"source":"戊","target":"己","relation":"关联"}]}"""
            }
            PromptResponse(body, usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(2, 1))
        }
        val extractor = testGraphExtractor(protocol, graphStore, chunkSize = 20, chunkOverlap = 0)

        val result = extractor.extractCandidate("甲乙内容\n\n丙丁内容", "split-doc")

        assertThat(received).containsExactly("甲乙内容\n\n丙丁内容", "甲乙内容\n\n", "丙丁内容").inOrder()
        assertThat(result.nodes.map { it.name }).containsExactly("甲", "乙", "丙", "丁")
        assertThat(result.edges).hasSize(2)
        assertThat(result.progress!!.calls).isEqualTo(3)
        assertThat(result.usage.outputTokens).isEqualTo(3)
    }

    @Test
    fun `输出达到上限被协议判为非正常结束时也拆半重抽`() = runTest {
        val received = mutableListOf<String>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            val text = firstArg<PromptRequest>().messages.last().content
            received += text
            if (received.size == 1) {
                throw com.promenar.infinity.data.remote.protocol.AbnormalFinishException(
                    com.promenar.infinity.data.remote.protocol.StreamChunk.Error(
                        code = com.promenar.infinity.domain.generation.GenerationFailureCode.OUTPUT_LIMIT,
                        technical = "OpenAI-compatible finished with reason: length",
                    ),
                )
            }
            val (a, b) = if (received.size == 2) "甲" to "乙" else "丙" to "丁"
            PromptResponse("""{"nodes":[{"name":"$a"},{"name":"$b"}],"edges":[{"source":"$a","target":"$b","relation":"关联"}]}""",
                usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(2, 1))
        }
        val extractor = testGraphExtractor(protocol, graphStore, chunkSize = 20, chunkOverlap = 0)

        val result = extractor.extractCandidate("甲乙内容\n\n丙丁内容", "truncated-doc")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(received).containsExactly("甲乙内容\n\n丙丁内容", "甲乙内容\n\n", "丙丁内容").inOrder()
        assertThat(result.nodes.map { it.name }).containsExactly("甲", "乙", "丙", "丁")
        assertThat(result.progress!!.calls).isEqualTo(3)
    }

    @Test
    fun `分半请求消耗实际输出预算`() = runTest {
        val limits = mutableListOf<Int?>()
        coEvery { protocol.sendPromptSync(any()) } answers {
            limits += firstArg<PromptRequest>().maxTokens
            if (limits.size == 1) PromptResponse("{", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 2))
            else PromptResponse("""{"nodes":[],"edges":[]}""", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), graphStore, "owner::model", chunkSize = 20, chunkOverlap = 0,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), concurrency = 1, gleaningPasses = 0,
            budget = KgExtractionBudget(maxOutputTokens = 3, requestMaxOutputTokens = 3))

        val result = extractor.extractCandidate("abcdefgh", "output-split")

        assertThat(limits).containsExactly(3, 1).inOrder()
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.OUTPUT_TOKENS)
        assertThat(result.usage.outputTokens).isEqualTo(3)
    }

    @Test
    fun `分半请求消耗剩余时长预算`() = runTest {
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } coAnswers {
            calls++
            kotlinx.coroutines.delay(80)
            PromptResponse(if (calls == 1) "{" else """{"nodes":[],"edges":[]}""",
                usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), graphStore, "owner::model", chunkSize = 20, chunkOverlap = 0,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), concurrency = 1, gleaningPasses = 0, budget = KgExtractionBudget(maxDurationMs = 200),
            nowMs = { testScheduler.currentTime })

        val result = extractor.extractCandidate("abcdefgh", "time-split")

        assertThat(calls).isEqualTo(3)
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.DURATION)
        assertThat(testScheduler.currentTime).isEqualTo(200)
    }

    @Test
    fun `分半请求消耗同一调用预算`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returnsMany listOf(
            PromptResponse("{", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1)),
            PromptResponse("""{"nodes":[],"edges":[]}""", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1)),
        )
        val extractor = GraphExtractor(testKgClient(protocol), graphStore, "owner::model", chunkSize = 20, chunkOverlap = 0,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), concurrency = 1, gleaningPasses = 0, budget = KgExtractionBudget(maxCalls = 2))

        val result = extractor.extractCandidate("abcdefgh", "budget-split")

        assertThat(result.status).isEqualTo(KgExtractionStatus.PARTIAL)
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.CALLS)
        assertThat(result.progress!!.calls).isEqualTo(2)
        coVerify(exactly = 2) { protocol.sendPromptSync(any()) }
    }

    @Test
    fun `四分之一分块仍无效时返回解析失败`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse("{", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1))
        val result = testGraphExtractor(protocol, graphStore, chunkSize = 20, chunkOverlap = 0)
            .extractCandidate("abcdefgh", "quarter-failure")

        assertThat(result.status).isEqualTo(KgExtractionStatus.PARSE_FAILURE)
        assertThat(result.progress!!.calls).isEqualTo(3)
        coVerify(exactly = 3) { protocol.sendPromptSync(any()) }
    }

    @Test
    fun `恢复分块断点时不重复成功子块或已拆分的父块`() = runTest {
        val requests = mutableListOf<String>()
        var failRight = true
        coEvery { protocol.sendPromptSync(any()) } answers {
            val text = firstArg<PromptRequest>().messages.last().content
            requests += text
            when {
                text == "abcdefgh" -> PromptResponse("{", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1))
                text == "efgh" && failRight -> throw IllegalStateException("fixture")
                else -> PromptResponse("""{"nodes":[],"edges":[]}""", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(1, 1))
            }
        }
        val extractor = testGraphExtractor(protocol, graphStore, chunkSize = 20, chunkOverlap = 0)

        assertThat(extractor.extractCandidate("abcdefgh", "resume-split").status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
        failRight = false
        val result = extractor.extractCandidate("abcdefgh", "resume-split")

        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        assertThat(requests).containsExactly("abcdefgh", "abcd", "efgh", "efgh").inOrder()
        assertThat(result.progress!!.calls).isEqualTo(1)
    }

    @Test
    fun `旧版本断点失效后重新抽取`() = runTest {
        val root = File(tempDir, "legacy-chunks")
        val text = "abcdefgh"
        val identity = GraphCheckpointIdentity(kgSha256(text), kgSha256(text), "owner::model",
            kgSha256(GraphExtractor.DEFAULT_KG_PROMPT), 20, 0, validationVersion = 1)
        GraphExtractionCheckpoints(root).save("legacy-doc", identity, 0, text, ExtractionResult(emptyList(), emptyList()))
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse("""{"nodes":[],"edges":[]}""")
        val result = testGraphExtractor(protocol, graphStore, chunkSize = 20, chunkOverlap = 0, checkpointRoot = root)
            .extractCandidate(text, "legacy-doc")
        assertThat(result.status).isEqualTo(KgExtractionStatus.SUCCESS)
        coVerify(exactly = 1) { protocol.sendPromptSync(any()) }
    }

    @Test
    fun `chunk取消必须透传且不得进入图谱写入`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } throws CancellationException("deleted")
        val extractor = testGraphExtractor(protocol, graphStore)

        val failure = runCatching { extractor.extractCandidate("cancel me", docId = "doc") }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { graphStore.clearGraphForDoc(any()) }
        coVerify(exactly = 0) { graphStore.upsertNode(any(), any(), any(), any(), collectionId = any()) }
    }

    @Test
    fun `候选不调用任何图谱写入API`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(
            content = """{"nodes":[{"name":"Alpha","type":"person"},{"name":"Beta","type":"person"}],"edges":[{"source":"Alpha","target":"Beta","relation":"knows"}]}""",
        )
        val result = testGraphExtractor(protocol, graphStore).extractCandidate("text", "doc")
        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { graphStore.clearGraphForDoc(any()) }
        coVerify(exactly = 0) { graphStore.upsertNode(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { graphStore.createEdge(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `删除可丢弃分块缓存不会返还同文档已发生的调用预算`() = runTest {
        var requests = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            requests++
            PromptResponse("""{"nodes":[],"edges":[]}""", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), graphStore, "owner::model", chunkSize = 10, chunkOverlap = 0,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), concurrency = 1, gleaningPasses = 0, budget = KgExtractionBudget(maxCalls = 1))
        extractor.extractCandidate("a".repeat(20), "cache-eviction-document", runId = "same-run")
        File(tempDir, "kg_extraction_checkpoint").deleteRecursively()

        val result = extractor.extractCandidate("a".repeat(20), "cache-eviction-document", runId = "same-run")

        assertThat(requests).isEqualTo(1)
        assertThat(result.progress!!.reason).isEqualTo(KgExtractionStopReason.CALLS)
        assertThat(result.progress!!.processedChunks).isEqualTo(0)
    }

    @Test
    fun `预算账本损坏时拒绝重新消费未知预算`() = runTest {
        var requests = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            requests++
            PromptResponse("""{"nodes":[],"edges":[]}""", usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(2, 1))
        }
        val extractor = GraphExtractor(testKgClient(protocol), graphStore, "owner::model", chunkSize = 10, chunkOverlap = 0,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), concurrency = 1, gleaningPasses = 0, budget = KgExtractionBudget(maxCalls = 1))
        extractor.extractCandidate("a".repeat(20), "corrupt-budget-document", runId = "same-run")
        File(tempDir, "durable").walkTopDown().first { it.name.startsWith("budget_") }.writeText("{")
        File(tempDir, "kg_extraction_checkpoint").deleteRecursively()

        val failure = runCatching { extractor.extractCandidate("a".repeat(20), "corrupt-budget-document", runId = "same-run") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun `已提交候选的精确清理保留其它文档版本账本`() {
        val store = GraphExtractionCheckpoints(File(tempDir, "chunks"), File(tempDir, "budgets"))
        val old = GraphCheckpointIdentity("old", "text", "owner::model", "prompt", 10, 0)
        val current = old.copy(contentHash = "current")
        store.saveBudget("document", old, GraphExtractionBudgetState(calls = 1))
        store.saveBudget("document", current, GraphExtractionBudgetState(calls = 2))

        store.clear("document", old)

        assertThat(store.loadBudget("document", old)).isNull()
        assertThat(store.loadBudget("document", current)!!.calls).isEqualTo(2)
    }
}
