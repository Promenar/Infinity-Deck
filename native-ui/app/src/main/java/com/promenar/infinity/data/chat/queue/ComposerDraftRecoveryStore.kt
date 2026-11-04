package com.promenar.infinity.data.chat.queue

import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageQuote

data class RecoverableComposerDraft(
    val text: String,
    val imageUris: List<String>,
    val documents: List<MessageDocumentAttachment>,
    val quotes: List<MessageQuote> = emptyList(),
)

/** 入队失败的附件仍由原输入区拥有，按会话保留，跨页面与 ViewModel 生命周期恢复。 */
class ComposerDraftRecoveryStore {
    private val drafts = mutableMapOf<String, List<RecoverableComposerDraft>>()

    @Synchronized fun retain(sessionId: String, draft: RecoverableComposerDraft) {
        drafts[sessionId] = drafts[sessionId].orEmpty() + draft
    }

    @Synchronized fun take(sessionId: String): List<RecoverableComposerDraft> = drafts.remove(sessionId).orEmpty()

    @Synchronized internal fun imageUris(sessionId: String): List<String> = drafts[sessionId].orEmpty().flatMap { it.imageUris }

    @Synchronized fun deleteSession(sessionId: String) { drafts.remove(sessionId) }
}
