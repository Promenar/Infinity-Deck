package com.promenar.infinity.domain.mediagen

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class MediaGenerationContractTest {
    private val capabilities = MediaCapabilities(
        modes = setOf(GenerationMode.TEXT, GenerationMode.REFERENCE),
        maxImages = 1, maxVideos = 0, maxAudio = 0,
    )
    private val channel = MediaChannel("a", MediaGroup.VIDEO, capabilities)

    @Test fun defaultUsesFirstCompatibleChannelInGroupOrder() {
        val first = channel.copy(id = "first", capabilities = capabilities.copy(modes = setOf(GenerationMode.TEXT)))
        val audio = channel.copy(id = "audio", group = MediaGroup.AUDIO)
        assertEquals("a", resolveDefaultChannel(MediaGroup.VIDEO, GenerationMode.REFERENCE, listOf(audio, first, channel))?.id)
        assertNull(resolveDefaultChannel(MediaGroup.IMAGE, GenerationMode.TEXT, listOf(channel)))
    }

    @Test fun validationListsEveryExcessInputWithoutTruncation() {
        val inputs = listOf(
            MediaInput("one", InputKind.IMAGE, InputRole.REFERENCE),
            MediaInput("two", InputKind.IMAGE, InputRole.REFERENCE),
            MediaInput("three", InputKind.IMAGE, InputRole.REFERENCE),
            MediaInput("video", InputKind.VIDEO, InputRole.REFERENCE),
        )
        val errors = validateRequest(channel, MediaGenerationRequest("a", GenerationMode.REFERENCE, "prompt", inputs, "source"))
        assertEquals(listOf("two", "three", "video"), errors.excessInputs.map { it.location })
        assertEquals(4, inputs.size)
    }

    @Test fun validationRejectsWrongModeChannelAndFrameRoles() {
        val request = MediaGenerationRequest("other", GenerationMode.FIRST_LAST_FRAME, "p", listOf(
            MediaInput("end", InputKind.IMAGE, InputRole.LAST_FRAME)), "source")
        val errors = validateRequest(channel, request)
        assertTrue(errors.channelMismatch)
        assertTrue(errors.unsupportedMode)
        assertTrue("firstFrameCount" in errors.specErrors)
    }

    @Test fun pricingUsesExactDecimalAndUnknownUsageRemainsUnknown() {
        val price = UnitPrice(BigDecimal("0.000002"), "CNY")
        assertEquals(BigDecimal("0.002000"), price.cost(1000).amount)
        assertNull(costFromUsage(price, null))
        assertNull(costFromUsage(null, 0))
    }
}
