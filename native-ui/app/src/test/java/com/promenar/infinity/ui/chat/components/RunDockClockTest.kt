package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunDockClockTest {
    @Test
    fun `用时不到 1 小时为分秒，以上为时分秒`() {
        assertThat(runDockElapsedText(0)).isEqualTo("0:00")
        assertThat(runDockElapsedText(59)).isEqualTo("0:59")
        assertThat(runDockElapsedText(60)).isEqualTo("1:00")
        assertThat(runDockElapsedText(59 * 60 + 59)).isEqualTo("59:59")
        assertThat(runDockElapsedText(3600)).isEqualTo("1:00:00")
        assertThat(runDockElapsedText(10 * 3600 + 5 * 60 + 7)).isEqualTo("10:05:07")
        assertThat(runDockElapsedText(-3)).isEqualTo("0:00")
    }

    @Test
    fun `文字尾部为计划进度与用时`() {
        val dock = runDockState(
            plan = null, isGenerating = true, pendingApprovals = 0, runningJobs = 0,
            enabledSchedules = 0, boardTitle = null, elapsedMillis = null,
        )!!
        assertThat(runDockMetaText(dock, elapsedSeconds = 75)).isEqualTo("1:15")
        assertThat(runDockMetaText(dock, elapsedSeconds = null)).isEmpty()
        assertThat(runDockMetaText(dock.copy(planDone = 2, planTotal = 5), elapsedSeconds = 9)).isEqualTo("2/5 · 0:09")
    }

    @Test
    fun `等待审批时省略计划分数，只保留本轮用时以免误解`() {
        val waitingDock = runDockState(
            plan = null, isGenerating = true, pendingApprovals = 3, runningJobs = 0,
            enabledSchedules = 0, boardTitle = null, elapsedMillis = null,
        )!!.copy(planDone = 0, planTotal = 3)
        assertThat(waitingDock.label).isEqualTo(RunDockLabel.WaitingApproval)
        assertThat(runDockMetaText(waitingDock, elapsedSeconds = null)).isEmpty()
        assertThat(runDockMetaText(waitingDock, elapsedSeconds = 42)).isEqualTo("0:42")

        val resumedDock = waitingDock.copy(label = RunDockLabel.Step("生成工作流"), pendingApprovals = 0)
        assertThat(runDockMetaText(resumedDock, elapsedSeconds = 42)).isEqualTo("0/3 · 0:42")
    }
}
