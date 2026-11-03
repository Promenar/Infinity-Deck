package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.ui.chat.ApprovalPreview
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class BrowserEvalApprovalTest {
    @Test fun `页面脚本在命令审批档逐次审批且不可限时授权`() {
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, "browser_eval", ToolRisk.SESSION_STATE)).isTrue()
        assertThat(ToolExecutionPolicy.grantCategory(ExecutionMode.WORKSPACE, "browser_eval", ToolRisk.SESSION_STATE)).isNull()
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, "browser_eval", ToolRisk.SESSION_STATE)).isFalse()
    }

    @Test fun `审批预览保留脚本全文`() {
        val code = "const value = document.title;\nvalue;" + " ".repeat(20_000)
        val preview = ApprovalPreview.format("browser_eval", buildJsonObject { put("code", code) })!!
        assertThat(preview.action).isEqualTo(ApprovalPreview.Action.SCRIPT)
        assertThat(preview.detail).isEqualTo(code)
    }
}
