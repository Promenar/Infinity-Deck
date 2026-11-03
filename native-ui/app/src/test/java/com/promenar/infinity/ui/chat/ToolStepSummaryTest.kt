package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ImageGenerationActual
import com.promenar.infinity.data.model.ImageGenerationRequested
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import org.junit.Test

class ToolStepSummaryTest {

    private fun image(path: String, mime: String = "image/png", bytes: Long = 1_000, width: Int? = 1024, height: Int? = 1024) =
        MediaRef(path, mime, MediaKind.IMAGE, bytes, width = width, height = height)

    @Test
    fun `生图摘要优先取接口回显，其次请求值`() {
        val data = ImageGenerationResultData(
            mode = "generate", model = "gpt-image-2",
            requested = ImageGenerationRequested(size = "1024x1024", quality = "low", outputFormat = "png"),
            actual = ImageGenerationActual(size = "1536x1024", quality = "HIGH", background = "transparent", outputFormat = "webp"),
        )
        val facts = imageGenerationFacts(data, listOf(image("/a.webp", "image/webp", 2_000), image("/b.webp", "image/webp", 3_000)))
        assertThat(facts.count).isEqualTo(2)
        assertThat(facts.dimensions).isEqualTo("1536×1024")
        assertThat(facts.quality).isEqualTo("high")
        assertThat(facts.format).isEqualTo("WEBP")
        assertThat(facts.transparent).isTrue()
        assertThat(facts.totalBytes).isEqualTo(5_000)
    }

    @Test
    fun `回显缺失时用请求值，auto 不展示`() {
        val data = ImageGenerationResultData(
            mode = "edit", model = "m",
            requested = ImageGenerationRequested(size = "auto", quality = "auto", outputFormat = "jpeg"),
            inputImages = listOf("/ref1.png", "/ref2.png"),
        )
        val facts = imageGenerationFacts(data, listOf(image("/out.jpg", "image/jpeg", width = 1024, height = 1536)))
        assertThat(facts.dimensions).isEqualTo("1024×1536")
        assertThat(facts.quality).isNull()
        assertThat(facts.format).isEqualTo("JPEG")
        assertThat(facts.referenceCount).isEqualTo(2)
    }

    @Test
    fun `旧数据没有结构化结果时从媒体推断，尺寸不一致不给统一尺寸`() {
        val facts = imageGenerationFacts(null, listOf(image("/a.png"), image("/b.png", width = 512, height = 512)))
        assertThat(facts.dimensions).isNull()
        assertThat(facts.format).isEqualTo("PNG")
        assertThat(facts.quality).isNull()
        assertThat(facts.referenceCount).isEqualTo(0)
    }

    @Test
    fun `查看媒体摘要只看媒体本身，缺少宽高或大小时留空`() {
        val facts = mediaViewFacts(listOf(image("/a.jpg", "image/jpeg", bytes = 0, width = null, height = null)))
        assertThat(facts.count).isEqualTo(1)
        assertThat(facts.dimensions).isNull()
        assertThat(facts.format).isEqualTo("JPEG")
        assertThat(facts.totalBytes).isNull()
    }

    @Test
    fun `参数字段读取只接受对象里的非空字符串`() {
        assertThat(toolArgumentText("""{"prompt":"一只猫","n":2}""", "prompt")).isEqualTo("一只猫")
        assertThat(toolArgumentText("""{"prompt":"  "}""", "prompt")).isNull()
        assertThat(toolArgumentText("""{"n":2}""", "n")).isNull()
        assertThat(toolArgumentText("不是 JSON", "prompt")).isNull()
        assertThat(toolArgumentText("""{"prompt":""", "prompt")).isNull()
    }

    @Test
    fun `参考图识别需要非空数组或字符串`() {
        assertThat(toolArgumentPresent("""{"images":["/a.png"]}""", "images")).isTrue()
        assertThat(toolArgumentPresent("""{"images":[]}""", "images")).isFalse()
        assertThat(toolArgumentPresent("""{"mask":"/m.png"}""", "mask")).isTrue()
        assertThat(toolArgumentPresent("""{"prompt":"x"}""", "images")).isFalse()
        assertThat(toolArgumentPresent("[1]", "images")).isFalse()
    }
}
