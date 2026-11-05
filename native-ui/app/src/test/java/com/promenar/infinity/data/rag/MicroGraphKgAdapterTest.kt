package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.ui.chat.manager.KgContextResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicroGraphKgAdapterTest {
    private val extractor: MicroGraphExtractor = mockk { every { modelId } returns "provider::kg" }
    private val adapter = MicroGraphKgAdapter(extractor)

    private fun node(name: String, type: String = "concept", metadata: String? = null) =
        ExtractedNode(name = name, type = type, metadata = metadata)

    private fun edge(source: String, target: String, relation: String, weight: Double = 1.0) =
        ExtractedEdge(source = source, target = target, relation = relation, weight = weight)

    private fun ragRef(id: String) =
        RagReference(id = id, content = "content-$id", source = "doc", score = 0.9f)

    private fun microResult(
        nodes: List<ExtractedNode>,
        edges: List<ExtractedEdge>,
        context: String = "ctx",
        query: String = "q",
        modelCalled: Boolean = true,
    ) = MicroGraphResult(
        nodes = nodes,
        edges = edges,
        context = context,
        sourceChunkIds = listOf("r1"),
        query = query,
        extractedAt = 100L,
        modelCalled = modelCalled,
    )

    @Test
    fun `命中缓存或无可抽取文本时不计临时抽取`() = runTest {
        coEvery { extractor.extract(any(), any(), any()) } returns
            microResult(listOf(node("A"), node("B")), listOf(edge("A", "B", "related_to")), modelCalled = false)

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))

        assertThat(result!!.paths).hasSize(1)
        assertThat(result.jitExtractionCount to result.jitModel).isEqualTo(0 to null)
    }

    @Test
    fun `传递失败状态且不产生上下文或路径`() = runTest {
        coEvery { extractor.extract(any(), any(), any()) } returns MicroGraphResult.failure(KgExtractionStatus.PROVIDER_ERROR, "请求失败", "q")

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))

        assertThat(result!!.status).isEqualTo(KgExtractionStatus.PROVIDER_ERROR)
        assertThat(result.context).isEmpty()
        assertThat(result.paths).isEmpty()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `maps nodes and edges into a single KgPath preserving order`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B"), node("C")),
            edges = listOf(edge("A", "B", "related_to"), edge("B", "C", "part_of")),
            context = "- A --[related_to]--> B"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))

        assertThat(result).isNotNull()
        assertThat(result!!.context).isEqualTo("- A --[related_to]--> B\n- B --[part_of]--> C")
        assertThat(result.jitExtractionCount to result.jitModel).isEqualTo(1 to "provider::kg")
        assertThat(result.paths).hasSize(1)
        val path = result.paths.first()
        assertThat(path.edges).containsExactly(
            KgEdge("A", "B", "related_to"),
            KgEdge("B", "C", "part_of")
        ).inOrder()
        assertThat(path.nodes).hasSize(3)
        assertThat(path.nodes.map { it.id }).containsExactly("A", "B", "C").inOrder()
        assertThat(path.nodes.map { it.label }).containsExactly("A", "B", "C").inOrder()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `empty graph with no edges yields empty paths but preserves context`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A")),
            edges = emptyList(),
            context = ""
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))

        assertThat(result).isNotNull()
        assertThat(result!!.context).isEmpty()
        assertThat(result.paths).isEmpty()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `drops dangling edges referencing unknown nodes without fabricating`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(edge("A", "B", "related_to"), edge("A", "GHOST", "knows")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths).hasSize(1)
        val path = result.paths.first()
        assertThat(path.edges).hasSize(1)
        assertThat(path.edges.first().targetId).isEqualTo("B")
        assertThat(path.nodes.map { it.id }).containsExactly("A", "B")
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `deduplicates completely identical edges including weight`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(
                edge("A", "B", "related_to", weight = 0.9),
                edge("A", "B", "related_to", weight = 0.9),
                edge("A", "B", "related_to", weight = 0.9)
            ),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths.first().edges).hasSize(1)
        assertThat(result.paths.first().edges.first().weight).isEqualTo(0.9)
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `keeps parallel edges that differ only by weight`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(
                edge("A", "B", "related_to", weight = 0.9),
                edge("A", "B", "related_to", weight = 0.5)
            ),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        val edges = result.paths.first().edges
        assertThat(edges).hasSize(2)
        assertThat(edges.map { it.weight }).containsExactly(0.5, 0.9).inOrder()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `keeps distinct edges that share endpoints but differ in relation`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(
                edge("A", "B", "related_to"),
                edge("A", "B", "depends_on")
            ),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths.first().edges).hasSize(2)
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `excludes isolated nodes that participate in no surviving edge`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B"), node("LONER")),
            edges = listOf(edge("A", "B", "related_to")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        val path = result.paths.first()
        assertThat(path.nodes.map { it.id }).containsExactly("A", "B")
        assertThat(path.nodes.map { it.id }).doesNotContain("LONER")
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `all edges dangling yields empty paths`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A")),
            edges = listOf(edge("A", "X", "r"), edge("Y", "A", "r")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths).isEmpty()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `有歧义的同名不同类型关系不猜测端点`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A", "person"), node("A", "concept")),
            edges = listOf(edge("A", "A", "self")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths).isEmpty()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `同名不同类型的即时关系保留各自的端点`() = runTest {
        val mg = microResult(
            nodes = listOf(node("星河", "person"), node("星河", "organization"), node("山城", "location")),
            edges = listOf(ExtractedEdge("星河", "山城", "居住", sourceType = "person"),
                ExtractedEdge("星河", "山城", "设址", sourceType = "organization")),
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg
        val path = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!.paths.single()
        val nodes = path.nodes.associateBy { it.id }
        assertThat(path.nodes.filter { it.label == "星河" }.map { it.id }.distinct()).hasSize(2)
        assertThat(path.edges.associate { it.relation to nodes.getValue(it.sourceId).type })
            .containsExactly("居住", "person", "设址", "organization")
    }

    @Test
    fun `node type defaults preserved in mapping`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A", "person"), node("B", "tool")),
            edges = listOf(edge("A", "B", "uses")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        val types = result.paths.first().nodes.associate { it.id to it.type }
        assertThat(types["A"]).isEqualTo("person")
        assertThat(types["B"]).isEqualTo("tool")
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `preserves node metadata and edge weight in mapping`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A", metadata = "{\"hint\":\"x\"}"), node("B")),
            edges = listOf(edge("A", "B", "related_to", weight = 0.42)),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        val path = result.paths.first()
        val nodeA = path.nodes.first { it.id == "A" }
        assertThat(nodeA.metadata).isEqualTo("{\"hint\":\"x\"}")
        assertThat(path.edges.first().weight).isEqualTo(0.42)
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `canonical sort makes reshuffled input produce identical output`() = runTest {
        val mgA = microResult(
            nodes = listOf(node("A"), node("B"), node("C")),
            edges = listOf(edge("A", "B", "related_to"), edge("B", "C", "part_of"))
        )
        // 相同节点/边但顺序完全打乱
        val mgB = microResult(
            nodes = listOf(node("C"), node("A"), node("B")),
            edges = listOf(edge("B", "C", "part_of"), edge("A", "B", "related_to"))
        )
        coEvery { extractor.extract(any(), any(), any()) } returnsMany listOf(mgA, mgB)

        val first = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!
        val second = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(second.paths).isEqualTo(first.paths)
        coVerify(exactly = 2) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `同名不同类型的路径对输入重排保持稳定`() = runTest {
        val mgA = microResult(
            nodes = listOf(node("A", "person", "{\"x\":1}"), node("A", "concept", "{\"y\":2}")),
            edges = listOf(ExtractedEdge("A", "A", "关联", sourceType = "person", targetType = "concept")),
            context = "ctx"
        )
        val mgB = microResult(
            nodes = listOf(node("A", "concept", "{\"y\":2}"), node("A", "person", "{\"x\":1}")),
            edges = listOf(ExtractedEdge("A", "A", "关联", sourceType = "person", targetType = "concept")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returnsMany listOf(mgA, mgB)

        val first = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!
        val second = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(first.paths.single().nodes.map { it.type }).containsExactly("person", "concept")
        assertThat(second.paths).isEqualTo(first.paths)
        coVerify(exactly = 2) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `canonical metadata tiebreak favors null metadata over non-null`() = runTest {
        // 同一 name、同 type：metadata null 视为最小，规范化选择胜出，且与输入顺序无关。
        val mgA = microResult(
            nodes = listOf(node("A", "concept", "{\"k\":1}"), node("A", "concept", metadata = null)),
            edges = listOf(edge("A", "A", "self")),
            context = "ctx"
        )
        val mgB = microResult(
            nodes = listOf(node("A", "concept", metadata = null), node("A", "concept", "{\"k\":1}")),
            edges = listOf(edge("A", "A", "self")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returnsMany listOf(mgA, mgB)

        val first = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!
        val second = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(first.paths.first().nodes.first().metadata).isNull()
        assertThat(second.paths).isEqualTo(first.paths)
        coVerify(exactly = 2) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `splits weakly connected components into stable separate paths`() = runTest {
        val mg = microResult(
            nodes = listOf(node("C"), node("D"), node("A"), node("B")),
            edges = listOf(
                edge("C", "D", "part_of"),
                edge("A", "B", "related_to")
            ),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths).hasSize(2)
        // 组件按最小节点 id 稳定排序：{A,B} 先于 {C,D}
        val first = result.paths[0]
        val second = result.paths[1]
        assertThat(first.nodes.map { it.id }).containsExactly("A", "B").inOrder()
        assertThat(first.edges.first().relation).isEqualTo("related_to")
        assertThat(second.nodes.map { it.id }).containsExactly("C", "D").inOrder()
        assertThat(second.edges.first().relation).isEqualTo("part_of")
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `keeps self-loop edge and its node as a single path`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(edge("A", "A", "self"), edge("A", "B", "related_to")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        // A-B 连通 + A 自环 → 同一连通分量，自环保留
        assertThat(result.paths).hasSize(1)
        val path = result.paths.first()
        assertThat(path.edges).hasSize(2)
        assertThat(path.edges.map { it.relation }).containsAtLeast("self", "related_to")
        assertThat(path.nodes.map { it.id }).containsExactly("A", "B")
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `isolated self-loop only node still forms a path`() = runTest {
        val mg = microResult(
            nodes = listOf(node("SOLO")),
            edges = listOf(edge("SOLO", "SOLO", "self")),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths).hasSize(1)
        val path = result.paths.first()
        assertThat(path.nodes.map { it.id }).containsExactly("SOLO")
        assertThat(path.edges).hasSize(1)
        assertThat(path.edges.first().sourceId).isEqualTo("SOLO")
        assertThat(path.edges.first().targetId).isEqualTo("SOLO")
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `queryKeywords uses real non-empty result query and reasoning is null`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(edge("A", "B", "related_to")),
            query = "quantum-leap"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        val path = result.paths.first()
        assertThat(path.queryKeywords).containsExactly("quantum-leap")
        assertThat(path.reasoning).isNull()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `blank query yields empty queryKeywords`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A"), node("B")),
            edges = listOf(edge("A", "B", "related_to")),
            query = "   "
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!

        assertThat(result.paths.first().queryKeywords).isEmpty()
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `forwards exact query sessionId and mapped search results with zero createdAt sentinel`() = runTest {
        val resultsSlot = slot<List<SearchResult>>()
        val querySlot = slot<String>()
        val sessionSlot = slot<String>()
        coEvery {
            extractor.extract(
                topKResults = capture(resultsSlot),
                query = capture(querySlot),
                sessionId = capture(sessionSlot)
            )
        } returns MicroGraphResult.failure(KgExtractionStatus.PROVIDER_ERROR, "请求失败", "q")

        adapter.extractContext("my-query", "session-42", listOf(
            RagReference(id = "r1", content = "alpha", source = "doc1", score = 0.8f),
            RagReference(id = "r2", content = "beta", source = "doc2", score = 0.5f)
        ))

        assertThat(querySlot.captured).isEqualTo("my-query")
        assertThat(sessionSlot.captured).isEqualTo("session-42")
        val mapped = resultsSlot.captured
        assertThat(mapped).hasSize(2)
        assertThat(mapped[0].id).isEqualTo("r1")
        assertThat(mapped[0].content).isEqualTo("alpha")
        assertThat(mapped[0].similarity).isEqualTo(0.8f)
        assertThat(mapped[0].createdAt).isEqualTo(0L)
        assertThat(mapped[1].id).isEqualTo("r2")
        assertThat(mapped[1].createdAt).isEqualTo(0L)
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `produced KgPath is serializable and round-trips with metadata and weight`() = runTest {
        val mg = microResult(
            nodes = listOf(node("A", metadata = "m"), node("B")),
            edges = listOf(edge("A", "B", "related_to", weight = 0.7)),
            context = "ctx"
        )
        coEvery { extractor.extract(any(), any(), any()) } returns mg

        val result = adapter.extractContext("q", "s1", listOf(ragRef("r1")))!!
        val json = kotlinx.serialization.json.Json.encodeToString(KgPath.serializer(), result.paths.first())
        val decoded = kotlinx.serialization.json.Json.decodeFromString(KgPath.serializer(), json)

        assertThat(decoded).isEqualTo(result.paths.first())
        assertThat(decoded.nodes.first().metadata).isEqualTo("m")
        assertThat(decoded.edges.first().weight).isEqualTo(0.7)
        coVerify(exactly = 1) { extractor.extract(any(), any(), any()) }
    }

    @Test
    fun `old json without metadata and weight deserializes with defaults`() = runTest {
        val oldNodeJson = """{"id":"A","label":"A","type":"concept"}"""
        val oldEdgeJson = """{"sourceId":"A","targetId":"B","relation":"r"}"""
        val json = kotlinx.serialization.json.Json

        val decodedNode = json.decodeFromString(KgNode.serializer(), oldNodeJson)
        val decodedEdge = json.decodeFromString(KgEdge.serializer(), oldEdgeJson)

        assertThat(decodedNode.metadata).isNull()
        assertThat(decodedEdge.weight).isEqualTo(1.0)
    }

    @Test
    fun `old kgpath json round-trips into new schema`() = runTest {
        val oldKgPathJson = """{"queryKeywords":["k"],"nodes":[{"id":"A","label":"A","type":"concept"}],""" +
            """"edges":[{"sourceId":"A","targetId":"A","relation":"self"}],"reasoning":null}"""
        val json = kotlinx.serialization.json.Json

        val decoded = json.decodeFromString(KgPath.serializer(), oldKgPathJson)
        val reencoded = json.encodeToString(KgPath.serializer(), decoded)

        assertThat(decoded.nodes.first().metadata).isNull()
        assertThat(decoded.edges.first().weight).isEqualTo(1.0)
        assertThat(json.decodeFromString(KgPath.serializer(), reencoded)).isEqualTo(decoded)
    }
}
