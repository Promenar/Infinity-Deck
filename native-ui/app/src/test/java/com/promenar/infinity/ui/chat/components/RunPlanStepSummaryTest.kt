package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.ui.chat.TaskCapsuleMode
import com.promenar.infinity.ui.chat.TaskPanelUiState
import com.promenar.infinity.ui.chat.taskPanelUiState
import org.junit.Test

/** 状态卡“第 N / M 步”、计划页签“已完成/总数”与清单“当前”标记三处口径一致。 */
class RunPlanStepSummaryTest {
    private fun plan(vararg statuses: String, groupTitle: String? = "迁移") = taskPanelUiState(
        tree = listOf(
            TaskStep(
                id = "root",
                title = groupTitle ?: "",
                children = statuses.mapIndexed { index, status ->
                    TaskStep(id = "s${index + 1}", title = "步骤 ${index + 1}", status = status, sortOrder = index)
                },
            ),
        ),
        isGenerating = false,
    )!!

    @Test fun `步骤乱序完成时卡片指向清单标为当前的那一步而不是已完成数加一`() {
        // 真机场景：5 步中第 2 步未完成，其余均已勾选。
        val state = plan("done", "todo", "done", "done", "done")

        assertThat(state.doneCount).isEqualTo(4)
        assertThat(state.totalCount).isEqualTo(5)
        assertThat(runPlanCurrentStepId(state)).isEqualTo("s2")
        assertThat(runPlanStepSummary(state)).isEqualTo(RunPlanStepSummary.AtStep(position = 2, total = 5))
    }

    @Test fun `进行中的步骤即当前步骤，卡片序号与之对应`() {
        val state = plan("done", "todo", "doing", "todo")

        assertThat(runPlanCurrentStepId(state)).isEqualTo("s3")
        assertThat(runPlanStepSummary(state)).isEqualTo(RunPlanStepSummary.AtStep(position = 3, total = 4))
    }

    @Test fun `按顺序推进时与已完成数加一相同`() {
        val state = plan("done", "done", "todo", "todo")

        assertThat(runPlanStepSummary(state)).isEqualTo(RunPlanStepSummary.AtStep(position = 3, total = 4))
    }

    @Test fun `全部完成时显示完成数与总数且清单不再标当前`() {
        val state = plan("done", "done", "done")

        assertThat(runPlanCurrentStepId(state)).isNull()
        assertThat(runPlanStepSummary(state)).isEqualTo(RunPlanStepSummary.Finished(done = 3, total = 3))
    }

    @Test fun `放弃的步骤算作结束，完成数只计完成的步骤`() {
        val state = plan("done", "dropped", "done")

        assertThat(state.isCompleted).isTrue()
        assertThat(runPlanCurrentStepId(state)).isNull()
        assertThat(runPlanStepSummary(state)).isEqualTo(RunPlanStepSummary.Finished(done = 2, total = 3))
    }

    @Test fun `分组标题不计入序号`() {
        val state = taskPanelUiState(
            tree = listOf(
                TaskStep(
                    id = "g1", title = "准备", sortOrder = 0,
                    children = listOf(
                        TaskStep(id = "a", title = "a", status = "done", sortOrder = 0),
                        TaskStep(id = "b", title = "b", status = "done", sortOrder = 1),
                    ),
                ),
                TaskStep(
                    id = "g2", title = "执行", sortOrder = 1,
                    children = listOf(
                        TaskStep(id = "c", title = "c", status = "todo", sortOrder = 0),
                        TaskStep(id = "d", title = "d", status = "done", sortOrder = 1),
                    ),
                ),
            ),
            isGenerating = true,
        )!!

        assertThat(runPlanCurrentStepId(state)).isEqualTo("c")
        assertThat(runPlanStepSummary(state)).isEqualTo(RunPlanStepSummary.AtStep(position = 3, total = 4))
    }

    @Test fun `没有清单只有计数时退回按完成数推算并不超过总数`() {
        val counted = TaskPanelUiState(
            planId = "p", title = "t", currentStepTitle = "x", currentStepId = "missing",
            doneCount = 5, totalCount = 5, pendingCount = 0,
            capsuleMode = TaskCapsuleMode.PENDING, shouldPulse = false, isCompleted = false,
        )

        assertThat(runPlanStepSummary(counted)).isEqualTo(RunPlanStepSummary.AtStep(position = 5, total = 5))
        assertThat(runPlanStepSummary(counted.copy(totalCount = 0, doneCount = 0))).isNull()
    }
}
