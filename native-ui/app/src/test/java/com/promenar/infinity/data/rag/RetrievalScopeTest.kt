package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.GlobalMemoryPolicy
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.KgEvidenceEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.GraphBinding
import com.promenar.infinity.data.model.GraphBindingMode
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 对话记忆与文档范围控制向量和关键词检索；图谱由会话绑定独立控制。
 * 助手 A 有 a1、a2 两个会话，B 有 b1，私密助手 P 有 p1；知识库会话 kb 属于系统助手。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RetrievalScopeTest {
    private lateinit var db: InfinityDatabase
    private lateinit var resolver: RetrievalScopeResolver

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        resolver = RetrievalScopeResolver(db)
        db.agentDao().insert(AgentEntity("A", "A", createdAt = 1))
        db.agentDao().insert(AgentEntity("P", "P", createdAt = 1,
            retrievalConfig = AgentRetrievalConfig(memoryPolicy = AgentMemoryPolicy(shareWithOtherAgents = false))))
        // B 是未写入 agents 表的预置助手，按默认共享处理。
        for ((session, agent) in listOf("a1" to "A", "a2" to "A", "b1" to "B", "p1" to "P")) {
            db.sessionDao().insert(SessionEntity(session, agent, createdAt = 1, updatedAt = 1, workspaceRootUuid = "root-$session"))
            db.vectorDao().insert(vector("mem-$session", sessionId = session, docId = null, metadata = "{\"type\":\"memory\",\"chunkIndex\":0}"))
            db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES(?,?,?,1)",
                arrayOf("session:$session", "session", session))
            db.kgNodeDao().insert(KgNodeEntity("node-$session", "甲$session", createdAt = 1, collectionId = "session:$session"))
            db.kgEvidenceDao().insert(KgEvidenceEntity("ev-$session", nodeId = "node-$session", sourceType = "session",
                sessionId = session, contentHash = "", taskId = "t-$session", createdAt = 1))
        }
        db.sessionDao().insert(SessionEntity("kb", RetrievalScopeResolver.KNOWLEDGE_BASE_AGENT_ID, createdAt = 1, updatedAt = 1,
            workspaceRootUuid = "root-kb"))
        for ((file, root) in listOf("f-a1" to "root-a1", "f-kb" to "root-kb", "f-b1" to "root-b1")) {
            db.fileEntryDao().insert(FileEntry(uuid = file, workspaceRootUuid = root, parentUuid = root, name = "$file.md", hash = "h",
                physicalRootPath = "/tmp/scope-fixture", materializedPath = "/$file.md", createdAt = 1, updatedAt = 1))
        }
        for (doc in listOf("f-a1", "f-kb", "f-b1", "legacy-doc")) {
            db.vectorDao().insert(vector("doc-$doc", sessionId = null, docId = doc, metadata = "{\"type\":\"document\",\"docId\":\"$doc\"}"))
            db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES(?,?,?,?,1)",
                arrayOf("work:$doc", "work", doc, doc))
            db.kgNodeDao().insert(KgNodeEntity("node-$doc", "乙$doc", createdAt = 1, collectionId = "work:$doc"))
            db.kgEvidenceDao().insert(KgEvidenceEntity("ev-$doc", nodeId = "node-$doc", sourceType = "document",
                docId = doc, contentHash = "", taskId = "index", createdAt = 1))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `会话记忆按智能体上限收紧但保存选择不变`() = runBlocking<Unit> {
        val saved = RagOptions(globalMemoryEnabled = true, globalMemoryScope = ConversationScope.ALL_AGENTS)
        val limited = AgentMemoryPolicy(globalMemory = GlobalMemoryPolicy.ALLOW_DEFAULT_OFF, memoryCap = ConversationScope.AGENT)
        val agent = db.agentDao().getById("A")!!
        db.agentDao().update(agent.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy = limited)))
        assertThat(resolver.resolve("a1", saved).memory).isEqualTo(ConversationReach.Agent("A"))
        assertThat(saved.globalMemoryScope).isEqualTo(ConversationScope.ALL_AGENTS)
        val forbidden = limited.copy(globalMemory = GlobalMemoryPolicy.FORBID)
        db.agentDao().update(agent.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy = forbidden)))
        assertThat(resolver.resolve("a1", saved).memory).isEqualTo(ConversationReach.Session("a1"))
        db.agentDao().update(agent.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy = limited.copy(memoryCap = ConversationScope.ALL_AGENTS))))
        assertThat(resolver.resolve("a1", saved).memory
            .allows("p1", "P")).isTrue()
    }

    @Test
    fun `合集限制覆盖文档关键词和图谱绑定并随改绑变化`() = runBlocking<Unit> {
        val dbSql = db.openHelper.writableDatabase
        dbSql.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('f-kb','work:f-kb')")
        dbSql.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('f-b1','work:f-b1')")
        val agent = db.agentDao().getById("A")!!
        db.agentDao().update(agent.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy =
            AgentMemoryPolicy(allowedCollectionIds = listOf("work:f-kb")))))
        val excluded = RagOptions(selectedCollectionIds = listOf("work:f-b1"),
            activeDocIds = listOf("f-b1"), graphBinding = GraphBinding("work:f-b1", GraphBindingMode.READ_ONLY))
        val blocked = resolver.resolve("a1", excluded)
        assertThat(db.vectorDao().documentRows(blocked.documents).map { it.docId }).doesNotContain("f-b1")
        assertThat(db.vectorDao().documentFts(blocked.documents, "needle").map { it.docId }).doesNotContain("f-b1")
        assertThat(blocked.graphRestriction).isNotNull()
        assertThat(GraphRetrievalStore(db).retrieve("乙f-b1", KgRetrievalScope.of(blocked)).seedCount).isEqualTo(0)
        val allowed = resolver.resolve("a1", excluded.copy(selectedCollectionIds = emptyList(), activeDocIds = emptyList(),
            graphBinding = GraphBinding("work:f-kb", GraphBindingMode.READ_ONLY)))
        assertThat(db.vectorDao().documentRows(allowed.documents).map { it.docId }).contains("f-kb")
        val own = resolver.resolve("a1", excluded.copy(selectedCollectionIds = emptyList(), activeDocIds = emptyList(),
            graphBinding = GraphBinding()))
        assertThat(db.vectorDao().documentRows(own.documents).map { it.docId }).doesNotContain("f-kb")
    }

    @Test
    fun `联合即时抽取的任一文档不可见时整个结果不可见`() = runBlocking<Unit> {
        db.kgNodeDao().insert(KgNodeEntity("mixed", "联合事实", createdAt = 1, collectionId = "session:a1"))
        for (doc in listOf("f-kb", "f-b1")) db.kgEvidenceDao().insert(KgEvidenceEntity(
            id = "mixed-$doc", nodeId = "mixed", sourceType = "jit", docId = doc,
            sessionId = "a1", contentHash = "", taskId = "mixed-task", createdAt = 1))
        val scope = KgRetrievalScope(sessionId = "a1", documents = DocumentReach.Scoped(emptySet(), setOf("f-kb")))
        val store = GraphRetrievalStore(db)
        assertThat(store.retrieve("联合事实", scope).seedCount).isEqualTo(0)
        val both = scope.copy(documents = DocumentReach.Scoped(emptySet(), setOf("f-kb", "f-b1")))
        assertThat(store.retrieve("联合事实", both).seedCount).isEqualTo(1)
        assertThat(store.retrieve("联合事实", both.copy(allowConversation = false)).seedCount).isEqualTo(1)
        db.kgEvidenceDao().insert(KgEvidenceEntity(id = "mixed-chat", nodeId = "mixed", sourceType = "jit",
            sessionId = "a1", contentHash = "", taskId = "mixed-task", createdAt = 1))
        assertThat(store.retrieve("联合事实", both.copy(allowConversation = false)).seedCount).isEqualTo(0)
        assertThat(store.retrieve("联合事实", both).seedCount).isEqualTo(1)
    }


    @Test
    fun `对话记忆三档改变向量和关键词范围而图谱保持本会话绑定`() = runBlocking<Unit> {
        val expected = mapOf(
            ConversationScope.SESSION to setOf("a1"),
            ConversationScope.AGENT to setOf("a1", "a2"),
            ConversationScope.ALL_AGENTS to setOf("a1", "a2", "b1", "p1"),
        )
        expected.forEach { (scope, sessions) ->
            val resolved = resolver.resolve("a1", RagOptions(globalMemoryEnabled = scope != ConversationScope.SESSION, globalMemoryScope = scope, graphScope = scope))
            assertThat(db.vectorDao().conversationRows(resolved.memory, "memory").map { it.sessionId }).containsExactlyElementsIn(sessions)
            assertThat(db.vectorDao().conversationFts(resolved.memory, "needle").map { it.sessionId }).containsExactlyElementsIn(sessions)
            assertThat(graphSessions(resolved)).containsExactly("a1")
        }
    }

    @Test
    fun `旧私密标记不影响显式启用的全局记忆`() = runBlocking<Unit> {
        val fromB = resolver.resolve("b1", RagOptions(globalMemoryEnabled = true, globalMemoryScope = ConversationScope.ALL_AGENTS))
        assertThat(db.vectorDao().conversationRows(fromB.memory, "memory").map { it.sessionId }).contains("p1")
        assertThat(graphSessions(fromB)).doesNotContain("p1")

        val fromP = resolver.resolve("p1", RagOptions(globalMemoryEnabled = true, globalMemoryScope = ConversationScope.ALL_AGENTS))
        assertThat(db.vectorDao().conversationRows(fromP.memory, "memory").map { it.sessionId })
            .containsExactly("a1", "a2", "b1", "p1")
        assertThat(graphSessions(fromP)).containsExactly("p1")
    }

    @Test
    fun `文档三档在向量、关键词、图谱三条路径一致`() = runBlocking<Unit> {
        val expected = mapOf(
            emptyList<String>() to setOf("f-a1"),
            listOf("work:f-kb") to setOf("f-a1", "f-kb"),
            listOf("work:f-kb", "work:f-b1", "work:legacy-doc") to setOf("f-a1", "f-kb", "f-b1", "legacy-doc"),
        )
        for (doc in listOf("f-kb", "f-b1", "legacy-doc")) db.openHelper.writableDatabase.execSQL(
            "INSERT INTO kg_collection_members(doc_id,collection_id) VALUES(?,?)", arrayOf(doc, "work:$doc"))
        expected.forEach { (selected, docs) ->
            val resolved = resolver.resolve("a1", RagOptions(selectedCollectionIds = selected))
            assertThat(db.vectorDao().documentRows(resolved.documents).map { it.docId }).containsExactlyElementsIn(docs)
            assertThat(db.vectorDao().documentFts(resolved.documents, "needle").map { it.docId }).containsExactlyElementsIn(docs)
            assertThat(GraphRetrievalStore(db).visibleDocumentIds(KgRetrievalScope.of(resolved))).containsExactlyElementsIn(docs)
        }
    }

    @Test
    fun `显式文档与文件夹在限定范围内可见，空范围不退化为全库`() = runBlocking<Unit> {
        db.fileEntryDao().insert(FileEntry(uuid = "folder", workspaceRootUuid = "root-b1", parentUuid = "root-b1", name = "folder", hash = "h",
            physicalRootPath = "/tmp/scope-fixture", materializedPath = "/folder", isDirectory = true, createdAt = 1, updatedAt = 1))
        db.fileEntryDao().insert(FileEntry(uuid = "f-folder", workspaceRootUuid = "root-b1", parentUuid = "folder", name = "x.md", hash = "h",
            physicalRootPath = "/tmp/scope-fixture", materializedPath = "/folder/x.md", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("doc-f-folder", sessionId = null, docId = "f-folder", metadata = "{\"type\":\"document\"}"))

        val selected = resolver.resolve("a1", RagOptions(documentScope = DocumentScope.WORKSPACE, selectedCollectionIds = emptyList(),
            activeDocIds = listOf("legacy-doc"), activeFolderIds = listOf("folder")))
        assertThat(db.vectorDao().documentRows(selected.documents).map { it.docId })
            .containsExactly("f-a1", "legacy-doc", "f-folder")

        db.sessionDao().insert(SessionEntity("bare", "A", createdAt = 1, updatedAt = 1))
        val empty = resolver.resolve("bare", RagOptions(documentScope = DocumentScope.WORKSPACE, selectedCollectionIds = emptyList()))
        assertThat(db.vectorDao().documentRows(empty.documents)).isEmpty()
        assertThat(db.vectorDao().documentFts(empty.documents, "needle")).isEmpty()
    }

    @Test fun `显式选择旧文档ID时按向量文件身份解析合集权限`() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('f-kb','work:f-kb')")
        db.vectorDao().insert(vector("alias-vector", null, "legacy-alias", "{\"type\":\"document\"}").copy(fileUuid = "f-kb"))
        val agent = db.agentDao().getById("A")!!
        db.agentDao().update(agent.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy = AgentMemoryPolicy(allowedCollectionIds = listOf("work:f-kb")))))
        val resolved = resolver.resolve("a1", RagOptions(selectedCollectionIds = emptyList(), activeDocIds = listOf("legacy-alias")))
        assertThat(db.vectorDao().documentRows(resolved.documents).map { it.id }).contains("alias-vector")
    }

    @Test fun `旧文档ID跨允许和禁止合集冲突时不能读取禁止文件`() = runBlocking<Unit> {
        for (doc in listOf("f-kb", "f-b1")) db.openHelper.writableDatabase.execSQL(
            "INSERT INTO kg_collection_members(doc_id,collection_id) VALUES(?,?)", arrayOf(doc, "work:$doc"))
        db.vectorDao().insert(vector("allow-alias", null, "same-id", "{\"type\":\"document\"}").copy(fileUuid = "f-kb"))
        db.vectorDao().insert(vector("deny-alias", null, "same-id", "{\"type\":\"document\"}").copy(fileUuid = "f-b1"))
        db.vectorDao().insert(vector("old-allow", null, "stale-id", "{\"type\":\"document\"}").copy(fileUuid = "f-kb", stale = true))
        db.vectorDao().insert(vector("active-deny", null, "stale-id", "{\"type\":\"document\"}").copy(fileUuid = "f-b1"))
        val agent = db.agentDao().getById("A")!!
        db.agentDao().update(agent.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy = AgentMemoryPolicy(allowedCollectionIds = listOf("work:f-kb")))))
        val resolved = resolver.resolve("a1", RagOptions(selectedCollectionIds = emptyList(), activeDocIds = listOf("same-id", "stale-id")))
        assertThat(db.vectorDao().documentRows(resolved.documents).map { it.id }).contains("allow-alias")
        assertThat(db.vectorDao().documentRows(resolved.documents).map { it.id }).containsNoneOf("deny-alias", "active-deny")
        assertThat(db.vectorDao().documentFts(resolved.documents, "needle").map { it.id }).containsNoneOf("deny-alias", "active-deny")
        val staleOnly = resolver.resolve("a1", RagOptions(selectedCollectionIds = emptyList(), activeDocIds = listOf("stale-id")))
        assertThat(db.vectorDao().documentRows(staleOnly.documents).map { it.id }).containsNoneOf("old-allow", "active-deny", "allow-alias")
    }

    @Test
    fun `范围统计与检索读取的会话集合一致`() = runBlocking<Unit> {
        fun stats(session: String, scope: ConversationScope) = runBlocking {
            resolver.stats(resolver.resolve(session, RagOptions(globalMemoryEnabled = scope != ConversationScope.SESSION, globalMemoryScope = scope)).memory)
        }
        assertThat(stats("a1", ConversationScope.SESSION)).isEqualTo(ConversationReachStats(1, 1))
        assertThat(stats("a1", ConversationScope.AGENT)).isEqualTo(ConversationReachStats(2, 1))
        assertThat(stats("a1", ConversationScope.ALL_AGENTS)).isEqualTo(ConversationReachStats(5, 4))
        assertThat(stats("p1", ConversationScope.ALL_AGENTS)).isEqualTo(ConversationReachStats(5, 4))
    }

    @Test
    fun `旧会话isGlobal迁移为本助手，会话档位不超过助手上限`() {
        assertThat(RagOptions(isGlobal = true).effectiveMemoryScope).isEqualTo(ConversationScope.AGENT)
        assertThat(RagOptions(isGlobal = true).effectiveGraphScope).isEqualTo(ConversationScope.AGENT)
        assertThat(RagOptions(isGlobal = false).effectiveMemoryScope).isEqualTo(ConversationScope.SESSION)
        assertThat(RagOptions().effectiveDocumentScope).isEqualTo(DocumentScope.KNOWLEDGE_BASE)
        assertThat(RagOptions(isGlobal = true, memoryScope = ConversationScope.SESSION).effectiveMemoryScope)
            .isEqualTo(ConversationScope.SESSION)

        val policy = AgentMemoryPolicy(memoryCap = ConversationScope.AGENT, graphCap = ConversationScope.SESSION,
            documentCap = DocumentScope.WORKSPACE)
        val clamped = policy.clamp(RagOptions(globalMemoryEnabled = true, globalMemoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.AGENT,
            documentScope = DocumentScope.ALL_INDEXED))
        assertThat(clamped.memoryScope).isEqualTo(ConversationScope.AGENT)
        assertThat(clamped.graphScope).isEqualTo(ConversationScope.AGENT)
        assertThat(clamped.documentScope).isEqualTo(DocumentScope.ALL_INDEXED)
        assertThat(policy.clamp(RagOptions(memoryScope = ConversationScope.SESSION)).memoryScope).isEqualTo(ConversationScope.SESSION)

        val normalized = AgentMemoryPolicy(memoryDefault = ConversationScope.ALL_AGENTS, memoryCap = ConversationScope.SESSION).normalized()
        assertThat(normalized.memoryDefault).isEqualTo(ConversationScope.SESSION)
        assertThat(normalized.defaultsFor(RagOptions(isGlobal = true)).effectiveMemoryScope).isEqualTo(ConversationScope.SESSION)
        val legacyGraphPolicy = AgentMemoryPolicy(graphDefault = ConversationScope.ALL_AGENTS, graphCap = ConversationScope.SESSION)
        assertThat(legacyGraphPolicy.defaultsFor(RagOptions(graphScope = ConversationScope.AGENT)).graphScope)
            .isEqualTo(ConversationScope.AGENT)
        assertThat(legacyGraphPolicy.normalized().graphDefault).isEqualTo(ConversationScope.ALL_AGENTS)
    }

    /** 每个会话恰有一个“甲+会话名”实体，只有对话证据；按名称检索命中即说明该会话的证据可见。 */
    private suspend fun graphSessions(resolved: ResolvedRetrievalScope): List<String> =
        listOf("a1", "a2", "b1", "p1").filter { session ->
            GraphRetrievalStore(db).retrieve("甲$session", KgRetrievalScope.of(resolved)).seedCount == 1
        }

    private fun vector(id: String, sessionId: String?, docId: String?, metadata: String) = VectorEntity(
        id = id,
        docId = docId,
        sessionId = sessionId,
        content = "needle $id",
        embedding = ByteBuffer.allocate(8).also { it.asFloatBuffer().put(floatArrayOf(1f, 0f)) }.array(),
        metadata = metadata,
        createdAt = 1,
    )
}
