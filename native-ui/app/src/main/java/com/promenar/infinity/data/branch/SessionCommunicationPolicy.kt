package com.promenar.infinity.data.branch

import com.promenar.infinity.data.local.db.entity.SessionEntity

/** 家族权限按当前持久关系判定，删除或换助手后原批准不能沿用。 */
object SessionCommunicationPolicy {
    enum class Access { FREE, APPROVAL, DENIED }
    fun access(source: SessionEntity, target: SessionEntity, operation: String, subagentId: String? = null): Access {
        if (source.agentId != target.agentId) return Access.DENIED
        if (subagentId != null) return if (target.id == source.id && operation in setOf("session_read", "session_search", "session_send")) Access.FREE else Access.DENIED
        if (source.id == target.id) return if (operation in setOf("session_read", "session_search")) Access.FREE else Access.DENIED
        if (source.parentSessionId != null) return if (source.parentSessionId == target.id && operation in setOf("session_read", "session_search", "session_send")) Access.FREE else Access.DENIED
        if (source.branchPlanJson != null || source.parentSessionDeleted) return Access.DENIED
        if (target.parentSessionId == source.id) return Access.FREE
        return if (operation in setOf("session_read", "session_search")) Access.APPROVAL else Access.DENIED
    }
    fun approvalTarget(source: SessionEntity, id: String) = "session-approval:${source.agentId}:${source.id}:$id"
}
