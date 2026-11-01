package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.skills.AgentSkillMetadata
import com.promenar.infinity.data.skills.FileSkillCatalog
import com.promenar.infinity.data.skills.InMemoryPreferences
import com.promenar.infinity.data.skills.ParsedSkillDocument
import com.promenar.infinity.data.skills.SkillCatalogFiles
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.ui.chat.command.slashTestSkill
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderParams
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlashSkillActivationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val document: suspend (com.promenar.infinity.data.skills.DiscoveredSkill) -> ParsedSkillDocument? = {
        ParsedSkillDocument(AgentSkillMetadata(it.name, it.description), "Steps for ${it.scope.name}")
    }

    @Test fun `点名可用技能时加载正文，工作区同名优先`() = runTest {
        val skills = listOf(slashTestSkill("demo"), slashTestSkill("demo", SkillScope.PROJECT))
        val requested = SlashSkillActivation.resolve("/demo 帮我整理", skills, document) as RequestedSkill.Loaded
        assertThat(requested.name).isEqualTo("demo")
        assertThat(requested.activation).contains("# Skill: demo")
        assertThat(requested.activation).contains("Steps for PROJECT")
        assertThat(requested.activation).contains("/.agents/skills/demo/SKILL.md")
        assertThat(requested.activation).contains("- references/guide.md")
    }

    @Test fun `不是命令、没有同名技能时不注入，技能不可用或读取失败时注入说明`() = runTest {
        val skills = listOf(slashTestSkill("demo"), slashTestSkill("off", status = SkillStatus.DISABLED))
        assertThat(SlashSkillActivation.resolve("普通消息", skills, document)).isNull()
        assertThat(SlashSkillActivation.resolve("/etc/hosts 是什么", skills, document)).isNull()
        assertThat(SlashSkillActivation.resolve("/missing 你好", skills, document)).isNull()
        assertThat(SlashSkillActivation.resolve("/off 你好", skills, document)).isEqualTo(RequestedSkill.Unavailable("off"))
        assertThat(SlashSkillActivation.resolve("/demo", skills) { null }).isEqualTo(RequestedSkill.Unavailable("demo"))
        assertThat(SlashSkillActivation.resolve("/demo", skills) { throw IOException("读取失败") }).isEqualTo(RequestedSkill.Unavailable("demo"))
    }

    @Test fun `预加载内容与 activate_skill 返回一致`() = runTest {
        val userRoot = temporary.newFolder("users")
        val catalog = FileSkillCatalog(
            userRoot, InMemoryPreferences(), temporary.newFolder("state"), Dispatchers.IO,
            System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem()),
        )
        File(userRoot, "demo").apply { mkdirs() }.resolve("SKILL.md")
            .writeText("---\nname: demo\ndescription: Demo\n---\nInstructions for demo\n")
        File(userRoot, "demo/scripts").mkdirs()
        File(userRoot, "demo/scripts/run.py").writeText("print(1)\n")
        catalog.setEnabled(catalog.scan(null).skills.single(), true)
        val skills = catalog.scan(null).skills

        val loaded = SlashSkillActivation.resolve("/demo 开始", skills) { catalog.readDocument(it) } as RequestedSkill.Loaded
        val tool = ActivateSkillTool(catalog).execute(
            skillArgs("name" to "demo"),
            object : SkillExecutionContext {
                override val sessionId = "session"
                override val agentId = "agent"
                override val workspacePath = "/"
                override val workspaceRootUuid = "root"
            },
        )
        assertThat(tool.status).isNotEqualTo("error")
        assertThat(loaded.activation).isEqualTo(tool.content)
        assertThat(loaded.activation).contains("Instructions for demo")
        assertThat(loaded.activation).contains("- scripts/run.py")
    }

    @Test fun `上下文段落说明技能已加载并保留正文`() {
        val section = SlashSkillActivation.promptSection(RequestedSkill.Loaded("demo", "# Skill: demo\nbody"))
        assertThat(section.lines().first()).isEqualTo("## Requested Skill")
        assertThat(section).contains("`demo`")
        assertThat(section).contains("do not call `activate_skill`")
        assertThat(section).contains("<skill_activation>\n# Skill: demo\nbody\n</skill_activation>")
        val unavailable = SlashSkillActivation.promptSection(RequestedSkill.Unavailable("off"))
        assertThat(unavailable).contains("`off`")
        assertThat(unavailable).contains("not available")
        assertThat(unavailable).doesNotContain("<skill_activation>")
    }

    @Test fun `上下文构建把点名技能放在冻结用户块中且不改变系统提示`() = runTest {
        val session = Session(id = "s1", agentId = "a1", summary = "Earlier summary")
        val base = ContextBuilderParams(
            sessionId = "s1", content = "/demo 整理", assistantMsgId = "m1", session = session,
            availableToolNames = setOf("activate_skill"), availableSkills = listOf(slashTestSkill("demo")),
        )
        val context = ContextBuilder().buildContext(
            base.copy(requestedSkill = RequestedSkill.Loaded("demo", "# Skill: demo\nSteps")),
        )
        val plain = ContextBuilder().buildContext(base.copy(content = "整理"))
        assertThat(context.finalSystemPrompt).isEqualTo(plain.finalSystemPrompt)
        assertThat(context.finalSystemPrompt).doesNotContain("## Requested Skill")
        assertThat(context.turnContext).contains("<skill_activation>\n# Skill: demo\nSteps\n</skill_activation>")
        assertThat(plain.turnContext).doesNotContain("## Requested Skill")
    }

    @Test fun `合法点名加载多个技能并保持确定顺序且拒绝词内伪点名`() = runTest {
        val skills = listOf(slashTestSkill("zulu"), slashTestSkill("alpha"))
        val requested = SlashSkillActivation.resolveAll("/zulu 开始，请使用 \$alpha 与 \$zulu", skills, document)
        assertThat(requested.map { it.name }).containsExactly("alpha", "zulu").inOrder()
        assertThat(SlashSkillActivation.resolveAll("x\$alpha \$zulu--bad \$alpha_", skills, document)).isEmpty()
    }
}
