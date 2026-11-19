package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.FileSkillCatalog
import com.promenar.infinity.data.skills.InMemoryPreferences
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.SkillCatalogFiles
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillWorkspace
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillToolsTest {
    @Test fun `技能广告在单路调度器上等待异步工作区并保持同步清单一致`() = runBlocking {
        install(scope = SkillScope.PROJECT)
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
        val registry = AgentSkillToolRegistry(catalog, {
            kotlinx.coroutines.withContext(dispatcher) { kotlinx.coroutines.yield(); resolveWorkspace(it) }
        }, "session")
        val asyncTools = kotlinx.coroutines.withTimeout(2_000) {
            kotlinx.coroutines.withContext(dispatcher) { registry.getAllToolsAsync() }
        }
        assertThat(asyncTools.map { it.runtimeToolId }).containsAtLeast("activate_skill", "read_skill_file")
        assertThat(asyncTools).isEqualTo(registry.getAllTools())
    }

    @get:Rule val temporary = TemporaryFolder()
    private val userRoot by lazy { temporary.newFolder("users") }
    private val projectRoot by lazy { temporary.newFolder("project") }
    private val workspace by lazy { SkillWorkspace("project", projectRoot) }
    private val directory by lazy {
        SessionWorkspace.Directory(WorkspaceEntity("project", "project", "项目", "project", 1), projectRoot)
    }
    private val catalog by lazy {
        FileSkillCatalog(userRoot, InMemoryPreferences(), temporary.newFolder("state"), Dispatchers.IO,
            System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem()))
    }
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath = "/wrong-workspace"
        override val workspaceRootUuid = "root"
    }
    private val resolveWorkspace: suspend (String) -> SessionWorkspace = { sessionId ->
        check(sessionId == "session")
        directory
    }

    private suspend fun install(
        name: String = "demo",
        scope: SkillScope = SkillScope.USER,
        enabled: Boolean = true,
        implicit: Boolean = true,
    ): DiscoveredSkill {
        val root = if (scope == SkillScope.USER) userRoot else File(projectRoot, ".agents/skills")
        val dir = File(root, name).apply { mkdirs() }
        dir.resolve("SKILL.md").writeText("---\nname: $name\ndescription: Demo\n---\nInstructions for ${scope.name}\n")
        dir.resolve("references").mkdirs()
        dir.resolve("references/guide.md").writeText("Guide for ${scope.name}")
        dir.resolve("scripts").mkdirs()
        dir.resolve("scripts/demo.py").writeText("print('demo')\n")
        if (!implicit) {
            dir.resolve("agents").mkdirs()
            dir.resolve("agents/openai.yaml").writeText("policy:\n  allow_implicit_invocation: false\n")
        }
        val skill = catalog.scan(workspace).skills.single { it.name == name && it.scope == scope }
        if (enabled) {
            if (scope == SkillScope.PROJECT) catalog.trust(skill) else catalog.setEnabled(skill, true)
        }
        return catalog.scan(workspace).skills.single { it.id == skill.id }
    }

    @Test fun `两种选择参数均通过真实schema门禁且缺少选择器拒绝执行`() = runTest {
        install()
        val activate = ActivateSkillTool(catalog)
        val read = ReadSkillFileTool(catalog)
        val validator = com.promenar.infinity.domain.tool.ToolSchemaValidator()
        for ((tool, args) in listOf(
            activate to skillArgs("name" to "demo"),
            activate to skillArgs("path" to "/skills/demo/SKILL.md"),
            read to skillArgs("name" to "demo", "path" to "references/guide.md"),
            read to skillArgs("path" to "/skills/demo/references/guide.md"),
        )) {
            assertThat(validator.validate(tool.parametersSchema, args))
                .isInstanceOf(com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid::class.java)
            assertThat(tool.execute(args, context).status).isEqualTo("success")
        }
        assertThat(activate.execute(skillArgs(), context).status).isEqualTo("error")
    }

    @Test fun `按名称加载用户技能包含正文来源目录文件与执行说明`() = runTest {
        install()
        val result = ActivateSkillTool(catalog, resolveWorkspace).execute(skillArgs("name" to "demo"), context)

        assertThat(result.status).isEqualTo("success")
        listOf("Instructions for USER", "user", "/skills/demo", "references/guide.md", "scripts/demo.py", "shell_exec", "python_exec", "approval", "\$INFINITY_SKILLS/demo").forEach {
            assertThat(result.content).contains(it)
        }
        assertThat(result.content).doesNotContain("reference only")
    }

    @Test fun `按名称加载项目技能从执行上下文会话解析工作区`() = runTest {
        install(scope = SkillScope.PROJECT)
        val result = ActivateSkillTool(catalog, resolveWorkspace).execute(skillArgs("name" to "demo"), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("Instructions for PROJECT")
        assertThat(result.content).contains("/.agents/skills/demo")
        assertThat(result.content).contains(".agents/skills/demo")
    }

    @Test fun `两级同名仅凭名称拒绝并列出两条文档路径`() = runTest {
        install()
        install(scope = SkillScope.PROJECT)
        val result = ActivateSkillTool(catalog, resolveWorkspace).execute(skillArgs("name" to "demo"), context)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("/skills/demo/SKILL.md")
        assertThat(result.content).contains("/.agents/skills/demo/SKILL.md")
    }

    @Test fun `目录和文档虚拟路径分别消除同名歧义`() = runTest {
        install()
        install(scope = SkillScope.PROJECT)
        val tool = ActivateSkillTool(catalog, resolveWorkspace)
        for ((path, expected) in listOf("/skills/demo" to "USER", "/skills/demo/SKILL.md" to "USER", "/.agents/skills/demo/" to "PROJECT", "/.agents/skills/demo/SKILL.md" to "PROJECT")) {
            val result = tool.execute(skillArgs("path" to path), context)
            assertThat(result.status).isEqualTo("success")
            assertThat(result.content).contains("Instructions for $expected")
        }
        assertThat(tool.execute(skillArgs("name" to "other", "path" to "/skills/demo"), context).status).isEqualTo("error")
    }

    @Test fun `停用待信任和损坏技能都不能加载或读取附带文件`() = runTest {
        install("disabled", enabled = false)
        install("pending", SkillScope.PROJECT, enabled = false)
        val broken = File(userRoot, "broken").apply { mkdirs() }
        broken.resolve("SKILL.md").writeText("invalid")
        for (name in listOf("disabled", "pending", "broken", "missing")) {
            assertThat(ActivateSkillTool(catalog, resolveWorkspace).execute(skillArgs("name" to name), context).status).isEqualTo("error")
            assertThat(ReadSkillFileTool(catalog, resolveWorkspace).execute(skillArgs("name" to name, "path" to "references/guide.md"), context).status).isEqualTo("error")
        }
    }

    @Test fun `已启用的禁止隐式技能仍可加载`() = runTest {
        install(implicit = false)
        assertThat(ActivateSkillTool(catalog).execute(skillArgs("name" to "demo"), context).status).isEqualTo("success")
    }

    @Test fun `附带文件支持名称和两级完整虚拟路径且歧义失败关闭`() = runTest {
        install()
        val tool = ReadSkillFileTool(catalog, resolveWorkspace)
        assertThat(tool.execute(skillArgs("name" to "demo", "path" to "references/guide.md"), context).content).isEqualTo("Guide for USER")
        install(scope = SkillScope.PROJECT)
        assertThat(tool.execute(skillArgs("name" to "demo", "path" to "references/guide.md"), context).status).isEqualTo("error")
        for ((path, expected) in listOf("/skills/demo/references/guide.md" to "USER", "/.agents/skills/demo/references/guide.md" to "PROJECT")) {
            assertThat(tool.execute(skillArgs("path" to path), context).content).isEqualTo("Guide for $expected")
        }
        assertThat(tool.execute(skillArgs("skill_path" to "/.agents/skills/demo/SKILL.md", "path" to "references/guide.md"), context).content).isEqualTo("Guide for PROJECT")
    }

    @Test fun `读取文件沿用目录安全规则且不回退其它工作区`() = runTest {
        install()
        install(scope = SkillScope.PROJECT)
        val tool = ReadSkillFileTool(catalog, resolveWorkspace)
        for (path in listOf("../outside", ".git/config", "missing.md")) {
            assertThat(tool.execute(skillArgs("skill_path" to "/skills/demo", "path" to path), context).status).isEqualTo("error")
        }
        assertThat(tool.execute(skillArgs("name" to "other", "path" to "/skills/demo/references/guide.md"), context).status).isEqualTo("error")
        val other = ReadSkillFileTool(catalog) { SessionWorkspace.Private }
        assertThat(other.execute(skillArgs("path" to "/.agents/skills/demo/references/guide.md"), context).status).isEqualTo("error")
    }

    @Test fun `每次调用重扫以拒绝已停用或项目内容已变化的技能`() = runTest {
        val user = install()
        val tool = ActivateSkillTool(catalog, resolveWorkspace)
        assertThat(tool.execute(skillArgs("name" to "demo"), context).status).isEqualTo("success")
        catalog.setEnabled(user, false)
        assertThat(tool.execute(skillArgs("name" to "demo"), context).status).isEqualTo("error")
        install("project-demo", SkillScope.PROJECT)
        File(projectRoot, ".agents/skills/project-demo/SKILL.md").appendText("Changed")
        assertThat(tool.execute(skillArgs("name" to "project-demo"), context).status).isEqualTo("error")
    }

    @Test fun `工具广告包含用户或当前会话启用项目技能并遵守过滤`() = runTest {
        val registry = AgentSkillToolRegistry(catalog, resolveWorkspace, "session")
        assertThat(registry.getAllTools(null)).isEmpty()
        install(scope = SkillScope.PROJECT, enabled = false)
        assertThat(registry.getAllTools(null)).isEmpty()
        val pending = catalog.scan(workspace).project.single()
        catalog.trust(pending)
        assertThat(registry.getAllTools(null).map { it.function.name }).containsExactly("activate_skill", "read_skill_file")
        assertThat(registry.getAllTools(listOf("read_skill_file")).map { it.function.name }).containsExactly("read_skill_file")
        assertThat(AgentSkillToolRegistry(catalog, resolveWorkspace).getAllTools(null)).isEmpty()
        assertThat(AgentSkillToolRegistry(catalog, { SessionWorkspace.Private }, "other").getAllTools(null)).isEmpty()
        catalog.setEnabled(catalog.scan(workspace).project.single(), false)
        assertThat(registry.getAllTools(null)).isEmpty()
        install("user-demo", implicit = false)
        assertThat(AgentSkillToolRegistry(catalog).getAllTools(null)).hasSize(2)
        assertThat(registry.getSkill("activate_skill")).isNotNull()
    }

    @Test fun `目录扫描失败广告为空且工具返回错误而取消向上传播`() = runTest {
        val failed = object : SkillCatalog by catalog {
            override suspend fun scan(workspace: SkillWorkspace?) = throw java.io.IOException("unavailable")
        }
        assertThat(AgentSkillToolRegistry(failed).getAllTools(null)).isEmpty()
        assertThat(ActivateSkillTool(failed).execute(skillArgs("name" to "demo"), context).status).isEqualTo("error")
        val cancelled = object : SkillCatalog by catalog {
            override suspend fun scan(workspace: SkillWorkspace?) = throw CancellationException("cancelled")
        }
        assertThat(runCatching { ActivateSkillTool(cancelled).execute(skillArgs("name" to "demo"), context) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}
