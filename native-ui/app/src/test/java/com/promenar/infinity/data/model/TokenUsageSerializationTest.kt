package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** messages.tokens 列的 JSON 兼容：旧记录可解码、新字段默认不写出、既有汇总键名与含义不变。 */
class TokenUsageSerializationTest {
    @Test
    fun `旧记录缺少缓存推理与计时字段时按 null 解码`() {
        val legacy = """{"input":120,"output":30,"total":150,"estimated":false,"summaryInput":0,"summaryOutput":0,"summaryTotal":0,"summaryEstimated":false}"""

        val decoded = json.decodeFromString(TokenUsage.serializer(), legacy)

        assertThat(decoded).isEqualTo(TokenUsage(120, 30, 150))
        assertThat(decoded.cacheReadInput).isNull()
        assertThat(decoded.cacheWriteInput).isNull()
        assertThat(decoded.cacheWriteLongTtlInput).isNull()
        assertThat(decoded.reasoningOutput).isNull()
        assertThat(decoded.timing).isNull()
    }

    @Test
    fun `更早的最小记录也能解码`() {
        assertThat(json.decodeFromString(TokenUsage.serializer(), """{"input":5}""")).isEqualTo(TokenUsage(input = 5))
    }

    @Test
    fun `新字段为默认值时不写入 JSON 且旧键保持不变`() {
        val encoded = json.encodeToString(TokenUsage.serializer(), TokenUsage(10, 2, 12))

        assertThat(encoded).doesNotContain("cacheReadInput")
        assertThat(encoded).doesNotContain("cacheWriteInput")
        assertThat(encoded).doesNotContain("cacheWriteLongTtlInput")
        assertThat(encoded).doesNotContain("reasoningOutput")
        assertThat(encoded).doesNotContain("timing")
        val tree = Json.parseToJsonElement(encoded).jsonObject
        assertThat(tree.keys).containsAtLeast("input", "output", "total", "estimated")
        assertThat(tree.getValue("input").jsonPrimitive.int).isEqualTo(10)
    }

    @Test
    fun `新字段往返且 0 与未知计时点区分保存`() {
        val usage = TokenUsage(
            input = 3520, output = 240, total = 3760,
            cacheReadInput = 3000, cacheWriteInput = 0, cacheWriteLongTtlInput = 0, reasoningOutput = null,
            timing = GenerationTimingRecord(requestStartedAt = 1_000, firstOutputAt = null, endedAt = 4_500),
        )

        val encoded = json.encodeToString(TokenUsage.serializer(), usage)
        val decoded = json.decodeFromString(TokenUsage.serializer(), encoded)

        assertThat(decoded).isEqualTo(usage)
        assertThat(encoded).contains("\"cacheWriteInput\":0")
        assertThat(encoded).contains("\"cacheWriteLongTtlInput\":0")
        assertThat(encoded).doesNotContain("reasoningOutput")
        assertThat(encoded).doesNotContain("firstOutputAt")
        // 汇总 SQL 依赖的 $.input 仍是全部输入。
        assertThat(Json.parseToJsonElement(encoded).jsonObject.getValue("input").jsonPrimitive.int).isEqualTo(3520)
    }

    @Test
    fun `消息实体映射保留新字段`() {
        val message = Message(
            id = "m", role = MessageRole.ASSISTANT, content = "ok",
            tokens = TokenUsage(10, 2, 12, reasoningOutput = 1, timing = GenerationTimingRecord(1, 2, 3)),
        )

        assertThat(message.toEntity("s").toDomain().tokens).isEqualTo(message.tokens)
    }
}
