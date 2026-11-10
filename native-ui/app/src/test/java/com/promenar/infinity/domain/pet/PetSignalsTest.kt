package com.promenar.infinity.domain.pet

import com.promenar.infinity.domain.generation.GenerationPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class PetSignalsTest {
    private fun resolve(keys: List<String>): String? {
        val manifest = PetManifest(
            contract = "0.2", id = "id", name = "鱼", version = "1",
            license = PetLicense("许可", true, false), display = PetDisplay(125, "right"),
            clips = emptyMap(), map = keys.associateWith { PetReaction() },
        )
        return PetSignalResolver.resolve(manifest, "task.tool.read_file")?.signal
    }

    @Test
    fun `工具信号命中自身`() {
        assertEquals("task.tool.read_file", resolve(listOf("task.tool.read_file", "task.tool", "task.working", "idle")))
    }

    @Test
    fun `工具信号回退到工具类别`() {
        assertEquals("task.tool", resolve(listOf("task.tool", "task.working", "idle")))
    }

    @Test
    fun `工具信号回退到工作类别`() {
        assertEquals("task.working", resolve(listOf("task.working", "idle")))
    }

    @Test
    fun `工具信号回退到空闲`() {
        assertEquals("idle", resolve(listOf("idle")))
    }

    @Test
    fun `仅空闲映射适用于任意信号`() {
        val manifest = PetManifest(
            contract = "0.2", id = "id", name = "鱼", version = "1",
            license = PetLicense("许可", true, false), display = PetDisplay(125, "right"),
            clips = emptyMap(), map = mapOf("idle" to PetReaction()),
        )
        listOf("task.tool.read_file", "role.enter", "未知").forEach {
            assertEquals("idle", PetSignalResolver.resolve(manifest, it)?.signal)
        }
    }

    @Test
    fun `每个生成阶段都有预期映射`() {
        val expected = mapOf(
            GenerationPhase.QUEUED to "task.queued",
            GenerationPhase.PREPARING to "task.queued",
            GenerationPhase.BUILDING_CONTEXT to "task.queued",
            GenerationPhase.CONNECTING to "task.queued",
            GenerationPhase.THINKING to "task.thinking",
            GenerationPhase.STREAMING to "task.generating",
            GenerationPhase.EXECUTING_TOOLS to "task.tool",
            GenerationPhase.WAITING_APPROVAL to "task.approval",
            GenerationPhase.WAITING_INPUT to "task.ask",
            GenerationPhase.POST_PROCESSING to "task.finishing",
            GenerationPhase.COMPLETED to "task.done",
            GenerationPhase.FAILED to "task.error",
            GenerationPhase.PERSISTENCE_FAILED to "task.error",
            GenerationPhase.CANCELLED to "task.cancelled",
        )
        assertEquals(GenerationPhase.entries.toSet(), expected.keys)
        expected.forEach { (phase, signal) -> assertEquals(signal, PetTaskSignalMapper.map(phase)) }
    }

    @Test
    fun `审批优先于回答`() {
        assertEquals("task.approval", PetTaskSignalMapper.map(GenerationPhase.STREAMING, true, true))
    }

    @Test
    fun `流式阶段只有思考增长时映射思考`() {
        assertEquals("task.thinking", PetTaskSignalMapper.map(GenerationPhase.STREAMING, reasoningGrowing = true))
        assertEquals("task.generating", PetTaskSignalMapper.map(GenerationPhase.STREAMING))
        assertEquals("task.generating", PetTaskSignalMapper.map(GenerationPhase.STREAMING, contentGrowing = true))
        assertEquals("task.generating", PetTaskSignalMapper.map(
            GenerationPhase.STREAMING, contentGrowing = true, reasoningGrowing = true,
        ))
    }

    @Test
    fun `空闲阈值在超过时切换`() {
        assertEquals("idle", PetIdleSignalMapper.map(120))
        assertEquals("idle.long", PetIdleSignalMapper.map(121))
        assertEquals("idle.long", PetIdleSignalMapper.map(600))
        assertEquals("sleep", PetIdleSignalMapper.map(601))
    }
}
