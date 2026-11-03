package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRulesReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun workspace(root: File, kind: String = WorkspaceEntity.KIND_PROJECT) = SessionWorkspace.Directory(
        WorkspaceEntity("demo", kind, "示例项目", "demo", 0), root.canonicalFile,
    )

    @Test fun `读取根目录两份规则且AGENTS优先`() = runTest {
        val root = temporary.newFolder().canonicalFile
        File(root, "CLAUDE.md").writeText("第二份规则")
        File(root, "AGENTS.md").writeText("第一份规则")
        File(root, "nested").mkdir()
        File(root, "nested/AGENTS.md").writeText("嵌套规则")
        val rules = ProjectRulesReader().read(workspace(root))
        assertThat(rules.map { it.fileName }).containsExactly("AGENTS.md", "CLAUDE.md").inOrder()
        assertThat(rules.map { it.content }).containsExactly("第一份规则", "第二份规则").inOrder()
    }

    @Test fun `两文件共享字节上限且截断不拆开UTF8字符`() = runTest {
        val root = temporary.newFolder().canonicalFile
        File(root, "AGENTS.md").writeText("a".repeat(ProjectRulesReader.MAX_BYTES - 2) + "中")
        File(root, "CLAUDE.md").writeText("第二份")
        val rules = ProjectRulesReader().read(workspace(root))
        assertThat(rules.sumOf { it.includedBytes }).isAtMost(ProjectRulesReader.MAX_BYTES)
        assertThat(rules.first().content).doesNotContain("�")
        assertThat(rules.first().originalBytes).isEqualTo((ProjectRulesReader.MAX_BYTES + 1).toLong())
        assertThat(rules.all { it.truncated }).isTrue()
        assertThat(SystemPromptSections.projectRules(rules)).contains("已截断")
        assertThat(SystemPromptSections.projectRules(rules)).contains("原始大小")
    }

    @Test fun `相同元数据复用缓存且mtime大小删除重建均会失效`() = runTest {
        val root = temporary.newFolder().canonicalFile
        val file = File(root, "AGENTS.md").apply { writeText("first") }
        val reader = ProjectRulesReader()
        val first = reader.read(workspace(root)).single()
        assertThat(reader.read(workspace(root)).single()).isSameInstanceAs(first)
        file.writeText("later")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 5000))
        assertThat(reader.read(workspace(root)).single().content).isEqualTo("later")
        file.writeText("larger content")
        assertThat(reader.read(workspace(root)).single().content).isEqualTo("larger content")
        file.delete()
        assertThat(reader.read(workspace(root))).isEmpty()
        file.writeText("restored")
        assertThat(reader.read(workspace(root)).single().content).isEqualTo("restored")
    }

    @Test fun `符号链接规则与经过链接的根目录均拒绝且不复用缓存`() = runTest {
        val root = temporary.newFolder().canonicalFile
        val outside = temporary.newFolder().canonicalFile
        val file = File(root, "AGENTS.md").apply { writeText("允许") }
        val reader = ProjectRulesReader()
        assertThat(reader.read(workspace(root))).hasSize(1)
        file.delete()
        val target = File(outside, "AGENTS.md").apply { writeText("越界") }
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertThat(reader.read(workspace(root))).isEmpty()
        val link = File(root, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThat(reader.read(workspace(outside).copy(root = link))).isEmpty()
        assertThat(reader.read(workspace(outside).copy(root = File(root, "../${outside.name}")))).isEmpty()
        assertThat(reader.read(workspace(outside).copy(root = File(link, "child")))).isEmpty()
    }

    @Test fun `无工作区私有空间不可用目录不读取且错误只返回诊断类型`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val reader = ProjectRulesReader { name, reason ->
            diagnostics += name to reason
            throw IllegalStateException("诊断后端不可用")
        }
        assertThat(reader.read(null)).isEmpty()
        assertThat(reader.read(SessionWorkspace.Private)).isEmpty()
        assertThat(reader.read(SessionWorkspace.Unavailable("missing", null, "不可用"))).isEmpty()
        val root = temporary.newFolder().canonicalFile
        File(root, "AGENTS.md").mkdir()
        File(root, "CLAUDE.md").writeText("可读规则")
        assertThat(reader.read(workspace(root)).single().content).isEqualTo("可读规则")
        assertThat(diagnostics).hasSize(1)
        assertThat(diagnostics.single().first).isEqualTo("AGENTS.md")
        assertThat(diagnostics.single().second).doesNotContain(root.path)
    }

    @Test fun `只有CLAUDE可读取且另一文件增长缩短后缓存遵循共享预算`() = runTest {
        val root = temporary.newFolder().canonicalFile
        File(root, "CLAUDE.md").writeText("中文规则")
        val reader = ProjectRulesReader()
        assertThat(reader.read(workspace(root)).single().fileName).isEqualTo("CLAUDE.md")
        val agents = File(root, "AGENTS.md").apply { writeText("a".repeat(ProjectRulesReader.MAX_BYTES)) }
        val full = reader.read(workspace(root))
        assertThat(full.first().truncated).isFalse()
        assertThat(full.last().content).isEmpty()
        agents.writeText("短")
        val restored = reader.read(workspace(root))
        assertThat(restored.last().content).isEqualTo("中文规则")
        assertThat(restored.last().truncated).isFalse()
    }

    @Test fun `Linux和分支工作区使用解析后的宿主根目录`() = runTest {
        val root = temporary.newFolder().canonicalFile
        File(root, "AGENTS.md").writeText("宿主规则")
        for (kind in listOf(WorkspaceEntity.KIND_LINUX_HOME, "branch_worktree")) {
            assertThat(ProjectRulesReader().read(workspace(root, kind)).single().content).isEqualTo("宿主规则")
        }
    }

    @Test fun `系统中规则位于会话指令之后且文件变化下一轮生效`() = runTest {
        val root = temporary.newFolder().canonicalFile
        val file = File(root, "AGENTS.md").apply { writeText("项目约定甲") }
        val params = ContextBuilderParams("s", "输入", assistantMsgId = "a",
            session = com.promenar.infinity.data.model.Session("s", "agent", customPrompt = "会话约定"),
            workspace = workspace(root))
        val builder = ContextBuilder()
        val first = builder.buildContext(params).finalSystemPrompt
        assertThat(first.indexOf("会话约定")).isLessThan(first.indexOf("## 项目规则"))
        assertThat(first.indexOf("项目约定甲")).isLessThan(first.indexOf("## Runtime Environment"))
        assertThat(first).contains("系统与用户的直接指示")
        file.writeText("项目约定乙新增")
        val next = builder.buildContext(params).finalSystemPrompt
        assertThat(next).contains("项目约定乙新增")
        assertThat(next).doesNotContain("项目约定甲")
    }

    @Test fun `实际系统段落边界可重建且用户同名标题保持在指令类别`() = runTest {
        val params = ContextBuilderParams("s", "输入", assistantMsgId = "a",
            session = com.promenar.infinity.data.model.Session("s", "agent", summary = "历史摘要"),
            agentSystemPrompt = "## 项目规则\n用户自定义提示")
        val result = ContextBuilder().buildContext(params)
        assertThat(result.systemPromptSections.joinToString("") { it.content }).isEqualTo(result.finalSystemPrompt)
        assertThat(result.systemPromptSections.first().id).isEqualTo("instructions")
        assertThat(result.systemPromptSections.first().content).contains("用户自定义提示")
        val normalized = result.systemPromptSections.map { section ->
            if (section.id == "history_summary") section.copy(content = com.promenar.infinity.data.generation.replaceHistorySummary(section.content, "历史摘要", "历史摘要")) else section
        }
        assertThat(normalized.joinToString("") { it.content }).isEqualTo(
            com.promenar.infinity.data.generation.replaceHistorySummary(result.finalSystemPrompt, "历史摘要", "历史摘要"))
    }
}
