package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SystemPromptRuntimeEnvironmentTest {
    @Test fun `环境固定段包含宿主事实和实际模型`() {
        val prompt = SystemPromptSections.runtimeEnvironment("provider/model-id")
        assertThat(prompt.lines()).containsAtLeast("## Runtime Environment", "- Environment: Infinity Deck", "- Model: provider/model-id")
        assertThat(prompt).contains("你是 Infinity Deck 的智能体，运行在 Android 应用内")
        assertThat(prompt).contains("infinity-deck")
    }

    @Test fun `空模型不产生模型行且保留固定环境事实`() {
        for (model in listOf(null, "", " ", "\t\n")) {
            val prompt = SystemPromptSections.runtimeEnvironment(model)
            assertThat(prompt).doesNotContain("- Model:")
            assertThat(prompt).contains("运行在 Android 应用内")
        }
    }

    @Test fun `同一会话两轮使用不同模型时旧ID不残留`() = runTest {
        val builder = ContextBuilder()
        val params = ContextBuilderParams("s", "q", assistantMsgId = "m", session = Session("s", "a"))
        val first = builder.buildContext(params.copy(runtimeModelId = "model-first")).finalSystemPrompt
        val second = builder.buildContext(params.copy(runtimeModelId = "model-second")).finalSystemPrompt
        assertThat(first).contains("- Model: model-first\n")
        assertThat(first).doesNotContain("model-second")
        assertThat(second).contains("- Model: model-second\n")
        assertThat(second).doesNotContain("model-first")
    }

    @Test fun `主聊天在会话指令与权限之间注入运行环境`() = runTest {
        val prompt = ContextBuilder().buildContext(ContextBuilderParams(
            "s", "q", assistantMsgId = "m", session = Session("s", "a"),
            agentSystemPrompt = "用户定义提示词", sessionCustomPrompt = "会话指令正文", runtimeModelId = "request-model",
        )).finalSystemPrompt
        assertThat(prompt).contains("## Runtime Environment\n- Environment: Infinity Deck\n")
        assertThat(prompt).contains("- Model: request-model\n")
        assertThat(prompt.indexOf("用户定义提示词")).isLessThan(prompt.indexOf("## Session Instructions"))
        assertThat(prompt.indexOf("## Runtime Environment")).isGreaterThan(prompt.indexOf("会话指令正文"))
        assertThat(prompt.indexOf("## Permissions and Runtime Data")).isGreaterThan(prompt.indexOf("## Runtime Environment"))
    }
}
