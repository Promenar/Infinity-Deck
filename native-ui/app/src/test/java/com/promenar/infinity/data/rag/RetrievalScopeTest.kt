package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
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
    fun `对话记忆三档改变向量和关键词范围而图谱保持本会话绑定`() = runBlocking<Unit> {
        val expected = mapOf(
            ConversationScope.SESSION to setOf("a1"),
            ConversationScope.AGENT to setOf("a1", "a2"),
            ConversationScope.ALL_AGENTS to setOf("a1", "a2", "b1"),
        )
        expected.forEach { (scope, sessions) ->
            val resolved = resolver.resolve("a1", RagOptions(memoryScope = scope, graphScope = scope))
            assertThat(db.vectorDao().conversationRows(resolved.memory, "memory").map { it.sessionId }).containsExactlyElementsIn(sessions)
            assertThat(db.vectorDao().conversationFts(resolved.memory, "needle").map { it.sessionId }).containsExactlyElementsIn(sessions)
            assertThat(graphSessions(resolved)).containsExactly("a1")
        }
    }

    @Test
    fun `私密助手不被其他助手的全部助手档读取，自身仍可读取`() = runBlocking<Unit> {
        val fromB = resolver.resolve("b1", RagOptions(memoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.ALL_AGENTS))
        assertThat(db.vectorDao().conversationRows(fromB.memory, "memory").map { it.sessionId }).doesNotContain("p1")
        assertThat(graphSessions(fromB)).doesNotContain("p1")

        val fromP = resolver.resolve("p1", RagOptions(memoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.ALL_AGENTS))
        assertThat(db.vectorDao().conversationRows(fromP.memory, "memory").map { it.sessionId })
            .containsExactly("a1", "a2", "b1", "p1")
        assertThat(graphSessions(fromP)).containsExactly("p1")
    }

    @Test
    fun `文档三档在向量、关键词、图谱三条路径一致`() = runBlocking<Unit> {
        val expected = mapOf(
            DocumentScope.WORKSPACE to setOf("f-a1"),
            DocumentScope.KNOWLEDGE_BASE to setOf("f-a1", "f-kb"),
            DocumentScope.ALL_INDEXED to setOf("f-a1", "f-kb", "f-b1", "legacy-doc"),
        )
        expected.forEach { (scope, docs) ->
            val resolved = resolver.resolve("a1", RagOptions(documentScope = scope))
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

        val selected = resolver.resolve("a1", RagOptions(documentScope = DocumentScope.WORKSPACE,
            activeDocIds = listOf("legacy-doc"), activeFolderIds = listOf("folder")))
        assertThat(db.vectorDao().documentRows(selected.documents).map { it.docId })
            .containsExactly("f-a1", "legacy-doc", "f-folder")

        db.sessionDao().insert(SessionEntity("bare", "A", createdAt = 1, updatedAt = 1))
        val empty = resolver.resolve("bare", RagOptions(documentScope = DocumentScope.WORKSPACE))
        assertThat(db.vectorDao().documentRows(empty.documents)).isEmpty()
        assertThat(db.vectorDao().documentFts(empty.documents, "needle")).isEmpty()
    }

    @Test
    fun `范围统计与检索读取的会话集合一致`() = runBlocking<Unit> {
        fun stats(session: String, scope: ConversationScope) = runBlocking {
            resolver.stats(resolver.resolve(session, RagOptions(memoryScope = scope)).memory)
        }
        assertThat(stats("a1", ConversationScope.SESSION)).isEqualTo(ConversationReachStats(1, 1))
        assertThat(stats("a1", ConversationScope.AGENT)).isEqualTo(ConversationReachStats(2, 1))
        // 全部助手档排除私密助手 P，包含 A、B 与知识库会话所属的系统助手。
        assertThat(stats("a1", ConversationScope.ALL_AGENTS)).isEqualTo(ConversationReachStats(4, 3))
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
        val clamped = policy.clamp(RagOptions(memoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.AGENT,
            documentScope = DocumentScope.ALL_INDEXED))
        assertThat(clamped.memoryScope).isEqualTo(ConversationScope.AGENT)
        assertThat(clamped.graphScope).isEqualTo(ConversationScope.AGENT)
        assertThat(clamped.documentScope).isEqualTo(DocumentScope.WORKSPACE)
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
