package com.promenar.infinity.data.mediagen.cloud

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.ImageGenInput
import java.util.Base64
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiImageProtocolTest {

    @Test
    fun `geminiImageAspectRatio 映射标准分辨率并在 auto 或空值时返回 null`() {
        assertThat(geminiImageAspectRatio(null)).isNull()
        assertThat(geminiImageAspectRatio("")).isNull()
        assertThat(geminiImageAspectRatio("auto")).isNull()
        assertThat(geminiImageAspectRatio("AUTO")).isNull()

        assertThat(geminiImageAspectRatio("1024x1024")).isEqualTo("1:1")
        assertThat(geminiImageAspectRatio("1536x1024")).isEqualTo("3:2")
        assertThat(geminiImageAspectRatio("1024x1536")).isEqualTo("2:3")
        assertThat(geminiImageAspectRatio("1920x1080")).isEqualTo("16:9")
        assertThat(geminiImageAspectRatio("1080x1920")).isEqualTo("9:16")
        assertThat(geminiImageAspectRatio("1024x768")).isEqualTo("4:3")
        assertThat(geminiImageAspectRatio("768x1024")).isEqualTo("3:4")
        assertThat(geminiImageAspectRatio("864x480")).isEqualTo("16:9")
        assertThat(geminiImageAspectRatio("2560x1088")).isEqualTo("21:9")
    }

    @Test
    fun `geminiImageGenerateContentBody 构造文生图与多图编辑及 mask 和 markup 载荷`() {
        // 1. 纯文生图且不传 size 时省略 imageConfig
        val plain = geminiImageGenerateContentBody(prompt = "一只戴眼镜的柴犬")
        val plainConfig = plain["generationConfig"]!!.jsonObject
        assertThat(plainConfig["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("IMAGE", "TEXT").inOrder()
        assertThat(plainConfig.containsKey("imageConfig")).isFalse()
        val plainContent = plain["contents"]!!.jsonArray.single().jsonObject
        assertThat(plainContent["role"]!!.jsonPrimitive.content).isEqualTo("user")
        assertThat(plainContent["parts"]!!.jsonArray).hasSize(1)

        // 2. 指定 size 时携带 imageConfig.aspectRatio
        val sized = geminiImageGenerateContentBody(prompt = "赛博雨夜街景", size = "1536x1024")
        assertThat(sized["generationConfig"]!!.jsonObject["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
            .isEqualTo("3:2")

        // 3. 多图编辑 + mask
        val refA = ImageGenInput(byteArrayOf(1, 2, 3), "image/png", "image-1.png")
        val refB = ImageGenInput(byteArrayOf(4, 5, 6), "image/jpeg", "image-2.jpg")
        val mask = ImageGenInput(byteArrayOf(7, 8, 9), "image/png", "mask.png")
        val edited = geminiImageGenerateContentBody(
            prompt = "把背景换成日落海滩",
            images = listOf(refA, refB),
            mask = mask,
            size = "1920x1080",
        )
        val editParts = edited["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertThat(editParts).hasSize(4)
        assertThat(editParts[0].jsonObject["text"]!!.jsonPrimitive.content).contains("把背景换成日落海滩")
        assertThat(editParts[0].jsonObject["text"]!!.jsonPrimitive.content).contains("Edit mask")
        assertThat(editParts[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/png")
        assertThat(editParts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            .isEqualTo(Base64.getEncoder().encodeToString(refA.bytes))
        assertThat(editParts[2].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/jpeg")
        assertThat(editParts[3].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            .isEqualTo(Base64.getEncoder().encodeToString(mask.bytes))

        // 4. markup 标注层
        val markup = ImageGenInput(byteArrayOf(10, 11), "image/png", "markup.png")
        val marked = geminiImageGenerateContentBody(prompt = "按标注移动杯子", images = listOf(refA), markup = markup)
        val markedParts = marked["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertThat(markedParts).hasSize(3)
        assertThat(markedParts[0].jsonObject["text"]!!.jsonPrimitive.content).contains("Visual markup")
    }

    @Test
    fun `parseGeminiImageResponse 提取最终图像并跳过思考分片且汇总修订说明与用量`() {
        val draftB64 = Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2))
        val finalB64 = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7, 6))
        val response = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("finishReason", "STOP")
                    put("content", buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("thought", true); put("text", "内部草稿思考") })
                            add(buildJsonObject {
                                put("thought", true)
                                put("inlineData", buildJsonObject {
                                    put("mimeType", "image/png")
                                    put("data", draftB64)
                                })
                            })
                            add(buildJsonObject { put("text", "最终画面说明") })
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/jpeg")
                                    put("data", "${finalB64.take(4)}\n${finalB64.drop(4)}")
                                })
                            })
                        })
                    })
                })
            })
            put("usageMetadata", buildJsonObject {
                put("promptTokenCount", 24)
                put("candidatesTokenCount", 1290)
                put("totalTokenCount", 1314)
            })
        }

        val parsed = parseGeminiImageResponse(response)
        assertThat(parsed.images).hasSize(1)
        assertThat(parsed.images.single().b64Json).isEqualTo(finalB64)
        assertThat(parsed.images.single().revisedPrompt).isEqualTo("最终画面说明")
        assertThat(parsed.outputFormat).isEqualTo("jpeg")
        assertThat(parsed.usage?.inputTokens).isEqualTo(24L)
        assertThat(parsed.usage?.outputTokens).isEqualTo(1290L)
        assertThat(parsed.usage?.totalTokens).isEqualTo(1314L)
    }

    @Test
    fun `parseGeminiImageResponse 在内容拦截或缺少 candidates 时抛出清晰错误`() {
        val blocked = buildJsonObject {
            put("promptFeedback", buildJsonObject { put("blockReason", "SAFETY") })
        }
        val blockedErr = assertThrows(IllegalStateException::class.java) {
            parseGeminiImageResponse(blocked)
        }
        assertThat(blockedErr.message).contains("SAFETY")

        val finishBlocked = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject { put("finishReason", "IMAGE_SAFETY") })
            })
        }
        val finishErr = assertThrows(IllegalStateException::class.java) {
            parseGeminiImageResponse(finishBlocked)
        }
        assertThat(finishErr.message).contains("IMAGE_SAFETY")

        val missing = buildJsonObject {}
        val missingErr = assertThrows(IllegalStateException::class.java) {
            parseGeminiImageResponse(missing)
        }
        assertThat(missingErr.message).contains("candidates")
    }
}
