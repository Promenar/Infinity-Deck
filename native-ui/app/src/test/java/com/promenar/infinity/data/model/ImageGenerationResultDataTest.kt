package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class ImageGenerationResultDataTest {
    @Test fun `结果往返保存请求与实际信息且不写入空字段或旧图片键`() {
        val result = ImageGenerationResultData(mode = "edit", model = "gpt-image-2", requested = ImageGenerationRequested(size = "auto", n = 2),
            actual = ImageGenerationActual(size = "1536x1024", outputFormat = "webp"), inputImages = listOf("/media/a.png"),
            usage = ImageGenerationUsage(inputTokens = 12, outputTokens = 34, totalTokens = 46), revisedPrompts = listOf("修订"), failedCount = 1)
        val encoded = result.encode()
        val tree = Json.parseToJsonElement(encoded).jsonObject
        assertThat(tree["schema"]?.jsonPrimitive?.content).isEqualTo("infinity-image-generation-v1")
        assertThat(tree["requested"]?.jsonObject?.keys).containsExactly("size", "n")
        assertThat(tree["actual"]?.jsonObject?.keys).containsExactly("size", "outputFormat")
        assertThat(tree.containsKey("mask")).isFalse()
        assertThat(encoded).doesNotContain(":null")
        listOf("localPath", "url", "b64Json").forEach { assertThat(encoded).doesNotContain("\"$it\"") }
        assertThat(ImageGenerationResultData.decode(ToolResult("img", "", data = encoded).data)).isEqualTo(result)
    }

    @Test fun `空值损坏旧数据和未知schema安全返回null`() {
        listOf(null, "", "{", "[]", """{"localPath":"/old.png"}""", """{"mode":"generate","model":"test"}""",
            """{"schema":"future","mode":"generate","model":"test"}""").forEach {
            assertThat(ImageGenerationResultData.decode(it)).isNull()
        }
    }
}
