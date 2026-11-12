package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.chat.queue.FileComposerQueueStore
import com.promenar.infinity.data.chat.queue.PanelMessageOrigin
import com.promenar.infinity.data.chat.queue.QueuePauseReason
import com.promenar.infinity.ui.chat.PanelSendResult
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductionReviewDeliveryTest {
    @TempDir lateinit var directory: Path
    private val target = ReviewTarget.script("S1E01")

    private inner class Repository : ProductionReviewDeliveryStore {
        var snapshot: ProductionSnapshot? = reviewFixture()
        var bound = true
        var conflicts = 0
        var applies = 0
        var onConflict: () -> Unit = {}
        override suspend fun get(sessionId: String) = snapshot.takeIf { bound && sessionId == "s" }
        override suspend fun getById(productionId: String) = snapshot?.takeIf { it.production.id == productionId }
        override suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction) {
            applies++
            if (conflicts > 0) {
                conflicts--
                onConflict()
                throw ProductionRevisionConflictException()
            }
            val current = snapshot ?: return
            require(current.production.id == productionId)
            snapshot = ProductionUserEngine.apply(current, revision, action, 100)
        }
        override suspend fun queuedProjects(): List<Pair<String, String?>> = snapshot?.production?.takeIf { it.queuedReviewRound != null }
            ?.let { listOf(it.id to "s".takeIf { bound }) }.orEmpty()
        fun add(kind: ReviewKind = ReviewKind.APPROVE) {
            snapshot = reviewApply(requireNotNull(snapshot), ProductionUserAction.Review(target, kind, null))
        }
    }

    private fun dispatcher(repo: Repository, queue: FileComposerQueueStore, scope: CoroutineScope, exists: () -> Boolean = { true }) =
        ProductionReviewDispatcher(repo, { emptyList() }, object : ReviewMessageStrings {
            override fun text(key: String, vararg args: Any) = "$key ${args.joinToString()}"
        }, queue, { exists() }, scope, { 50 })

    @Test fun `没有意见返回空且已有排队时保留两组意见`() = runTest {
        val repo = Repository()
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        assertThat(dispatcher.send("s") { _, _ -> error("不应发送") }.result).isEqualTo(ReviewDispatchResult.EMPTY)
        repo.add()
        dispatcher.send("s") { _, origin -> queue.enqueue("s", "审核", origin = origin); PanelSendResult.QUEUED }
        repo.add(ReviewKind.REJECT)
        assertThat(dispatcher.send("s") { _, _ -> error("不应重复发送") }.result).isEqualTo(ReviewDispatchResult.ALREADY_QUEUED)
        assertThat(repo.snapshot!!.production.queuedReviews()).hasSize(1)
        assertThat(repo.snapshot!!.production.unsentReviews()).hasSize(1)
    }

    @Test fun `三种拒绝回退意见且轮次不变`() = runTest {
        for (result in listOf(PanelSendResult.IMPORTING, PanelSendResult.APPROVAL_PENDING, PanelSendResult.UNAVAILABLE)) {
            val repo = Repository().apply { add() }
            val queue = FileComposerQueueStore(directory.resolve(result.name).toFile(), { null }, StandardTestDispatcher(testScheduler))
            val dispatcher = dispatcher(repo, queue, this)
            assertThat(dispatcher.send("s") { _, _ -> result }).isEqualTo(ReviewDispatch(ReviewDispatchResult.valueOf(result.name), 1, 1))
            assertThat(repo.snapshot!!.production.queuedReviewRound).isNull()
            assertThat(repo.snapshot!!.production.unsentReviews()).hasSize(1)
            assertThat(repo.snapshot!!.production.nextReviewRound).isEqualTo(1)
        }
    }

    @Test fun `开轮与入队只标记排队送达后归档且重复回调幂等`() = runTest {
        for (result in listOf(PanelSendResult.STARTED, PanelSendResult.QUEUED)) {
            val repo = Repository().apply { add() }
            val queue = FileComposerQueueStore(directory.resolve(result.name).toFile(), { null }, StandardTestDispatcher(testScheduler))
            val dispatcher = dispatcher(repo, queue, this)
            val origin = PanelMessageOrigin("p", 1)
            assertThat(dispatcher.send("s") { text, actual ->
                assertThat(text).contains("title 1")
                assertThat(actual).isEqualTo(origin)
                assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
                result
            }).isEqualTo(ReviewDispatch(ReviewDispatchResult.valueOf(result.name), 1, 1))
            assertThat(repo.snapshot!!.production.reviewRounds).isEmpty()
            dispatcher.delivered("s", origin)
            val sent = repo.snapshot!!
            assertThat(sent.production.nextReviewRound).isEqualTo(2)
            assertThat(sent.production.reviewRounds.single().entries.single().target).isEqualTo(target)
            dispatcher.delivered("s", origin)
            dispatcher.withdrawn("s", origin)
            assertThat(repo.snapshot).isEqualTo(sent)
        }
    }

    @Test fun `发送异常先回退再抛出原异常`() = runTest {
        val repo = Repository().apply { add() }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        val failure = java.io.IOException("写盘失败")
        val result = runCatching { dispatcher.send("s") { _, _ -> throw failure } }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(repo.snapshot!!.production.unsentReviews()).hasSize(1)
        assertThat(repo.snapshot!!.production.nextReviewRound).isEqualTo(1)
    }

    @Test fun `排队提交冲突重新合成最新快照并限制三次`() = runTest {
        val repo = Repository().apply { add(); conflicts = 1 }
        repo.onConflict = {
            repo.snapshot = reviewApply(repo.snapshot!!, ProductionUserAction.Review(ReviewTarget.project(), ReviewKind.SUGGEST, "新增意见"))
        }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        val result = dispatcher.send("s") { text, _ -> assertThat(text).contains("新增意见"); PanelSendResult.STARTED }
        assertThat(result.count).isEqualTo(2)
        assertThat(repo.applies).isEqualTo(2)
        dispatcher.withdrawn("s", PanelMessageOrigin("p", 1))
        repo.conflicts = 3
        repo.onConflict = {}
        val failure = runCatching { dispatcher.send("s") { _, _ -> error("不应发送") } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ProductionRevisionConflictException::class.java)
        assertThat(repo.applies).isEqualTo(6)
    }

    @Test fun `撤回回调重试修订冲突且保留下一轮意见`() = runTest {
        val repo = Repository().apply { add() }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        dispatcher.send("s") { _, _ -> PanelSendResult.STARTED }
        repo.add(ReviewKind.REJECT)
        repo.conflicts = 1
        dispatcher.withdrawn("s", PanelMessageOrigin("p", 1))
        assertThat(repo.snapshot!!.production.unsentReviews().single().kind).isEqualTo(ReviewKind.REJECT)
        assertThat(repo.snapshot!!.production.nextReviewRound).isEqualTo(1)
        val current = repo.snapshot
        dispatcher.withdrawn("s", PanelMessageOrigin("p", 1))
        assertThat(repo.snapshot).isEqualTo(current)
    }

    @Test fun `核对保留队列认领和正在开轮且回退孤立轮次`() = runTest {
        val repo = Repository().apply { add() }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        val origin = PanelMessageOrigin("p", 1)
        dispatcher.send("s") { _, _ -> PanelSendResult.STARTED }
        dispatcher.reconcile("s")
        assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
        // 新实例模拟进程重启，内存派发标记不继承。
        val restored = dispatcher(repo, queue, this)
        val item = queue.enqueue("s", "审核", origin = origin)
        restored.reconcile("s")
        assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
        queue.claimNext("s")
        restored.reconcile("s")
        assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
        queue.rollback("s", item.id, QueuePauseReason.INTERRUPTED)
        restored.reconcile("s")
        assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
        queue.remove("s", item.id)
        restored.reconcile("s")
        assertThat(repo.snapshot!!.production.queuedReviewRound).isNull()
    }

    @Test fun `独立送达回执归档冲突时保持排队且不会被孤立清理回退`() = runTest {
        val repo = Repository().apply { add() }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        dispatcher.send("s") { _, _ -> PanelSendResult.STARTED }
        queue.finishPanelMessage("s", PanelMessageOrigin("p", 1), true)
        queue.deleteSession("s")
        repo.bound = false
        repo.conflicts = 6
        dispatcher.reconcileAll()
        assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
        assertThat(repo.snapshot!!.production.unsentReviews()).isEmpty()
        repo.conflicts = 0
        dispatcher.reconcileAll()
        assertThat(repo.snapshot!!.production.nextReviewRound).isEqualTo(2)
        assertThat(repo.snapshot!!.production.reviewRounds).hasSize(1)
    }

    @Test fun `旧撤回通知延迟到同轮重新发送后不能撤回新消息`() = runTest {
        val repo = Repository().apply { add() }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        val origin = PanelMessageOrigin("p", 1)
        dispatcher.send("s") { _, _ -> PanelSendResult.QUEUED }
        val oldWithdrawal = dispatcher.captureWithdrawal("s", origin)
        dispatcher.reconcile("s")
        assertThat(repo.snapshot!!.production.queuedReviewRound).isNull()
        dispatcher.send("s") { _, _ -> PanelSendResult.STARTED }
        oldWithdrawal()
        assertThat(repo.snapshot!!.production.queuedReviewRound).isEqualTo(1)
        dispatcher.withdrawn("s", origin)
        assertThat(repo.snapshot!!.production.queuedReviewRound).isNull()
    }

    @Test fun `核对优先处理已送达回执且启动回退失去会话的项目`() = runTest {
        val repo = Repository().apply { add() }
        val queue = FileComposerQueueStore(directory.toFile(), { null }, StandardTestDispatcher(testScheduler))
        val dispatcher = dispatcher(repo, queue, this)
        val origin = PanelMessageOrigin("p", 1)
        dispatcher.send("s") { _, _ -> PanelSendResult.QUEUED }
        queue.finishPanelMessage("s", origin, true)
        dispatcher.reconcile("s")
        assertThat(repo.snapshot!!.production.nextReviewRound).isEqualTo(2)
        repo.add(ReviewKind.REJECT)
        dispatcher.send("s") { _, _ -> PanelSendResult.STARTED }
        repo.bound = false
        dispatcher(repo, queue, this) { false }.reconcileAll()
        assertThat(repo.snapshot!!.production.unsentReviews().single().kind).isEqualTo(ReviewKind.REJECT)
        assertThat(repo.snapshot!!.production.nextReviewRound).isEqualTo(2)
        repo.snapshot = null
        dispatcher.delivered("missing", origin)
        dispatcher.withdrawn("missing", origin)
    }
}
