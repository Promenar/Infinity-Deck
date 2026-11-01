package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.MessageEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test

class TurnContextPersistenceTest {
    @Test
    fun `轮次上下文经消息映射与序列化往返后保留原文且正文独立`() {
        val original = Message("user", MessageRole.USER, "用户正文", turnContext = RAW_CONTEXT, requestInstruction = RAW_INSTRUCTION)
        val entity = original.toEntity("session")
        val restored = entity.toDomain()
        assertThat(entity.turnContext).isEqualTo(RAW_CONTEXT)
        assertThat(entity.requestInstruction).isEqualTo(RAW_INSTRUCTION)
        assertThat(restored.requestInstruction).isEqualTo(RAW_INSTRUCTION)
        assertThat(restored.turnContext!!.toByteArray(Charsets.UTF_8)).isEqualTo(RAW_CONTEXT.toByteArray(Charsets.UTF_8))
        assertThat(restored.content).isEqualTo("用户正文")
        assertThat(json.decodeFromString<Message>(json.encodeToString(original)).turnContext).isEqualTo(RAW_CONTEXT)
        assertThat(json.decodeFromString<MessageEntity>(json.encodeToString(entity)).turnContext).isEqualTo(RAW_CONTEXT)
        assertThat(original.copy(id = "copy").turnContext).isEqualTo(RAW_CONTEXT)
        assertThat(original.copy(id = "copy").requestInstruction).isEqualTo(RAW_INSTRUCTION)
        assertThat(json.decodeFromString<Message>(json.encodeToString(original)).requestInstruction).isEqualTo(RAW_INSTRUCTION)
        assertThat(json.decodeFromString<MessageEntity>(json.encodeToString(entity)).requestInstruction).isEqualTo(RAW_INSTRUCTION)
    }

    @Test
    fun `历史消息缺轮次上下文时兼容为空`() {
        val legacy = """{"id":"legacy","role":"USER","content":"旧正文"}"""
        val restored = json.decodeFromString<Message>(legacy)
        assertThat(restored.turnContext).isNull()
        assertThat(restored.requestInstruction).isNull()
        assertThat(restored.toEntity("session").toDomain().turnContext).isNull()
        assertThat(MessageEntity("legacy", "session", "user", "旧正文", createdAt = 1).toDomain().turnContext).isNull()
    }

    companion object {
        const val RAW_INSTRUCTION = "  请输出最终答复。\r\n<instruction>保持原文 🧪</instruction>\n"
        const val RAW_CONTEXT = "  工作区：/项目/甲\r\n<state>\n  key=\"值\" & emoji=🧪\n</state>\n\n"
    }
}
