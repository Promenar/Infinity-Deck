package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ToolCall
import io.mockk.mockk
import org.junit.Test

class IrreversibleDeleteApprovalTest {
    @Test fun `按风险审批档普通删除需审批，免批准档普通删除放行而技能目录删除两档均需审批`() {
        val strategy = DefaultChatGenerationContentStrategy(mockk<SharedPreferences>(relaxed = true), null)
        val call = ToolCall("permanent", "delete_file", """{"path":"/mnt/termux/big.bin"}""")
        listOf("workspace", "semi", "manual").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(listOf(call), mode)).containsExactly("permanent")
        }
        listOf("full_access", "auto").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(listOf(call), mode)).isEmpty()
        }
        listOf("workspace", "full_access", "auto", "semi", "manual").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(listOf(call.copy(arguments = """{"path":"/skills/demo"}""")), mode))
                .containsExactly("permanent")
            assertThat(strategy.pendingApprovalIds(listOf(call.copy(arguments = """{"path":"/data/user/0/com.promenar.infinity/files/agent_skills/demo/SKILL.md"}""")), mode))
                .containsExactly("permanent")
        }
    }
}
