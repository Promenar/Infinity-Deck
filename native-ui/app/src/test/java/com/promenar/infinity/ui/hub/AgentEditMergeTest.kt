package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import org.junit.jupiter.api.Test

class AgentEditMergeTest {
    private val base = Agent(id = "a", name = "写作助手", description = "润色", systemPrompt = "P0", modelId = "m1", temperature = 0.5)

    @Test
    fun `本地改、远端未改时写入本地字段`() {
        val local = base.copy(description = "润色中文")
        val result = mergeAgentEdit(base, local, remote = base)
        assertThat(result.toWrite).containsExactly(AgentField.DESCRIPTION)
        assertThat(result.conflicts).isEmpty()
        assertThat(result.remoteOnly).isEmpty()
        assertThat(result.merged.description).isEqualTo("润色中文")
    }

    @Test
    fun `远端改、本地未改时静默采用远端值且不写入`() {
        val remote = base.copy(systemPrompt = "P1")
        val result = mergeAgentEdit(base, local = base, remote = remote)
        assertThat(result.toWrite).isEmpty()
        assertThat(result.remoteOnly).containsExactly(AgentField.SYSTEM_PROMPT)
        assertThat(result.conflicts).isEmpty()
        assertThat(result.merged).isEqualTo(remote)
    }

    @Test
    fun `两边改同一字段且取值不同为冲突，不覆盖远端`() {
        val local = base.copy(systemPrompt = "P-mine")
        val remote = base.copy(systemPrompt = "P-agent")
        val result = mergeAgentEdit(base, local, remote)
        assertThat(result.conflicts).containsExactly(AgentField.SYSTEM_PROMPT)
        assertThat(result.toWrite).isEmpty()
        assertThat(result.merged.systemPrompt).isEqualTo("P-agent")
    }

    @Test
    fun `两边改不同字段时各自保留`() {
        val local = base.copy(modelId = "m2")
        val remote = base.copy(description = "智能体写的描述")
        val result = mergeAgentEdit(base, local, remote)
        assertThat(result.toWrite).containsExactly(AgentField.MODEL_ID)
        assertThat(result.remoteOnly).containsExactly(AgentField.DESCRIPTION)
        assertThat(result.conflicts).isEmpty()
        assertThat(result.merged.modelId).isEqualTo("m2")
        assertThat(result.merged.description).isEqualTo("智能体写的描述")
    }

    @Test
    fun `两边改成同一个值既不冲突也不必写入`() {
        val local = base.copy(temperature = 0.9)
        val remote = base.copy(temperature = 0.9)
        val result = mergeAgentEdit(base, local, remote)
        assertThat(result.conflicts).isEmpty()
        assertThat(result.toWrite).isEmpty()
        assertThat(result.merged.temperature).isEqualTo(0.9)
    }

    @Test
    fun `名称改动同时携带自定义标记`() {
        val local = base.copy(name = "新名字", nameCustomized = true)
        val result = mergeAgentEdit(base, local, remote = base)
        assertThat(result.toWrite).containsExactly(AgentField.NAME)
        assertThat(result.merged.nameCustomized).isTrue()
    }
}
