package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.domain.media.MediaFetchPaths
import com.promenar.infinity.data.ssh.isSftpProtectedLocalPath
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.ActivateSkillTool
import com.promenar.infinity.ui.chat.manager.skills.ReadSkillFileTool
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinSkillCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val user by lazy { temporary.newFolder("user") }
    private val workspace by lazy { SkillWorkspace("project", temporary.newFolder("project")) }
    private val catalog by lazy { BuiltinSkillCatalog(
        FileSkillCatalog(user, InMemoryPreferences(), temporary.newFolder("state"), Dispatchers.IO,
            System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem())),
    ) { runtimeGuideMainDirectory().resolve("assets/$it").readText() } }
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath = "/"
        override val workspaceRootUuid = "root"
    }

    @Test fun `没有用户或项目技能时内置技能始终启用且正文可通过两个入口读取`() = runTest {
        val skill = catalog.scan(null).skills.single()
        assertThat(skill.scope).isEqualTo(SkillScope.BUILTIN)
        assertThat(skill.status).isEqualTo(SkillStatus.ENABLED)
        val args = buildJsonObject { put("name", BuiltinSkillCatalog.NAME) }
        val activated = ActivateSkillTool(catalog).execute(args, context)
        assertThat(activated.status).isEqualTo("success")
        assertThat(activated.content).contains("五级权限边界")
        assertThat(activated.content).doesNotContain("Script directory in the shell")
        val read = ReadSkillFileTool(catalog).execute(buildJsonObject {
            put("path", "${skill.virtualDir}/SKILL.md")
        }, context)
        assertThat(read.status).isEqualTo("success")
        assertThat(SkillMarkdownParser.parse(read.content)).isEqualTo(catalog.readDocument(skill))
        for (path in listOf("../SKILL.md", "/SKILL.md", "missing.md", ".git/config")) {
            assertThat(runCatching { catalog.readFile(skill, path) }.isFailure).isTrue()
        }
    }

    @Test fun `会话技能选择为空或只选其它技能时内置读取工具仍可用`() = runTest {
        val registry = com.promenar.infinity.ui.chat.manager.skills.AgentSkillToolRegistry(catalog)
        val resolver = com.promenar.infinity.data.generation.DefaultSessionToolResolver(InMemoryPreferences(), registry)
        for (selected in listOf(emptyList(), listOf("unrelated"))) {
            val session = com.promenar.infinity.data.model.Session("s", "a", activeSkillIds = selected)
            assertThat(resolver.resolve(session).map { it.function.name }).containsAtLeast("activate_skill", "read_skill_file")
        }
    }

    @Test fun `普通目录扫描失败不影响内置而取消信号必须传播`() = runTest {
        val failing = object : SkillCatalog by catalog {
            override suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot = throw java.io.IOException("目录不可读")
        }
        val isolated = BuiltinSkillCatalog(failing) { runtimeGuideMainDirectory().resolve("assets/$it").readText() }
        assertThat(isolated.scan(workspace).skills.single().scope).isEqualTo(SkillScope.BUILTIN)
        assertThat(ActivateSkillTool(isolated).execute(buildJsonObject { put("name", BuiltinSkillCatalog.NAME) }, context).status).isEqualTo("success")
        val cancelled = BuiltinSkillCatalog(object : SkillCatalog by catalog {
            override suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot = throw kotlinx.coroutines.CancellationException("取消")
        }) { runtimeGuideMainDirectory().resolve("assets/$it").readText() }
        assertThat(runCatching { cancelled.scan(null) }.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    @Test fun `工作区解析失败时仍能按名称或内置路径读取指南`() = runTest {
        val brokenWorkspace: suspend (String) -> com.promenar.infinity.data.workspace.SessionWorkspace = { error("工作区不可读") }
        val advertised = com.promenar.infinity.ui.chat.manager.skills.AgentSkillToolRegistry(catalog, brokenWorkspace, "s")
        assertThat(advertised.getAllTools(null).map { it.function.name }).containsExactly("activate_skill", "read_skill_file")
        val activated = ActivateSkillTool(catalog, brokenWorkspace).execute(buildJsonObject { put("name", BuiltinSkillCatalog.NAME) }, context)
        assertThat(activated.status).isEqualTo("success")
        val read = ReadSkillFileTool(catalog, brokenWorkspace).execute(buildJsonObject {
            put("path", "${BuiltinSkillCatalog.VIRTUAL_DIR}/SKILL.md")
        }, context)
        assertThat(read.status).isEqualTo("success")
    }

    @Test fun `内置技能不能启停删除信任或确认修改且伪造句柄不能加载`() = runTest {
        val skill = catalog.scan(workspace).builtin.single()
        for (action in listOf<suspend () -> Unit>(
            { catalog.setEnabled(skill, false) }, { catalog.setEnabled(skill, true) }, { catalog.delete(skill) },
            { catalog.trust(skill) }, { catalog.acknowledgeModified(skill) },
        )) assertThat(runCatching { action() }.isFailure).isTrue()
        assertThat(catalog.scan(null).builtin.single()).isEqualTo(skill)
        assertThat(runCatching { catalog.readDocument(skill.copy(name = "forged")) }.isFailure).isTrue()
    }

    @Test fun `用户及项目同名技能可用路径读取且不遮蔽内置按名称与斜杠调用`() = runTest {
        for (root in listOf(user, workspace.root.resolve(".agents/skills"))) {
            root.resolve(BuiltinSkillCatalog.NAME).apply { mkdirs() }.resolve("SKILL.md").writeText(
                "---\nname: infinity-deck\ndescription: 自定义副本\n---\n用户正文")
        }
        var snapshot = catalog.scan(workspace)
        catalog.setEnabled(snapshot.user.single(), true)
        catalog.trust(snapshot.project.single())
        snapshot = catalog.scan(workspace)
        assertThat(snapshot.byName(BuiltinSkillCatalog.NAME)).hasSize(3)
        assertThat((SkillSlashInvocation.select(snapshot.skills, BuiltinSkillCatalog.NAME) as SkillSlashInvocation.Selection.Ready).skill.scope).isEqualTo(SkillScope.BUILTIN)
        assertThat(ActivateSkillTool(catalog).execute(buildJsonObject { put("name", BuiltinSkillCatalog.NAME) }, context).content).contains("五级权限边界")
        val custom = ActivateSkillTool(catalog).execute(buildJsonObject { put("path", "/skills/infinity-deck") }, context)
        assertThat(custom.content).contains("用户正文")
        val listed = SystemPromptSections.skillCatalog(snapshot.skills, modelContextTokens = 1)
        assertThat(listed).contains("source=builtin")
        assertThat(listed).contains("${BuiltinSkillCatalog.VIRTUAL_DIR}/SKILL.md")
        assertThat(listed).doesNotContain("source=user")
        assertThat(SystemPromptSections.skillCatalog(snapshot.skills.reversed(), "另一轮输入", 1)).isEqualTo(listed)
    }

    @Test fun `所有文件路径入口拒绝内置虚拟路径且文件系统副本不会影响资产`() = runTest {
        val path = "${BuiltinSkillCatalog.VIRTUAL_DIR}/SKILL.md"
        assertThat(runCatching { ExternalFolderPaths.normalize(path) }.isFailure).isTrue()
        assertThat(runCatching { LinuxSessionPaths.normalize(path) }.isFailure).isTrue()
        assertThat(runCatching { MediaFetchPaths.targetPath(path) }.isFailure).isTrue()
        assertThat(isSftpProtectedLocalPath(path)).isTrue()
        val original = catalog.readFile(catalog.scan(null).builtin.single(), "SKILL.md")
        temporary.newFolder("rootfs").resolve(path.removePrefix("/")).apply { parentFile.mkdirs(); writeText("伪造文件") }
        assertThat(catalog.readFile(catalog.scan(null).builtin.single(), "SKILL.md")).isEqualTo(original)
    }
    @Test fun `宠物制作技能可发现并通过名称和路径读取`() = runTest {
        val combined = BuiltinSkillCatalog(catalog, includePetAuthoring = true) {
            runtimeGuideMainDirectory().resolve("assets/$it").readText()
        }
        val pet = combined.scan(null).builtin.single { it.name == BuiltinSkillCatalog.PET_NAME }
        assertThat(pet.description).contains("宠物包")
        val activated = ActivateSkillTool(combined).execute(buildJsonObject { put("name", pet.name) }, context)
        assertThat(activated.status).isEqualTo("success")
        assertThat(activated.content).contains("manifest.json")
        val read = ReadSkillFileTool(combined).execute(buildJsonObject { put("path", "${pet.virtualDir}/SKILL.md") }, context)
        assertThat(read.status).isEqualTo("success")
        assertThat(read.content).contains("persona.json")
    }
}
