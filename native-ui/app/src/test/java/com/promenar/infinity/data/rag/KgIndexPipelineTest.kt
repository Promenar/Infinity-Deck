package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.seedKgNode
import com.promenar.infinity.data.local.db.seedKgEdge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.ui.common.KgStatus
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KgIndexPipelineTest {
    private lateinit var database: InfinityDatabase
    private lateinit var root: File
    private val embedding = mockk<EmbeddingClient>()

    @Before
    fun setup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // JVM 套件没有打包 Android assets，读取当前依赖自身的字体资源，解析器保持真实实现。
        val classResource = requireNotNull(PDDocument::class.java.getResource("PDDocument.class"))
        val dependencyJar = File((classResource.openConnection() as java.net.JarURLConnection).jarFileURL.toURI())
        val transforms = generateSequence(dependencyJar.parentFile) { it.parentFile }.first { it.name == "transforms" }
        val assetsRoot = transforms.listFiles().orEmpty().asSequence()
            .flatMap { File(it, "transformed").listFiles().orEmpty().asSequence() }
            .filter { it.name.contains("pdfbox") }
            .map { File(it, "assets") }
            .first { File(it, "com/tom_roush/pdfbox/resources/afm/Times-Roman.afm").isFile }
        val assets = mockk<android.content.res.AssetManager>()
        every { assets.open(any()) } answers { File(assetsRoot, firstArg<String>()).inputStream() }
        val resourcesContext = mockk<Context>()
        every { resourcesContext.applicationContext } returns resourcesContext
        every { resourcesContext.assets } returns assets
        PDFBoxResourceLoader.init(resourcesContext)
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        root = Files.createTempDirectory("kg-index-pipeline").toFile()
        database.sessionDao().insert(SessionEntity("session", "__system__", "session", workspaceRootUuid = "root", createdAt = 1, updatedAt = 1))
        database.fileEntryDao().insert(FileEntry(
            uuid = "root", workspaceRootUuid = "root", parentUuid = null, name = "root", hash = "",
            isDirectory = true, physicalRootPath = root.path, materializedPath = "/", createdAt = 1, updatedAt = 1,
        ))
        coEvery { embedding.embedDocuments(any()) } answers { EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) }) }

        every { embedding.frozenForIndex() } returns embedding
        every { embedding.vectorSpaceKey() } returns "test-space"
        every { embedding.localModelGeneration() } returns null
    }

    @After
    fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun `文档清理不删除其它任务尚未连边的节点`() = runTest {
        val file = register("cleanup.txt", "text/plain", "text".toByteArray())
        seedOld(file)
        database.seedKgNode(KgNodeEntity("pending-other", "Other task", createdAt = 1))
        GraphStore(database).clearGraphForDoc(file.uuid)
        assertThat(database.kgNodeDao().getById("pending-other")).isNotNull()
        assertThat(database.kgEdgeDao().getByDocId(file.uuid)).isEmpty()
    }

    @Test
    fun `重复归并置信度不累加越界`() = runTest {
        val file = register("weight.txt", "text/plain", "text".toByteArray())
        seedOld(file)
        val edge = database.kgEdgeDao().getByDocId(file.uuid).single()
        GraphStore(database).createEdge(edge.sourceId, edge.targetId, edge.relation, file.uuid, 0.9)
        GraphStore(database).createEdge(edge.sourceId, edge.targetId, edge.relation, file.uuid, 0.8)
        assertThat(database.kgEdgeDao().getByDocId(file.uuid).single().weight).isEqualTo(0.9)
    }

    @Test
    fun `全量合法空图仍完成向量事务并持久标记`() = runTest {
        val file = register("empty.txt", "text/plain", "no relations".toByteArray())
        seedOld(file)
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse("""{"nodes":[],"edges":[]}""")
        val graph = testGraphExtractor(protocol, GraphStore(database), checkpointRoot = File(root, "checkpoints"))
        val (queue, _) = pipeline(GraphExtractorKnowledgeGraphCandidateBuilder(graph))
        enqueue(queue, file, skipVectorization = false)
        advanceUntilIdle()
        queue.awaitSettled()

        assertThat(database.vectorizationTaskDao().getByDocId(file.uuid)).isEmpty()
        assertThat(database.vectorDao().getByDocId(file.uuid).single().content).isEqualTo("no relations")
        assertThat(database.kgEdgeDao().getByDocId(file.uuid)).isEmpty()
        assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.kgExtractedAt).isAtLeast(file.updatedAt)
        queue.shutdown()
    }

    @Test
    fun `自动大文档先完成向量索引且不绕过KG确认或清空旧图`() = runTest {
        val file = register("auto-large.txt", "text/plain", "a".repeat(128_001).toByteArray())
        seedOld(file)
        var graphCalls = 0
        val (queue, _) = pipeline { _, _, _ ->
            graphCalls++
            ExtractionResult(emptyList(), emptyList())
        }
        enqueue(queue, file, skipVectorization = false)
        advanceUntilIdle()
        queue.awaitSettled()

        assertThat(graphCalls).isEqualTo(0)
        assertThat(database.vectorDao().getByDocId(file.uuid)).isNotEmpty()
        assertThat(database.kgEdgeDao().getByDocId(file.uuid).single().id).isEqualTo("edge-${file.uuid}")
        val task = database.vectorizationTaskDao().getByDocId(file.uuid).single()
        assertThat(task.status).isEqualTo("partial")
        assertThat(task.lastChunkIndex).isEqualTo(0)
        assertThat(task.totalChunks).isEqualTo(9)
        assertThat(task.subStatus).contains("已处理 0 / 9 分块")
        assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.kgExtractedAt).isEqualTo(1)
        queue.shutdown()
    }

    @Test
    fun `预算耗尽保留旧图和进度`() = runTest {
        val file = register("partial.txt", "text/plain", "Alpha knows Beta".toByteArray())
        seedOld(file)
        val (queue, _) = pipeline { _, _, _ ->
            ExtractionResult(
                listOf(ExtractedNode("Alpha", "person"), ExtractedNode("Beta", "person")),
                listOf(ExtractedEdge("Alpha", "Beta", "knows")),
                status = KgExtractionStatus.PARTIAL,
                progress = KgExtractionProgress(2, 5, KgExtractionStopReason.CALLS, calls = 2),
            )
        }
        enqueue(queue, file)
        advanceUntilIdle()
        queue.awaitSettled()

        val task = database.vectorizationTaskDao().getByDocId(file.uuid).single()
        assertThat(task.status).isEqualTo("partial")
        assertThat(task.lastChunkIndex).isEqualTo(2)
        assertThat(task.totalChunks).isEqualTo(5)
        assertThat(task.progress).isEqualTo(40.0)
        assertThat(database.kgEdgeDao().getByDocId(file.uuid).single().id).isEqualTo("edge-${file.uuid}")
        assertThat(database.vectorDao().getByDocId(file.uuid).single().id).isEqualTo("old-${file.uuid}")
        val persisted = database.fileEntryDao().getByUuid("root", file.uuid)!!
        assertThat(persisted.kgExtractedAt).isEqualTo(1)
        queue.shutdown()
    }

    @Test
    fun `图谱失败仍提交向量并保留旧图谱和可重试任务`() = runTest {
        val file = register("graph-failure.txt", "text/plain", "Alpha knows Beta".toByteArray())
        seedOld(file)
        var fail = true
        val (queue, _) = pipeline { _, _, _ ->
            if (fail) ExtractionResult(
                listOf(ExtractedNode("Partial", "concept")), emptyList(), "内部请求详情",
                KgExtractionStatus.PROVIDER_ERROR, progress = KgExtractionProgress(1, 2),
            ) else ExtractionResult(emptyList(), emptyList())
        }
        enqueue(queue, file, skipVectorization = false)
        advanceUntilIdle()
        queue.awaitSettled()

        val task = database.vectorizationTaskDao().getByDocId(file.uuid).single()
        assertThat(task.status).isEqualTo("partial")
        assertThat(task.error).startsWith(VectorizationQueue.KG_FAILED)
        assertThat(task.subStatus).doesNotContain("内部请求详情")
        assertThat(database.vectorDao().getByDocId(file.uuid).single().content).isEqualTo("Alpha knows Beta")
        assertThat(database.kgEdgeDao().getByDocId(file.uuid).single().id).isEqualTo("edge-${file.uuid}")
        assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.vectorizedAt).isNotNull()

        fail = false
        assertThat(queue.retryDocumentReference("root", file.uuid)).isTrue()
        advanceUntilIdle()
        queue.awaitSettled()
        coVerify(exactly = 1) { embedding.embedDocuments(any()) }
        queue.shutdown()
    }

    @Test
    fun `完整候选事务回滚后重试复用分块且不重复消费文档预算`() = runTest {
        val file = register("rollback-budget.txt", "text/plain", "a".repeat(25).toByteArray())
        seedOld(file)
        val protocol = mockk<LlmProtocol>()
        var requests = 0
        coEvery { protocol.sendPromptSync(any()) } answers {
            requests++
            PromptResponse("""{"nodes":[{"name":"Alpha","type":"person"},{"name":"Beta","type":"person"}],"edges":[{"source":"Alpha","target":"Beta","relation":"knows"}]}""",
                usage = com.promenar.infinity.data.remote.protocol.ProtocolUsage(5, 2))
        }
        val extractor = GraphExtractor(testKgClient(protocol), GraphStore(database), "owner::model",
            chunkSize = 10, chunkOverlap = 0, checkpointRoot = File(root, "rollback-checkpoints"),
            dispatcher = StandardTestDispatcher(testScheduler), budget = KgExtractionBudget(maxCalls = 3), concurrency = 1, gleaningPasses = 0,
            nowMs = { testScheduler.currentTime })
        val builder = WorkspaceDocumentIndexCandidateBuilder(database.fileEntryDao(), embedding, RagConfiguration(),
            dispatcher = StandardTestDispatcher(testScheduler), checkpointRoot = File(root, "embedding-checkpoints"), graphCandidateBuilder = GraphExtractorKnowledgeGraphCandidateBuilder(extractor))
        var failCommit = true
        val service = RoomDocumentIndexService(database) { event ->
            builder.build(event).let { candidate ->
                if (failCommit) candidate.copy(edges = listOf(KgEdgeEntity("bad", "missing", "missing2", "knows", createdAt = 1)))
                else candidate
            }
        }
        val queue = queue(service)
        enqueue(queue, file)
        advanceUntilIdle()
        queue.awaitSettled()
        assertThat(database.vectorizationTaskDao().getByDocId(file.uuid).single().status).isEqualTo("failed")
        assertThat(requests).isEqualTo(3)

        failCommit = false
        assertThat(queue.retryDocumentReference("root", file.uuid)).isTrue()
        advanceUntilIdle()
        queue.awaitSettled()

        assertThat(requests).isEqualTo(3)
        assertThat(database.kgEdgeDao().getByDocId(file.uuid)).hasSize(1)
        assertThat(database.vectorizationTaskDao().getByDocId(file.uuid)).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `手动PDF与DOCX和大文本经过真实解析器且保留向量`() = runTest {
        val received = mutableMapOf<String, String>()
        val (queue, _) = pipeline { text, id, _ ->
            received[id] = text
            ExtractionResult(emptyList(), emptyList())
        }
        val pdf = File(root, "source.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(20f, 700f); stream.showText("PDF Alpha knows Beta"); stream.endText()
            }
            document.save(pdf)
        }
        val docx = File(root, "source.docx")
        XWPFDocument().use { document ->
            document.createParagraph().createRun().setText("DOCX Alpha knows Beta")
            docx.outputStream().use { document.write(it) }
        }
        val files = listOf(
            register("read.pdf", "application/pdf", pdf.readBytes()),
            register("read.docx", DocumentReferenceExtractor.DOCX_MIME, docx.readBytes()),
            register("large.txt", "text/plain", ("a".repeat(300 * 1024) + "END").toByteArray()),
        )
        for (file in files) { seedOld(file); enqueue(queue, file, confirmed = true) }
        advanceUntilIdle()
        queue.awaitSettled()

        assertThat(received["read.pdf"]).contains("PDF Alpha knows Beta")
        assertThat(received["read.docx"]).contains("DOCX Alpha knows Beta")
        assertThat(received["large.txt"]!!.length).isEqualTo(300 * 1024 + 3)
        assertThat(received["large.txt"]).endsWith("END")
        files.forEach { file ->
            assertThat(database.vectorDao().getByDocId(file.uuid).single().id).isEqualTo("old-${file.uuid}")
            assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.kgExtractedAt).isNotNull()
        }
        coVerify(exactly = 0) { embedding.embedDocuments(any()) }
        queue.shutdown()
    }

    @Test
    fun `删除屏障等待不响应取消的迟到回片且文档关系不复活`() = runTest {
        val file = register("deleted.txt", "text/plain", "Alpha Beta".toByteArray())
        seedOld(file)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val (queue, service) = pipeline { _, _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            ExtractionResult(listOf(ExtractedNode("Alpha", "person"), ExtractedNode("Beta", "person")), listOf(ExtractedEdge("Alpha", "Beta", "knows")))
        }
        enqueue(queue, file)
        runCurrent()
        entered.await()
        val barrier = queue.acquireDeleteBarrier("root", listOf(file.uuid))
        val deletion = async {
            barrier.awaitReady()
            service.delete("root", file.uuid)
            database.fileEntryDao().delete(file)
            barrier.commit()
        }
        runCurrent()
        assertThat(deletion.isCompleted).isFalse()
        release.complete(Unit)
        advanceUntilIdle()
        queue.awaitSettled()
        deletion.await()

        assertThat(database.kgEvidenceDao().affectedByDocument(file.uuid)).isEmpty()
        assertThat(database.vectorDao().getByDocId(file.uuid)).isEmpty()
        assertThat(database.kgNodeDao().getAll()).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(file.uuid)).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `同内容正在向量化时手动KG请求升级活动任务且旧候选不能提交`() = runTest {
        val file = register("upgrade.txt", "text/plain", "Alpha Beta".toByteArray())
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { embedding.embedDocuments(any()) } coAnswers {
            if (++calls == 1) { entered.complete(Unit); awaitCancellation() }
            EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) })
        }
        var graphCalls = 0
        val (queue, _) = pipeline { _, _, _ ->
            graphCalls++
            ExtractionResult(listOf(ExtractedNode("Alpha", "person"), ExtractedNode("Beta", "person")), listOf(ExtractedEdge("Alpha", "Beta", "knows")))
        }
        val oldId = queue.enqueueDocumentReference("root", file.uuid, file.name, file.mimeType!!, file.hash, file.updatedAt)
        runCurrent()
        entered.await()
        val newId = enqueue(queue, file)
        assertThat(newId).isNotEqualTo(oldId)
        advanceUntilIdle()
        queue.awaitSettled()
        assertThat(graphCalls).isEqualTo(1)
        assertThat(database.vectorDao().getByDocId(file.uuid)).hasSize(1)
        assertThat(database.kgEdgeDao().getByDocId(file.uuid)).hasSize(1)
        assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.kgExtractedAt).isNotNull()
        queue.shutdown()
    }

    @Test
    fun `失败展示期间立即重抽会建立新活动任务并执行`() = runTest {
        val file = register("retry.txt", "text/plain", "Alpha Beta".toByteArray())
        val (_, goodService) = pipeline { _, _, _ -> ExtractionResult(emptyList(), emptyList()) }
        var calls = 0
        val service = object : DocumentIndexService by goodService {
            override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult =
                if (++calls == 1) DocumentIndexResult.Failed(IllegalStateException("storage unavailable")) else goodService.rebuild(event)
        }
        val queue = queue(service)
        val failureShown = CompletableDeferred<Unit>()
        queue.beforeFailureDisplayDelayForTest = { failureShown.complete(Unit); awaitCancellation() }
        val oldId = enqueue(queue, file)
        runCurrent()
        failureShown.await()
        val newId = enqueue(queue, file)
        assertThat(newId).isNotEqualTo(oldId)
        advanceUntilIdle()
        queue.awaitSettled()
        assertThat(calls).isEqualTo(2)
        assertThat(database.vectorizationTaskDao().getByDocId(file.uuid)).isEmpty()
        assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.kgExtractedAt).isNotNull()
        queue.shutdown()
    }

    @Test
    fun `事务写库失败保留旧图与完成标记且队列报告失败`() = runTest {
        val file = register("failed.txt", "text/plain", "Alpha Beta".toByteArray())
        seedOld(file)
        val builder = WorkspaceDocumentIndexCandidateBuilder(database.fileEntryDao(), embedding, RagConfiguration(),
            dispatcher = StandardTestDispatcher(testScheduler), checkpointRoot = File(root, "embedding-checkpoints"), graphCandidateBuilder = { _, _, _ -> ExtractionResult(emptyList(), emptyList()) })
        val service = RoomDocumentIndexService(database) { event ->
            builder.build(event).copy(edges = listOf(KgEdgeEntity("bad", "missing", "missing2", "knows", weight = 0.7, createdAt = 1)))
        }
        val queue = queue(service)
        enqueue(queue, file)
        advanceUntilIdle()
        queue.awaitSettled()

        assertThat(database.vectorizationTaskDao().getByDocId(file.uuid).single().status).isEqualTo("failed")
        assertThat(database.kgEdgeDao().getByDocId(file.uuid).single().id).isEqualTo("edge-${file.uuid}")
        assertThat(database.fileEntryDao().getByUuid("root", file.uuid)!!.kgExtractedAt).isEqualTo(1)
        queue.shutdown()
    }

    private fun TestScope.pipeline(graph: KnowledgeGraphCandidateBuilder): Pair<VectorizationQueue, DocumentIndexService> {
        val builder = WorkspaceDocumentIndexCandidateBuilder(database.fileEntryDao(), embedding, RagConfiguration(docChunkSize = 800, chunkOverlap = 100),
            dispatcher = StandardTestDispatcher(testScheduler), checkpointRoot = File(root, "embedding-checkpoints"), graphCandidateBuilder = graph)
        val service = RoomDocumentIndexService(database, candidateBuilder = builder)
        return queue(service) to service
    }

    private fun TestScope.queue(service: DocumentIndexService) = VectorizationQueue(
        VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao(), database.kgEvidenceDao()), embedding, null,
        database.vectorDao(), database.vectorizationTaskDao(), dispatcher = StandardTestDispatcher(testScheduler),
        fileEntryDao = database.fileEntryDao(), documentIndexService = service,
    )

    /** 排空虚拟调度器后还须等待真实 IO 清理与队列收尾。 */
    private suspend fun VectorizationQueue.awaitSettled() {
        state.first { snapshot ->
            !snapshot.isProcessing && snapshot.queue.all { it.status in setOf("completed", "partial", "failed", "interrupted") }
        }
    }

    private suspend fun enqueue(queue: VectorizationQueue, file: FileEntry, skipVectorization: Boolean = true, confirmed: Boolean = false) =
        queue.enqueueDocumentReference("root", file.uuid, file.name, file.mimeType!!, file.hash, file.updatedAt,
            if (confirmed) KgExtractionBudget.CONFIRMED_STRATEGY else "full", skipVectorization)

    private suspend fun register(name: String, mime: String, bytes: ByteArray): FileEntry {
        File(root, name).writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val file = FileEntry(uuid = name, workspaceRootUuid = "root", parentUuid = "root", name = name, hash = hash,
            mimeType = mime, sizeBytes = bytes.size.toLong(), physicalRootPath = root.path, materializedPath = "/$name", createdAt = 1, updatedAt = 2)
        database.fileEntryDao().insert(file)
        return file
    }

    private suspend fun seedOld(file: FileEntry) {
        database.vectorDao().insert(VectorEntity(id = "old-${file.uuid}", docId = file.uuid, content = "old", embedding = ByteArray(8), createdAt = 1, fileUuid = file.uuid))
        database.seedKgNode(KgNodeEntity("a-${file.uuid}", "Old Alpha ${file.uuid}", createdAt = 1) , docId = file.uuid, workspaceDocument = true)
        database.seedKgNode(KgNodeEntity("b-${file.uuid}", "Old Beta ${file.uuid}", createdAt = 1) , docId = file.uuid, workspaceDocument = true)
        database.seedKgEdge(KgEdgeEntity("edge-${file.uuid}", "a-${file.uuid}", "b-${file.uuid}", "knows", weight = 0.7, createdAt = 1) , docId = file.uuid, workspaceDocument = true)
        database.fileEntryDao().update(file.copy(vectorizedAt = 1, kgExtractedAt = 1))
    }
}
