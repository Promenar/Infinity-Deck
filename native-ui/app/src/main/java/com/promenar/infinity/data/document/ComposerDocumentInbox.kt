package com.promenar.infinity.data.document

import com.promenar.infinity.data.model.MessageDocumentAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * 从应用其它位置交给某个会话输入框的文档草稿附件（例如外部文档查看器的“发送到会话”）。
 * 会话页成为该会话的当前页时取走并并入草稿附件，之后照常走会话附件的预算与发送规则。只存内存。
 */
class ComposerDocumentInbox {
    private val state = MutableStateFlow<Map<String, List<MessageDocumentAttachment>>>(emptyMap())
    val pending: StateFlow<Map<String, List<MessageDocumentAttachment>>> = state.asStateFlow()

    fun offer(sessionId: String, document: MessageDocumentAttachment) {
        require(sessionId.isNotBlank()) { "会话 id 不能为空" }
        state.update { current -> current + (sessionId to mergeDraftDocuments(current[sessionId].orEmpty(), listOf(document))) }
    }

    /** 取走并清空该会话的待并入附件。 */
    fun take(sessionId: String): List<MessageDocumentAttachment> =
        state.getAndUpdate { it - sessionId }[sessionId].orEmpty()
}

/** 并入草稿附件：同一内容（SHA-256 相同）只留最新一份，排在末尾。 */
fun mergeDraftDocuments(
    current: List<MessageDocumentAttachment>,
    incoming: List<MessageDocumentAttachment>,
): List<MessageDocumentAttachment> {
    val incomingHashes = incoming.mapTo(mutableSetOf()) { it.sha256 }
    return current.filterNot { it.sha256 in incomingHashes } + incoming.distinctBy { it.sha256 }
}
