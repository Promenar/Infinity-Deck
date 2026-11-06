package com.promenar.infinity.ui.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.ui.common.motion.IndexMotionPhase
import com.promenar.infinity.ui.common.motion.IndexMotionState
import org.junit.Test

class FileKnowledgeMotionTest {
    @Test fun `排队与索引中给出与资料首页同款的索引指示状态`() {
        assertThat(FileKnowledge(KnowledgeIndexState.QUEUED).indexMotionState()).isEqualTo(IndexMotionState(IndexMotionPhase.QUEUED))
        assertThat(FileKnowledge(KnowledgeIndexState.INDEXING).indexMotionState()).isEqualTo(IndexMotionState(IndexMotionPhase.PREPARING))
        assertThat(FileKnowledge(KnowledgeIndexState.INDEXING, stage = PendingStage.VECTORIZING, processed = 1, total = 4).indexMotionState())
            .isEqualTo(IndexMotionState(IndexMotionPhase.VECTORIZING, vectorProgress = 0.25f))
        assertThat(FileKnowledge(KnowledgeIndexState.INDEXING, stage = PendingStage.EXTRACTING_GRAPH, processed = 2, total = 4).indexMotionState())
            .isEqualTo(IndexMotionState(IndexMotionPhase.EXTRACTING_GRAPH, vectorProgress = 1f, graphProgress = 0.5f, withGraph = true))
        assertThat(FileKnowledge(KnowledgeIndexState.INDEXING, stage = PendingStage.SAVING).indexMotionState())
            .isEqualTo(IndexMotionState(IndexMotionPhase.SAVING, vectorProgress = 1f))
    }

    @Test fun `其它资料状态只显示徽标文字`() {
        listOf(KnowledgeIndexState.FAILED, KnowledgeIndexState.STALE, KnowledgeIndexState.INDEXED, KnowledgeIndexState.NONE, KnowledgeIndexState.UNSUPPORTED)
            .forEach { assertThat(FileKnowledge(it).indexMotionState()).isNull() }
    }
}
