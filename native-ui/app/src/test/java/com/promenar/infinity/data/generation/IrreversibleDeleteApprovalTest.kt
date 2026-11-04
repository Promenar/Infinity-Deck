package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ToolCall
import io.mockk.mockk
import org.junit.Test

class IrreversibleDeleteApprovalTest {
    @Test fun `各模式下普通文件和技能目录删除都需要审批`() {
        val strategy = DefaultChatGenerationContentStrategy(mockk<SharedPreferences>(relaxed = true), null)
        val call = ToolCall("permanent", "delete_file", """{"path":"/mnt/termux/big.bin"}""")
        listOf("workspace", "full_access", "auto", "semi", "manual").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(listOf(call), mode)).containsExactly("permanent")
            assertThat(strategy.pendingApprovalIds(listOf(call.copy(arguments = """{"path":"/skills/demo"}""")), mode))
                .containsExactly("permanent")
        }
    }
}
