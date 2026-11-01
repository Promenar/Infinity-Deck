package com.promenar.infinity.ui.lab.fakes

import com.promenar.infinity.domain.branch.BranchControls
import com.promenar.infinity.domain.branch.BranchPlanAnswer
import com.promenar.infinity.domain.branch.BranchSummary
import com.promenar.infinity.domain.branch.PendingBranchPlan
import com.promenar.infinity.domain.branch.WorktreeEntry
import com.promenar.infinity.ui.chat.BranchAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class LabBranchControls(
    private val onResolvePlan: suspend (PendingBranchPlan, BranchPlanAnswer) -> Unit = { _, _ -> },
    private val onRemoveWorktree: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    private val onListWorktrees: (suspend (String) -> Result<List<WorktreeEntry>>)? = null,
) : LabCallRecorder(), BranchControls {
    val branches = MutableStateFlow<Map<String, List<BranchSummary>>>(emptyMap())
    val pendingPlans = MutableStateFlow<Map<String, PendingBranchPlan>>(emptyMap())
    val worktrees = MutableStateFlow<Map<String, List<WorktreeEntry>>>(emptyMap())

    fun setBranches(parentSessionId: String, value: List<BranchSummary>) {
        branches.update { it + (parentSessionId to value.toList()) }
    }

    fun setPendingPlan(sessionId: String, value: PendingBranchPlan?) {
        pendingPlans.update { if (value == null) it - sessionId else it + (sessionId to value) }
    }

    fun setWorktrees(sessionId: String, value: List<WorktreeEntry>) {
        worktrees.update { it + (sessionId to value.toList()) }
    }

    override fun observeBranches(parentSessionId: String): Flow<List<BranchSummary>> =
        branches.map { it[parentSessionId].orEmpty() }.distinctUntilChanged()

    override fun observePendingPlan(sessionId: String): Flow<PendingBranchPlan?> =
        pendingPlans.map { it[sessionId] }.distinctUntilChanged()

    override suspend fun resolvePlan(pending: PendingBranchPlan, answer: BranchPlanAnswer) {
        record("resolvePlan", pending, answer)
        onResolvePlan(pending, answer)
    }

    override suspend fun removeWorktree(branchSessionId: String): Result<Unit> {
        record("removeWorktree", branchSessionId)
        return onRemoveWorktree(branchSessionId)
    }

    override suspend fun listWorktrees(sessionId: String): Result<List<WorktreeEntry>> {
        record("listWorktrees", sessionId)
        return onListWorktrees?.invoke(sessionId) ?: Result.success(worktrees.value[sessionId].orEmpty())
    }
}

/** 宿主收集 access 后将其中的真实界面契约注入 CompositionLocal。 */
internal class LabBranchAccess(
    private val sessionId: String,
    private val controls: BranchControls,
    private val onOpenSession: (String) -> Unit = {},
) : LabCallRecorder() {
    val access = MutableStateFlow(create(null, null, null))

    fun setContext(parentSessionId: String? = null, parentTitle: String? = null, branchPlanTitle: String? = null) {
        access.value = create(parentSessionId, parentTitle, branchPlanTitle)
    }

    private fun create(parentSessionId: String?, parentTitle: String?, branchPlanTitle: String?) = BranchAccess(
        sessionId, controls,
        onOpenSession = { record("openSession", it); onOpenSession(it) },
        parentSessionId, parentTitle, branchPlanTitle,
    )
}
