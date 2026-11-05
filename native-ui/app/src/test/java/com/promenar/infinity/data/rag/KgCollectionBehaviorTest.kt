package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.KgJitCacheEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.GraphBinding
import com.promenar.infinity.data.model.GraphBindingMode
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.library.ask.collectionIdsForAskScope
import com.promenar.infinity.data.library.ask.graphPathsForAskScope
import com.promenar.infinity.ui.chat.manager.KgContextResult
import com.promenar.infinity.ui.chat.manager.KgProvider
import io.mockk.mockk
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgCollectionBehaviorTest {
    private lateinit var db: InfinityDatabase
    private lateinit var store: GraphStore
    private lateinit var collections: KgCollectionRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        store = GraphStore(db)
        collections = KgCollectionRepository(db)
    }

    @After fun close() = db.close()

    @Test fun `外层事务内图谱绑定与文档归属不会等待自身连接`() = runBlocking {
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        folder("p", null); file("one", "p")
        collections.setFolderSetting("p", KgFolderSetting.WORK)
        withTimeout(3_000) {
            db.withTransaction {
                assertThat(collections.readBinding("s").collectionId).isEqualTo("session:s")
                collections.ensureSessionCollectionIfNeeded("s", "session:s")
                assertThat(collections.collectionExists("session:s")).isTrue()
                assertThat(collections.resolveDocumentForWrite("one")).isEqualTo("work:p")
                assertThat(collections.collectionExists("work:p")).isTrue()
                assertThat(collections.readBinding("s", RagOptions(graphBinding = GraphBinding("work:p"))).collectionId).isEqualTo("work:p")
            }
        }
    }

    @Test fun `读取绑定只查询不会补建共享图谱或校正文档`() = runBlocking {
        folder("p", null); folder("q", null); file("one", "p"); write("one")
        val file = requireNotNull(db.fileEntryDao().findActiveByUuid("one"))
        db.fileEntryDao().update(file.copy(parentUuid = "q"))
        val shared = collections.readBinding("s", RagOptions(graphBinding = GraphBinding("shared")))
        assertThat(shared.collectionId).isEqualTo("session:s")
        assertThat(collections.collectionExists("shared")).isFalse()
        assertThat(collections.readBinding("s", RagOptions(graphBinding = GraphBinding("work:p"))).collectionId).isEqualTo("work:p")
        assertThat(entityCollections()).containsExactly("work:p:2")
    }

    @Test fun `空库可绑定固定通用图谱且全量清空后仍可绑定`() = runBlocking {
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        assertThat(collections.listBindableCollections("s").map { it.id }).contains("shared")
        collections.setBinding("s", GraphBinding("shared", GraphBindingMode.READ_ONLY))
        assertThat(collections.readBinding("s").collectionId).isEqualTo("shared")
        db.kgEdgeDao().clearGraph()
        collections.reconcileDocuments()
        assertThat(collections.readBinding("s").collectionId).isEqualTo("shared")
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_collections WHERE id='shared' AND kind='shared'").use {
            check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(1)
        }
    }

    @Test fun `直接父文件夹限定实体身份与关系扩展`() = runBlocking {
        folder("p", null); folder("q", null)
        file("one", "p"); file("two", "p"); file("three", "q")
        write("one"); write("two"); write("three")
        assertThat(entityCollections()).containsExactly("work:p:2", "work:q:2")
        val first = GraphRetrievalStore(db).retrieve("共同人物", KgRetrievalScope("s", collectionId = "work:p"))
        val second = GraphRetrievalStore(db).retrieve("共同人物", KgRetrievalScope("s", collectionId = "work:q"))
        assertThat(first.paths).hasSize(1)
        assertThat(second.paths).hasSize(1)
        assertThat(first.paths.single().sourceDocumentIds).containsExactly("one", "two")
        assertThat(second.paths.single().sourceDocumentIds).containsExactly("three")
        assertThat(first.paths.single().nodes.map { it.id }.toSet()).containsNoneIn(second.paths.single().nodes.map { it.id })
    }

    @Test fun `问资料按范围选择只有图谱没有向量的文档并逐图检索`() = runBlocking {
        folder("p", null); folder("q", null)
        file("one", "p"); file("two", "q")
        write("one"); write("two")
        val retrieval = GraphRetrievalStore(db)
        val reach = DocumentReach.Scoped(setOf("root"), emptySet())
        val ids = collectionIdsForAskScope(retrieval, collections, reach)
        assertThat(ids).containsExactly("work:p", "work:q")
        val paths = graphPathsForAskScope(retrieval, collections, reach, "共同人物", emptyList())
        assertThat(paths).hasSize(2)
        assertThat(paths.map { it.sourceDocumentIds.single() }).containsExactly("one", "two")
        assertThat(paths[0].nodes.map { it.id }.toSet()).containsNoneIn(paths[1].nodes.map { it.id })
        val otherRoot = DocumentReach.Scoped(setOf("other-root"), emptySet())
        assertThat(collectionIdsForAskScope(retrieval, collections, otherRoot)).isEmpty()
    }

    @Test fun `文件夹设置重建和文档移动后重新抽取前的归属校正`() = runBlocking {
        folder("p", null); folder("child", "p"); folder("q", null)
        file("one", "p"); file("two", "child")
        write("one"); write("two")
        collections.setFolderSetting("child", KgFolderSetting.MERGE_PARENT)
        assertThat(entityCollections()).containsExactly("work:p:2")
        collections.setFolderSetting("child", KgFolderSetting.AUTO)
        assertThat(entityCollections()).containsExactly("work:p:2", "work:child:2")
        val entry = requireNotNull(db.fileEntryDao().findActiveByUuid("two"))
        db.fileEntryDao().update(entry.copy(parentUuid = "q"))
        collections.reconcileDocuments()
        assertThat(entityCollections()).containsExactly("work:p:2", "work:q:2")
    }

    @Test fun `文档条目变为目录后回收无效来源与成员`() = runBlocking {
        folder("p", null); file("one", "p"); write("one")
        val entry = requireNotNull(db.fileEntryDao().findActiveByUuid("one"))
        db.fileEntryDao().update(entry.copy(isDirectory = true))

        collections.reconcileDocuments()

        assertThat(db.kgEdgeDao().getCount()).isEqualTo(0)
        assertThat(db.kgNodeDao().getCount()).isEqualTo(0)
        assertThat(collections.collectionIdsForDocuments(listOf("one"))).isEmpty()
    }

    @Test fun `两个通用知识文件夹共享实体`() = runBlocking {
        folder("p", null); folder("q", null)
        collections.setFolderSetting("p", KgFolderSetting.SHARED)
        collections.setFolderSetting("q", KgFolderSetting.SHARED)
        file("one", "p"); file("two", "q")
        write("one"); write("two")
        assertThat(entityCollections()).containsExactly("shared:2")
    }

    @Test fun `永久删除文件夹后回收设置，回收站中的设置保留`() = runBlocking {
        folder("p", null); folder("q", null)
        collections.setFolderSetting("p", KgFolderSetting.SHARED)
        collections.setFolderSetting("q", KgFolderSetting.MERGE_PARENT)
        val recycled = requireNotNull(db.fileEntryDao().findActiveByUuid("q"))
        db.fileEntryDao().update(recycled.copy(inRecycleBin = true))
        db.openHelper.writableDatabase.execSQL("DELETE FROM workspace_files WHERE uuid='p'")

        collections.reconcileDocuments()

        assertThat(collections.readFolderSetting("p")).isEqualTo(KgFolderSetting.AUTO)
        assertThat(collections.readFolderSetting("q")).isEqualTo(KgFolderSetting.MERGE_PARENT)
    }

    @Test fun `实体类型与图谱共同限定身份且禁止跨图谱关系`() = runBlocking {
        folder("p", null); folder("q", null)
        file("one", "p"); file("two", "q"); write("one"); write("two")
        val first = store.upsertNode("共同人物", "person", collectionId = "work:p")
        val otherType = store.upsertNode("共同人物", "place", collectionId = "work:p")
        val otherGraph = store.upsertNode("共同人物", "person", collectionId = "work:q")
        assertThat(setOf(first, otherType, otherGraph)).hasSize(3)
        assertThat(runCatching { store.createEdge(first, otherGraph, "越界", collectionId = "work:p") }.isFailure).isTrue()
        store.updateNode(otherType, KgNodeUpdate(name = "改名"))
        assertThat(db.kgNodeDao().getRawById(otherGraph)?.name).isEqualTo("共同人物")
        val edge = db.kgEdgeDao().getByDocId("one").single()
        db.openHelper.writableDatabase.execSQL("DELETE FROM kg_collections WHERE id='work:p'")
        assertThat(runCatching { store.createEdge(first, otherType, "无效图谱", collectionId = "work:p") }.isFailure).isTrue()
        assertThat(runCatching { store.updateNode(first, KgNodeUpdate(name = "无效图谱")) }.isFailure).isTrue()
        assertThat(runCatching { store.updateEdge(edge.id, KgEdgeUpdate(relation = "无效图谱")) }.isFailure).isTrue()
    }

    @Test fun `会话抽取的同名不同类型关系指向各自实体`() = runBlocking {
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity(id = "m", sessionId = "s", role = "assistant", content = "测试", createdAt = 1))
        val graph = ValidatedGraph.from(
            listOf(ExtractedNode("星河", "person"), ExtractedNode("星河", "organization"), ExtractedNode("山城", "location")),
            listOf(ExtractedEdge("星河", "山城", "居住", sourceType = "person"),
                ExtractedEdge("星河", "山城", "设址", sourceType = "organization")),
        )
        assertThat(store.replaceTurn("s", "m", "hash", graph)).isTrue()
        val relations = db.kgEdgeDao().getBySessionId("s").associate { edge ->
            edge.relation to requireNotNull(db.kgNodeDao().getRawById(edge.sourceId)).type
        }
        assertThat(relations).containsExactly("居住", "person", "设址", "organization")
    }

    @Test fun `只有关系证据的端点仍能在图谱页和列表计数中显示`() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('work:legacy','work','历史','legacy',1)")
        sql.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('a','甲','person','甲',1,'work:legacy'),('b','乙','person','乙',1,'work:legacy')")
        sql.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation,collection_id) VALUES('edge','a','b','认识',0.8,1,'认识','work:legacy')")
        sql.execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,content_hash,task_id,created_at,workspace_document,weight) VALUES('evidence','edge','legacy','','fixture',1,0,0.8)")

        val graph = store.getGraphSubgraph(GraphSubgraphRequest(collectionId = "work:legacy"))
        assertThat(graph.data.nodes.map { it.id }).containsExactly("a", "b")
        assertThat(graph.data.edges.map { it.id }).containsExactly("edge")
        val collection = collections.listBindableCollections("s").single { it.id == "work:legacy" }
        assertThat(collection.nodeCount).isEqualTo(2)
        assertThat(collection.edgeCount).isEqualTo(1)
        assertThat(db.kgNodeDao().getCount()).isEqualTo(2)
        store.updateNode("a", KgNodeUpdate(name = "新甲"))
        assertThat(db.kgNodeDao().getRawById("a")?.name).isEqualTo("新甲")
        store.mergeNodes("b", "新甲")
        assertThat(db.kgNodeDao().getRawById("b")).isNull()
        assertThat(db.kgEdgeDao().getRawById("edge")?.targetId).isEqualTo("a")
    }

    @Test fun `手动删除全部文档实体后回收成员和空作品图谱`() = runBlocking {
        folder("p", null); file("one", "p"); write("one")
        val ids = db.kgNodeDao().getByCollection("work:p").map { it.id }
        ids.forEach { store.deleteNode(it) }
        assertThat(collections.collectionIdsForDocuments(listOf("one"))).isEmpty()
        assertThat(collections.listBindableCollections("s").map { it.id }).doesNotContain("work:p")
        assertThat(collections.listBindableCollections("s").map { it.id }).contains("shared")
    }

    @Test fun `清除只有关系证据的文档时回收端点与空图谱`() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('work:legacy','work','历史','legacy',1)")
        sql.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('legacy','work:legacy')")
        sql.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('a','甲','person','甲',1,'work:legacy'),('b','乙','person','乙',1,'work:legacy')")
        sql.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation,collection_id) VALUES('edge','a','b','认识',0.8,1,'认识','work:legacy')")
        sql.execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document,weight) VALUES('evidence','edge','legacy','legacy','','fixture',1,0,0.8)")

        assertThat(db.kgNodeDao().getLibraryDocumentEntityNames("legacy")).containsExactly("甲", "乙")
        assertThat(store.getGraphData(docIds = listOf("legacy")).nodes.map { it.id }).containsExactly("a", "b")
        db.kgEvidenceDao().clearDocument("legacy")

        assertThat(db.kgEdgeDao().getRawById("edge")).isNull()
        assertThat(db.kgNodeDao().getRawById("a")).isNull()
        assertThat(db.kgNodeDao().getRawById("b")).isNull()
        assertThat(collections.listBindableCollections("s").map { it.id }).doesNotContain("work:legacy")
    }

    @Test fun `清除只有关系证据的会话消息时回收端点与空图谱`() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('session:s','session','会话',NULL,1)")
        sql.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('a','甲','person','甲',1,'session:s'),('b','乙','person','乙',1,'session:s')")
        sql.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation,collection_id) VALUES('edge','a','b','认识',0.8,1,'认识','session:s')")
        sql.execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,session_id,message_id,content_hash,task_id,created_at,workspace_document,weight) VALUES('evidence','edge','session','s','m','','fixture',1,0,0.8)")

        db.kgEvidenceDao().clearMessage("m")

        assertThat(db.kgEdgeDao().getRawById("edge")).isNull()
        assertThat(db.kgNodeDao().getRawById("a")).isNull()
        assertThat(db.kgNodeDao().getRawById("b")).isNull()
        sql.query("SELECT 1 FROM kg_collections WHERE id='session:s'").use { assertThat(it.moveToFirst()).isFalse() }
    }

    @Test fun `全量清空图谱同时清理成员与空图谱并保留文件夹设置`() = runBlocking {
        folder("p", null); file("one", "p")
        collections.setFolderSetting("p", KgFolderSetting.SHARED)
        write("one")

        db.kgEdgeDao().clearGraph()

        val sql = db.openHelper.readableDatabase
        listOf("kg_edges", "kg_nodes", "kg_evidence", "kg_collection_members").forEach { table ->
            sql.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0) }
        }
        sql.query("SELECT id FROM kg_collections").use { check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("shared"); assertThat(it.moveToNext()).isFalse() }
        assertThat(collections.readFolderSetting("p")).isEqualTo(KgFolderSetting.SHARED)
    }

    @Test fun `会话只读绑定拒绝排队和持久化，读写绑定可按来源撤回`() = runBlocking {
        folder("p", null); file("one", "p"); write("one")
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity(id = "u", sessionId = "s", role = "user", content = "足够长的问题".repeat(5), createdAt = 1))
        db.messageDao().insert(MessageEntity(id = "m", sessionId = "s", role = "assistant", content = "足够长的回答".repeat(5), parentMessageId = "u", createdAt = 2))
        val graph = ValidatedGraph.from(listOf(ExtractedNode("共同人物", "person"), ExtractedNode("目标", "person")),
            listOf(ExtractedEdge("共同人物", "目标", "帮助")))
        assertThat(store.replaceTurn("s", "m", "h", graph)).isTrue()
        assertThat(collections.readBinding("s").collectionId).isEqualTo("session:s")
        val original = graphCounts("work:p")
        collections.setBinding("s", GraphBinding("work:p", GraphBindingMode.READ_ONLY))
        val scope = KgRetrievalScope.of(RetrievalScopeResolver(db).resolve("s", RagOptions(enableKnowledgeGraph = true)))
        assertThat(GraphRetrievalStore(db).retrieve("共同人物", scope).paths.single().sourceDocumentIds).containsExactly("one")
        val scheduler = RoomSessionGraphScheduler({ db }, { true }, { error("只读模式不应访问队列") })
        assertThat(scheduler.schedule("s", "m", "u")).isFalse()
        val processor = RoomSessionGraphProcessor({ db }, { error("只读模式不应调用模型") },
            SessionGraphStats(mockk<SharedPreferences>(relaxed = true)))
        val queued = VectorizationTask(id = "queued", type = VectorizationQueue.TYPE_SESSION_GRAPH,
            sessionId = "s", assistantMessageId = "m", userContent = "问题".repeat(20), aiContent = "回答".repeat(20))
        assertThat(processor.process(queued)).isNull()
        assertThat(store.replaceTurn("s", "m", "h", graph)).isFalse()
        val result = MicroGraphResult(graph.nodes, graph.edges, graph.context, emptyList(), "q", 1)
        val cache = KgJitCacheEntity("key", "query", "chunks", "{}", 1, Long.MAX_VALUE)
        assertThat(store.persistJit(result, listOf(KgJitSource(null, "h", null)), "s", cache, "work:p")).isFalse()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_jit_cache").use { check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0) }
        collections.setBinding("s", GraphBinding("work:p", GraphBindingMode.READ_WRITE))
        assertThat(store.replaceTurn("s", "m", "h", graph)).isTrue()
        val sessionEvidence = db.kgEvidenceDao().evidenceForMessageInCollection("m", "work:p")
        assertThat(sessionEvidence).isNotEmpty()
        assertThat(sessionEvidence.all { it.sourceType == "session" && it.sessionId == "s" && it.messageId == "m" }).isTrue()
        assertThat(store.persistJit(result, listOf(KgJitSource(null, "h", null)), "s", cache, "work:p")).isTrue()
        assertThat(graphCounts("work:p")).isNotEqualTo(original)
        assertThat(collections.revokeSessionEvidence("s", "work:p")).isGreaterThan(0)
        assertThat(graphCounts("work:p")).isEqualTo(original)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_jit_cache").use { check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0) }
        assertThat(graphCounts("session:s").second).isEqualTo(1)
    }

    @Test fun `会话图谱页和搜索始终使用会话绑定`() = runBlocking {
        folder("p", null); folder("q", null); file("one", "p"); file("two", "q")
        write("one"); write("two")
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        collections.setBinding("s", GraphBinding("work:p"))
        val boundIds = db.kgNodeDao().getByCollection("work:p").map { it.id }.toSet()
        val graph = store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s", collectionId = "work:q"))
        assertThat(graph.data.nodes.map { it.id }.toSet()).isEqualTo(boundIds)
        assertThat(store.searchGraphEntities(GraphEntitySearchRequest("共同人物", sessionId = "s", collectionId = "work:q")).map { it.id })
            .containsExactlyElementsIn(boundIds.filter { id -> graph.data.nodes.first { it.id == id }.name == "共同人物" })
    }

    @Test fun `会话图数据入口只返回当前绑定图谱的全部存活来源`() = runBlocking {
        folder("p", null); file("one", "p"); write("one")
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity(id = "m", sessionId = "s", role = "assistant", content = "测试", createdAt = 1))
        val ownGraph = ValidatedGraph.from(listOf(ExtractedNode("会话甲", "person"), ExtractedNode("会话乙", "person")),
            listOf(ExtractedEdge("会话甲", "会话乙", "会话关系")))
        store.replaceTurn("s", "m", "h", ownGraph)
        collections.setBinding("s", GraphBinding("work:p", GraphBindingMode.READ_ONLY))

        val graph = store.getGraphData(sessionId = "s")

        assertThat(graph.nodes.map { it.name }).containsExactly("共同人物", "目标")
        assertThat(graph.edges.map { it.relation }).containsExactly("认识")
    }

    @Test fun `绑定作品随文档移动变空后立即回落会话图谱`() = runBlocking {
        folder("p", null); folder("q", null); file("one", "p"); write("one")
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity(id = "m", sessionId = "s", role = "assistant", content = "测试", createdAt = 1))
        val own = ValidatedGraph.from(listOf(ExtractedNode("会话甲", "person"), ExtractedNode("会话乙", "person")),
            listOf(ExtractedEdge("会话甲", "会话乙", "会话关系")))
        store.replaceTurn("s", "m", "h", own)
        collections.setBinding("s", GraphBinding("work:p"))
        val entry = requireNotNull(db.fileEntryDao().findActiveByUuid("one"))
        db.fileEntryDao().update(entry.copy(parentUuid = "q"))
        collections.reconcileDocuments()
        val binding = collections.readBinding("s")
        assertThat(binding.collectionId).isEqualTo("session:s")
        assertThat(GraphRetrievalStore(db).retrieve("会话甲", KgRetrievalScope("s", collectionId = binding.collectionId)).paths).hasSize(1)
    }

    @Test fun `旧选项默认本会话图谱且不允许绑定其他会话`() = runBlocking {
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        db.sessionDao().insert(SessionEntity("other", "agent", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity(id = "m-other", sessionId = "other", role = "assistant", content = "测试", createdAt = 1))
        val graph = ValidatedGraph.from(listOf(ExtractedNode("甲", "person"), ExtractedNode("乙", "person")), listOf(ExtractedEdge("甲", "乙", "认识")))
        store.replaceTurn("other", "m-other", "h", graph)
        assertThat(collections.readBinding("s", json.decodeFromString<RagOptions>("{\"enableDocs\":true}")).collectionId).isEqualTo("session:s")
        assertThat(runCatching { collections.setBinding("s", GraphBinding("session:other")) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        folder("p", null); file("one", "p"); write("one")
        collections.setBinding("s", GraphBinding("work:p", GraphBindingMode.READ_ONLY))
        db.openHelper.writableDatabase.execSQL("DELETE FROM kg_collections WHERE id='work:p'")
        val fallback = collections.readBinding("s")
        assertThat(fallback.collectionId).isEqualTo("session:s")
        assertThat(fallback.writable).isFalse()
    }

    @Test fun `会话其他检索选项损坏时仍保留图谱只读绑定`() = runBlocking {
        folder("p", null); file("one", "p"); write("one")
        db.sessionDao().insert(SessionEntity("s", "agent", ragOptions =
            """{"enableDocs":"invalid","graphBinding":{"collectionId":"work:p","mode":"READ_ONLY"}}""",
            createdAt = 1, updatedAt = 1))
        val binding = collections.readBinding("s")
        assertThat(binding.collectionId).isEqualTo("work:p")
        assertThat(binding.writable).isFalse()
    }

    @Test fun `图谱模式无法解码时不静默获得写权限`() = runBlocking {
        db.sessionDao().insert(SessionEntity("s", "agent", ragOptions =
            """{"graphBinding":{"mode":"unknown"}}""", createdAt = 1, updatedAt = 1))
        val binding = collections.readBinding("s")
        assertThat(binding.collectionId).isEqualTo("session:s")
        assertThat(binding.writable).isFalse()
    }

    @Test fun `检索期间改绑不会返回先前图谱结果`() = runBlocking {
        folder("p", null); file("one", "p"); write("one")
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        collections.setBinding("s", GraphBinding("work:p"))
        var called = false
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                called = true
                collections.setBinding("s", GraphBinding())
                return KgContextResult("")
            }
        }
        val provider = PersistentGraphKgProvider(store, jit) { RagConfiguration(kgJitMinSeeds = 32) }
        val result = provider.retrieveContext("共同人物", "s", listOf(RagReference("chunk", "片段", documentId = "one")),
            RagOptions(activeDocIds = listOf("one"), enableKnowledgeGraph = true))
        assertThat(called).isTrue()
        assertThat(result.paths).isEmpty()
    }

    private suspend fun folder(id: String, parent: String?) = insert(id, parent, true)
    private suspend fun file(id: String, parent: String?) = insert(id, parent, false)
    private suspend fun insert(id: String, parent: String?, directory: Boolean) {
        db.fileEntryDao().insert(FileEntry(uuid = id, workspaceRootUuid = "root", parentUuid = parent, name = id,
            hash = "h", physicalRootPath = "/fixture", materializedPath = "/$id", isDirectory = directory,
            createdAt = 1, updatedAt = 1))
    }

    private suspend fun write(doc: String) {
        val nodes = listOf(KgNodeEntity(id = "a", name = "共同人物", type = "person", createdAt = 1),
            KgNodeEntity(id = "b", name = "目标", type = "person", createdAt = 1))
        val edges = listOf(KgEdgeEntity(id = "e", sourceId = "a", targetId = "b", relation = "认识", createdAt = 1))
        store.commitCandidate(DocumentIndexCandidate(emptyList(), nodes, edges), KgSource("document", docId = doc, contentHash = "h", workspaceDocument = true))
    }

    private fun entityCollections(): List<String> = db.openHelper.readableDatabase.query(
        "SELECT collection_id,COUNT(*) FROM kg_nodes GROUP BY collection_id ORDER BY collection_id"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add("${cursor.getString(0)}:${cursor.getInt(1)}") } }

    private fun graphCounts(id: String): Triple<Int, Int, Int> = db.openHelper.readableDatabase.query(
        "SELECT (SELECT COUNT(*) FROM kg_nodes WHERE collection_id=?),(SELECT COUNT(*) FROM kg_edges WHERE collection_id=?),(SELECT COUNT(*) FROM kg_evidence p LEFT JOIN kg_nodes n ON n.id=p.node_id LEFT JOIN kg_edges e ON e.id=p.edge_id WHERE COALESCE(n.collection_id,e.collection_id)=?)",
        arrayOf(id, id, id),
    ).use { cursor -> check(cursor.moveToFirst()); Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)) }
}
