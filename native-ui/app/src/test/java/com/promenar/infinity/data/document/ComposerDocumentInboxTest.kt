package com.promenar.infinity.data.document

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.MessageDocumentAttachment
import org.junit.Test

class ComposerDocumentInboxTest {
    private fun doc(id: String, sha: String) = MessageDocumentAttachment(id, "$id.md", "text/markdown", "x", 1, sha, 1)

    @Test
    fun `按会话暂存并一次取走`() {
        val inbox = ComposerDocumentInbox()
        inbox.offer("s1", doc("a", "h1"))
        inbox.offer("s2", doc("b", "h2"))
        inbox.offer("s1", doc("c", "h3"))

        assertThat(inbox.take("s1").map { it.id }).containsExactly("a", "c").inOrder()
        assertThat(inbox.take("s1")).isEmpty()
        assertThat(inbox.pending.value.keys).containsExactly("s2")
    }

    @Test
    fun `同一内容只留最新一份`() {
        val inbox = ComposerDocumentInbox()
        inbox.offer("s1", doc("old", "same"))
        inbox.offer("s1", doc("new", "same"))
        assertThat(inbox.take("s1").map { it.id }).containsExactly("new")
    }

    @Test
    fun `并入草稿时新内容排在末尾并替换同内容旧项`() {
        val current = listOf(doc("a", "h1"), doc("b", "h2"))
        val merged = mergeDraftDocuments(current, listOf(doc("c", "h2"), doc("d", "h3"), doc("e", "h3")))
        assertThat(merged.map { it.id }).containsExactly("a", "c", "d").inOrder()
    }
}
