package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class ImageGenerationRequestTest {
    private fun request(fields: String = "") = parseImageGenerationRequest(Json.parseToJsonElement("""{"prompt":"一棵树"$fields}""").jsonObject)

    private fun rejects(fields: String, message: String) {
        val failure = runCatching { request(fields) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).contains(message)
    }

    @Test fun `省略参数保持空值且标准与自定义尺寸可用`() {
        assertThat(request().requested.n).isNull()
        listOf("auto", "1024x1024", "1536x1024", "1024x1536", "2048x2048", "3840x2160", "2160x3840", "768x2304").forEach {
            assertThat(request(",\"size\":\"$it\"").requested.size).isEqualTo(it)
        }
    }

    @Test fun `尺寸格式倍数比例与上限均校验`() {
        listOf("1024*1024", "1024X1024", "0x1024", "1025x1024", "256x1024", "3856x2160", "3840x3840", "999999999999999x1024").forEach {
            rejects(",\"size\":\"$it\"", "size")
        }
    }

    @Test fun `所有枚举接受允许值并拒绝枚举外值`() {
        mapOf("quality" to listOf("auto", "low", "medium", "high", "xhigh", "max"),
            "background" to listOf("auto", "transparent", "opaque"), "output_format" to listOf("png", "jpeg", "webp")).forEach { (key, values) ->
            values.forEach { request(",\"$key\":\"$it\"") }
            rejects(",\"$key\":\"invalid\"", values.first())
            rejects(",\"$key\":true", key)
        }
        listOf("high", "low").forEach { request(",\"images\":[\"/a.png\"],\"input_fidelity\":\"$it\"") }
        rejects(",\"images\":[\"/a.png\"],\"input_fidelity\":\"auto\"", "high")
    }

    @Test fun `压缩与透明背景组合校验且未指定格式使用服务端png默认值`() {
        request(",\"background\":\"transparent\"")
        request(",\"background\":\"transparent\",\"output_format\":\"webp\",\"output_compression\":0")
        request(",\"output_format\":\"jpeg\",\"output_compression\":100")
        rejects(",\"background\":\"transparent\",\"output_format\":\"jpeg\"", "png")
        listOf("", ",\"output_format\":\"png\"").forEach { rejects("$it,\"output_compression\":80", "jpeg/webp") }
        listOf("-1", "101", "1.5", "\"50\"").forEach { rejects(",\"output_format\":\"jpeg\",\"output_compression\":$it", "0–100") }
    }

    @Test fun `数量图片数组和编辑专用参数校验`() {
        listOf(1, 4).forEach { assertThat(request(",\"n\":$it").requested.n).isEqualTo(it) }
        listOf("0", "5", "1.5", "\"2\"").forEach { rejects(",\"n\":$it", "1–4") }
        listOf("[]", "[1]", "[\"\"]", "\"/a.png\"", (1..17).joinToString(",", "[", "]") { "\"/a.png\"" }).forEach {
            rejects(",\"images\":$it", "images")
        }
        assertThat(request(",\"images\":" + (1..16).joinToString(",", "[", "]") { "\"/a.png\"" }).images).hasSize(16)
        rejects(",\"mask\":\"/mask.png\"", "images")
        rejects(",\"input_fidelity\":\"high\"", "images")
    }

    @Test fun `提示词必填且最多32000字符`() {
        listOf("{}", """{"prompt":true}""", """{"prompt":" "}""").forEach {
            assertThat(runCatching { parseImageGenerationRequest(Json.parseToJsonElement(it).jsonObject) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        parseImageGenerationRequest(buildJsonObject { put("prompt", "字".repeat(32000)) })
        assertThat(runCatching { parseImageGenerationRequest(buildJsonObject { put("prompt", "字".repeat(32001)) }) }.exceptionOrNull()?.message).contains("32000")
    }
}
