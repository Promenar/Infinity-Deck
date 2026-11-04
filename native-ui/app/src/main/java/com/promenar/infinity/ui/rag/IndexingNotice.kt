package com.promenar.infinity.ui.rag

import com.promenar.infinity.R
import com.promenar.infinity.data.rag.VectorizationTask
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.ResolvedStatus
import com.promenar.infinity.ui.common.status.UiStatusNotice

/**
 * RAG 向量化管线 UI 派生状态的结构化状态码（F-3/F-6，仅 UI 派生层）。
 *
 * 由 [VectorizationTask] 的稳定 `status` 字段派生；VectorizationQueue 产出的自然语言
 * `subStatus`/`error` 一律视为 [UiStatusNotice.technical]（仅日志/诊断），**不**回显给用户。
 *
 * 码集与后续 VectorizationQueue codec（F-1/F-7）保持一致意图，便于复用。
 */
object IndexingNotice {
    const val CODE_PENDING = "rag.index.pending"
    const val CODE_EXTRACTING_SOURCE = "rag.index.extracting_source"
    const val CODE_CHUNKING = "rag.index.chunking"
    const val CODE_VECTORIZING = "rag.index.vectorizing"
    const val CODE_SAVING = "rag.index.saving"
    const val CODE_EXTRACTING_KG = "rag.index.extracting_kg"
    const val CODE_COMPLETED = "rag.index.completed"
    const val CODE_PARTIAL = "rag.index.partial"
    const val CODE_KG_PROGRESS = "rag.index.kg_progress"
    const val CODE_KG_PARTIAL = "rag.index.kg_partial"
    const val CODE_KG_CONFIRMATION = "rag.index.kg_confirmation"
    const val CODE_WARNING = "rag.index.warning"
    const val CODE_FAILED = "rag.index.failed"
    const val CODE_OPERATION_FAILED = "rag.operation.failed"
    const val CODE_IMPORT_FAILED = "rag.index.import_failed"
    const val CODE_MOVE_FAILED = "rag.move.failed"
    const val CODE_DELETE_FAILED = "rag.delete.failed"
    const val CODE_UNKNOWN = "rag.index.unknown"

    /**
     * 由当前队列任务派生 UI 状态码 + 严重性 + 类型化参数。
     * `totalChunks` 以原样（数字或占位）进入 [UiStatusNotice.formatArgs]，由展示层格式化。
     * `task.error`/`task.subStatus` 仅进入 [UiStatusNotice.technical]。
     */
    fun fromTask(task: VectorizationTask): UiStatusNotice = when (task.status) {
        "pending" -> UiStatusNotice(NoticeSeverity.Info, CODE_PENDING, technical = task.subStatus)
        "extracting_source" -> UiStatusNotice(NoticeSeverity.Info, CODE_EXTRACTING_SOURCE, technical = task.subStatus)
        "chunking" -> UiStatusNotice(NoticeSeverity.Info, CODE_CHUNKING, technical = task.subStatus)
        "vectorizing" -> UiStatusNotice(
            severity = NoticeSeverity.Info,
            code = CODE_VECTORIZING,
            formatArgs = listOf(task.totalChunks?.toString() ?: "?"),
            technical = task.subStatus,
        )
        "saving" -> UiStatusNotice(NoticeSeverity.Info, CODE_SAVING, technical = task.subStatus)
        "extracting" -> if (task.totalChunks != null) UiStatusNotice(
            NoticeSeverity.Info, CODE_KG_PROGRESS,
            formatArgs = listOf(task.lastChunkIndex.toString(), task.totalChunks.toString()), technical = task.subStatus,
        ) else UiStatusNotice(NoticeSeverity.Info, CODE_EXTRACTING_KG, technical = task.subStatus)
        "completed" -> UiStatusNotice(NoticeSeverity.Success, CODE_COMPLETED, technical = task.subStatus)
        "partial" -> UiStatusNotice(
            severity = NoticeSeverity.Warning,
            code = when {
                task.error == com.promenar.infinity.data.rag.VectorizationQueue.KG_CONFIRMATION_REQUIRED -> CODE_KG_CONFIRMATION
                task.error?.startsWith("kg.budget.") == true -> CODE_KG_PARTIAL
                else -> CODE_PARTIAL
            },
            formatArgs = listOf(task.lastChunkIndex.toString(), task.totalChunks?.toString() ?: "?"),
            technical = listOfNotNull(task.error, task.subStatus).joinToString(" | ").ifEmpty { null },
        )
        "warning" -> UiStatusNotice(
            severity = NoticeSeverity.Warning,
            code = CODE_WARNING,
            technical = listOfNotNull(task.error, task.subStatus).joinToString(" | ").ifEmpty { null },
        )
        "failed" -> UiStatusNotice(
            severity = NoticeSeverity.Error,
            code = CODE_FAILED,
            technical = listOfNotNull(task.error, task.subStatus).joinToString(" | ").ifEmpty { null },
        )
        else -> UiStatusNotice(
            severity = NoticeSeverity.Warning,
            code = CODE_UNKNOWN,
            technical = listOfNotNull(task.status, task.error, task.subStatus).joinToString(" | ").ifEmpty { null },
        )
    }

    /** 导入失败（非队列路径）：展示安全通用文案，原始明细仅进入 technical。 */
    fun importFailed(technical: String?): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Error,
        code = CODE_IMPORT_FAILED,
        technical = technical,
    )

    /**
     * 纯函数：将 [notice] 映射到展示用的 string resource 模板。
     * 未知 code 返回 null，调用方须显示安全通用文案（rag_index_phase_unknown），不得泄漏 code/裸串。
     */
    fun template(notice: UiStatusNotice): ResolvedStatus? = when (notice.code) {
        CODE_PENDING -> ResolvedStatus(R.string.rag_index_phase_pending)
        CODE_EXTRACTING_SOURCE -> ResolvedStatus(R.string.rag_index_phase_extracting_source)
        CODE_CHUNKING -> ResolvedStatus(R.string.rag_index_phase_chunking)
        CODE_VECTORIZING -> ResolvedStatus(
            R.string.rag_index_phase_vectorizing,
            listOf(notice.formatArgs.getOrElse(0) { "?" }),
        )
        CODE_SAVING -> ResolvedStatus(R.string.rag_index_phase_saving)
        CODE_EXTRACTING_KG -> ResolvedStatus(R.string.rag_index_phase_extracting_kg)
        CODE_COMPLETED -> ResolvedStatus(R.string.rag_index_phase_completed)
        CODE_PARTIAL -> ResolvedStatus(R.string.rag_index_phase_partial)
        CODE_KG_PROGRESS -> ResolvedStatus(R.string.kg_extraction_progress, notice.formatArgs)
        CODE_KG_PARTIAL -> ResolvedStatus(R.string.kg_extraction_partial, notice.formatArgs)
        CODE_KG_CONFIRMATION -> ResolvedStatus(R.string.kg_extraction_confirmation_needed, notice.formatArgs)
        CODE_WARNING -> ResolvedStatus(R.string.rag_index_phase_warning)
        CODE_FAILED -> ResolvedStatus(R.string.rag_index_phase_failed)
        CODE_OPERATION_FAILED -> ResolvedStatus(R.string.library_knowledge_action_failed)
        CODE_IMPORT_FAILED -> ResolvedStatus(R.string.rag_import_failed)
        CODE_MOVE_FAILED -> ResolvedStatus(R.string.common_cd_failed)
        CODE_DELETE_FAILED -> ResolvedStatus(R.string.rag_delete_failed_notice)
        else -> null
    }
}
