package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.SessionRunModeToolDefinitions
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.skills.TermuxExecSkill
import org.junit.Test
import java.io.File

/** 每轮请求里同一段运行说明只出现一次；面向用户的上限文案取自代码常量。 */
class AgentGuidanceSingleSourceTest {
    private fun builtin(name: String) = ProtocolTool(
        function = ProtocolToolFunction(name, "base", "{\"type\":\"object\"}"),
        sourceId = "builtin", runtimeToolId = name,
    )

    private fun occurrences(text: String, fragment: String) = text.windowed(fragment.length).count { it == fragment }

    @Test fun `Linux 会话的环境说明只随 shell_exec 工具说明出现一次`() {
        val marker = "使用 apk add 安装软件包"
        val shell = SessionRunModeToolDefinitions.forSession(builtin("shell_exec"), SessionRunMode.LINUX).function.description
        val python = SessionRunModeToolDefinitions.forSession(builtin("python_exec"), SessionRunMode.LINUX).function.description
        val prompt = SystemPromptSections.toolGuidance(setOf("shell_exec", "python_exec", "read_file"), false, runMode = SessionRunMode.LINUX)

        assertThat(occurrences(shell + python + prompt, marker)).isEqualTo(1)
        assertThat(shell).contains(marker)
        assertThat(python).contains("Linux")
        assertThat(python).contains("shell_exec")
        assertThat(prompt).contains(SystemPromptSections.LINUX_SHELL_POINTER)
    }

    @Test fun `内置会话仍说明系统 sh`() {
        val prompt = SystemPromptSections.toolGuidance(setOf("shell_exec"), false, runMode = SessionRunMode.BUILTIN)
        assertThat(prompt).contains("/system/bin/sh")
        assertThat(prompt).doesNotContain(SystemPromptSections.LINUX_SHELL_POINTER)
    }

    @Test fun `Termux 说明不公布原始日志位置并给出完整输出取法`() {
        val description = TermuxExecSkill({ error("unused") }, { true }).description
        assertThat(description).doesNotContain(".infinity/jobs")
        assertThat(description).contains("termux_exec 读取")
    }

    @Test fun `后台与定时上限文案使用占位符而不写死数字`() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        listOf("values", "values-zh-rCN").forEach { dir ->
            val xml = File(res, "$dir/agent_background_strings.xml").readText()
            listOf("settings_background_jobs_footer", "settings_background_schedules_footer").forEach { name ->
                val value = Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1]
                assertThat(value).contains("%1\$d")
                assertThat(value).contains("%2\$d")
                assertThat(value.replace(Regex("%\\d\\\$d"), "")).doesNotContainMatch("\\d")
            }
        }
    }
}
