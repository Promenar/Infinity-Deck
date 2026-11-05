package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.GraphBinding
import com.promenar.infinity.data.model.GraphBindingMode
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptResponse
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgJitProvenanceTest {
    private lateinit var db: InfinityDatabase
    private lateinit var store: GraphStore
    private val extractors = mutableListOf<MicroGraphExtractor>()
    private val protocol = mockk<LlmProtocol>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        store = GraphStore(db)
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(GRAPH)
    }

    @After fun close() { extractors.forEach { it.close() }; db.close() }

    @Test fun `适配器保留已知文档标识`() = runTest {
        val extractor = mockk<MicroGraphExtractor> { every { modelId } returns null }
        val refs = slot<List<SearchResult>>()
        coEvery { extractor.extract(capture(refs), any(), any(), any(), any()) } returns
            MicroGraphResult(emptyList(), emptyList(), "", emptyList(), "q", 1)
        MicroGraphKgAdapter(extractor).extractContext("q", "s", listOf(RagReference(id = "chunk", documentId = "doc", content = "text")))
        assertThat(refs.captured.single().docId).isEqualTo("doc")
    }

    @Test fun `不同会话图谱的缓存隔离且同图谱命中不重复登记证据`() = runTest {
        seedFile()
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        extractor.extract(refs(), "q", "s1")
        testScheduler.runCurrent()
        awaitEdges("s1")
        extractor.extract(refs(), "q", "s2")
        testScheduler.runCurrent()
        awaitEdges("s2")
        val before = db.kgEvidenceDao().affectedBySession("s2").size
        extractor.extract(refs(), "q", "s2")
        testScheduler.runCurrent()
        assertThat(db.kgEvidenceDao().affectedBySession("s2")).hasSize(before)
        coVerify(exactly = 2) { protocol.sendPromptSync(any()) }
        assertThat(store.getGraphData(sessionId = "s2").edges).hasSize(1)
    }

    @Test fun `同一会话切换图谱不会命中另一图谱的即时缓存`() = runTest {
        seedFile()
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        val collections = KgCollectionRepository(db)
        seedBindableWork("work:first")
        collections.setBinding("s", GraphBinding("work:first"))
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        extractor.extract(refs(), "q", "s")
        testScheduler.runCurrent()
        awaitEdges("s")
        seedBindableWork("work:second")
        collections.setBinding("s", GraphBinding("work:second"))
        extractor.extract(refs(), "q", "s")
        testScheduler.runCurrent()
        awaitEdges("s")
        collections.setBinding("s", GraphBinding("work:first"))
        extractor.extract(refs(), "q", "s")
        coVerify(exactly = 2) { protocol.sendPromptSync(any()) }
    }

    @Test fun `只读绑定的即时抽取可返回结果但不写入证据和缓存`() = runTest {
        seedFile()
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        seedBindableWork("work:doc")
        KgCollectionRepository(db).setBinding("s", GraphBinding("work:doc", GraphBindingMode.READ_ONLY))
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        val result = extractor.extract(refs(), "q", "s")
        testScheduler.runCurrent()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.edges).hasSize(1)
        assertThat(db.kgEvidenceDao().affectedBySession("s")).isEmpty()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_jit_cache").use {
            check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0)
        }
        coVerify(exactly = 1) { protocol.sendPromptSync(any()) }
    }

    @Test fun `相同片段标识预算外内容变化也使缓存失效`() = runTest {
        seedFile()
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        extractor.extract(refs("Alpha Beta tail-v1"), "q", "s", maxChars = 10)
        testScheduler.runCurrent()
        awaitEdges("s")
        extractor.extract(refs("Alpha Beta tail-v2"), "q", "s", maxChars = 10)
        coVerify(exactly = 2) { protocol.sendPromptSync(any()) }
    }

    @Test fun `清理文档会删除JIT证据关系和缓存`() = runTest {
        seedFile()
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        extractor.extract(refs(), "q", "s")
        testScheduler.runCurrent()
        awaitEdges("s")
        store.clearGraphForDoc("doc")
        assertThat(store.getGraphData().edges).isEmpty()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_jit_cache").use {
            check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0)
        }
    }

    @Test fun `后台写入前文档删除或版本变化均不复活图与缓存`() = runTest {
        seedFile()
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        val result = extractor.extract(refs(), "q", "s")
        assertThat(result.isSuccess).isTrue()
        val file = db.fileEntryDao().findActiveByUuid("doc")!!
        db.kgEvidenceDao().clearDocument("doc")
        db.fileEntryDao().delete(file)
        testScheduler.runCurrent()
        assertThat(store.getGraphData().edges).isEmpty()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_jit_cache").use {
            check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0)
        }
    }

    @Test fun `召回片段版本已过期时禁止持久化JIT证据`() = runTest {
        seedFile()
        val file = db.fileEntryDao().findActiveByUuid("doc")!!
        db.fileEntryDao().update(file.copy(hash = "version-2", updatedAt = 2))
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        assertThat(extractor.extract(refs(), "q", "s").isSuccess).isTrue()
        testScheduler.runCurrent()
        assertThat(store.getGraphData().edges).isEmpty()
        assertThat(db.kgEvidenceDao().affectedBySession("s")).isEmpty()
    }

    @Test fun `模型或提示词变化均不命中原缓存`() = runTest {
        seedFile()
        val first = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        first.extract(refs(), "q", "s")
        testScheduler.runCurrent()
        awaitEdges("s")
        listOf("other::model" to GraphExtractor.DEFAULT_KG_PROMPT, "owner::model" to "新的系统提示词").forEach { (model, prompt) ->
            val extractor = MicroGraphExtractor(testKgClient(protocol), store, db.kgJitCacheDao(), modelId = model,
                systemPrompt = prompt, dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)).also(extractors::add)
            extractor.extract(refs(), "q", "s")
        }
        coVerify(exactly = 3) { protocol.sendPromptSync(any()) }
    }

    @Test fun `联合JIT任一文档版本变化即隐藏该任务但保留独立来源`() = runTest {
        seedFile()
        val original = db.fileEntryDao().findActiveByUuid("doc")!!
        db.fileEntryDao().insert(original.copy(uuid = "other", name = "other.md", materializedPath = "/other.md"))
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        val inputs = refs() + refs().single().copy(id = "other-chunk", docId = "other")
        extractor.extract(inputs, "q", "s")
        testScheduler.runCurrent()
        awaitEdges("s")
        db.fileEntryDao().update(original.copy(hash = "version-2", updatedAt = 2))
        assertThat(store.getGraphData().edges).isEmpty()
        assertThat(store.getGraphData(docIds = listOf("other")).edges).isEmpty()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO kg_collections(id,kind,title,created_at) VALUES('session:independent','session','independent',1)",
        )
        val a = store.upsertNode("Alpha", "person", scope = KgScope(sessionId = "independent"), collectionId = "session:independent")
        val b = store.upsertNode("Beta", "person", scope = KgScope(sessionId = "independent"), collectionId = "session:independent")
        store.createEdge(a, b, "knows", scope = KgScope(sessionId = "independent"), collectionId = "session:independent")
        assertThat(store.getGraphData().edges).hasSize(1)
        assertThat(store.getGraphData(sessionId = "s").edges).isEmpty()
    }

    @Test fun `联合JIT不登记文本预算从未消费的文档`() = runTest {
        seedFile()
        val file = db.fileEntryDao().findActiveByUuid("doc")!!
        db.fileEntryDao().insert(file.copy(uuid = "unused", name = "unused.md", materializedPath = "/unused.md"))
        val extractor = testMicroExtractor(protocol, store, db.kgJitCacheDao()).also(extractors::add)
        val result = extractor.extract(refs() + refs().single().copy(id = "unused-chunk", docId = "unused"), "q", "s", maxChars = 10)
        testScheduler.runCurrent()
        awaitEdges("s")
        assertThat(result.sourceDocumentIds).containsExactly("doc")
        assertThat(store.getGraphData(docIds = listOf("unused")).edges).isEmpty()
    }

    private fun seedBindableWork(id: String) {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES(?,?,?,?,1)", arrayOf(id, "work", id, id))
        sqlite.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES(?,?,'concept',?,1,?)", arrayOf("seed-$id", "seed-$id", "seed-$id", id))
        sqlite.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document) VALUES(?,?,'legacy',?,'','fixture',1,0)", arrayOf("evidence-$id", "seed-$id", "historical-$id"))
    }

    private suspend fun seedFile() = db.fileEntryDao().insert(FileEntry(uuid = "doc", workspaceRootUuid = "root",
        parentUuid = "root", name = "doc.md", hash = "version-1", physicalRootPath = "/tmp/kg-jit", materializedPath = "/doc.md",
        createdAt = 1, updatedAt = 1))

    private suspend fun kotlinx.coroutines.test.TestScope.awaitEdges(sessionId: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            testScheduler.runCurrent()
            if (store.getGraphData(sessionId = sessionId).edges.isNotEmpty()) return
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { delay(10) }
        }
        assertThat(store.getGraphData(sessionId = sessionId).edges).isNotEmpty()
    }

    private fun refs(text: String = "Alpha Beta") = listOf(SearchResult("chunk", docId = "doc", documentContentHash = "version-1", content = text, similarity = 1f, createdAt = 0))

    companion object {
        const val GRAPH = """{"nodes":[{"name":"Alpha","type":"person"},{"name":"Beta","type":"person"}],"edges":[{"source":"Alpha","target":"Beta","relation":"knows"}]}"""
    }
}
