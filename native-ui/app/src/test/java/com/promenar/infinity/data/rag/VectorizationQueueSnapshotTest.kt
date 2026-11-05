package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VectorizationQueueSnapshotTest {
    @Test
    fun `任务进度变更不会修改已发布快照并可触发新状态`() {
        val task = VectorizationTask(id = "doc", type = "document", totalChunks = 10)
        val previous = snapshotVectorizationQueueState(listOf(task), isProcessing = true, restored = true, activeTaskIds = setOf("doc"))

        task.lastChunkIndex = 6
        task.status = "extracting"
        val next = snapshotVectorizationQueueState(listOf(task), isProcessing = true, restored = true, activeTaskIds = setOf("doc"))

        assertThat(previous.queue.single().lastChunkIndex).isEqualTo(0)
        assertThat(previous.currentTask?.status).isEqualTo("pending")
        assertThat(next.currentTask?.lastChunkIndex).isEqualTo(6)
        assertThat(next).isNotEqualTo(previous)
    }
}
