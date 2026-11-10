package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.pet.PetPreferenceStore
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PetSkillTest {
    private val ctx = object : SkillExecutionContext {
        override val sessionId = "s"; override val agentId = "a"; override val workspacePath: String? = null; override val workspaceRootUuid = "r"
    }
    @Test fun `三个动作遵守总开关并反映收起状态`() = runTest {
        for (enabled in listOf(false, true)) for (dismissed in listOf(false, true)) for (action in listOf("summon", "dismiss", "status")) {
            val prefs = mockk<PetPreferenceStore>(relaxed = true)
            every { prefs.enabled } returns MutableStateFlow(enabled)
            every { prefs.dismissed } returns MutableStateFlow(dismissed)
            every { prefs.defaultPetId } returns MutableStateFlow("fish")
            val result = PetSkill(prefs) { "小鱼" }.execute(skillArgs("action" to action), ctx)
            if (!enabled) {
                assertThat(result.content).contains("宠物功能未启用")
                verify(exactly = 0) { prefs.setDismissed(any()) }
            } else when (action) {
                "summon" -> verify { prefs.setDismissed(false) }
                "dismiss" -> verify { prefs.setDismissed(true) }
                else -> { assertThat(result.content).contains("小鱼"); assertThat(result.content).contains(if (dismissed) "已收起" else "未收起") }
            }
        }
    }
    @Test fun `宠物工具各模式不需要审批`() {
        for (mode in com.promenar.infinity.domain.model.ExecutionMode.entries) {
            assertThat(com.promenar.infinity.domain.tool.ToolExecutionPolicy.requiresApproval(mode, "pet", com.promenar.infinity.domain.tool.ToolRisk.SAFE_READ)).isFalse()
        }
    }
    @Test fun `没有安装包与非法动作`() = runTest {
        val prefs = mockk<PetPreferenceStore>(relaxed = true)
        every { prefs.enabled } returns MutableStateFlow(true); every { prefs.dismissed } returns MutableStateFlow(false)
        every { prefs.defaultPetId } returns MutableStateFlow(null)
        val skill = PetSkill(prefs) { null }
        assertThat(skill.execute(skillArgs("action" to "status"), ctx).content).contains("没有安装宠物包")
        assertThat(skill.execute(skillArgs("action" to "bad"), ctx).status).isEqualTo("error")
    }
}
