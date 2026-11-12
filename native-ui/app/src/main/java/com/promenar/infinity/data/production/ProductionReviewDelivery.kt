package com.promenar.infinity.data.production

import com.promenar.infinity.data.chat.queue.ComposerQueueStore
import com.promenar.infinity.data.chat.queue.PanelMessageOrigin
import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.ui.chat.PanelSendResult
import com.promenar.infinity.utils.InfinityLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 会话把带来源标记的面板消息写入或撤回时的回调。 */
interface PanelMessageListener {
    suspend fun delivered(sessionId: String, origin: PanelMessageOrigin)
    suspend fun withdrawn(sessionId: String, origin: PanelMessageOrigin)
}

/** 在投递前捕获本次接收序号，避免旧撤回回调影响同轮次的重新发送。 */
internal fun PanelMessageListener.captureWithdrawal(sessionId: String, origin: PanelMessageOrigin): suspend () -> Unit =
    if (this is ProductionReviewDispatcher) withdrawalCallback(sessionId, origin) else suspend { withdrawn(sessionId, origin) }

enum class ReviewDispatchResult { STARTED, QUEUED, EMPTY, ALREADY_QUEUED, IMPORTING, APPROVAL_PENDING, UNAVAILABLE }
data class ReviewDispatch(val result: ReviewDispatchResult, val round: Int = 0, val count: Int = 0)

/** 按项目身份提交回执；会话删除后仍可回退保留的项目快照。 */
interface ProductionReviewDeliveryStore {
    suspend fun get(sessionId: String): ProductionSnapshot?
    suspend fun getById(productionId: String): ProductionSnapshot?
    suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction)
    suspend fun queuedProjects(): List<Pair<String, String?>>
}

