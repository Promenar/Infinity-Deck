package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.seedKgNode
import com.promenar.infinity.data.local.db.seedKgEdge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.DocumentTagEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.TagEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DocumentIndexServiceTest {
    private lateinit var database: InfinityDatabase
    private lateinit var checkpointRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        checkpointRoot = File(context.cacheDir, "embedding-test-${java.util.UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        database.close()
        checkpointRoot.deleteRecursively()
    }

    @Test
    fun `候选构建失败时旧索引保持可检索`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database) { throw IllegalStateException("embedding failed") }

        val result = service.rebuild(changed())

        assertThat(result).isInstanceOf(DocumentIndexResult.Failed::class.java)
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(database.vectorDao().searchByKeyword("old searchable").map { it.id })
            .containsExactly(OLD_VECTOR)
    }

    @Test
    fun `提交前模型校验失败时旧索引保留且不清理向量检查点`() = runTest {
        seedFileAndOldArtifacts()
        var cleaned = false
        val service = RoomDocumentIndexService(database) {
            candidate().copy(validateBeforeCommit = { error("向量模型已变化") }, onEmbeddingCommitted = { cleaned = true })
        }
        assertThat(service.rebuild(changed())).isInstanceOf(DocumentIndexResult.Failed::class.java)
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(cleaned).isFalse()
    }

    @Test
    fun `向量检查点仅在事务提交后清理`() = runTest {
        seedFileAndOldArtifacts()
        var statusAtCleanup: String? = null
        val service = RoomDocumentIndexService(database) {
            candidate().copy(onEmbeddingCommitted = {
                statusAtCleanup = database.vectorizationTaskDao().getById(TASK)?.status
            })
        }
        assertThat(service.rebuild(changed())).isInstanceOf(DocumentIndexResult.Rebuilt::class.java)
        assertThat(statusAtCleanup).isEqualTo("completed")
    }

    @Test
    fun `候选清理前图谱与任务完整终态已在同事务提交`() = runTest {
        seedFileAndOldArtifacts()
        var statusAtCleanup: String? = null
        val service = RoomDocumentIndexService(database) {
            candidate().copy(graphExtracted = true, kgProgress = KgExtractionProgress(1, 1), onGraphCommitted = {
                statusAtCleanup = database.vectorizationTaskDao().getById(TASK)?.status
            })
        }

        val result = service.rebuild(changed())

        assertThat(result).isInstanceOf(DocumentIndexResult.Rebuilt::class.java)
        assertThat(statusAtCleanup).isEqualTo("completed")
        val edge = database.kgEdgeDao().getByDocId(FILE).single()
        assertThat(database.kgNodeDao().getById(edge.sourceId)!!.name).isEqualTo("new-source")
        assertThat(database.kgNodeDao().getById(edge.targetId)!!.name).isEqualTo("new-target")
        assertThat(edge.normalizedRelation).isEqualTo("rel")
        val evidence = database.kgEvidenceDao().getForEdges(listOf(edge.id)).single()
        assertThat(evidence.sourceType).isEqualTo("document")
        assertThat(evidence.docId).isEqualTo(FILE)
        assertThat(evidence.contentHash).isEqualTo(HASH)
        assertThat(evidence.taskId).isEqualTo(TASK)
        assertThat(evidence.workspaceDocument).isTrue()
    }

    @Test
    fun `删除关闭admission后迟到候选不能提交派生数据`() = runTest {
        seedFileAndOldArtifacts()
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        val candidateEntered = CompletableDeferred<Unit>()
        val releaseCandidate = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        val service = RoomDocumentIndexService(database, executionGate = gate) {
            candidateEntered.complete(Unit)
            releaseCandidate.await()
            candidate()
        }
        val rebuild = async { service.rebuild(changed()) }
        candidateEntered.await()
        val deletion = async {
            gate.withDeletion("session-1", ROOT) {
                releaseDeletion.await()
                database.vectorDao().deleteByDocId(FILE)
            }
        }
        while (!gate.isDeleting("session-1")) kotlinx.coroutines.yield()

        releaseCandidate.complete(Unit)
        assertThat(rebuild.await()).isInstanceOf(DocumentIndexResult.Failed::class.java)
        releaseDeletion.complete(Unit)
        deletion.await()

        assertThat(database.vectorDao().getByDocId(FILE)).isEmpty()
    }

    @Test
    fun `候选完成后文件hash变化时不切换旧索引`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database) {
            val file = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(file.copy(hash = "hash-newer"))
            candidate()
        }

        val result = service.rebuild(changed())

        assertThat(result).isEqualTo(DocumentIndexResult.HashChanged("hash-newer"))
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_EDGE)
    }

    @Test
    fun `候选完成后epoch变化即使hash相同也拒绝提交`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database) {
            val file = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(file.copy(updatedAt = EPOCH + 1))
            candidate()
        }

        val result = service.rebuild(changed())

        assertThat(result).isEqualTo(DocumentIndexResult.HashChanged(HASH))
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_EDGE)
    }

    @Test
    fun `候选构建开始前拒绝hash相同但epoch过期的事件`() = runTest {
        seedFileAndOldArtifacts()
        val embedding = mockk<EmbeddingClient>()
        val builder = WorkspaceDocumentIndexCandidateBuilder(
            database.fileEntryDao(),
            embedding,
            RagConfiguration(),
        )

        val failure = runCatching {
            builder.build(FileIndexEvent.Changed(ROOT, FILE, HASH, EPOCH + 1))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { embedding.embedDocuments(any()) }
    }

    @Test
    fun `候选构建开始前拒绝已回收文件且不调用embedding`() = runTest {
        seedFileAndOldArtifacts()
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        database.fileEntryDao().update(current.copy(inRecycleBin = true))
        val embedding = mockk<EmbeddingClient>()
        val builder = WorkspaceDocumentIndexCandidateBuilder(
            database.fileEntryDao(),
            embedding,
            RagConfiguration(),
        )

        val failure = runCatching { builder.build(changed()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.FileNotFoundException::class.java)
        coVerify(exactly = 0) { embedding.embedDocuments(any()) }
    }

    @Test
    fun `候选完成后文件进入回收站则提交复核拒绝且派生数据不复活`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database) {
            val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(current.copy(inRecycleBin = true))
            database.vectorDao().deleteByDocId(FILE)
            database.kgEvidenceDao().clearDocument(FILE)
            candidate()
        }

        val result = service.rebuild(changed())

        assertThat(result).isEqualTo(DocumentIndexResult.HashChanged(null))
        assertThat(database.vectorDao().getByDocId(FILE)).isEmpty()
        assertThat(database.kgEdgeDao().getByDocId(FILE)).isEmpty()
        assertThat(database.kgNodeDao().getById("new-source")).isNull()
    }

    @Test
    fun `遗留派生行在文件回收后也不进入vector FTS keyword与KG普通查询`() = runTest {
        seedFileAndOldArtifacts()
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        database.fileEntryDao().update(current.copy(inRecycleBin = true))

        assertThat(database.vectorDao().getAll()).isEmpty()
        assertThat(database.vectorDao().searchByKeyword("old searchable")).isEmpty()
        assertThat(database.vectorDao().searchFts("old")).isEmpty()
        assertThat(database.kgEdgeDao().getByDocId(FILE)).isEmpty()
        assertThat(database.kgNodeDao().getAll()).isEmpty()
    }

    @Test
    fun `仅docId关联的遗留派生行按活动文件隔离而真正全局数据仍可见`() = runTest {
        seedFileAndOldArtifacts()
        database.vectorDao().insert(VectorEntity(
            id = "legacy-vector", docId = FILE, content = "legacy searchable",
            sessionId = "session", embedding = byteArrayOf(0, 0, 0, 0), metadata = "{}", createdAt = 1,
        ))
        database.vectorDao().insert(VectorEntity(
            id = "global-vector", content = "global searchable",
            embedding = byteArrayOf(0, 0, 0, 0), metadata = "{}", createdAt = 1,
        ))
        database.seedKgNode(KgNodeEntity(id = "legacy-a", name = "legacy-a", createdAt = 1), docId = FILE, workspaceDocument = true)
        database.seedKgNode(KgNodeEntity(id = "legacy-b", name = "legacy-b", createdAt = 1), docId = FILE, workspaceDocument = true)
        database.seedKgNode(KgNodeEntity(id = "global-node", name = "global", createdAt = 1))
        database.seedKgEdge(KgEdgeEntity(
            id = "legacy-edge", sourceId = "legacy-a", targetId = "legacy-b",
            relation = "rel", createdAt = 1,
        ) , sessionId = "session", docId = FILE, workspaceDocument = true)

        assertThat(database.vectorDao().getById("legacy-vector")).isNotNull()
        assertThat(database.kgEdgeDao().getById("legacy-edge")).isNotNull()
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        database.fileEntryDao().update(current.copy(inRecycleBin = true))

        assertThat(database.vectorDao().getById("legacy-vector")).isNull()
        assertThat(database.vectorDao().searchByKeyword("legacy searchable")).isEmpty()
        assertThat(database.vectorDao().searchFts("legacy")).isEmpty()
        assertThat(database.kgEdgeDao().getById("legacy-edge")).isNull()
        assertThat(database.kgNodeDao().getById("legacy-a")).isNull()
        assertThat(database.vectorDao().getById("global-vector")).isNotNull()
        assertThat(database.kgNodeDao().getById("global-node")).isNotNull()
    }

    @Test
    fun `候选完成后活动任务目标变化时拒绝旧目标提交`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database) {
            val task = database.vectorizationTaskDao().getById(TASK)!!
            database.vectorizationTaskDao().update(
                task.copy(targetContentHash = "hash-newer", targetEpoch = EPOCH + 1),
            )
            candidate()
        }

        val result = service.rebuild(changed())

        assertThat(result).isEqualTo(DocumentIndexResult.HashChanged(HASH))
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_EDGE)
        assertThat(database.vectorizationTaskDao().getById(TASK)?.targetEpoch).isEqualTo(EPOCH + 1)
    }

    @Test
    fun `旧候选构建中同hash换epoch与newer任务后拒绝提交且保留旧派生数据`() = runTest {
        seedFileAndOldArtifacts()
        val fileDao = database.fileEntryDao()
        val taskDao = database.vectorizationTaskDao()
        val original = fileDao.getByUuid(ROOT, FILE)!!
        fileDao.update(original.copy(vectorizedAt = MARKER, kgExtractedAt = MARKER))
        val candidateStarted = CompletableDeferred<Unit>()
        val releaseCandidate = CompletableDeferred<Unit>()
        val service = RoomDocumentIndexService(database) {
            candidateStarted.complete(Unit)
            releaseCandidate.await()
            candidate()
        }

        val oldRebuild = async { service.rebuild(changed()) }
        candidateStarted.await()
        fileDao.update(fileDao.getByUuid(ROOT, FILE)!!.copy(updatedAt = EPOCH + 1))
        val oldTask = taskDao.getById(TASK)!!
        taskDao.upsertTarget(
            oldTask.copy(status = "pending", targetContentHash = HASH, targetEpoch = EPOCH + 1),
        )
        releaseCandidate.complete(Unit)

        val result = oldRebuild.await()

        assertThat(result).isEqualTo(DocumentIndexResult.HashChanged(HASH))
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_EDGE)
        val current = fileDao.getByUuid(ROOT, FILE)!!
        assertThat(current.updatedAt).isEqualTo(EPOCH + 1)
        assertThat(current.vectorizedAt).isEqualTo(MARKER)
        assertThat(current.kgExtractedAt).isEqualTo(MARKER)
        val active = taskDao.getByWorkspaceFile(ROOT, FILE, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)!!
        assertThat(active.id).isEqualTo(TASK)
        assertThat(active.targetContentHash).isEqualTo(HASH)
        assertThat(active.targetEpoch).isEqualTo(EPOCH + 1)
    }

    @Test
    fun `旧service先提交后同hash换epoch仍能建立newer活动任务`() = runTest {
        seedFileAndOldArtifacts()
        val fileDao = database.fileEntryDao()
        val taskDao = database.vectorizationTaskDao()
        val service = RoomDocumentIndexService(database, now = { EPOCH }) { candidate() }

        val oldResult = service.rebuild(changed())

        assertThat(oldResult).isEqualTo(DocumentIndexResult.Rebuilt(FILE))
        val completedOldTarget = taskDao.getById(TASK)
        assertThat(completedOldTarget).isNotNull()
        assertThat(completedOldTarget!!.targetEpoch).isEqualTo(EPOCH)
        fileDao.update(fileDao.getByUuid(ROOT, FILE)!!.copy(
            updatedAt = EPOCH + 1,
            vectorizedAt = null,
            kgExtractedAt = null,
        ))
        taskDao.upsertTarget(
            completedOldTarget.copy(
                status = "pending",
                targetContentHash = HASH,
                targetEpoch = EPOCH + 1,
            ),
        )

        val active = taskDao.getByWorkspaceFile(ROOT, FILE, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)!!
        assertThat(active.id).isEqualTo(TASK)
        assertThat(active.targetContentHash).isEqualTo(HASH)
        assertThat(active.targetEpoch).isEqualTo(EPOCH + 1)
        val current = fileDao.getByUuid(ROOT, FILE)!!
        assertThat(current.updatedAt).isEqualTo(EPOCH + 1)
        assertThat(current.vectorizedAt).isNull()
        assertThat(current.kgExtractedAt).isNull()
    }

    @Test
    fun `KG已尝试但零节点零边也写入当前epoch完成标记`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database, now = { EPOCH }) {
            DocumentIndexCandidate(vectors = emptyList(), nodes = emptyList(), edges = emptyList())
        }

        val result = service.rebuild(changed().copy(kgStrategy = "full"))

        assertThat(result).isEqualTo(DocumentIndexResult.Rebuilt(FILE))
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        assertThat(current.vectorizedAt).isAtLeast(EPOCH)
        assertThat(current.kgExtractedAt).isAtLeast(EPOCH)
    }

    @Test
    fun `事务中候选写入失败会回滚并保留全部旧派生数据`() = runTest {
        seedFileAndOldArtifacts()
        val invalid = candidate().copy(
            edges = listOf(edge("invalid-edge", "missing-node", "new-target")),
        )
        val service = RoomDocumentIndexService(database) { invalid }

        val result = service.rebuild(changed())

        assertThat(result).isInstanceOf(DocumentIndexResult.Failed::class.java)
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_EDGE)
        assertThat(database.documentTagDao().getByDocId(FILE)).hasSize(1)
        assertThat(database.vectorizationTaskDao().getByDocId(FILE)).hasSize(1)
    }

    @Test
    fun `目标未变时事务切换新索引且不推进文件epoch也不清理队列任务`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database, now = { EPOCH - 1 }) { candidate() }

        val result = service.rebuild(changed())

        assertThat(result).isInstanceOf(DocumentIndexResult.Rebuilt::class.java)
        assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(NEW_VECTOR)
        assertThat(database.vectorDao().searchByKeyword("old searchable")).isEmpty()
        assertThat(database.vectorDao().searchByKeyword("new searchable").map { it.id })
            .containsExactly(NEW_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.relation }).containsExactly("rel")
        assertThat(database.kgEvidenceDao().affectedByDocument(FILE).map { it.taskId }.distinct()).containsExactly(TASK)
        assertThat(database.kgNodeDao().getById("old-source")).isNull()
        assertThat(database.documentTagDao().getByDocId(FILE)).hasSize(1)
        assertThat(database.vectorizationTaskDao().getByDocId(FILE)).hasSize(1)
        assertThat(database.fileEntryDao().getByUuid(ROOT, FILE)?.vectorizedAt).isEqualTo(EPOCH)
        assertThat(database.fileEntryDao().getByUuid(ROOT, FILE)?.updatedAt).isEqualTo(EPOCH)
    }

    @Test
    fun `删除事件事务清理全部派生数据`() = runTest {
        seedFileAndOldArtifacts()
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        database.fileEntryDao().insertAbort(
            current.copy(
                uuid = "other-file",
                name = "other.txt",
                materializedPath = "/other.txt",
            ),
        )
        database.seedKgNode(KgNodeEntity(
            id = "unrelated-orphan", name = "unrelated", createdAt = 1,
        ) , docId = "other-file", workspaceDocument = true)
        val service = RoomDocumentIndexService(database) { error("删除不应构建候选") }

        val result = service.rebuild(FileIndexEvent.Deleted(ROOT, FILE))

        assertThat(result).isInstanceOf(DocumentIndexResult.Deleted::class.java)
        assertThat(database.vectorDao().getByDocId(FILE)).isEmpty()
        assertThat(database.kgEdgeDao().getByDocId(FILE)).isEmpty()
        assertThat(database.kgNodeDao().getById("old-source")).isNull()
        assertThat(database.kgNodeDao().getById("unrelated-orphan")).isNotNull()
        assertThat(database.documentTagDao().getByDocId(FILE)).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(FILE)).isEmpty()
    }

    @Test
    fun `默认候选构建器只读当前hash文件并生成隔离向量`() = runTest {
        seedFileAndOldArtifacts()
        val file = File.createTempFile("infinity-index", ".txt").apply { writeText("candidate text") }
        try {
            val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(current.copy(
                physicalRootPath = file.parentFile!!.absolutePath,
                materializedPath = "/${file.name}",
                hash = com.promenar.infinity.infra.util.Sha256Utils.hash("candidate text"),
            ))
            val embedding = mockIndexEmbedding()
            coEvery { embedding.embedDocuments(any()) } answers {
                EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) })
            }
            val builder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(), embedding, RagConfiguration(docChunkSize = 100, chunkOverlap = 10),
                checkpointRoot = checkpointRoot,
            )
            val hash = database.fileEntryDao().getByUuid(ROOT, FILE)!!.hash

            val candidate = builder.build(FileIndexEvent.Changed(ROOT, FILE, hash, current.updatedAt))

            assertThat(candidate.vectors).isNotEmpty()
            assertThat(candidate.vectors.all { it.docId == FILE && it.fileUuid == FILE }).isTrue()
            assertThat(database.vectorDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_VECTOR)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `跳过向量化的图谱提交不清理已有向量检查点`() = runTest {
        seedFileAndOldArtifacts()
        val file = File.createTempFile("infinity-index-skip", ".txt").apply { writeText("plain") }
        try {
            val hash = com.promenar.infinity.infra.util.Sha256Utils.hash("plain")
            val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(current.copy(
                physicalRootPath = file.parentFile!!.absolutePath,
                materializedPath = "/${file.name}", hash = hash,
            ))
            val checkpoints = EmbeddingCheckpoints(checkpointRoot)
            val identity = EmbeddingCheckpointIdentity(hash, "test-model", 800, 100)
            checkpoints.save(ROOT, FILE, identity, 0, listOf("plain"), listOf(floatArrayOf(1f)))
            val builder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(), mockk(), RagConfiguration(), checkpointRoot = checkpointRoot,
            )
            val candidate = builder.build(FileIndexEvent.Changed(
                ROOT, FILE, hash, current.updatedAt, skipVectorization = true, useConfiguredKgStrategy = false,
            ))
            candidate.onEmbeddingCommitted()
            assertThat(checkpoints.load(ROOT, FILE, identity, 0, listOf("plain"))).isNotNull()
        } finally { file.delete() }
    }

    @Test
    fun `启用知识图谱时候选构建器同时生成隔离节点与边`() = runTest {
        seedFileAndOldArtifacts()
        val file = File.createTempFile("infinity-index-kg", ".txt").apply { writeText("Alice knows Bob") }
        try {
            val hash = com.promenar.infinity.infra.util.Sha256Utils.hash("Alice knows Bob")
            val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(current.copy(
                physicalRootPath = file.parentFile!!.absolutePath,
                materializedPath = "/${file.name}",
                hash = hash,
            ))
            val embedding = mockIndexEmbedding()
            coEvery { embedding.embedDocuments(any()) } returns EmbeddingResult(listOf(floatArrayOf(1f, 0f)))
            val graphRequests = mutableListOf<Triple<String, String, String>>()
            val graphBuilder = KnowledgeGraphCandidateBuilder { text, fileUuid, contentHash ->
                graphRequests += Triple(text, fileUuid, contentHash)
                ExtractionResult(
                    nodes = listOf(ExtractedNode("Alice", "person"), ExtractedNode("Bob", "person")),
                    edges = listOf(ExtractedEdge("Alice", "Bob", "knows")),
                )
            }
            val builder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(),
                embedding,
                RagConfiguration(enableKnowledgeGraph = true, docChunkSize = 100, chunkOverlap = 10),
                graphCandidateBuilder = graphBuilder,
                checkpointRoot = checkpointRoot,
            )

            val candidate = builder.build(FileIndexEvent.Changed(ROOT, FILE, hash, current.updatedAt))

            assertThat(graphRequests).containsExactly(Triple("Alice knows Bob", FILE, hash))
            assertThat(candidate.nodes.map { it.name }).containsExactly("Alice", "Bob")
            assertThat(candidate.edges.single().normalizedRelation).isEqualTo("knows")
            assertThat(database.kgEdgeDao().getByDocId(FILE).map { it.id }).containsExactly(OLD_EDGE)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `图谱失败时完整向量候选不包含半张图并保留旧图`() = runTest {
        seedFileAndOldArtifacts()
        val text = "Alpha knows Beta"
        val file = File.createTempFile("infinity-index-kg-failure", ".txt").apply { writeText(text) }
        try {
            val hash = com.promenar.infinity.infra.util.Sha256Utils.hash(text)
            val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(current.copy(physicalRootPath = file.parentFile!!.absolutePath,
                materializedPath = "/${file.name}", hash = hash))
            val embedding = mockIndexEmbedding()
            coEvery { embedding.embedDocuments(any()) } answers {
                EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) })
            }
            val builder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(), embedding, RagConfiguration(enableKnowledgeGraph = true, docChunkSize = 8, chunkOverlap = 0),
                checkpointRoot = checkpointRoot,
                graphCandidateBuilder = { _, _, _ -> ExtractionResult(
                    listOf(ExtractedNode("Partial", "concept")), emptyList(), "fixture",
                    KgExtractionStatus.PARSE_FAILURE, KgExtractionProgress(1, 2),
                ) },
            )

            val candidate = builder.build(FileIndexEvent.Changed(ROOT, FILE, hash, current.updatedAt))

            assertThat(candidate.vectors).hasSize(2)
            assertThat(candidate.nodes).isEmpty()
            assertThat(candidate.edges).isEmpty()
            assertThat(candidate.preserveExistingGraph).isTrue()
            assertThat(candidate.kgFailure).isEqualTo(KgExtractionStatus.PARSE_FAILURE)
        } finally { file.delete() }
    }

    @Test
    fun `任务选项优先于全局配置且跳过向量与图谱`() = runTest {
        seedFileAndOldArtifacts()
        val file = File.createTempFile("infinity-index-options", ".txt").apply { writeText("plain") }
        try {
            val hash = com.promenar.infinity.infra.util.Sha256Utils.hash("plain")
            val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
            database.fileEntryDao().update(current.copy(
                physicalRootPath = file.parentFile!!.absolutePath,
                materializedPath = "/${file.name}",
                hash = hash,
            ))
            val embedding = mockk<EmbeddingClient>()
            val graphBuilder = mockk<KnowledgeGraphCandidateBuilder>()
            val builder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(), embedding, RagConfiguration(enableKnowledgeGraph = true),
                graphCandidateBuilder = graphBuilder,
            )

            val candidate = builder.build(FileIndexEvent.Changed(
                ROOT, FILE, hash, current.updatedAt, skipVectorization = true,
                kgStrategy = null, useConfiguredKgStrategy = false,
            ))

            assertThat(candidate.vectors).isEmpty()
            assertThat(candidate.nodes).isEmpty()
            coVerify(exactly = 0) { embedding.embedDocuments(any()) }
            coVerify(exactly = 0) { graphBuilder.build(any(), any(), any()) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `活动任务计数排除失败部分与中断仅包含真实运行态`() = runTest {
        seedFileAndOldArtifacts()
        val dao = database.vectorizationTaskDao()
        assertThat(dao.countActiveForFile(ROOT, FILE)).isEqualTo(0)
        dao.insert(VectorizationTaskEntity(
            id = "pending", type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, status = "pending",
            docId = FILE, workspaceRootUuid = ROOT, sourceMimeType = "text/plain", createdAt = 2, updatedAt = 2,
        ))
        assertThat(dao.countActiveForFile(ROOT, FILE)).isEqualTo(1)
    }

    @Test
    fun `恢复查询包含从未索引与内容晚于索引时间的文件`() = runTest {
        seedFileAndOldArtifacts()
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        database.fileEntryDao().update(current.copy(vectorizedAt = 5, updatedAt = 10))

        val recoverable = database.fileEntryDao().getUnvectorizedSupportedFiles(listOf("text/plain"))

        assertThat(recoverable.map { it.uuid }).contains(FILE)
    }

    @Test
    fun `配置启用KG的候选提交持久完成标记且页面重建后仍可读取`() = runTest {
        seedFileAndOldArtifacts()
        val service = RoomDocumentIndexService(database, now = { EPOCH }) {
            DocumentIndexCandidate(listOf(vector(NEW_VECTOR, "new")), graphExtracted = true)
        }
        assertThat(service.rebuild(changed())).isInstanceOf(DocumentIndexResult.Rebuilt::class.java)
        val reloaded = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        assertThat(reloaded.kgExtractedAt).isEqualTo(EPOCH)
    }

    @Test
    fun `手动KG空图事务保留向量和向量完成标记并清理旧关系`() = runTest {
        seedFileAndOldArtifacts()
        val current = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        database.fileEntryDao().update(current.copy(vectorizedAt = 123))
        val service = RoomDocumentIndexService(database, now = { EPOCH }) {
            DocumentIndexCandidate(emptyList(), graphExtracted = true)
        }
        val result = service.rebuild(changed().copy(skipVectorization = true, kgStrategy = "full"))
        assertThat(result).isInstanceOf(DocumentIndexResult.Rebuilt::class.java)
        assertThat(database.vectorDao().getByDocId(FILE).single().id).isEqualTo(OLD_VECTOR)
        assertThat(database.kgEdgeDao().getByDocId(FILE)).isEmpty()
        val after = database.fileEntryDao().getByUuid(ROOT, FILE)!!
        assertThat(after.vectorizedAt).isEqualTo(123)
        assertThat(after.kgExtractedAt).isEqualTo(EPOCH)
    }

    private suspend fun seedFileAndOldArtifacts() {
        database.sessionDao().insert(SessionEntity("session", "__system__", "session", workspaceRootUuid = ROOT, createdAt = 1, updatedAt = 1))
        database.fileEntryDao().insert(FileEntry(
            uuid = ROOT, workspaceRootUuid = ROOT, parentUuid = null, name = "root", hash = "root-hash",
            isDirectory = true, physicalRootPath = "/tmp", materializedPath = "/", createdAt = 1, updatedAt = 1,
        ))
        database.fileEntryDao().insert(FileEntry(
            uuid = FILE, workspaceRootUuid = ROOT, parentUuid = ROOT, name = "doc.txt", hash = HASH,
            mimeType = "text/plain", sizeBytes = 3, physicalRootPath = "/tmp", materializedPath = "/doc.txt",
            createdAt = 1, updatedAt = EPOCH,
        ))
        database.vectorDao().insert(vector(OLD_VECTOR, "old searchable"))
        database.seedKgNode(node("old-source"), docId = FILE, workspaceDocument = true)
        database.seedKgNode(node("old-target"), docId = FILE, workspaceDocument = true)
        database.seedKgEdge(edge(OLD_EDGE, "old-source", "old-target"), docId = FILE, workspaceDocument = true)
        database.tagDao().insert(TagEntity("tag", "tag", createdAt = 1))
        database.documentTagDao().insert(DocumentTagEntity(FILE, "tag", 1))
        database.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = TASK, type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, status = "failed",
            docId = FILE, workspaceRootUuid = ROOT, sourceMimeType = "text/plain",
            targetContentHash = HASH, targetEpoch = EPOCH, createdAt = 1, updatedAt = 1,
        ))
    }

    private fun changed() = FileIndexEvent.Changed(
        ROOT,
        FILE,
        HASH,
        EPOCH,
        activeTaskId = TASK,
    )

    private fun candidate() = DocumentIndexCandidate(
        vectors = listOf(vector(NEW_VECTOR, "new searchable")),
        nodes = listOf(node("new-source"), node("new-target")),
        edges = listOf(edge(NEW_EDGE, "new-source", "new-target")),
    )

    private fun mockIndexEmbedding(): EmbeddingClient = mockk<EmbeddingClient>().also { client ->
        every { client.frozenForIndex() } returns client
        every { client.vectorSpaceKey() } returns "test-model"
        every { client.localModelGeneration() } returns null
    }

    private fun vector(id: String, content: String) = VectorEntity(
        id = id, docId = FILE, content = content, embedding = byteArrayOf(0, 0, 0, 0),
        metadata = "{\"type\":\"document\"}", createdAt = 1, fileUuid = FILE,
    )

    private fun node(id: String) = KgNodeEntity(
        id = id, name = id, createdAt = 1,
    )

    private fun edge(id: String, source: String, target: String) = KgEdgeEntity(
        id = id, sourceId = source, targetId = target, relation = "rel",
        createdAt = 1,
    )

    private companion object {
        const val ROOT = "root"
        const val FILE = "file"
        const val HASH = "hash-v1"
        const val EPOCH = 10L
        const val MARKER = 7L
        const val TASK = "task"
        const val OLD_VECTOR = "old-vector"
        const val NEW_VECTOR = "new-vector"
        const val OLD_EDGE = "old-edge"
        const val NEW_EDGE = "new-edge"
    }
}
