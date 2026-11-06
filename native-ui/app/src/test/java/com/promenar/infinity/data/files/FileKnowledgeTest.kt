package com.promenar.infinity.data.files

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.domain.files.FileGraphState
import com.promenar.infinity.data.library.graphStateFor
import com.promenar.infinity.domain.library.PendingStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileKnowledgeTest {
    private val file = FileEntry(uuid = "file", workspaceRootUuid = "root", parentUuid = "root", name = "note.txt", hash = "hash",
        mimeType = "text/plain", physicalRootPath = "/fictional", materializedPath = "/note.txt", createdAt = 1, updatedAt = 10)
    private fun task(status: String) = VectorizationTaskEntity(id = "task", type = "document_reference", status = status,
        docId = "file", workspaceRootUuid = "root", error = "失败", createdAt = 1, updatedAt = 2)

    @Test fun `队列失败和已索引状态按来源判断`() {
        assertEquals(KnowledgeIndexState.QUEUED, knowledgeOf(file, emptyList(), pending = true).state)
        assertEquals(KnowledgeIndexState.INDEXING, knowledgeOf(file, listOf(task("vectorizing"))).state)
        assertEquals(KnowledgeIndexState.FAILED, knowledgeOf(file, listOf(task("failed"))).state)
        assertEquals(KnowledgeIndexState.INDEXED, knowledgeOf(file.copy(vectorizedAt = 11), emptyList()).state)
        assertEquals(KnowledgeIndexState.STALE, knowledgeOf(file.copy(vectorizedAt = 9), emptyList()).state)
        assertEquals(KnowledgeIndexState.UNSUPPORTED, knowledgeOf(file.copy(name = "image.png", mimeType = "image/png"), emptyList()).state)
        assertNull(knowledgeOf(file.copy(isDirectory = true), emptyList()).documentId)
    }

    @Test fun `索引中带阶段与进度，准备与写入阶段不带总数`() {
        val vectorizing = knowledgeOf(file, listOf(task("vectorizing").copy(lastChunkIndex = 3, totalChunks = 8)))
        assertEquals(PendingStage.VECTORIZING, vectorizing.stage)
        assertEquals(3, vectorizing.processed)
        assertEquals(8, vectorizing.total)
        val saving = knowledgeOf(file, listOf(task("saving").copy(lastChunkIndex = 8, totalChunks = 8)))
        assertEquals(PendingStage.SAVING, saving.stage)
        assertNull(saving.total)
        assertEquals(PendingStage.PREPARING, knowledgeOf(file, listOf(task("chunking"))).stage)
        assertEquals(PendingStage.EXTRACTING_GRAPH, knowledgeOf(file, listOf(task("extracting"))).stage)
        assertNull(knowledgeOf(file, emptyList(), pending = true).stage)
    }
    @Test fun `图谱状态优先级与时间判断`() {
        assertEquals(FileGraphState.NOT_EXTRACTED, graphStateFor(null, 10, null))
        assertEquals(FileGraphState.NOT_EXTRACTED, graphStateFor(9, 10, null))
        assertEquals(FileGraphState.EXTRACTED, graphStateFor(10, 10, null))
        assertEquals(FileGraphState.CONFIRMATION_REQUIRED, graphStateFor(11, 10, "kg.confirmation_required"))
        assertEquals(FileGraphState.PARTIAL, graphStateFor(11, 10, "kg.budget.calls"))
        assertEquals(FileGraphState.FAILED, graphStateFor(11, 10, "kg.failed.parse"))
    }

    @Test fun `只有图谱未完成的任务不算解析失败，真正的失败仍是解析失败`() {
        val indexed = file.copy(vectorizedAt = 11)
        listOf("kg.confirmation_required", "kg.budget.calls", "kg.failed.timeout").forEach { error ->
            assertEquals(error, KnowledgeIndexState.INDEXED, knowledgeOf(indexed, listOf(task("partial").copy(error = error))).state)
        }
        assertEquals(KnowledgeIndexState.FAILED, knowledgeOf(indexed, listOf(task("partial"))).state)
        assertEquals(KnowledgeIndexState.FAILED, knowledgeOf(indexed, listOf(task("failed").copy(error = "kg.failed"))).state)
    }

}