class ProductionReviewDispatcher(
    private val repository: ProductionReviewDeliveryStore,
    private val tasks: suspend (String) -> List<MediaTask>,
    private val strings: ReviewMessageStrings,
    private val queue: ComposerQueueStore,
    private val sessionExists: suspend (String) -> Boolean,
    private val applicationScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : PanelMessageListener {
    private val mutex = Mutex()
    private val recoveredDeliveries = ConcurrentHashMap<PanelMessageOrigin, String>()
    private val attempts = ConcurrentHashMap<PanelMessageOrigin, Long>()
    /** 覆盖标记排队、写队列文件、直接准备和通知权限等待之间的无文件窗口。 */
    private val dispatching = mutableSetOf<Pair<String, PanelMessageOrigin>>()

    /** 面板“发送”：合成本轮消息、标记已排队、交给会话；会话不接收时回退为未发送。 */
    suspend fun send(sessionId: String, send: suspend (text: String, origin: PanelMessageOrigin) -> PanelSendResult): ReviewDispatch = withContext(NonCancellable) { mutex.withLock {
        repeat(3) { attempt ->
            val snapshot = repository.get(sessionId) ?: return@withLock ReviewDispatch(ReviewDispatchResult.UNAVAILABLE)
            val p = snapshot.production
            val count = p.unsentReviews().size
            if (count == 0) return@withLock ReviewDispatch(ReviewDispatchResult.EMPTY)
            if (p.queuedReviewRound != null) return@withLock ReviewDispatch(ReviewDispatchResult.ALREADY_QUEUED, p.queuedReviewRound ?: 0)
            val text = ProductionReviewMessage.compose(p, tasks(p.id).filter { p.ownsTask(it) }, strings)
                ?: return@withLock ReviewDispatch(ReviewDispatchResult.EMPTY)
            val origin = PanelMessageOrigin(p.id, p.nextReviewRound)
            try {
                repository.apply(sessionId, p.id, snapshot.revision, ProductionUserAction.MarkReviewQueued(origin.round, now()))
            } catch (conflict: ProductionRevisionConflictException) {
                if (attempt == 2) throw conflict
                return@repeat
            }
            attempts.merge(origin, 1L) { previous, _ -> previous + 1 }
            dispatching += sessionId to origin
            try {
                val result = send(text, origin)
                when (result) {
                    PanelSendResult.STARTED -> Unit
                    PanelSendResult.QUEUED -> dispatching -= sessionId to origin
                    else -> {
                        change(sessionId, origin, ProductionUserAction.RevertReviewQueued(origin.round))
                        dispatching -= sessionId to origin
                    }
                }
                return@withLock ReviewDispatch(ReviewDispatchResult.valueOf(result.name), origin.round, count)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { change(sessionId, origin, ProductionUserAction.RevertReviewQueued(origin.round)) }
                    catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                    dispatching -= sessionId to origin
                }
                throw failure
            }
        }
        error("审核发送重试未完成。")
    } }

    override suspend fun delivered(sessionId: String, origin: PanelMessageOrigin) = mutex.withLock {
        change(sessionId, origin, ProductionUserAction.MarkReviewSent(origin.round, now()))
        dispatching -= sessionId to origin
        attempts.remove(origin)
        recoveredDeliveries.remove(origin)
        Unit
    }

    internal fun withdrawalCallback(sessionId: String, origin: PanelMessageOrigin): suspend () -> Unit {
        val attempt = attempts[origin]
        return { withdraw(sessionId, origin, attempt) }
    }

    override suspend fun withdrawn(sessionId: String, origin: PanelMessageOrigin) = withdrawalCallback(sessionId, origin).invoke()

    private suspend fun withdraw(sessionId: String, origin: PanelMessageOrigin, attempt: Long?) = mutex.withLock {
        if (attempt == attempts[origin]) {
            change(sessionId, origin, ProductionUserAction.RevertReviewQueued(origin.round))
            dispatching -= sessionId to origin
        }
    }

    private suspend fun change(sessionId: String, origin: PanelMessageOrigin, action: ProductionUserAction) {
        repeat(3) { attempt ->
            val snapshot = repository.getById(origin.productionId) ?: return
            if (snapshot.production.queuedReviewRound != origin.round) return
            try {
                repository.apply(sessionId, origin.productionId, snapshot.revision, action)
                return
            } catch (conflict: ProductionRevisionConflictException) {
                if (attempt == 2) throw conflict
            }
        }
    }

    /** 打开面板与应用启动时调用：回退既无队列条目、也无派发或落盘回执的轮次。 */
    suspend fun reconcile(sessionId: String) = mutex.withLock {
        val p = repository.get(sessionId)?.production
        if (p != null) reconcileProject(sessionId, p.id)
        else repository.queuedProjects().filter { it.second == sessionId || it.second == null }.forEach { (id, session) -> reconcileProject(session, id) }
    }

    private suspend fun reconcileProject(sessionId: String?, productionId: String) {
        val p = repository.getById(productionId)?.production ?: return
        val round = p.queuedReviewRound ?: return
        val origin = PanelMessageOrigin(productionId, round)
        recoveredDeliveries[origin]?.let { deliveredSession ->
            change(deliveredSession, origin, ProductionUserAction.MarkReviewSent(round, now()))
            recoveredDeliveries.remove(origin)
            dispatching -= deliveredSession to origin
            return
        }
        if (sessionId != null && sessionExists(sessionId)) {
            val state = queue.snapshot(sessionId)
            if (origin in state.deliveredPanelMessages) {
                change(sessionId, origin, ProductionUserAction.MarkReviewSent(round, now()))
                dispatching -= sessionId to origin
                return
            }
            if (sessionId to origin in dispatching || state.items.any { it.origin == origin } || state.sending?.origin == origin ||
                state.panelPreparations.any { it.origin == origin }) return
        }
        change(sessionId.orEmpty(), origin, ProductionUserAction.RevertReviewQueued(round))
        dispatching -= sessionId.orEmpty() to origin
    }

    /** 启动恢复也覆盖已经删除会话、仅保留项目快照的情况。 */
    suspend fun reconcileAll() {
        val receipts = try { queue.recoverPanelMessages() }
        catch (_: Exception) { InfinityLogger.diagnostic("production_review.reconcile_failed", emptyMap()); return }
        receipts.forEach { (sessionId, origin) -> recoveredDeliveries[origin] = sessionId }
        receipts.forEach { (sessionId, origin) ->
            try { delivered(sessionId, origin) }
            catch (_: Exception) { InfinityLogger.diagnostic("production_review.reconcile_failed", emptyMap()) }
        }
        val projects = try { repository.queuedProjects() }
        catch (_: Exception) { InfinityLogger.diagnostic("production_review.reconcile_failed", emptyMap()); return }
        projects.forEach { (productionId, sessionId) ->
            try { mutex.withLock { reconcileProject(sessionId, productionId) } }
            catch (_: Exception) { InfinityLogger.diagnostic("production_review.reconcile_failed", emptyMap()) }
        }
    }

    /** ViewModel 生命周期结束不取消审核回执。 */
    fun post(sessionId: String, origin: PanelMessageOrigin, delivered: Boolean) {
        val withdrawal = if (delivered) null else withdrawalCallback(sessionId, origin)
        applicationScope.launch {
            try { if (delivered) this@ProductionReviewDispatcher.delivered(sessionId, origin) else withdrawal?.invoke() }
            catch (_: Exception) { InfinityLogger.diagnostic("production_review.callback_failed", mapOf("delivered" to delivered.toString())) }
        }
    }
}
