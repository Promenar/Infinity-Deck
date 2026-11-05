package com.promenar.infinity.background.indexing

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.rag.VectorizationTask
import org.junit.Test

class IndexForegroundDecisionTest {
    @Test
    fun `排队的文档也需要前台服务并显示文件名与不确定进度`() {
        val decision = reduceIndexForeground(state(task("a", title = "资料.md")))
            as IndexForegroundDecision.Active

        assertThat(decision.notification.docTitle).isEqualTo("资料.md")
        assertThat(decision.notification.position).isEqualTo(1)
        assertThat(decision.notification.total).isEqualTo(1)
        assertThat(decision.notification.progressPercent).isNull()
    }

    @Test
    fun `批次跨文档保留序号并按块数计算百分比`() {
        val first = task("a", title = "第一份", status = "vectorizing")
        val second = task("b", title = "第二份", status = "extracting", totalChunks = 8, lastChunkIndex = 3)
        val initial = reduceIndexForeground(state(first, second)) as IndexForegroundDecision.Active
        val next = reduceIndexForeground(state(second), initial.batch) as IndexForegroundDecision.Active

        assertThat(next.notification.docTitle).isEqualTo("第二份")
        assertThat(next.notification.position).isEqualTo(2)
        assertThat(next.notification.total).isEqualTo(2)
        assertThat(next.notification.progressPercent).isEqualTo(37)
    }

    @Test
    fun `新文档并入批次且进度约束在零到一百`() {
        val first = task("a", totalChunks = 4, lastChunkIndex = -2)
        val initial = reduceIndexForeground(state(first)) as IndexForegroundDecision.Active
        val next = reduceIndexForeground(
            state(first.copy(totalChunks = 4, lastChunkIndex = 99), task("b")),
            initial.batch,
        ) as IndexForegroundDecision.Active

        assertThat(initial.notification.progressPercent).isEqualTo(0)
        assertThat(next.notification.progressPercent).isEqualTo(100)
        assertThat(next.notification.total).isEqualTo(2)
    }

    @Test
    fun `对话任务不触发但不会挡住后面的文档`() {
        val memory = task("memory", type = "memory")
        val graph = task("graph", type = VectorizationQueue.TYPE_SESSION_GRAPH)
        val sessionState = task("state", type = VectorizationQueue.TYPE_SESSION_STATE)
        assertThat(reduceIndexForeground(state(memory, graph, sessionState)))
            .isEqualTo(IndexForegroundDecision.Stop)

        val document = task("doc", type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, title = "排队文档")
        val decision = reduceIndexForeground(state(memory, document)) as IndexForegroundDecision.Active
        assertThat(decision.notification.docTitle).isEqualTo("排队文档")
        assertThat(decision.notification.total).isEqualTo(1)
    }

    @Test
    fun `恢复中的文档即使保留中断状态且排在会话任务后也需要前台服务`() {
        val conversation = task("memory", type = "memory")
        val recovered = task("doc", type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, title = "恢复中的资料", status = "interrupted")
        val queue = VectorizationQueue.QueueState(
            listOf(conversation, recovered), conversation, isProcessing = true, activeTaskIds = setOf("memory", "doc"),
        )

        val decision = reduceIndexForeground(queue) as IndexForegroundDecision.Active
        assertThat(decision.notification.docTitle).isEqualTo("恢复中的资料")
    }

    @Test
    fun `全部文档结束或取消后停止前台服务`() {
        val statuses = listOf("completed", "failed", "partial", "interrupted", "cancelled", "canceled")
        statuses.forEach { status ->
            assertThat(reduceIndexForeground(state(task(status, status = status))))
                .isEqualTo(IndexForegroundDecision.Stop)
        }
        assertThat(reduceIndexForeground(state())).isEqualTo(IndexForegroundDecision.Stop)
        assertThat(reduceIndexForeground(state(task("unknown", type = "unknown"))))
            .isEqualTo(IndexForegroundDecision.Stop)
    }

    @Test
    fun `块数未知或为零时进度不确定`() {
        listOf(null, 0, -1).forEach { total ->
            val decision = reduceIndexForeground(state(task("a", totalChunks = total))) as IndexForegroundDecision.Active
            assertThat(decision.notification.progressPercent).isNull()
        }
    }

    private fun state(vararg tasks: VectorizationTask) =
        VectorizationQueue.QueueState(tasks.toList(), tasks.firstOrNull(), tasks.isNotEmpty())

    private fun task(
        id: String,
        type: String = "document",
        title: String? = null,
        status: String = "pending",
        totalChunks: Int? = null,
        lastChunkIndex: Int = 0,
    ) = VectorizationTask(id = id, type = type, docTitle = title, status = status,
        totalChunks = totalChunks, lastChunkIndex = lastChunkIndex)
}
