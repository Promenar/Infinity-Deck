package com.promenar.infinity.domain.mediagen

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MediaGenerationContractsTest {
    private val channel = MediaChannel("video", MediaGroup.VIDEO, MediaCapabilities(
        GenerationMode.entries.toSet(), maxImages = 1, maxVideos = 1, maxAudio = 1,
        resolutions = listOf("720p"), minDurationSeconds = 3, maxDurationSeconds = 10,
    ))
    private fun request(mode: GenerationMode = GenerationMode.TEXT, inputs: List<MediaInput> = emptyList()) =
        MediaGenerationRequest("video", mode, "风吹过树林", inputs, "project/shot", sessionId = "session", outputDir = "takes")

    @Test fun `空提示词与不支持的规格分别列出`() {
        val result = validateRequest(channel, request().copy(prompt = "  ", specs = GenerationSpecs(resolution = "4k", durationSeconds = 20, width = 0, height = 2, frames = -1)))
        assertFalse(result.valid)
        assertTrue(result.specErrors.containsAll(listOf("prompt", "resolution", "durationSeconds", "width", "frames")))
    }
    @Test fun `参考上限按类型计数并保留超出输入顺序`() {
        val image = MediaInput("a.png", InputKind.IMAGE, InputRole.REFERENCE)
        val video = MediaInput("a.mp4", InputKind.VIDEO, InputRole.REFERENCE)
        val audio = MediaInput("a.wav", InputKind.AUDIO, InputRole.REFERENCE_VOICE)
        val extras = listOf(image.copy(location = "b.png"), video.copy(location = "b.mp4"), audio.copy(location = "b.wav"))
        val inputs = listOf(image, video, audio) + extras
        assertEquals(extras, validateRequest(channel, request(GenerationMode.REFERENCE, inputs)).excessInputs)
        assertEquals(6, inputs.size)
    }
    @Test fun `首尾帧必须各一张且不占参考上限`() {
        val first = MediaInput("first.png", InputKind.IMAGE, InputRole.FIRST_FRAME)
        val last = first.copy(location = "last.png", role = InputRole.LAST_FRAME)
        val noReferences = channel.copy(capabilities = channel.capabilities.copy(maxImages = 0))
        assertTrue(validateRequest(noReferences, request(GenerationMode.FIRST_LAST_FRAME, listOf(first, last))).valid)
        assertFalse(validateRequest(channel, request(GenerationMode.FIRST_LAST_FRAME, listOf(first))).valid)
        assertFalse(validateRequest(channel, request(GenerationMode.FIRST_FRAME, listOf(first, first))).valid)
        assertFalse(validateRequest(channel, request(GenerationMode.FIRST_FRAME)).valid)
    }
    @Test fun `模式角色媒体类型与缺少参考分别校验`() {
        val voice = MediaInput("a.wav", InputKind.AUDIO, InputRole.REFERENCE_VOICE)
        assertTrue(validateRequest(channel, request(GenerationMode.SPEECH, listOf(voice))).valid)
        assertEquals(listOf(voice), validateRequest(channel, request(inputs = listOf(voice))).invalidInputs)
        assertFalse(validateRequest(channel, request(GenerationMode.REFERENCE)).valid)
        assertFalse(validateRequest(channel, request(GenerationMode.SPEECH, listOf(voice.copy(kind = InputKind.IMAGE)))).valid)
        assertTrue(validateRequest(channel, request(GenerationMode.MUSIC)).valid)
    }
    @Test fun `默认通道遵守组与模式顺序`() {
        val image = channel.copy(id = "image", group = MediaGroup.IMAGE)
        assertEquals(channel, resolveDefaultChannel(MediaGroup.VIDEO, GenerationMode.TEXT, listOf(image, channel)))
        assertNull(resolveDefaultChannel(MediaGroup.AUDIO, GenerationMode.TEXT, listOf(channel)))
    }
    @Test fun `多项计费支持每百万token秒字符和固定附加费`() {
        val pricing = MediaPricing(listOf(
            BillingItem("tokens", UnitPrice(BigDecimal("2"), "USD"), BigDecimal("1000000")),
            BillingItem("seconds", UnitPrice(BigDecimal("0.1"), "USD")),
            BillingItem("characters", UnitPrice(BigDecimal("0.01"), "USD")),
            BillingItem("addon", UnitPrice(BigDecimal("0.3"), "USD"), fixedUnits = BigDecimal.ONE),
        ))
        assertEquals(0, pricing.cost(mapOf("tokens" to 500000, "seconds" to 2, "characters" to 10))!!.amount.compareTo(BigDecimal("1.6")))
        assertNull(pricing.cost(mapOf("tokens" to 0, "seconds" to 0)))
        assertNull(costFromUsage(null, 0))
        assertNull(costFromUsage(UnitPrice(BigDecimal.ONE, "USD"), null))
    }
    @Test fun `混合币种负用量和重复计费项拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { MediaPricing(listOf(BillingItem("a", UnitPrice(BigDecimal.ONE, "USD")), BillingItem("b", UnitPrice(BigDecimal.ONE, "CNY")))) }
        assertThrows(IllegalArgumentException::class.java) { UnitPrice(BigDecimal.ONE, "USD").cost(-1) }
        assertThrows(IllegalArgumentException::class.java) { BillingItem("a", UnitPrice(BigDecimal.ONE, "USD"), BigDecimal.ZERO) }
    }
}
