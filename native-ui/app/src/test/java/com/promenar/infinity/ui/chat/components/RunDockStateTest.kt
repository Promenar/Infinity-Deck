package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.state.StateField
import com.promenar.infinity.ui.chat.TaskCapsuleMode
import com.promenar.infinity.ui.chat.TaskPanelStep
import com.promenar.infinity.ui.chat.TaskPanelStepStatus
import com.promenar.infinity.ui.chat.TaskPanelUiState
import org.junit.Test

class RunDockStateTest {
    private fun plan(done: Int, total: Int, current: String = "文件工具读写") = TaskPanelUiState(
        planId = "p",
        title = "探测环境",
        currentStepTitle = if (done == total) "" else current,
        currentStepId = if (done == total) "" else "s$done",
        doneCount = done,
        totalCount = total,
        pendingCount = total - done,
        capsuleMode = TaskCapsuleMode.GENERATING,
        shouldPulse = done < total,
        isCompleted = done == total,
    )

    private fun dock(
        plan: TaskPanelUiState? = null,
        generating: Boolean = false,
        approvals: Int = 0,
        jobs: Int = 0,
        schedules: Int = 0,
        board: String? = null,
        elapsed: Long? = null,
        answering: Boolean = false,
    ) = runDockState(plan, generating, approvals, jobs, schedules, board, elapsed, awaitingAnswer = answering)

    @Test fun `没有计划、生成、审批、后台与状态面板时不占位`() {
        assertThat(dock()).isNull()
    }

    @Test fun `生成中有计划时显示当前步骤、进度与整分钟用时`() {
        val state = dock(plan = plan(7, 17), generating = true, elapsed = 12 * 60_000L + 4_000L)!!
        assertThat(state.tone).isEqualTo(RunDockTone.RUNNING)
        assertThat(state.label).isEqualTo(RunDockLabel.Step("文件工具读写"))
        assertThat(state.planDone).isEqualTo(7)
        assertThat(state.planTotal).isEqualTo(17)
        assertThat(state.elapsedMinutes).isEqualTo(12)
        assertThat(state.progress).isWithin(1e-6f).of(7f / 17f)
    }

    @Test fun `不足一分钟或未在生成时不显示用时`() {
        assertThat(dock(generating = true, elapsed = 59_000L)!!.elapsedMinutes).isNull()
        assertThat(dock(plan = plan(3, 5), elapsed = 5 * 60_000L)!!.elapsedMinutes).isNull()
    }

    @Test fun `待审批优先于生成中的步骤`() {
        val state = dock(plan = plan(16, 17), generating = true, approvals = 2)!!
        assertThat(state.tone).isEqualTo(RunDockTone.WAITING)
        assertThat(state.label).isEqualTo(RunDockLabel.WaitingApproval)
        assertThat(state.pendingApprovals).isEqualTo(2)
    }

    @Test fun `等待回答单独占位，待审批优先`() {
        val answering = dock(plan = plan(3, 5), answering = true)!!
        assertThat(answering.tone).isEqualTo(RunDockTone.WAITING)
        assertThat(answering.label).isEqualTo(RunDockLabel.WaitingAnswer)
        assertThat(dock(answering = true)!!.label).isEqualTo(RunDockLabel.WaitingAnswer)
        assertThat(dock(approvals = 1, answering = true)!!.label).isEqualTo(RunDockLabel.WaitingApproval)
    }

    @Test fun `生成结束后计划完成显示完成，未完成显示暂停与当前步骤`() {
        assertThat(dock(plan = plan(17, 17))!!.label).isEqualTo(RunDockLabel.PlanCompleted)
        val paused = dock(plan = plan(7, 17))!!
        assertThat(paused.tone).isEqualTo(RunDockTone.PAUSED)
        assertThat(paused.label).isEqualTo(RunDockLabel.Paused("文件工具读写"))
    }

    @Test fun `没有计划的生成显示通用运行中且不画进度线`() {
        val state = dock(generating = true, jobs = 1)!!
        assertThat(state.label).isEqualTo(RunDockLabel.Running)
        assertThat(state.progress).isNull()
        assertThat(state.runningJobs).isEqualTo(1)
    }

    @Test fun `空闲时后台任务优先于状态面板标题`() {
        assertThat(dock(schedules = 1, board = "进度")!!.label).isEqualTo(RunDockLabel.BackgroundOnly)
        val board = dock(board = "进度")!!
        assertThat(board.label).isEqualTo(RunDockLabel.Board("进度"))
        assertThat(board.hasStateBoard).isTrue()
    }

