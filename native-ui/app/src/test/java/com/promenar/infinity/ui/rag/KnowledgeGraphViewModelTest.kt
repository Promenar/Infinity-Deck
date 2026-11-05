package com.promenar.infinity.ui.rag

import com.promenar.infinity.R
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.rag.GraphData
import com.promenar.infinity.data.rag.GraphSubgraph
import com.promenar.infinity.data.rag.GraphSubgraphRequest
import com.promenar.infinity.data.rag.GraphEntitySearchRequest
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.KgCollectionSummary
import com.promenar.infinity.data.rag.KgDocumentOption
import com.promenar.infinity.data.rag.KgEntitySessionRef
import com.promenar.infinity.data.rag.KgEntitySources
import com.promenar.infinity.data.rag.KgSessionTurn
import com.promenar.infinity.ui.rag.canvas.KgEntityKind
import com.promenar.infinity.data.rag.KgNode
import com.promenar.infinity.data.rag.KgEdge
import com.promenar.infinity.domain.repository.IKnowledgeGraphRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeGraphViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo: IKnowledgeGraphRepository = mockk()
    private val graphStore: GraphStore = mockk(relaxed = true)
    private val app: InfinityApplication = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun dataNode(
        id: String = "n1",
        name: String = "Node1",
        type: String = "concept"
    ) = KgNode(id = id, name = name, type = type, createdAt = 100L)

    @Test
    fun `图谱失败文案按同scope stale与跨scope generic精确区分`() {
        assertThat(kgLoadErrorMessageRes(isStale = false))
            .isEqualTo(R.string.kg_load_failed)
        assertThat(kgLoadErrorMessageRes(isStale = true))
            .isEqualTo(R.string.kg_load_failed_stale)
    }

    private fun dataEdge(
        id: String = "e1",
        sourceId: String = "n1",
        targetId: String = "n2",
        relation: String = "contains"
    ) = KgEdge(id = id, sourceId = sourceId, targetId = targetId, relation = relation, createdAt = 100L)

    @Test
    fun `初始图谱只展示有界子图`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(
            (1..300).map { dataNode(id = "node-$it") },
            emptyList(),
            total = 301,
        )

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        assertThat(vm.nodes.value.size).isAtMost(300)
        assertThat(vm.totalNodeCount.value).isEqualTo(301)
    }

    @Test
    fun `初始图谱映射有界读取返回的实体`() = runTest {
        val nodes = listOf(dataNode(id = "n1", name = "Alpha", type = "concept"))
        val edges = listOf(dataEdge(id = "e1", sourceId = "n1", targetId = "n2"))
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(nodes, edges)

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        assertThat(vm.nodes.value).hasSize(1)
        assertThat(vm.nodes.value[0].id).isEqualTo("n1")
        assertThat(vm.nodes.value[0].label).isEqualTo("Alpha")
        assertThat(vm.nodes.value[0].type).isEqualTo("concept")
    }

    @Test
    fun `loadGraph maps node types to correct icons`() = runTest {
        val nodes = listOf(
            dataNode(id = "c", type = "concept"),
            dataNode(id = "d", type = "document"),
            dataNode(id = "p", type = "person"),
            dataNode(id = "o", type = "other")
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(nodes, emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        assertThat(vm.nodes.value).hasSize(4)
    }



    @Test
    fun `isLoading is false after loadGraph completes`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `loadGraph handles exception gracefully`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } throws RuntimeException("db error")

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `文档选项加载异常暴露可重试typed error而非伪装为空列表`() = runTest {
        coEvery { graphStore.getDocumentOptions() } throws
            IllegalStateException("private document option detail")
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        assertThat(vm.documentOptions.value).isEmpty()
        assertThat(vm.documentOptionsError.value?.code)
            .isEqualTo(KgDocumentOptionsErrorCode.LoadFailed)
        assertThat(vm.documentOptionsError.value?.canRetry).isTrue()
        assertThat(vm.documentOptionsError.value?.technical)
            .doesNotContain("private document option detail")
    }

    @Test
    fun `文档选项失败后重试成功恢复列表并清除错误`() = runTest {
        coEvery { graphStore.getDocumentOptions() } throws
            IllegalStateException("first failure") andThen
            listOf(KgDocumentOption(docId = "doc-a", title = "Alpha"))
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.retryDocumentOptions()

        assertThat(vm.documentOptionsError.value).isNull()
        assertThat(vm.documentOptions.value)
            .containsExactly(KgDocumentOption(docId = "doc-a", title = "Alpha"))
    }

    @Test
    fun `文档模式未选择docId时不复用全局图且明确要求选择`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(
            listOf(dataNode(id = "global")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.setViewMode(KgViewMode.DOCUMENT)

        assertThat(vm.selectedDocumentId.value).isNull()
        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.documentSelectionRequired.value).isTrue()
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) }
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(any()) }
    }

    @Test
    fun `全局与文档scope切换每次重读GraphStore且不复用跨scope缓存`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returnsMany listOf(
            subgraph(listOf(dataNode(id = "global-1")), emptyList()),
            subgraph(listOf(dataNode(id = "global-2")), emptyList()),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returnsMany listOf(
            subgraph(listOf(dataNode(id = "doc-a-1")), emptyList()),
            subgraph(listOf(dataNode(id = "doc-a-2")), emptyList()),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } returns subgraph(
            listOf(dataNode(id = "doc-b-node")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.loadGraphByDoc("doc-a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-a-1")
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-a")
        vm.loadGraphByDoc("doc-b")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
        vm.loadGraphByDoc("doc-a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-a-2")
        vm.setViewMode(KgViewMode.GLOBAL)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("global-2")
        assertThat(vm.graphState.value.scopeKey).isEqualTo("collection:shared")

        coVerify(exactly = 2) { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) }
        coVerify(exactly = 2) { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) }
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) }
    }

    @Test
    fun `跨文档scope加载失败不会泄露上一文档图`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returns subgraph(
            listOf(dataNode(id = "last-good")),
            emptyList(),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } throws
            IllegalStateException("private database detail")
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        vm.loadGraphByDoc("doc-a")

        vm.loadGraphByDoc("doc-b")

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-b")
        assertThat(vm.graphState.value.isStale).isFalse()
        assertThat(vm.loadError.value?.code).isEqualTo(KgLoadErrorCode.LoadFailed)
        assertThat(vm.loadError.value?.canRetry).isTrue()
        assertThat(vm.loadError.value?.technical).doesNotContain("private database detail")
    }

    @Test
    fun `同scope显式刷新失败保留last good并标记stale`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returns
            subgraph(listOf(dataNode(id = "last-good")), emptyList()) andThenThrows
            IllegalStateException("refresh failed")
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        vm.loadGraphByDoc("doc-a")

        vm.refreshCurrentScope()

        assertThat(vm.nodes.value.map { it.id }).containsExactly("last-good")
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-a")
        assertThat(vm.graphState.value.isStale).isTrue()
        assertThat(vm.loadError.value?.code).isEqualTo(KgLoadErrorCode.LoadFailed)
        coVerify(exactly = 2) { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) }
    }

    @Test
    fun `切换scope立即清空画布并进入新scope loading`() = runTest {
        val releaseB = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returns
            subgraph(listOf(dataNode(id = "doc-a-node")), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } coAnswers {
            releaseB.await()
            subgraph(listOf(dataNode(id = "doc-b-node")), emptyList())
        }
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        vm.loadGraphByDoc("doc-a")

        vm.loadGraphByDoc("doc-b")

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-b")
        assertThat(vm.graphState.value.isLoading).isTrue()
        releaseB.complete(Unit)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
    }

    @Test
    fun `迟到文档加载不会覆盖更新的文档选择`() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } coAnswers {
            withContext(NonCancellable) { releaseA.await() }
            subgraph(listOf(dataNode(id = "doc-a-node")), emptyList())
        }
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } returns subgraph(
            listOf(dataNode(id = "doc-b-node")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.loadGraphByDoc("doc-a")
        vm.loadGraphByDoc("doc-b")
        releaseA.complete(Unit)

        assertThat(vm.selectedDocumentId.value).isEqualTo("doc-b")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
    }

    @Test
    fun `切回已缓存全局图会取消迟到文档结果`() = runTest {
        val releaseDocument = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(
            listOf(dataNode(id = "global-node")),
            emptyList(),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } coAnswers {
            withContext(NonCancellable) { releaseDocument.await() }
            subgraph(listOf(dataNode(id = "doc-a-node")), emptyList())
        }
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.loadGraphByDoc("doc-a")
        vm.setViewMode(KgViewMode.GLOBAL)
        releaseDocument.complete(Unit)

        assertThat(vm.viewMode.value).isEqualTo(KgViewMode.GLOBAL)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("global-node")
    }

    @Test
    fun `会话模式未选会话时要求选择且不复用全局图`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns
            subgraph(listOf(dataNode(id = "global-node")), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.setViewMode(KgViewMode.SESSION)

        assertThat(vm.sessionSelectionRequired.value).isTrue()
        assertThat(vm.documentSelectionRequired.value).isFalse()
        assertThat(vm.nodes.value).isEmpty()
        coVerify(exactly = 0) { graphStore.getGraphSubgraph(match { it.sessionId != null }) }
    }

    @Test
    fun `选会话后按会话读取子图与轮次并在会话内搜索`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s1")) } returns
            subgraph(listOf(dataNode("a"), dataNode("b")), listOf(dataEdge(sourceId = "a", targetId = "b")), total = 2)
        coEvery { graphStore.getSessionTurns("s1") } returns listOf(
            KgSessionTurn("m1", 1, 10L, listOf("a"), listOf("a")),
            KgSessionTurn("m2", 2, 20L, listOf("a", "b"), listOf("b")),
        )
        coEvery { graphStore.searchGraphEntities(GraphEntitySearchRequest("a", sessionId = "s1")) } returns listOf(dataNode("a"))
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.loadGraphBySession("s1")
        vm.searchEntities("a")
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.viewMode.value).isEqualTo(KgViewMode.SESSION)
        assertThat(vm.graphState.value.scopeKey).isEqualTo("session:s1")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("a", "b")
        assertThat(vm.sessionTurns.value.map { it.turnNumber }).containsExactly(1, 2).inOrder()
        assertThat(vm.searchResults.value.map { it.id }).containsExactly("a")
    }

    @Test
    fun `轮次再次点选取消聚焦且切换范围清空轮次筛选与选中`() = runTest {
        coEvery { graphStore.getGraphSubgraph(any()) } returns subgraph(listOf(dataNode("a")), emptyList())
        coEvery { graphStore.getSessionTurns("s1") } returns listOf(KgSessionTurn("m1", 1, 10L, listOf("a"), listOf("a")))
        coEvery { graphStore.getEntitySources("a") } returns KgEntitySources(emptyList(), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        vm.loadGraphBySession("s1")

        vm.selectTurn("m1")
        assertThat(vm.selectedTurnId.value).isEqualTo("m1")
        vm.selectTurn("m1")
        assertThat(vm.selectedTurnId.value).isNull()

        vm.selectTurn("m1")
        vm.toggleTypeFilter(KgEntityKind.CONCEPT)
        vm.selectEntity("a")
        vm.loadGraph()

        assertThat(vm.selectedTurnId.value).isNull()
        assertThat(vm.sessionTurns.value).isEmpty()
        assertThat(vm.typeFilter.value).isEmpty()
        assertThat(vm.selectedEntity.value).isNull()
    }

    @Test
    fun `选中实体加载来源，失败时标记且再次点同一实体取消选中`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns
            subgraph(listOf(dataNode("a"), dataNode("b")), emptyList())
        val sources = KgEntitySources(
            documents = listOf(KgDocumentOption("doc-1", "架构说明.md")),
            sessionTurns = listOf(KgEntitySessionRef("s1", "选型", "m2", 2)),
        )
        coEvery { graphStore.getEntitySources("a") } returns sources
        coEvery { graphStore.getEntitySources("b") } throws IllegalStateException("db")
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.selectEntity("a")
        assertThat(vm.selectedEntity.value).isEqualTo(KgEntityDetail("a", sources, isLoading = false))

        vm.selectEntity("b")
        assertThat(vm.selectedEntity.value).isEqualTo(KgEntityDetail("b", isLoading = false, failed = true))

        vm.selectEntity("b")
        assertThat(vm.selectedEntity.value).isNull()
    }

    @Test
    fun `画布点实体选中并请求居中，再点同一实体取消且不再移动视角`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(listOf(dataNode("a")), emptyList())
        coEvery { graphStore.getEntitySources("a") } returns KgEntitySources(emptyList(), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        val before = vm.centerRequest.value.key

        vm.tapEntity("a")
        assertThat(vm.selectedEntity.value?.nodeId).isEqualTo("a")
        assertThat(vm.centerRequest.value).isEqualTo(KgCenterRequest("a", before + 1))

        vm.tapEntity("a")
        assertThat(vm.selectedEntity.value).isNull()
        assertThat(vm.centerRequest.value.key).isEqualTo(before + 1)
    }

    @Test
    fun `搜索选中画布外实体先展开再选中并请求居中`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns subgraph(listOf(dataNode("root")), emptyList())
        coEvery { graphStore.getGraphSubgraph(match { it.expandNodeId == "far" }) } returns
            subgraph(listOf(dataNode("root"), dataNode("far")), emptyList())
        coEvery { graphStore.getEntitySources(any()) } returns KgEntitySources(emptyList(), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        val before = vm.centerRequest.value.key

        vm.focusEntity("far")

        assertThat(vm.nodes.value.map { it.id }).contains("far")
        assertThat(vm.selectedEntity.value?.nodeId).isEqualTo("far")
        assertThat(vm.centerRequest.value).isEqualTo(KgCenterRequest("far", before + 1))

        vm.focusEntity("root")
        assertThat(vm.selectedEntity.value?.nodeId).isEqualTo("root")
        assertThat(vm.centerRequest.value).isEqualTo(KgCenterRequest("root", before + 2))
        coVerify(exactly = 0) { graphStore.getGraphSubgraph(match { it.expandNodeId == "root" }) }
    }

    @Test
    fun `展开把当前实体作为保留集合并更新同一子图与总数`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns
            subgraph(listOf(dataNode("root")), emptyList(), total = 500)
        coEvery { graphStore.getGraphSubgraph(match { it.expandNodeId == "root" && it.retainedNodeIds == listOf("root") && it.nodeLimit <= 300 }) } returns
            subgraph(listOf(dataNode("root"), dataNode("neighbor")), listOf(dataEdge(sourceId = "root", targetId = "neighbor")), total = 500)
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }

        vm.expandNode("root")

        assertThat(vm.nodes.value.map { it.id }).containsExactly("root", "neighbor")
        assertThat(vm.edges.value.single().targetId).isEqualTo("neighbor")
        assertThat(vm.totalNodeCount.value).isEqualTo(500)
    }

    @Test
    fun `实体搜索遵循当前文档并在切换范围时清空结果`() = runTest {
        coEvery { graphStore.getGraphSubgraph(any()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.searchGraphEntities(GraphEntitySearchRequest("人工", docId = "doc-a")) } returns
            listOf(dataNode("found", "人工智能"))
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { emptyList() }
        vm.loadGraphByDoc("doc-a")

        vm.searchEntities("人工")
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.searchResults.value.map { it.id }).containsExactly("found")
        assertThat(vm.isSearching.value).isFalse()
        vm.loadGraphByDoc("doc-b")
        assertThat(vm.searchQuery.value).isEmpty()
        assertThat(vm.searchResults.value).isEmpty()
    }

    @Test
    fun `图谱视角默认打开实体最多的作品图谱且可切换到通用知识`() = runTest {
        val collections = listOf(
            KgCollectionSummary("shared", "shared", "通用知识", null, 3, 5, 2),
            KgCollectionSummary("work:a", "work", "长夜行", "资料库/长夜行", 20, 412, 300),
            KgCollectionSummary("work:b", "work", "短篇", "资料库/短篇", 2, 9, 4),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "work:a")) } returns
            subgraph(listOf(dataNode("hero", "主角")), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(collectionId = "shared")) } returns
            subgraph(listOf(dataNode("fact", "常识")), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app) { collections }

        assertThat(vm.collectionOptions.value.map { it.id }).containsExactly("shared", "work:a", "work:b").inOrder()
        assertThat(vm.selectedCollectionId.value).isEqualTo("work:a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("hero")

        vm.loadGraphByCollection("shared")

        assertThat(vm.selectedCollectionId.value).isEqualTo("shared")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("fact")
        coVerify(exactly = 0) { graphStore.getGraphSubgraph(GraphSubgraphRequest()) }
    }

    @Test
    fun `图谱视角搜索只在当前图谱内进行`() = runTest {
        coEvery { graphStore.getGraphSubgraph(any()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.searchGraphEntities(GraphEntitySearchRequest("主", collectionId = "work:a")) } returns
            listOf(dataNode("hero", "主角"))
        val vm = KnowledgeGraphViewModel(repo, graphStore, app) {
            listOf(KgCollectionSummary("work:a", "work", "长夜行", "资料库/长夜行", 20, 412, 300))
        }

        vm.searchEntities("主")
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.searchResults.value.map { it.id }).containsExactly("hero")
    }

    private fun subgraph(nodes: List<KgNode>, edges: List<KgEdge>, total: Int = nodes.size) =
        GraphSubgraph(GraphData(nodes, edges), total)
}
