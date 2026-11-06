package com.promenar.infinity.ui.files

import com.promenar.infinity.domain.files.FileGraphState
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.KnowledgeIndexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileRowStatusTest {
    private fun knowledge(state: KnowledgeIndexState, graph: FileGraphState?) = FileKnowledge(state, documentId = "doc", graph = graph)

    @Test fun `索引需要留意时状态位显示索引，图谱状态让位`() {
        listOf(KnowledgeIndexState.FAILED, KnowledgeIndexState.QUEUED, KnowledgeIndexState.STALE, KnowledgeIndexState.INDEXING).forEach { state ->
            assertEquals(FileRowStatus.Index(state), knowledge(state, FileGraphState.FAILED).rowStatus())
        }
    }

    @Test fun `索引正常时显示图谱状态，未抽取与没有图谱不显示`() {
        assertEquals(FileRowStatus.Graph(FileGraphState.EXTRACTED), knowledge(KnowledgeIndexState.INDEXED, FileGraphState.EXTRACTED).rowStatus())
        assertEquals(FileRowStatus.Graph(FileGraphState.CONFIRMATION_REQUIRED), knowledge(KnowledgeIndexState.INDEXED, FileGraphState.CONFIRMATION_REQUIRED).rowStatus())
        assertEquals(FileRowStatus.Graph(FileGraphState.PARTIAL), knowledge(KnowledgeIndexState.NONE, FileGraphState.PARTIAL).rowStatus())
        assertNull(knowledge(KnowledgeIndexState.INDEXED, FileGraphState.NOT_EXTRACTED).rowStatus())
        assertNull(knowledge(KnowledgeIndexState.INDEXED, null).rowStatus())
        assertNull(knowledge(KnowledgeIndexState.UNSUPPORTED, null).rowStatus())
    }

    @Test fun `只有未抽取没有短语`() {
        FileGraphState.entries.forEach { state ->
            assertEquals(state == FileGraphState.NOT_EXTRACTED, graphStatusLabel(state) == null)
        }
    }
}
