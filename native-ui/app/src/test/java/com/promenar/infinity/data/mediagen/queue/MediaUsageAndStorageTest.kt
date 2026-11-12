package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MediaUsageAndStorageTest {
    @Test fun `按提供商模型币种聚合未知费用单独保留`() {
        fun entry(id: String, provider: String = "provider", model: String = "model", cost: Money? = null) = MediaUsageEntry(id, provider, model, "project/shot", cost, 1)
        val rows = summarizeMediaUsage(listOf(
            entry("a", cost = Money(BigDecimal("0.1"), "USD")), entry("b", cost = Money(BigDecimal("0.2"), "USD")),
            entry("c"), entry("d", cost = Money(BigDecimal.ONE, "CNY")), entry("e", provider = "other"), entry("f", model = "other"),
        ))
        assertEquals(5, rows.size)
        assertEquals(BigDecimal("0.3"), rows.single { it.providerId == "provider" && it.modelId == "model" && it.currency == "USD" }.knownCost!!.amount)
        assertNull(rows.single { it.providerId == "provider" && it.modelId == "model" && it.currency == null }.knownCost)
        assertEquals(1, rows.single { it.providerId == "provider" && it.modelId == "model" && it.currency == null }.unknownCostTasks)
    }
    @Test fun `持久化保留费用精度远端输出与恢复时间`() {
        val channel = MediaChannel("c", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT)))
        val task = MediaTask("task", MediaGenerationRequest("c", GenerationMode.TEXT, "树林", emptyList(), "project/shot", sessionId = "s", outputDir = "takes"), Money(BigDecimal("0.12345678901234567890"), "CNY"))
            .approve().submitting().started("remote")
        val record = PersistentMediaTask(task, channel, "binding", "provider", "model", 100,
            pollAttempt = 4, nextPollAt = 500, remoteOutputs = listOf(RemoteOutput("output", InputKind.VIDEO)), usage = mapOf("tokens" to 100))
        assertEquals(record, MediaTaskCodec.decode(MediaTaskCodec.encode(record)))
    }
    @Test fun `退避封顶不会溢出且拒绝非法并发配置`() {
        assertEquals(30000L, MediaPollingPolicy().delayAfter(Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { MediaQueueConcurrency(defaultProvider = 0) }
        assertThrows(IllegalArgumentException::class.java) { MediaQueueConcurrency(channelLimits = mapOf("c" to 9)) }
    }
    @Test fun `产物必须在请求目录下且路径不能逃逸`() {
        assertTrue(PersistentMediaGenerationQueue.isOutputPath("takes", "takes/a.mp4"))
        listOf("takes-other/a.mp4", "/takes/a.mp4", "takes/../other/a.mp4", "takes/./a.mp4", "takes/a\\b", "https://host/a").forEach {
            assertFalse(it, PersistentMediaGenerationQueue.isOutputPath("takes", it))
        }
    }
}
