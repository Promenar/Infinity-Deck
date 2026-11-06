package com.promenar.infinity.data.library

import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.domain.files.FileGraphState

/** 仅判定已经完成的抽取结果；进行中状态由调用方处理。 */
internal fun graphStateFor(extractedAt: Long?, updatedAt: Long, error: String?): FileGraphState = when {
    error == VectorizationQueue.KG_CONFIRMATION_REQUIRED -> FileGraphState.CONFIRMATION_REQUIRED
    error?.startsWith("kg.budget.") == true -> FileGraphState.PARTIAL
    error?.startsWith(VectorizationQueue.KG_FAILED) == true -> FileGraphState.FAILED
    extractedAt != null && extractedAt >= updatedAt -> FileGraphState.EXTRACTED
    else -> FileGraphState.NOT_EXTRACTED
}
