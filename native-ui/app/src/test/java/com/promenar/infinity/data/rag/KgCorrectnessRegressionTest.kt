package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.KgJitCacheDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptResponse
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class KgCorrectnessRegressionTest {
    private lateinit var cache: File
    private val protocol = mockk<LlmProtocol>()
    private val store = mockk<GraphStore>(relaxed = true)
    private val cacheDao = mockk<KgJitCacheDao>(relaxed = true)
    private val database = mockk<InfinityDatabase>()
    private val sessionDao = mockk<SessionDao>()

    @BeforeEach
    fun setup() {
        cache = Files.createTempDirectory("kg-correctness").toFile()
        val app = mockk<InfinityApplication>()
        every { app.cacheDir } returns cache
        every { app.filesDir } returns File(cache, "durable").apply { mkdirs() }
        InfinityApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, app)
        }
        coEvery { cacheDao.getByKey(any()) } returns null
        every { store.database } returns database
        every { database.sessionDao() } returns sessionDao
        coEvery { sessionDao.getById(any()) } returns null
    }

    @AfterEach
    fun cleanup() {
        cache.deleteRecursively()
        InfinityApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun `合法空图是成功候选`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(EMPTY)
        val result = testGraphExtractor(protocol, store).extractCandidate("plain text", "doc")
        assertThat(result.error).isNull()
        assertThat(result.nodes).isEmpty()
        assertThat(result.edges).isEmpty()
    }

    @Test
    fun `大小写与空白端点统一为节点显示名`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(GRAPH)
        val result = testGraphExtractor(protocol, store).extractCandidate("text")
        assertThat(result.edges.single().source).isEqualTo("Alpha")
        assertThat(result.edges.single().target).isEqualTo("Beta")
        assertThat(result.edges.single().relation).isEqualTo("knows")
    }

    @Test fun `内容变化不能复用旧断点`() = checkpointChange(text = "xxxxxxxxxxyyyyyyyyyy")
    @Test fun `模型变化不能复用旧断点`() = checkpointChange(model = "other::model")
    @Test fun `提示词变化不能复用旧断点`() = checkpointChange(prompt = "新的提示词")
    @Test fun `切块参数变化不能复用旧断点`() = checkpointChange(chunk = 12, overlap = 2)

    private fun checkpointChange(
        text: String = "aaaaaaaaaabbbbbbbbbb",
        model: String = "owner::model",
        prompt: String = "提示词",
        chunk: Int = 10,
        overlap: Int = 0,
    ) = runTest {
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            if (calls == 2) error("provider unavailable")
            PromptResponse(GRAPH)
        }
        testGraphExtractor(protocol, store, "owner::model", "提示词", 10, 0)
            .extractCandidate("aaaaaaaaaabbbbbbbbbb", "doc")
        calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers { calls++; PromptResponse(EMPTY) }

        testGraphExtractor(protocol, store, model, prompt, chunk, overlap).extractCandidate(text, "doc")

        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `JIT使用显式超时预算`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } coAnswers { delay(100); PromptResponse(GRAPH) }
        val result = testMicroExtractor(protocol, store, cacheDao).extract(refs(), "q", "s", timeout = 5)
        assertThat(testScheduler.currentTime).isEqualTo(5)
        assertThat(result.status).isEqualTo(KgExtractionStatus.TIMEOUT)
        assertThat(result.context).isEmpty()
    }

    @Test
    fun `JIT构造参数决定默认超时`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } coAnswers { delay(100); PromptResponse(GRAPH) }
        val extractor = testMicroExtractor(protocol, store, cacheDao, timeoutMs = 7)
        assertThat(extractor.extract(refs(), "q", "s").status).isEqualTo(KgExtractionStatus.TIMEOUT)
        assertThat(testScheduler.currentTime).isEqualTo(7)
        extractor.close()
    }

    @Test
    fun `模型与提示词降级链对全量与JIT相同`() {
        val config = RagConfiguration(kgExtractionModel = "kg::chosen", kgExtractionPrompt = "指定提示词")
        assertThat(KgExtractionSettings.resolve(config, "summary::m", "main::m"))
            .isEqualTo(KgExtractionSettings("kg::chosen", "指定提示词"))
        assertThat(KgExtractionSettings.resolve(config.copy(kgExtractionModel = " ", kgExtractionPrompt = ""), "summary::m", "main::m"))
            .isEqualTo(KgExtractionSettings("summary::m", GraphExtractor.DEFAULT_KG_PROMPT))
        assertThat(KgExtractionSettings.resolve(RagConfiguration(), "", "main::m").modelId).isEqualTo("main::m")
    }

    @Test
    fun `JIT取消不能吞成无结果`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } throws CancellationException("cancel")
        val failure = runCatching { testMicroExtractor(protocol, store, cacheDao).extract(refs(), "q", "s") }
            .exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `JIT缓存与归并写入不延迟当前请求`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(GRAPH)
        coEvery { store.persistJit(any(), any(), any(), any(), any()) } coAnswers { delay(100); true }
        val result = testMicroExtractor(protocol, store, cacheDao).extract(refs(), "q", "s")
        assertThat(result?.edges).hasSize(1)
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test
    fun `JIT非法边不得进入注入上下文`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse("""{
            "nodes":[{"name":"Alpha","type":"person"},{"name":"Beta","type":"person"}],
            "edges":[{"source":"Alpha","target":"GHOST","relation":"knows"},
              {"source":"Alpha","target":"Beta","relation":" "},
              {"source":"Alpha","target":"Beta","relation":"bad","weight":2.0}]
        }""")
        val result = testMicroExtractor(protocol, store, cacheDao).extract(refs(), "q", "s")
        assertThat(result?.context.orEmpty()).isEmpty()
        assertThat(result?.edges.orEmpty()).isEmpty()
    }

    @Test
    fun `解析失败与提供商错误是不同结果`() = runTest {
        val extractor = testMicroExtractor(protocol, store, cacheDao)
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse("not JSON")
        assertThat(extractor.extract(refs(), "q1", "s").status).isEqualTo(KgExtractionStatus.PARSE_FAILURE)
        coEvery { protocol.sendPromptSync(any()) } throws IllegalStateException("unavailable")
        assertThat(extractor.extract(refs(), "q2", "s").status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
        extractor.close()
    }

    @Test
    fun `协议归一化包装的超时保留超时类型`() = runTest {
        for (message in listOf("[TIMEOUT] request timed out", "[HTTP 408][TIMEOUT] upstream timed out")) {
            coEvery { protocol.sendPromptSync(any()) } throws Exception(message)
            val result = testGraphExtractor(protocol, store).extractCandidate("text")
            assertThat(result.status).isEqualTo(KgExtractionStatus.TIMEOUT)
        }
    }

    @Test
    fun `全量超时保留超时类型且不返回部分图`() = runTest {
        coEvery { protocol.sendPromptSync(any()) } coAnswers { delay(100); PromptResponse(GRAPH) }
        val result = testGraphExtractor(protocol, store, timeoutMs = 5).extractCandidate("text")
        assertThat(result.status).isEqualTo(KgExtractionStatus.TIMEOUT)
        assertThat(result.nodes).isEmpty()
    }

    @Test
    fun `图校验移除空字段悬挂端点和所有非法权重`() {
        val graph = ValidatedGraph.from(
            listOf(ExtractedNode(" Alpha ", "person"), ExtractedNode("alpha", "person"), ExtractedNode("Beta", "person"), ExtractedNode(" ", "person")),
            listOf(
                ExtractedEdge("ALPHA", " beta ", " knows ", 0.8),
                ExtractedEdge("Alpha", "missing", "knows", 1.0),
                ExtractedEdge("Alpha", "Beta", " ", 1.0),
            ) + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1).map {
                ExtractedEdge("Alpha", "Beta", "bad", it)
            },
        )
        assertThat(graph.nodes.map { it.name }).containsExactly("Alpha", "Beta")
        assertThat(graph.edges).containsExactly(ExtractedEdge("Alpha", "Beta", "knows", 0.8))
        assertThat(graph.context).isEqualTo("- Alpha --[knows]--> Beta")
    }

    @Test
    fun `图校验用名称和类型解析端点并丢弃有歧义的未标注关系`() {
        val graph = ValidatedGraph.from(
            listOf(ExtractedNode("星河", "person"), ExtractedNode("星河", "organization"),
                ExtractedNode("山城", "location")),
            listOf(
                ExtractedEdge("星河", "山城", "居住", sourceType = "person"),
                ExtractedEdge("星河", "山城", "设址", sourceType = "organization"),
                ExtractedEdge("星河", "山城", "未知"),
            ),
        )
        assertThat(graph.nodes.map { it.name to it.type }).containsExactly(
            "星河" to "person", "星河" to "organization", "山城" to "location")
        assertThat(graph.edges.map { it.relation }).containsExactly("居住", "设址")
        assertThat(graph.resolveEndpoint("星河", null)).isNull()
        assertThat(graph.resolveEndpoint("星河", "person")?.type).isEqualTo("person")
    }

    @Test
    fun `解析抽取响应时保留端点类型并兼容缺省字段`() {
        val parsed = KgGraphValidation.parse("""{"nodes":[],"edges":[
            {"source":"甲","sourceType":"person","target":"乙","targetType":"location","relation":"位于"},
            {"source":"乙","target":"甲","relation":"有关"}] }""")
        assertThat(parsed.edges[0].sourceType).isEqualTo("person")
        assertThat(parsed.edges[0].targetType).isEqualTo("location")
        assertThat(parsed.edges[1].sourceType).isNull()
        assertThat(parsed.edges[1].targetType).isNull()
    }

    @Test
    fun `超长名称不能截断合并到另一个合法实体`() {
        val validName = "A".repeat(512)
        val graph = ValidatedGraph.from(
            listOf(ExtractedNode(validName, "person"), ExtractedNode(validName + "B", "person"), ExtractedNode("Beta", "person")),
            listOf(ExtractedEdge(validName, "Beta", "valid"), ExtractedEdge(validName + "B", "Beta", "wrong")),
        )
        assertThat(graph.edges.map { it.relation }).containsExactly("valid")
    }

    @Test
    fun `图总量有界且裁剪不会产生悬挂端点`() {
        val nodes = (0..1200).map { ExtractedNode("node-$it", "concept") }
        val edges = (0..5000).map { ExtractedEdge("node-${it % 100}", "node-${(it + 1) % 100}", "relation-$it", 0.7) }
        val graph = ValidatedGraph.from(nodes, edges)
        assertThat(graph.nodes.size).isAtMost(256)
        assertThat(graph.edges).hasSize(512)
        val names = graph.nodes.map { it.name }.toSet()
        assertThat(graph.edges.all { it.source in names && it.target in names }).isTrue()
    }

    @Test
    fun `JIT后台缓存写入可随抽取器关闭而取消`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(GRAPH)
        coEvery { store.persistJit(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val extractor = testMicroExtractor(protocol, store, cacheDao)
        assertThat(extractor.extract(refs(), "q", "s").isSuccess).isTrue()
        runCurrent()
        entered.await()
        extractor.close()
        runCurrent()
        assertThat(cancelled.isCompleted).isTrue()
        assertThat(extractor.extract(refs(), "q", "s").status).isEqualTo(KgExtractionStatus.CANCELLED)
        coVerify(exactly = 0) { store.upsertNode(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `源文件hash变化即使提取文本相同也废弃断点`() = runTest {
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            if (calls == 2) error("provider unavailable")
            PromptResponse(GRAPH)
        }
        val extractor = testGraphExtractor(protocol, store, chunkSize = 10, chunkOverlap = 0)
        extractor.extractCandidate("aaaaaaaaaabbbbbbbbbb", "doc", "file-v1")
        calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers { calls++; PromptResponse(EMPTY) }
        extractor.extractCandidate("aaaaaaaaaabbbbbbbbbb", "doc", "file-v2")
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `删除文档断点后重试不能读取缓存`() = runTest {
        var calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            calls++
            if (calls == 2) error("provider unavailable")
            PromptResponse(GRAPH)
        }
        val extractor = testGraphExtractor(protocol, store, chunkSize = 10, chunkOverlap = 0)
        extractor.extractCandidate("aaaaaaaaaabbbbbbbbbb", "doc")
        extractor.clearCheckpoints("doc")
        calls = 0
        coEvery { protocol.sendPromptSync(any()) } answers { calls++; PromptResponse(EMPTY) }
        extractor.extractCandidate("aaaaaaaaaabbbbbbbbbb", "doc")
        assertThat(calls).isEqualTo(2)
    }

    private fun refs() = listOf(SearchResult(id = "chunk", content = "Alpha Beta", similarity = 1f, createdAt = 0))

    private companion object {
        const val EMPTY = """{"nodes":[],"edges":[]}"""
        const val GRAPH = """{"nodes":[{"name":"Alpha","type":"person"},{"name":"Beta","type":"person"}],"edges":[{"source":" alpha ","target":"BETA","relation":" knows ","weight":0.8}]}"""
    }
}
