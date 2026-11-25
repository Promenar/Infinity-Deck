package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.SessionRunMode
import org.junit.Test

class SystemPromptPermissionsTest {
    @Test fun `两档说明准确，关键数据开放只读自诊断与书面同意要求，用户技能只经审批卡确认，毁灭命令硬拦截`() {
        for (mode in ExecutionMode.entries) for (runMode in SessionRunMode.entries) {
            val text = SystemPromptSections.permissions(mode, runMode = runMode)
            listOf(
                "databases/", "shared_prefs/", "no_backup/", "files/agent_skills/",
                "infinity_diagnostics.jsonl", "infinity_logs.txt", "diag-keep",
                "files/python-user", "Termux 身份", "SSH 在远端执行", "无进程路径沙盒",
                "审批被拒后不得重试", "EXEC_BLOCKED_CATASTROPHIC_COMMAND", "不可审批",
                "infinity_encrypted_secrets.xml* 读写全硬拒", "runtime_changes 是事后检测", "infinity-deck",
            ).forEach {
                assertThat(text).contains(it)
            }
            val critical = text.lines().single { it.contains("关键运行数据") }
            assertThat(critical).contains("只读自诊断")
            assertThat(critical).contains("写删硬拒")
            assertThat(critical).contains("明确书面同意")
            assertThat(critical).contains("审批卡不可替代")
            assertThat(critical).doesNotContain("agent_skills")
            val skills = text.lines().single { it.contains("files/agent_skills/") }
            assertThat(skills).contains("用文件工具逐次审批，只需审批卡，无需另取书面同意")
            assertThat(skills).contains("禁止用命令绕过审批")
            val always = text.lines().single { it.contains("两档均逐次审批") }
            listOf("install_skill", "技能目录变更", "ComfyUI 配置或模板变更", "fixed_overrides", "SSH 主机配置",
                "schedule_create", "request_knowledge_access", "resolve_startup_issue", "agents_write", "从下一轮生效")
                .forEach { assertThat(always).contains(it) }
            if (mode == ExecutionMode.WORKSPACE) {
                assertThat(text).contains("严格解析的零副作用只读 shell_exec 命令免批")
                assertThat(text).contains("删除和工作区外写入需审批")
                assertThat(text).doesNotContain("免批准档：常规命令")
            } else {
                assertThat(text).contains("常规命令、删除与工作区外写入免逐次审批")
                assertThat(text).doesNotContain("严格解析的零副作用只读 shell_exec")
            }
            if (runMode == SessionRunMode.LINUX) assertThat(text).contains("Linux 装包属正常使用")
        }
    }
}
