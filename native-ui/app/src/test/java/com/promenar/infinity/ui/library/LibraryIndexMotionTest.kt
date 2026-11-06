package com.promenar.infinity.ui.library

import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.ui.common.motion.IndexMotionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexMotionTest {
    private fun doc(
        kind: PendingKind,
        stage: PendingStage? = null,
        processed: Int? = null,
        total: Int? = null,
        lastError: String? = null,
        graphConfirmation: Boolean = false,
    ) = PendingDocument(
        documentId = "doc-1",
        ref = FileRef("kb", "/示例.txt"),
        name = "示例.txt",
        folderPath = "存储空间",
        kind = kind,
        stage = stage,
        processed = processed,
        total = total,
        lastError = lastError,
        graphConfirmation = graphConfirmation,
    )

    @Test
    fun `已改动待重建的文档不显示索引指示`() {
        assertNull(doc(PendingKind.STALE).indexMotionState())
    }

    @Test
    fun `向量化按已处理与总数给出进度，有最近错误时显示重试等待`() {
        val running = doc(PendingKind.INDEXING, PendingStage.VECTORIZING, processed = 3, total = 12).indexMotionState()!!
        assertEquals(IndexMotionPhase.VECTORIZING, running.phase)
        assertEquals(0.25f, running.vectorProgress, 1e-6f)
        val retrying = doc(PendingKind.INDEXING, PendingStage.VECTORIZING, processed = 3, total = 12, lastError = "限流").indexMotionState()!!
        assertEquals(IndexMotionPhase.RETRYING, retrying.phase)
    }

    @Test
    fun `抽图谱时向量视为完成，图谱进度取当前阶段`() {
        val state = doc(PendingKind.INDEXING, PendingStage.EXTRACTING_GRAPH, processed = 2, total = 8).indexMotionState()!!
        assertEquals(IndexMotionPhase.EXTRACTING_GRAPH, state.phase)
        assertEquals(1f, state.vectorProgress, 0f)
        assertEquals(0.25f, state.graphProgress, 1e-6f)
        assertTrue(state.withGraph)
    }

    @Test
    fun `没有总数时进度按零处理，越界的处理数被截断`() {
        assertEquals(0f, doc(PendingKind.INDEXING, PendingStage.VECTORIZING, processed = 5, total = null).indexMotionState()!!.vectorProgress, 0f)
        assertEquals(1f, doc(PendingKind.INDEXING, PendingStage.VECTORIZING, processed = 20, total = 12).indexMotionState()!!.vectorProgress, 0f)
        assertEquals(IndexMotionPhase.PREPARING, doc(PendingKind.INDEXING, stage = null).indexMotionState()!!.phase)
    }

    @Test
    fun `图谱待确认与图谱失败分别对应确认与失败，向量都已完成`() {
        val confirm = doc(PendingKind.GRAPH_ATTENTION, graphConfirmation = true).indexMotionState()!!
        assertEquals(IndexMotionPhase.NEEDS_CONFIRMATION, confirm.phase)
        assertEquals(1f, confirm.vectorProgress, 0f)
        val failed = doc(PendingKind.GRAPH_ATTENTION, processed = 3, total = 6).indexMotionState()!!
        assertEquals(IndexMotionPhase.FAILED, failed.phase)
        assertEquals(1f, failed.vectorProgress, 0f)
        assertEquals(0.5f, failed.graphProgress, 1e-6f)
        val indexFailed = doc(PendingKind.FAILED).indexMotionState()!!
        assertEquals(IndexMotionPhase.FAILED, indexFailed.phase)
        assertEquals(0f, indexFailed.vectorProgress, 0f)
    }

    @Test
    fun graphRowUsesLiveWindowCountsWhileExtracting() {
        val live = doc(PendingKind.INDEXING, PendingStage.EXTRACTING_GRAPH, processed = 4, total = 7)
        assertEquals(4 to 7, liveGraphProgress(0, 7, live))
    }

    @Test
    fun graphRowFallsBackToSnapshotOutsideGraphStage() {
        val vectorizing = doc(PendingKind.INDEXING, PendingStage.VECTORIZING, processed = 30, total = 136)
        assertEquals(2 to 7, liveGraphProgress(2, 7, vectorizing))
        assertEquals(2 to 7, liveGraphProgress(2, 7, null))
    }

    @Test
    fun graphRowShowsAllWindowsDoneWhileSavingAfterExtraction() {
        // 提交阶段的待处理项不带总数，快照仍停在打开页面时的 0 / 13。
        val saving = doc(PendingKind.INDEXING, PendingStage.SAVING)
        assertEquals(13 to 13, liveGraphProgress(0, 13, saving))
    }

    @Test
    fun graphRowHidesCountsWithoutTotalAndClampsOverflow() {
        assertNull(liveGraphProgress(0, null, null))
        assertNull(liveGraphProgress(0, 0, doc(PendingKind.INDEXING, PendingStage.EXTRACTING_GRAPH, processed = 0, total = 0)))
        assertEquals(7 to 7, liveGraphProgress(0, 7, doc(PendingKind.INDEXING, PendingStage.EXTRACTING_GRAPH, processed = 9, total = 7)))
    }
}
