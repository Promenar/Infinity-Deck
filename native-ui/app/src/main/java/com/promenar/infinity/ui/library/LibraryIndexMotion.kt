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

/**
 * 文档页“知识图谱”行在抽取中显示的已处理 / 总窗口数。文档快照只在阶段变化时重读，
 * 实时数字取自正在抽取图谱的待处理项；没有实时数字时沿用快照。总数缺失或为 0 时返回 null。
 */
internal fun liveGraphProgress(snapshotProcessed: Int?, snapshotTotal: Int?, pending: PendingDocument?): Pair<Int, Int>? {
    val live = pending?.takeIf { it.kind == PendingKind.INDEXING && it.stage == PendingStage.EXTRACTING_GRAPH }
    val total = (live?.total ?: snapshotTotal)?.takeIf { it > 0 } ?: return null
    val processed = live?.processed ?: snapshotProcessed ?: return null
    return processed.coerceIn(0, total) to total
}
