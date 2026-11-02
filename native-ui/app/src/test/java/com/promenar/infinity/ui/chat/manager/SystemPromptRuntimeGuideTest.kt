package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SystemPromptRuntimeGuideTest {
    @Test fun `固定段只随模式权限和Linux可用状态变化且在十五行内`() {
        for (runMode in SessionRunMode.entries) for (mode in ExecutionMode.entries) for (available in listOf(false, true)) {
            val text = SystemPromptSections.runtimeEnvironment("model", mode, runMode, available, com.promenar.infinity.data.workspace.SessionWorkspace.Private)
            assertThat(text).contains("本会话运行模式：${if (runMode == SessionRunMode.LINUX) "Linux" else "内置"}")
            assertThat(text).contains("权限档：${if (mode == ExecutionMode.WORKSPACE) "工作区" else "完全访问"}")
            assertThat(text).contains("Linux：${if (available) "可用" else "不可用"}")
            assertThat(text).contains(if (runMode == SessionRunMode.LINUX) "用户技能：/mnt/skills" else "用户技能：/skills")
            assertThat(text).contains(if (runMode == SessionRunMode.LINUX) "/workspace/.agents/skills" else "/.agents/skills")
            assertThat(text).contains("/root")
            assertThat(text).contains("第三方工具")
            assertThat(text.lines().filter(String::isNotBlank).size).isAtMost(15)
            assertThat(text).doesNotContain("System Time")
        }
    }

    @Test fun `Linux分支工作树使用已绑定目录而未解析工作区不猜测根目录`() {
        val workspace = com.promenar.infinity.data.workspace.SessionWorkspace.Directory(
            com.promenar.infinity.data.local.db.entity.WorkspaceEntity("branch", "branch_worktree", "分支", "/root/repo-wt", 1),
            java.io.File("/not-exposed-host-root"),
        )
        val branch = SystemPromptSections.runtimeEnvironment("model", runMode = SessionRunMode.LINUX, workspace = workspace)
        assertThat(branch).contains("应用工作区：/root/repo-wt")
        assertThat(branch).contains("/root/repo-wt/.agents/skills")
        assertThat(branch).doesNotContain("not-exposed-host-root")
        val unavailable = com.promenar.infinity.data.workspace.SessionWorkspace.Unavailable("branch", workspace.workspace, "目录暂时不可用")
        assertThat(SystemPromptSections.runtimeEnvironment("model", runMode = SessionRunMode.LINUX, workspace = unavailable)).isEqualTo(branch)
        val identity = "nx1:00000000-0000-0000-0000-000000000001|" + "a".repeat(64)
        val home = workspace.copy(workspace = workspace.workspace.copy(kind = "linux_home", location = "/root/project#$identity"))
        assertThat(SystemPromptSections.runtimeEnvironment("model", runMode = SessionRunMode.LINUX, workspace = home)).contains("应用工作区：/root/project")
        val invalid = unavailable.copy(workspace = home.workspace.copy(location = "无效绑定"))
        assertThat(SystemPromptSections.runtimeEnvironment("model", runMode = SessionRunMode.LINUX, workspace = invalid)).contains("应用工作区：\$INFINITY_WORKSPACE")
        val unresolved = SystemPromptSections.runtimeEnvironment("model", runMode = SessionRunMode.LINUX)
        assertThat(unresolved).contains("应用工作区：\$INFINITY_WORKSPACE")
        assertThat(unresolved).contains("\$INFINITY_WORKSPACE/.agents/skills")
    }

    @Test fun `渲染内置和Linux两份固定段交付示例`() {
        val builtin = SystemPromptSections.runtimeEnvironment("example/model", ExecutionMode.WORKSPACE, SessionRunMode.BUILTIN, false)
        val linux = SystemPromptSections.runtimeEnvironment("example/model", ExecutionMode.FULL_ACCESS, SessionRunMode.LINUX, true, com.promenar.infinity.data.workspace.SessionWorkspace.Private)
        assertThat(builtin).contains("本会话运行模式：内置；权限档：工作区；Linux：不可用")
        assertThat(linux).contains("本会话运行模式：Linux；权限档：完全访问；Linux：可用")
        println("BUILTIN_EXAMPLE_BEGIN\n${builtin}BUILTIN_EXAMPLE_END")
        println("LINUX_EXAMPLE_BEGIN\n${linux}LINUX_EXAMPLE_END")
    }

    @Test fun `固定段与系统前缀不含本轮问题时间或检索资料`() = runTest {
        val params = ContextBuilderParams("s", "独有问题甲", assistantMsgId = "a", session = Session("s", "a"),
            runtimeModelId = "model", availableToolNames = setOf("activate_skill"), knowledgeCatalog = "独有检索甲")
        val builder = ContextBuilder()
        val first = builder.buildContext(params)
        val second = builder.buildContext(params.copy(content = "独有问题乙", assistantMsgId = "b", knowledgeCatalog = "独有检索乙"))
        assertThat(second.finalSystemPrompt).isEqualTo(first.finalSystemPrompt)
        assertThat(first.finalSystemPrompt).doesNotContain("独有")
        assertThat(first.finalSystemPrompt).doesNotContain("Current scope:")
        val linux = builder.buildContext(params.copy(session = params.session.copy(runMode = SessionRunMode.LINUX, executionMode = "full_access")))
        assertThat(linux.finalSystemPrompt).contains("本会话运行模式：Linux；权限档：完全访问")
    }
}
