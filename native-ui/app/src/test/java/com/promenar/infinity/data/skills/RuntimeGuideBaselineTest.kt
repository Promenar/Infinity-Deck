package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import java.io.File
import org.junit.Test

class RuntimeGuideBaselineTest {
    @Test fun `固定段声明Android宿主身份并指向内置指南`() {
        val prompt = SystemPromptSections.runtimeEnvironment("test-model")
        assertThat(prompt).contains("你是 Infinity Workbench 的智能体，运行在 Android 应用内")
        assertThat(prompt).contains("activate_skill")
        assertThat(prompt).contains("infinity-workbench")
        assertThat(prompt.lines().size).isAtMost(16)
    }

    @Test fun `权限固定段在所有运行模式与权限档声明配置写入逐次审批`() {
        for (mode in com.promenar.infinity.domain.model.ExecutionMode.entries)
            for (runMode in com.promenar.infinity.domain.model.SessionRunMode.entries) {
                val prompt = SystemPromptSections.permissions(mode, runMode = runMode)
                assertThat(prompt).contains("agents_write 每次都需用户审批")
                assertThat(prompt).contains("从下一轮生效")
            }
    }

    @Test fun `技能来源包含内置只读作用域`() {
        assertThat(SkillScope.entries.map { it.name }).contains("BUILTIN")
    }

    @Test fun `运行指南作为只读应用资产存在且拥有技能元数据`() {
        val asset = runtimeGuideMainDirectory().resolve("assets/builtin_skills/infinity-workbench/SKILL.md")
        assertThat(asset.isFile).isTrue()
        val document = SkillMarkdownParser.parse(asset.readText())
        assertThat(document.metadata.name).isEqualTo("infinity-workbench")
        assertThat(document.metadata.description).contains("权限")
        assertThat(document.body).contains("审批加书面同意")
    }
}
