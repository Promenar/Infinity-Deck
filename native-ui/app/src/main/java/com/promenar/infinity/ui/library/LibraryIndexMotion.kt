package com.promenar.infinity.ui.library

import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.ui.common.motion.IndexMotionPhase
import com.promenar.infinity.ui.common.motion.IndexMotionState

/**
 * 待处理文档对应的索引指示状态；只是“已改动待重建”的文档不显示指示，返回 null。
 * 进度取当前阶段的已处理 / 总数，没有总数时按 0 处理，指示只表达阶段。
 */
internal fun PendingDocument.indexMotionState(): IndexMotionState? {
    val fraction = total?.takeIf { it > 0 }?.let { (processed ?: 0).toFloat() / it }?.coerceIn(0f, 1f) ?: 0f
    return when (kind) {
        PendingKind.STALE -> null
        PendingKind.QUEUED -> IndexMotionState(IndexMotionPhase.QUEUED)
        PendingKind.FAILED -> IndexMotionState(IndexMotionPhase.FAILED, vectorProgress = 0f)
        PendingKind.GRAPH_ATTENTION ->
            if (graphConfirmation) IndexMotionState(IndexMotionPhase.NEEDS_CONFIRMATION, vectorProgress = 1f)
            else IndexMotionState(IndexMotionPhase.FAILED, vectorProgress = 1f, graphProgress = fraction, withGraph = true)
        PendingKind.INDEXING -> when (stage) {
            PendingStage.VECTORIZING -> IndexMotionState(
                if (lastError != null) IndexMotionPhase.RETRYING else IndexMotionPhase.VECTORIZING,
                vectorProgress = fraction,
            )
            PendingStage.EXTRACTING_GRAPH ->
                IndexMotionState(IndexMotionPhase.EXTRACTING_GRAPH, vectorProgress = 1f, graphProgress = fraction, withGraph = true)
            PendingStage.SAVING -> IndexMotionState(IndexMotionPhase.SAVING, vectorProgress = 1f)
            PendingStage.PREPARING, null -> IndexMotionState(IndexMotionPhase.PREPARING)
        }
    }
}
