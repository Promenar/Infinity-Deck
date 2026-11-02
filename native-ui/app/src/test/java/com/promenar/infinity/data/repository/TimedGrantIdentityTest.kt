package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.json
import com.promenar.infinity.domain.tool.ApprovalGrantCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Test

class TimedGrantIdentityTest {
    @Test fun `无类别历史JSON保留原摘要并通过校验而新请求类别绑定摘要`() {
        val legacy = ToolApprovalRequestFactory.createFromIdentities("assistant", listOf(
            ApprovalCallIdentity("call", "runtime", "write_file", "args", "definition", true, "{}", "file_write"),
        ))
        val encoded = json.encodeToString(legacy)
        val historical = encoded.replace(",\"grantCategory\":null", "")
        val decoded = json.decodeFromString<ApprovalRequest>(historical)
        assertThat(decoded.calls.single().grantCategory).isNull()
        assertThat(ToolApprovalRequestFactory.isValid(decoded)).isTrue()
        val changed = decoded.copy(calls = decoded.calls.map { it.copy(grantCategory = ApprovalGrantCategory.SKILLS) })
        assertThat(ToolApprovalRequestFactory.isValid(changed)).isFalse()
        val current = ToolApprovalRequestFactory.createFromIdentities("assistant", changed.calls)
        assertThat(ToolApprovalRequestFactory.isValid(json.decodeFromString<ApprovalRequest>(json.encodeToString(current)))).isTrue()
        assertThat(current.identityHash).isNotEqualTo(legacy.identityHash)
    }
}
