package com.promenar.infinity.data.branch

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolRisk
import org.junit.Test

class BranchApprovalPolicyTest {
    @Test fun `同助手其它会话读取每次强制审批且家族通信免审批`() {
        ExecutionMode.entries.forEach { mode ->
            listOf("session_read", "session_search", "session_list").forEach { tool ->
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.SESSION_STATE,
                    resolvedTarget = "session-approval:a:s:other")).isTrue()
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.SESSION_STATE,
                    resolvedTarget = "session-family:a:s:child")).isFalse()
            }
        }
    }
}
