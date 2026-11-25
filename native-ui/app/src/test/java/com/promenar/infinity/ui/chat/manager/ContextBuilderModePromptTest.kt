package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.generation.HISTORY_RECALL_HINT
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.skills.BuiltinSkillCatalog
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalMount
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextBuilderModePromptTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `会话模式无工具时只保留聊天环境与会话内容`() = runTest {
        for (tools in listOf(null, emptySet<String>())) {
            val result = ContextBuilder().buildContext(params(false, tools))
            println("prompt-length chat tools=$tools: ${result.finalSystemPrompt.length}")
            assertChatPrompt(result, withToolUse = false)
        }
    }

    @Test fun `会话模式保留联网搜索时只增加通用工具指引`() = runTest {
        val result = ContextBuilder().buildContext(params(false, setOf("web_search")))
        println("prompt-length chat-search: ${result.finalSystemPrompt.length}")
        assertChatPrompt(result, withToolUse = true)
        assertThat(result.finalSystemPrompt).contains("## Tool Use")
    }

    @Test fun `会话模式保留知识目录快照`() = runTest {
        val result = ContextBuilder().buildContext(params(false, emptySet()).copy(
            retrievalMode = AgentRetrievalMode.TOOL,
            knowledgeCatalog = "示例资料已索引",
        ))
        assertThat(result.finalSystemPrompt).contains("## Knowledge Catalog")
        assertThat(result.finalSystemPrompt).doesNotContain("Use knowledge tools")
        assertThat(result.turnContext).contains("示例资料已索引")
    }

    @Test fun `智能体模式保留运行契约并限制常驻提示长度`() = runTest {
        val result = ContextBuilder().buildContext(params(true, setOf(
            "shell_exec", "python_exec", "git_exec", "read_file", "activate_skill", "read_skill_file",
            "generate_image", "view_media", "media_fetch", "comfy_run_workflow", "production_update",
            "canvas_create", "canvas_update", "schedule_create", "job_wait", "session_read", "session_search",
        )))
        val prompt = result.finalSystemPrompt
        println("prompt-length agent: ${prompt.length}; linux-shell: ${SystemPromptSections.LINUX_SHELL_GUIDANCE.length}")
        assertThat(result.systemPromptSections.map { it.id }).containsExactly(
            "assistant_prompt", "session_instructions", "project_rules", "runtime", "permissions",
            "tool_guidance", "skill_catalog", "history_summary",
        ).inOrder()
        listOf(
            "项目规则示例", "Model: test-model", "运行模式：Linux", "权限档：按风险审批",
            "/workspace", "/home/session", "/mnt/media", "EXEC_BLOCKED_CATASTROPHIC_COMMAND",
            "activate_skill(name=\"infinity-deck\")", "[source=builtin", HISTORY_RECALL_HINT,
        ).forEach { assertThat(prompt).contains(it) }
        assertThat(prompt.length).isAtMost(6400)
    }

    private fun assertChatPrompt(result: ContextBuilderResult, withToolUse: Boolean) {
        val expectedSections = buildList {
            addAll(listOf("assistant_prompt", "session_instructions", "runtime"))
            if (withToolUse) add("tool_guidance")
            add("history_summary")
        }
        assertThat(result.systemPromptSections.map { it.id }).containsExactlyElementsIn(expectedSections).inOrder()
        assertThat(result.systemPromptSections.single { it.id == "runtime" }.content.trim()).isEqualTo(
            "## Runtime Environment\n- Environment: Infinity Deck\n" +
                "- 你是 Infinity Deck 的助手，运行在 Android 应用内。\n- Model: test-model",
        )
        val prompt = result.finalSystemPrompt
        listOf("示例人设", "会话指令示例", "<history_summary>\n历史摘要示例\n</history_summary>").forEach {
            assertThat(prompt).contains(it)
        }
        listOf(
            "## Permissions and Runtime Data", "shell_exec", "python_exec", "agents_write",
            "EXEC_BLOCKED_CATASTROPHIC_COMMAND", "activate_skill", "infinity-deck", "## 项目规则",
            "项目规则示例", "/workspace", "/home/session", "/mnt/", "INFINITY_SKILLS",
            "Codex CLI", "Claude Code", "### Media", "### 会话画布", "### 主动记忆", HISTORY_RECALL_HINT,
        ).forEach { assertThat(prompt).doesNotContain(it) }
    }

    private fun params(toolsEnabled: Boolean, tools: Set<String>?): ContextBuilderParams {
        val root = temporary.newFolder().canonicalFile
        root.resolve("AGENTS.md").writeText("项目规则示例")
        return ContextBuilderParams(
            sessionId = "s", content = "示例问题", assistantMsgId = "m",
            session = Session("s", "agent", summary = "历史摘要示例", runMode = SessionRunMode.LINUX,
                options = SessionOptions(toolsEnabled = toolsEnabled, keepWebSearchWhenToolsOff = tools?.contains("web_search") == true)),
            runtimeModelId = "test-model",
            agentSystemPrompt = "示例人设",
            sessionCustomPrompt = "会话指令示例",
            workspace = SessionWorkspace.Directory(
                WorkspaceEntity("test", WorkspaceEntity.KIND_PROJECT, "示例项目", "project", 0), root,
            ),
            mounts = listOf(ExternalMount("media", "media", "real_path", "/example/media", "s", 0)),
            availableToolNames = tools,
            availableSkills = listOf(DiscoveredSkill(
                id = BuiltinSkillCatalog.ID, scope = SkillScope.BUILTIN, name = BuiltinSkillCatalog.NAME,
                description = "应用运行指南", displayName = null, shortDescription = null,
                virtualDir = BuiltinSkillCatalog.VIRTUAL_DIR, files = BuiltinSkillCatalog.REFERENCE_FILES,
                status = SkillStatus.ENABLED, modifiedSinceConfirmed = false, allowImplicitInvocation = true,
                brokenReason = null, metadata = null,
            )),
        )
    }
}
