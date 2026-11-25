package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import com.promenar.infinity.ui.chat.manager.skills.PythonExecSkill
import com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Test

class WorkspaceEnvironmentAliasesTest {
    @Test fun `Linux提示说明脚本唤醒的使用方式和边界`() {
        val guidance = SystemPromptSections.LINUX_SHELL_GUIDANCE
        for (part in listOf("infinity-wake", "background:true", "references/background-processes.md")) {
            assertThat(guidance).contains(part)
        }
        val reference = com.promenar.infinity.data.skills.runtimeGuideMainDirectory()
            .resolve("assets/builtin_skills/infinity-deck/references/background-processes.md").readText()
        for (part in listOf("infinity-wake [--key K] [--title T] [消息]", "stdin", "background: true", "60 秒", "4096", "同 key", "应用运行期间")) {
            assertThat(reference).contains(part)
        }
    }

    @Test fun `Linux命令构造时为普通和分支起始目录导出兼容别名`() {
        for (directory in listOf(null, "/worktrees/feature")) {
            val environment = linuxExecSpec("pwd", File("workspace"), "project", emptyList(), File("skills"),
                workspaceDirectory = directory).environment
            assertThat(environment["INFINITY_WORKSPACE"]).isEqualTo(directory ?: "/workspace")
            assertThat(environment["INFINITY_WORKSPACE_KIND"]).isEqualTo("project")
            assertThat(environment["INFINITY_SKILLS"]).isEqualTo("/mnt/skills")
            for (suffix in listOf("WORKSPACE", "WORKSPACE_KIND", "SKILLS")) {
                assertThat(environment["NEXARA_$suffix"]).isEqualTo(environment["INFINITY_$suffix"])
            }
        }
    }

    @Test fun `不存在的新变量不会从过期旧别名派生且输入不被修改`() {
        val source = mapOf("NEXARA_SKILLS" to "/stale", "PATH" to "/bin")
        assertThat(source.withLegacyWorkspaceAliases()).containsExactly("PATH", "/bin")
        assertThat(source).containsEntry("NEXARA_SKILLS", "/stale")
    }

    @Test fun `内置技能描述和系统提示词仅公布新名`() {
        val service = mockk<BuiltinExecService> { every { pipAvailable } returns true }
        val descriptions = listOf(ShellExecSkill(service).description, PythonExecSkill(service).description) +
            SessionRunMode.entries.map { mode ->
                SystemPromptSections.toolGuidance(setOf("shell_exec", "python_exec", "read_file"), false, runMode = mode)
            } + SystemPromptSections.LINUX_SHELL_GUIDANCE
        descriptions.forEach { description ->
            assertThat(description).doesNotContain("NEXARA_")
        }
        descriptions.take(2).forEach { description ->
            assertThat(description).contains("INFINITY_WORKSPACE")
            assertThat(description).contains("INFINITY_SKILLS")
        }
    }
}
