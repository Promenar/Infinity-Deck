package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagMetadata
import com.promenar.infinity.data.model.RagRetrievalTrace
import org.junit.jupiter.api.Test

class ChatScreenRagSelectionTest {
    private val kgPath = KgPath(
        queryKeywords = listOf("Infinity Deck"),
        nodes = listOf(KgNode("n1", "Infinity Deck", "project")),
        edges = emptyList(),
    )

    @Test
    fun `只有未命中或失败轨迹的消息均提供检索入口`() {
        listOf(
            RagRetrievalTrace.OUTCOME_EMPTY,
            RagRetrievalTrace.OUTCOME_FAILED,
        ).forEach { outcome ->
            val message = Message(
                id = outcome,
                role = MessageRole.ASSISTANT,
                content = "回复",
                ragMetadata = RagMetadata(trace = RagRetrievalTrace(outcome = outcome)),
            )

            assertThat(message.hasRagArtifacts()).isTrue()
        }
    }

    @Test
    fun `历史消息中未配置嵌入模型或没有检索范围的轨迹不再显示检索步骤`() {
        listOf(
            RagRetrievalTrace.OUTCOME_UNAVAILABLE,
            RagRetrievalTrace.OUTCOME_NO_SCOPE,
        ).forEach { outcome ->
            val message = Message(
                id = outcome,
                role = MessageRole.ASSISTANT,
                content = "回复",
                ragMetadata = RagMetadata(trace = RagRetrievalTrace(outcome = outcome)),
            )

            assertThat(message.hasRagArtifacts()).isFalse()
        }
    }

    @Test
    fun `工具续轮后仍选中持有检索轨迹的首条回复`() {
        val first = Message(
            id = "first",
            role = MessageRole.ASSISTANT,
            content = "调用工具",
            ragMetadata = RagMetadata(trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY)),
        )
        val continuation = Message(id = "next", role = MessageRole.ASSISTANT, content = "后续回复")

        assertThat(selectRagActiveMessage(listOf(first, continuation))).isEqualTo(first)
        assertThat(continuation.hasRagArtifacts()).isFalse()
    }

    @Test
    fun `multiple assistant messages select the one containing only kgPaths`() {
        val plain = Message(id = "plain", role = MessageRole.ASSISTANT, content = "plain")
        val kgOnly = Message(
            id = "kg",
            role = MessageRole.ASSISTANT,
            content = "graph answer",
            kgPaths = listOf(kgPath),
        )
        val last = Message(id = "last", role = MessageRole.ASSISTANT, content = "last")

        assertThat(selectRagActiveMessage(listOf(plain, kgOnly, last))).isEqualTo(kgOnly)
    }

    @Test
    fun `pure kg message reports rag artifacts and shows progress card entry`() {
        val message = Message(
            id = "kg",
            role = MessageRole.ASSISTANT,
            content = "graph answer",
            kgPaths = listOf(kgPath),
        )

        assertThat(message.hasRagArtifacts()).isTrue()
    }

    @Test
    fun `empty kgPaths do not create a fake rag card`() {
        val message = Message(
            id = "empty",
            role = MessageRole.ASSISTANT,
            content = "answer",
            kgPaths = emptyList(),
        )

        assertThat(message.hasRagArtifacts()).isFalse()
    }
}
