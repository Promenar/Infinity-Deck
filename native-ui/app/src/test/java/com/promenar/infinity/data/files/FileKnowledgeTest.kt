package com.promenar.infinity.data.files

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.domain.files.KnowledgeIndexState
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
}
