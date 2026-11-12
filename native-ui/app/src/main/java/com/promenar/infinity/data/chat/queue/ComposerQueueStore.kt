package com.promenar.infinity.data.chat.queue

import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageQuote
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class QueuePauseReason { STOPPED, FAILED, RESTORED, INTERRUPTED, BUSY }

@Serializable
data class PanelMessageOrigin(val productionId: String, val round: Int)

/** 直接开轮在写入用户消息前保留绑定，供进程恢复核实落盘结果。 */
@Serializable
data class PanelMessagePreparation(val origin: PanelMessageOrigin, val userMessageId: String)

@Serializable
data class QueuedMessage(
    val id: String,
    val text: String,
    val imagePaths: List<String> = emptyList(),
    val documents: List<MessageDocumentAttachment> = emptyList(),
    val createdAt: Long,
    val steering: Boolean = false,
    /** 写了批注的引用；未写批注的引用卡在入队前已丢弃。 */
    val quotes: List<MessageQuote> = emptyList(),
    val origin: PanelMessageOrigin? = null,
)

@Serializable
data class ComposerQueueState(
    val items: List<QueuedMessage> = emptyList(),
    val pauseReason: QueuePauseReason? = null,
    /** 出队到生成接受之间的恢复副本；不在托盘展示。 */
    val sending: QueuedMessage? = null,
    val sendingUserMessageId: String? = null,
    /** 当前生成循环持有的注入租约，涵盖普通排队和插队。 */
    val sendingInLoop: Boolean = false,
    /** 已落盘、尚未完成审核归档的回执；归档成功后清除。 */
    val deliveredPanelMessages: List<PanelMessageOrigin> = emptyList(),
    val panelPreparations: List<PanelMessagePreparation> = emptyList(),
    /** 编辑中的队首暂不参与插队唤醒；草稿租约不跨进程恢复。 */
    @kotlinx.serialization.Transient val editingId: String? = null,
)

interface ComposerQueueStore {
    val handlesPanelMessages: Boolean get() = false
    suspend fun observe(sessionId: String): StateFlow<ComposerQueueState>
    suspend fun snapshot(sessionId: String): ComposerQueueState = observe(sessionId).value
    suspend fun enqueue(sessionId: String, text: String, imageUris: List<String> = emptyList(), documents: List<MessageDocumentAttachment> = emptyList(), quotes: List<MessageQuote> = emptyList(), origin: PanelMessageOrigin? = null): QueuedMessage
    /** 恢复会话删除后独立保留的送达回执。 */
    suspend fun recoverPanelMessages(): List<Pair<String, PanelMessageOrigin>> = emptyList()
    suspend fun bindPanelMessage(sessionId: String, origin: PanelMessageOrigin, userMessageId: String) {}
    suspend fun finishPanelMessage(sessionId: String, origin: PanelMessageOrigin, persisted: Boolean) {}
    suspend fun remove(sessionId: String, id: String)
    suspend fun moveToFront(sessionId: String, id: String)
    suspend fun steer(sessionId: String, id: String): Boolean
    suspend fun pause(sessionId: String, reason: QueuePauseReason?)
    suspend fun claimNext(sessionId: String): QueuedMessage?
    /** 仅认领插队项；回执和恢复与普通发送使用同一持久租约。 */
    suspend fun claimSteering(sessionId: String): QueuedMessage? = null
    /** 完整步骤结束时只认领队首一条。 */
    suspend fun claimBoundary(sessionId: String): QueuedMessage? = null
    suspend fun bindSending(sessionId: String, id: String, userMessageId: String)
    suspend fun acceptForUser(sessionId: String, userMessageId: String?) {
        val state = snapshot(sessionId)
        if (userMessageId != null && state.sendingUserMessageId == userMessageId) state.sending?.let { accept(sessionId, it.id) }
    }
    suspend fun rollbackForUser(sessionId: String, userMessageId: String?) {
        val state = snapshot(sessionId)
        if (userMessageId != null && state.sendingUserMessageId == userMessageId) state.sending?.let {
            rollback(sessionId, it.id, state.pauseReason ?: QueuePauseReason.FAILED)
        }
    }
    suspend fun accept(sessionId: String, id: String)
    suspend fun rollback(sessionId: String, id: String, reason: QueuePauseReason)
    suspend fun prepareEdit(sessionId: String, id: String): QueuedMessage?
    suspend fun completeEdit(sessionId: String, draft: QueuedMessage): Boolean
    suspend fun cancelEdit(sessionId: String, draft: QueuedMessage)
    suspend fun releaseDraftImages(sessionId: String, paths: List<String>)
    suspend fun deleteSession(sessionId: String)
}
