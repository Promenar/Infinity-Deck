package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.GraphBinding
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.ui.chat.manager.KgContextResult
import com.promenar.infinity.ui.chat.manager.KgProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PersistentGraphRetrievalTest {
    private lateinit var db: InfinityDatabase
    private lateinit var store: GraphStore
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        store = GraphStore(db)
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('session:s','session','s',1)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('session:other','session','other',1)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('work:fixture','work','fixture',1)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('shared','shared','通用知识',1)")
    }
    @After fun close() = db.close()

    @Test fun `JIT仅消费当前会话可核验的记忆片段`() = runBlocking<Unit> {
        for (session in listOf("s", "other")) db.sessionDao().insert(com.promenar.infinity.data.local.db.entity.SessionEntity(
            session, "agent", createdAt = 1, updatedAt = 1))
        for ((id, session) in listOf("own" to "s", "hidden" to "other")) db.vectorDao().insert(
            com.promenar.infinity.data.local.db.entity.VectorEntity(id, sessionId = session,
                content = "片段-$id", embedding = byteArrayOf(), createdAt = 1))
        var consumed = emptyList<String>()
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                consumed = topKResults.map { it.id }
                return KgContextResult("")
            }
        }
        PersistentGraphKgProvider(store, jit) { RagConfiguration() }.retrieveContext("无覆盖", "s",
            listOf(RagReference("own", "片段-own"), RagReference("hidden", "片段-hidden"), RagReference("missing", "无来源")),
            RagOptions(enableKnowledgeGraph = true))
        assertThat(consumed).containsExactly("own")
    }

    @Test fun `大选择集不截断且来源ID不成为SQL语法`() = runBlocking<Unit> {
        val special = "文档,'); DROP TABLE kg_nodes; --"
        val docs = (0..1200).map { "doc-$it" } + special
        val scope = KgScope(docId = special)
        val a = store.upsertNode("边界实体", scope = scope, collectionId = "session:s")
        val b = store.upsertNode("目标实体", scope = scope, collectionId = "session:s")
        store.createEdge(a, b, "边界关系", scope = scope, collectionId = "session:s")
        val retrieval = GraphRetrievalStore(db)
        val selected = KgRetrievalScope("s", docs)
        assertThat(retrieval.visibleDocumentIds(selected)).containsAtLeast("doc-400", "doc-1200", special)
        val paths = retrieval.retrieve("边界实体", selected).paths
        assertThat(paths).hasSize(1)
        assertThat(paths.single().sourceDocumentIds).containsExactly(special)
        assertThat(db.kgNodeDao().getCount()).isEqualTo(2)
    }

    @Test fun `第129个文件夹子文档可见`() = runBlocking<Unit> {
        val folders = (0..128).map { "folder-$it" }
        db.fileEntryDao().insert(com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = folders.last(), workspaceRootUuid = "root", parentUuid = "root", name = "folder", hash = "h",
            physicalRootPath = "/tmp/kg-scope-fixture", materializedPath = "/folder", isDirectory = true, createdAt = 1, updatedAt = 1))
        db.fileEntryDao().insert(com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = "last-folder-doc", workspaceRootUuid = "root", parentUuid = folders.last(), name = "doc.md", hash = "h",
            physicalRootPath = "/tmp/kg-scope-fixture", materializedPath = "/folder/doc.md", createdAt = 1, updatedAt = 1))
        assertThat(GraphRetrievalStore(db).visibleDocumentIds(KgRetrievalScope("s", folderIds = folders)))
            .contains("last-folder-doc")
    }

    @Test fun `即时抽取文档范围排除回收站和目录并覆盖历史向量文档`() = runBlocking<Unit> {
        val root = com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = "root", workspaceRootUuid = "root", parentUuid = null, name = "root", hash = "h",
            physicalRootPath = "/tmp/kg-scope-fixture", materializedPath = "/", isDirectory = true, createdAt = 1, updatedAt = 1,
        )
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(root.copy(uuid = "alive", parentUuid = "root", name = "alive", materializedPath = "/alive", isDirectory = false))
        db.fileEntryDao().insert(root.copy(uuid = "recycled", parentUuid = "root", name = "recycled", materializedPath = "/recycled", isDirectory = false, inRecycleBin = true))
        db.vectorDao().insert(com.promenar.infinity.data.local.db.entity.VectorEntity(
            id = "legacy-chunk", docId = "legacy-document", content = "历史片段", embedding = byteArrayOf(), createdAt = 1,
        ))
        val retrieval = GraphRetrievalStore(db)
        val selected = retrieval.visibleDocumentIds(KgRetrievalScope("s", documentIds = listOf("alive", "recycled", "root", "untracked")))
        assertThat(selected).containsAtLeast("alive", "untracked")
        assertThat(selected).containsNoneOf("recycled", "root")
        val all = retrieval.visibleDocumentIds(KgRetrievalScope("s", documents = DocumentReach.All))
        assertThat(all).contains("legacy-document")
        assertThat(all).containsNoneOf("recycled", "root")
    }

    @Test fun `绑定图谱内联合JIT不受文档选择范围限制`() = runBlocking<Unit> {
        val docs = (0..400).map { "joint-$it" }
        val first = KgScope(sessionId = "s", docId = docs.first(), taskId = "joint-task")
        val last = first.copy(docId = docs.last())
        val a = store.upsertNode("联合甲", scope = first, sourceType = "jit", collectionId = "session:s")
        val b = store.upsertNode("联合乙", scope = first, sourceType = "jit", collectionId = "session:s")
        store.createEdge(a, b, "联合关系", scope = first, sourceType = "jit", collectionId = "session:s")
        store.createEdge(a, b, "联合关系", scope = last, sourceType = "jit", collectionId = "session:s")
        val retrieval = GraphRetrievalStore(db)
        assertThat(retrieval.retrieve("联合甲", KgRetrievalScope("s", docs)).paths).hasSize(1)
        assertThat(retrieval.retrieve("联合甲", KgRetrievalScope("s", docs.dropLast(1))).paths).hasSize(1)
    }

    @Test fun `索引种子匹配中英文大小写包含与分词`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        store.upsertNode("北京大学", scope = scope, collectionId = "session:s")
        store.upsertNode("Ａlice", "person", scope = scope, collectionId = "session:s")
        store.upsertNode("Knowledge Graph", scope = scope, collectionId = "session:s")
        val retrieval = GraphRetrievalStore(db)
        for (query in listOf("北京", "请介绍北京大学", "ALICE", "graph 技术")) {
            assertThat(retrieval.retrieve(query, KgRetrievalScope("s")).seedCount).isAtLeast(1)
        }
        val plan = retrieval.explainSeedQuery("graph", KgRetrievalScope("s"))
        assertThat(plan.none { it.contains("SCAN n") }).isTrue()
        assertThat(plan.any { it.contains("INDEX") }).isTrue()
    }

    @Test fun `一二跳扩展上限和会话证据隔离`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        val a = store.upsertNode("甲", scope = scope, collectionId = "session:s")
        val b = store.upsertNode("乙", scope = scope, collectionId = "session:s")
        val c = store.upsertNode("丙", scope = scope, collectionId = "session:s")
        val otherOrigin = store.upsertNode("甲", scope = KgScope(sessionId = "other"), collectionId = "session:other")
        val hidden = store.upsertNode("私有", scope = KgScope(sessionId = "other"), collectionId = "session:other")
        store.createEdge(a, b, "一跳", scope = scope, collectionId = "session:s")
        store.createEdge(b, c, "二跳", scope = scope, collectionId = "session:s")
        store.createEdge(otherOrigin, hidden, "秘密关系", scope = KgScope(sessionId = "other"), collectionId = "session:other")
        assertThat(runCatching { store.createEdge(a, hidden, "越界", collectionId = "session:s") }.isFailure).isTrue()
        val retrieval = GraphRetrievalStore(db)
        assertThat(retrieval.retrieve("甲", KgRetrievalScope("s"), config = RagConfiguration(kgRetrievalHops = 1)).paths.map { it.edges.single().relation })
            .containsExactly("一跳")
        val two = retrieval.retrieve("甲", KgRetrievalScope("s"), config = RagConfiguration(kgRetrievalHops = 2))
        assertThat(two.paths.map { it.edges.single().relation }).containsExactly("一跳", "二跳")
        assertThat(two.paths.map { it.distance }).containsExactly(1, 2)
        val bounded = retrieval.retrieve("甲", KgRetrievalScope("s"), config = RagConfiguration(kgRetrievalHops = 2, kgMaxNodes = 2, kgMaxEdges = 1, kgMaxSeeds = 1))
        assertThat(bounded.paths).hasSize(1)
        assertThat(bounded.seedCount).isEqualTo(1)
        assertThat(retrieval.retrieve("私有", KgRetrievalScope("s")).seedCount).isEqualTo(0)
    }

    @Test fun `绑定作品图谱时文档和会话的存活证据共同计算置信度`() = runBlocking<Unit> {
        val a = store.upsertNode("甲", scope = KgScope(docId = "visible"), collectionId = "work:fixture")
        val b = store.upsertNode("乙", scope = KgScope(docId = "visible"), collectionId = "work:fixture")
        store.createEdge(a, b, "关系", docId = "visible", weight = 0.2, collectionId = "work:fixture")
        store.createEdge(a, b, "关系", scope = KgScope(sessionId = "other"), weight = 0.99, collectionId = "work:fixture")
        val found = GraphRetrievalStore(db).retrieve("无词面命中", KgRetrievalScope("s", listOf("visible"), collectionId = "work:fixture"), listOf("visible"))
        assertThat(found.seedCount).isEqualTo(2)
        assertThat(found.paths.single().edges.single().weight).isEqualTo(0.99)
        assertThat(found.paths.single().sourceDocumentIds).containsExactly("visible")
        Unit
    }

    @Test fun `持久图充足不调用JIT不足才调用且可关闭`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        val a = store.upsertNode("甲", scope = scope, collectionId = "session:s")
        val b = store.upsertNode("乙", scope = scope, collectionId = "session:s")
        store.createEdge(a, b, "关系", scope = scope, collectionId = "session:s")
        var calls = 0
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                calls++
                return KgContextResult("", jitExtractionCount = 1, jitModel = "p::kg")
            }
        }
        val options = RagOptions(activeDocIds = listOf("doc"), enableKnowledgeGraph = true)
        val refs = listOf(RagReference("r", "片段", documentId = "doc"))
        val provider = PersistentGraphKgProvider(store, jit) { RagConfiguration(kgJitMinSeeds = 1) }
        val persistentOnly = provider.retrieveContext("甲", "s", refs, options)
        assertThat(calls).isEqualTo(0)
        assertThat(persistentOnly.jitExtractionCount).isEqualTo(0)
        // 临时抽取的次数与模型交给检索轨迹展示。
        val withJit = provider.retrieveContext("不存在", "s", refs, options)
        assertThat(calls).isEqualTo(1)
        assertThat(withJit.jitExtractionCount to withJit.jitModel).isEqualTo(1 to "p::kg")
        val disabled = PersistentGraphKgProvider(store, jit) { RagConfiguration(kgJitFallbackEnabled = false) }
        disabled.retrieveContext("不存在", "s", refs, options)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `工具检索返回持久图但种子不足时也不调用JIT`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        val a = store.upsertNode("甲", scope = scope, collectionId = "session:s")
        val b = store.upsertNode("乙", scope = scope, collectionId = "session:s")
        store.createEdge(a, b, "关系", scope = scope, collectionId = "session:s")
        var calls = 0
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                calls++
                return KgContextResult("", jitExtractionCount = 1)
            }
        }
        val provider = PersistentGraphKgProvider(store, jit) { RagConfiguration(kgJitMinSeeds = 32) }
        val options = RagOptions(activeDocIds = listOf("doc"), enableKnowledgeGraph = true)
        val references = listOf(RagReference("chunk", "片段", documentId = "doc"))

        val persistent = provider.retrieveForTool("甲", "s", references, options)
        val empty = provider.retrieveForTool("无覆盖", "s", references, options)

        assertThat(persistent.paths).hasSize(1)
        assertThat(persistent.paths.single().edges.single().relation).isEqualTo("关系")
        assertThat(empty.paths).isEmpty()
        assertThat(persistent.jitExtractionCount).isEqualTo(0)
        assertThat(empty.jitExtractionCount).isEqualTo(0)
        assertThat(calls).isEqualTo(0)

        provider.retrieveContext("无覆盖", "s", references, options)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `工具与自动图谱均只按绑定图谱过滤存活证据`() = runBlocking<Unit> {
        db.sessionDao().insert(com.promenar.infinity.data.local.db.entity.SessionEntity(
            "s", "agent", createdAt = 1, updatedAt = 1))
        KgCollectionRepository(db).setBinding("s", GraphBinding("shared"))
        val conversation = KgScope(sessionId = "s")
        val document = KgScope(docId = "doc")
        val historySource = store.upsertNode("历史来源", scope = conversation, collectionId = "shared")
        val historyTarget = store.upsertNode("历史目标", scope = conversation, collectionId = "shared")
        store.createEdge(historySource, historyTarget, "历史关系", scope = conversation, collectionId = "shared")
        val documentSource = store.upsertNode("文档来源", scope = document, collectionId = "shared")
        val documentTarget = store.upsertNode("文档目标", scope = document, collectionId = "shared")
        store.createEdge(documentSource, documentTarget, "文档关系", scope = document, collectionId = "shared")
        val provider = PersistentGraphKgProvider(store, object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                error("不应调用即时抽取")
            }
        }) { RagConfiguration(kgJitFallbackEnabled = false) }
        val options = RagOptions(activeDocIds = listOf("doc"), enableKnowledgeGraph = true)

        fun KgContextResult.relations() = paths.flatMap { it.edges }.map { it.relation }
        val documents = provider.retrieveForTool("来源", "s", emptyList(), options.copy(enableMemory = false))
        val history = provider.retrieveForTool("来源", "s", emptyList(), options.copy(enableDocs = false))
        val neither = provider.retrieveForTool("来源", "s", emptyList(), options.copy(enableDocs = false, enableMemory = false))
        val automatic = provider.retrieveContext("来源", "s", emptyList(), options.copy(enableDocs = false, enableMemory = false))

        assertThat(documents.relations()).containsExactly("文档关系", "历史关系")
        assertThat(history.relations()).containsExactly("文档关系", "历史关系")
        assertThat(neither.relations()).containsExactly("文档关系", "历史关系")
        assertThat(automatic.relations()).containsExactly("文档关系", "历史关系")
    }
}
