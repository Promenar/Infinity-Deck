package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.skills.AgentSkillMetadata
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillStatus
import org.junit.Test

class SystemPromptSectionsSkillsTest {
    @Test fun `通用与完整工具引导均要求调用前给出简短进展`() {
        for (generic in listOf(true, false)) {
            val prompt = SystemPromptSections.toolGuidance(setOf("read_file"), generic)
            assertThat(prompt).contains("Before starting a group of tool calls or changing direction")
            assertThat(prompt).contains("one sentence")
            assertThat(prompt).contains("parallel calls")
        }
    }

    private fun skill(
        name: String,
        scope: SkillScope = SkillScope.USER,
        implicit: Boolean = true,
        status: SkillStatus = SkillStatus.ENABLED,
        description: String = "Description $name",
    ) = DiscoveredSkill(
        id = "${scope.name}:$name", scope = scope, name = name, description = description,
        displayName = null, shortDescription = null,
        virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
        files = emptyList(), status = status, modifiedSinceConfirmed = false,
        allowImplicitInvocation = implicit, brokenReason = null, metadata = AgentSkillMetadata(name, description),
    )

    @Test fun `两级同名并列且清单包含来源和文档路径`() {
        val catalog = SystemPromptSections.skillCatalog(listOf(skill("demo"), skill("demo", SkillScope.PROJECT)))

        assertThat(catalog).contains("source=user")
        assertThat(catalog).contains("source=project")
        assertThat(catalog).contains("/skills/demo/SKILL.md")
        assertThat(catalog).contains("/.agents/skills/demo/SKILL.md")
        assertThat(catalog).contains("activate_skill")
        assertThat(catalog).contains("shell_exec")
        assertThat(catalog).contains("python_exec")
        assertThat(catalog).contains("\$INFINITY_SKILLS/<name>")
    }

    @Test fun `完整技能目录包含显式技能但排除停用与未信任技能`() {
        val skills = listOf(
            skill("demo", implicit = false), skill("demo", SkillScope.PROJECT, implicit = false),
            skill("disabled", status = SkillStatus.DISABLED), skill("pending", status = SkillStatus.PENDING_TRUST),
            skill("broken", status = SkillStatus.BROKEN),
        )
        val catalog = SystemPromptSections.skillCatalog(skills)
        assertThat(catalog).contains("/skills/demo/SKILL.md")
        assertThat(catalog).contains("/.agents/skills/demo/SKILL.md")
        assertThat(catalog).contains("explicit request only")
        listOf("disabled", "pending", "broken").forEach { assertThat(catalog).doesNotContain("/$it/SKILL.md") }
    }

    @Test fun `技能目录不依赖本条用户消息或扫描顺序`() {
        val skills = listOf(skill("zulu", implicit = false), skill("alpha"))
        val catalog = SystemPromptSections.skillCatalog(skills)
        listOf("", "请使用 \$zulu", "x\$alpha", "/zulu 执行任务").forEach {
            assertThat(SystemPromptSections.skillCatalog(skills.reversed(), it)).isEqualTo(catalog)
        }
        assertThat(catalog.indexOf("- alpha:")).isLessThan(catalog.indexOf("- zulu:"))
    }

    @Test fun `窗口截断只依赖技能清单与窗口`() {
        val skills = (0..29).map { skill("alpha-$it", description = "A".repeat(100)) } + skill("zulu", implicit = false)
        val catalog = SystemPromptSections.skillCatalog(skills, modelContextTokens = 15_000)
        assertThat(SystemPromptSections.skillCatalog(skills, "\$zulu", 15_000)).isEqualTo(catalog)
        assertThat(catalog.length).isAtMost(1200)
        val listed = catalog.lineSequence().count { it.startsWith("- ") && it.contains("source=") }
        assertThat(catalog).contains("${31 - listed} more skills")
    }

    @Test fun `普通技能按名称顺序截断且默认不再限制五十项`() {
        val catalog = SystemPromptSections.skillCatalog((0..59).reversed().map { skill("s-$it", description = "D") })
        assertThat(catalog).contains("/skills/s-59/SKILL.md")
        assertThat(catalog.indexOf("- s-0:")).isLessThan(catalog.indexOf("- s-1:"))
        assertThat(catalog.length).isAtMost(8000)
    }

    @Test fun `显式技能数量超过预算时保留能容纳的名称前缀`() {
        val skills = (0..49).map { skill("demo-$it", implicit = false) }
        val mentions = skills.joinToString(" ") { "\$${it.name}" }
        val catalog = SystemPromptSections.skillCatalog(skills, mentions, modelContextTokens = 15_000)

        assertThat(catalog.length).isAtMost(1200)
        assertThat(catalog).contains("/skills/demo-0/SKILL.md")
        val listed = catalog.lineSequence().count { it.startsWith("- ") && it.contains("source=") }
        assertThat(listed).isGreaterThan(0)
        assertThat(catalog).contains("${50 - listed} more skills")
    }

    @Test fun `未知窗口使用八千字符上限且极小窗口不越界`() {
        val skills = (1..100).map { skill("skill-$it", description = "D".repeat(100)) }
        listOf<Int?>(null, 0, -1, Int.MAX_VALUE).forEach {
            val catalog = SystemPromptSections.skillCatalog(skills, modelContextTokens = it)
            assertThat(catalog).isNotEmpty()
            assertThat(catalog.length).isAtMost(8000)
            assertThat(catalog).contains("more skills")
        }
        assertThat(SystemPromptSections.skillCatalog(skills, modelContextTokens = 10).length).isAtMost(0)
    }

    @Test fun `短描述可用且多行描述保持单条清单格式`() {
        val catalog = SystemPromptSections.skillCatalog(listOf(skill("demo").copy(shortDescription = "Short\nsummary")))
        assertThat(catalog).contains("demo: Short summary")
    }
}
