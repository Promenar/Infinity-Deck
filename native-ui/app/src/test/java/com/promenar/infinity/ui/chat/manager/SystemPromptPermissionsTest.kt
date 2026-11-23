package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import org.junit.Test

class SystemPromptPermissionsTest {
    @Test fun `两档说明准确，关键数据开放只读自诊断与书面同意要求，用户技能只经审批卡确认，毁灭命令硬拦截`() {
        ExecutionMode.entries.forEach { mode ->
            val text = SystemPromptSections.permissions(mode)
            listOf(
                "databases/", "shared_prefs/", "no_backup/", "files/agent_skills/",
                "infinity_diagnostics.jsonl", "infinity_logs.txt", "diag-keep",
                "files/python-user", "explicit written consent", "Termux identity",
                "remote host", "not constrained to the workspace",
                "EXEC_BLOCKED_CATASTROPHIC_COMMAND", "infinity_encrypted_secrets.xml", "read-only self-diagnosis",
            ).forEach {
                assertThat(text).contains(it)
            }
            val critical = text.lines().single { it.contains("Critical runtime data") }
            assertThat(critical).doesNotContain("agent_skills")
            assertThat(text).contains("needs no separate written consent")
            if (mode == ExecutionMode.WORKSPACE) {
                assertThat(text).contains("zero-side-effect read-only shell_exec commands")
                assertThat(text).contains("require user approval")
                assertThat(text).contains("If rejected, do not repeat")
                assertThat(text).doesNotContain("without per-command approval")
            } else {
                assertThat(text).contains("without per-command approval")
                assertThat(text).doesNotContain("zero-side-effect read-only shell_exec commands")
            }
        }
    }
}