    private fun step(id: String, depth: Int, group: Boolean = false, status: TaskPanelStepStatus = TaskPanelStepStatus.TODO) =
        TaskPanelStep(id, id, status, depth, group)

    @Test fun `顶层父步骤分组，全部完成的组折叠为标题`() {
        val groups = runPlanGroups(listOf(
            step("环境", 0, group = true),
            step("a", 1, status = TaskPanelStepStatus.DONE),
            step("b", 1, status = TaskPanelStepStatus.DROPPED),
            step("文件", 0, group = true),
            step("c", 1, status = TaskPanelStepStatus.DONE),
            step("d", 1, status = TaskPanelStepStatus.DOING),
        ))
        assertThat(groups.map { it.title }).containsExactly("环境", "文件").inOrder()
        assertThat(groups[0].collapsed).isTrue()
        assertThat(groups[0].doneCount).isEqualTo(2)
        assertThat(groups[1].collapsed).isFalse()
        assertThat(groups[1].steps.map { it.id }).containsExactly("c", "d").inOrder()
    }

    @Test fun `没有分组的平铺计划归入无标题组且不折叠`() {
        val groups = runPlanGroups(listOf(
            step("a", 0, status = TaskPanelStepStatus.DONE),
            step("b", 0, status = TaskPanelStepStatus.DONE),
        ))
        assertThat(groups).hasSize(1)
        assertThat(groups[0].title).isNull()
        assertThat(groups[0].collapsed).isFalse()
        assertThat(groups[0].steps).hasSize(2)
    }

    @Test fun `平铺步骤与分组交替时保持原顺序`() {
        val groups = runPlanGroups(listOf(
            step("a", 0),
            step("组", 0, group = true),
            step("b", 1),
            step("c", 0),
        ))
        assertThat(groups.map { it.title to it.steps.map(TaskPanelStep::id) })
            .containsExactly(null to listOf("a"), "组" to listOf("b"), null to listOf("c")).inOrder()
    }

    private fun field(key: String, type: String) = StateField(key, key, type)

    @Test fun `数值与进度两两并排，文本与列表占整行`() {
        val rows = stateFieldRows(listOf(
            field("done", "meter"), field("fail", "number"),
            field("stage", "text"),
            field("n", "number"),
            field("blocked", "list"),
            field("m", "meter"),
        ))
        assertThat(rows.map { row -> row.map(StateField::key) }).containsExactly(
            listOf("done", "fail"), listOf("stage"), listOf("n"), listOf("blocked"), listOf("m"),
        ).inOrder()
    }

    @Test fun `详情页只列出有内容的页`() {
        assertThat(runDetailTabs(hasPlan = true, hasBackground = false, hasState = true))
            .containsExactly(RunDetailTab.PLAN, RunDetailTab.STATE).inOrder()
        assertThat(runDetailTabs(hasPlan = false, hasBackground = true, hasState = false))
            .containsExactly(RunDetailTab.BACKGROUND)
    }

    private fun step(id: String, status: TaskPanelStepStatus, group: Boolean = false) =
        TaskPanelStep(id = id, title = id, status = status, depth = if (group) 0 else 1, isGroup = group)

    @Test fun `运行坞只显示状态面板或后台任务时，点按直接打开对应页`() {
        assertThat(runDockDefaultTab(RunDockLabel.Board("Probe"))).isEqualTo(RunDetailTab.STATE)
        assertThat(runDockDefaultTab(RunDockLabel.BackgroundOnly)).isEqualTo(RunDetailTab.BACKGROUND)
        assertThat(runDockDefaultTab(RunDockLabel.Step("写入"))).isNull()
        assertThat(runDockDefaultTab(RunDockLabel.WaitingApproval)).isNull()
        assertThat(runDockDefaultTab(RunDockLabel.PlanCompleted)).isNull()
    }

    @Test
    fun `点阵屏随运行坞状态切换：生成与等待实时、完成停在本轮、暂停调暗、只有后台任务留底格`() {
        assertThat(runDockScopeMode(RunDockTone.RUNNING)).isEqualTo(RunDockScopeMode.LIVE)
        assertThat(runDockScopeMode(RunDockTone.WAITING)).isEqualTo(RunDockScopeMode.LIVE)
        assertThat(runDockScopeMode(RunDockTone.DONE)).isEqualTo(RunDockScopeMode.REST)
        assertThat(runDockScopeMode(RunDockTone.PAUSED)).isEqualTo(RunDockScopeMode.PAUSED)
        assertThat(runDockScopeMode(RunDockTone.IDLE)).isEqualTo(RunDockScopeMode.OFF)
    }
}
