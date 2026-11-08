package com.promenar.infinity.data.branch

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.SessionEntity
import org.junit.Test

class SessionCommunicationPolicyTest {
    private val parent = SessionEntity("main", "a", createdAt = 1, updatedAt = 1)
    private val branch = parent.copy(id = "branch", parentSessionId = "main", branchPlanJson = "{}")
    private val sibling = branch.copy(id = "sibling")
    private val other = parent.copy(id = "other")
    private val foreign = parent.copy(id = "foreign", agentId = "b")
    @Test fun `主会话家族查看发送和硬停止免费`() {
        listOf("session_read", "session_search", "session_send", "session_stop").forEach {
            assertThat(SessionCommunicationPolicy.access(parent, branch, it)).isEqualTo(SessionCommunicationPolicy.Access.FREE)
        }
    }
    @Test fun `同助手其它会话仅允许逐次批准读取`() {
        listOf("session_read", "session_search").forEach {
            assertThat(SessionCommunicationPolicy.access(parent, other, it)).isEqualTo(SessionCommunicationPolicy.Access.APPROVAL)
            assertThat(SessionCommunicationPolicy.access(parent, parent, it, "sa_test")).isEqualTo(SessionCommunicationPolicy.Access.FREE)
        }
        listOf("session_send", "session_stop").forEach {
            assertThat(SessionCommunicationPolicy.access(parent, other, it)).isEqualTo(SessionCommunicationPolicy.Access.DENIED)
        }
    }
    @Test fun `跨助手不可见且分支不能读兄弟或停止主会话`() {
        listOf("session_read", "session_search", "session_send", "session_stop").forEach {
            assertThat(SessionCommunicationPolicy.access(parent, foreign, it)).isEqualTo(SessionCommunicationPolicy.Access.DENIED)
            assertThat(SessionCommunicationPolicy.access(branch, sibling, it)).isEqualTo(SessionCommunicationPolicy.Access.DENIED)
        }
        assertThat(SessionCommunicationPolicy.access(branch, parent, "session_send")).isEqualTo(SessionCommunicationPolicy.Access.FREE)
        assertThat(SessionCommunicationPolicy.access(branch, parent, "session_stop")).isEqualTo(SessionCommunicationPolicy.Access.DENIED)
    }
    @Test fun `子代理只能读或发自己的主会话`() {
        assertThat(SessionCommunicationPolicy.access(parent, parent, "session_send", "sa_test")).isEqualTo(SessionCommunicationPolicy.Access.FREE)
        assertThat(SessionCommunicationPolicy.access(parent, parent, "session_stop", "sa_test")).isEqualTo(SessionCommunicationPolicy.Access.DENIED)
        assertThat(SessionCommunicationPolicy.access(parent, branch, "session_send", "sa_test")).isEqualTo(SessionCommunicationPolicy.Access.DENIED)
    }
}
