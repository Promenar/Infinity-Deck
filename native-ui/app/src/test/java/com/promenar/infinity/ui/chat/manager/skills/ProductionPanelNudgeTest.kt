package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProductionPanelNudgeTest {
    @Test
    fun `未绑定项目时视频类工作流首次触发软提醒且每会话仅提醒一次`() = runBlocking {
        val tracker = ProductionPanelNudgeTracker(
            hasProduction = { false },
            isDefineEnabled = { true },
        )
        val first = tracker.hintIfNeeded("s1", isVideoOrPostProcess = true)
        val second = tracker.hintIfNeeded("s1", isVideoOrPostProcess = true)
        assertThat(first).isEqualTo(ProductionPanelNudgeTracker.PRODUCTION_PANEL_NUDGE_HINT)
        assertThat(second).isNull()
    }

    @Test
    fun `单张生图首次不打扰而同会话连续两次触发软提醒`() = runBlocking {
        val tracker = ProductionPanelNudgeTracker(
            hasProduction = { false },
            isDefineEnabled = { true },
        )
        assertThat(tracker.hintIfNeeded("s2", isVideoOrPostProcess = false)).isNull()
        assertThat(tracker.hintIfNeeded("s2", isVideoOrPostProcess = false))
            .isEqualTo(ProductionPanelNudgeTracker.PRODUCTION_PANEL_NUDGE_HINT)
        assertThat(tracker.hintIfNeeded("s2", isVideoOrPostProcess = false)).isNull()
    }

    @Test
    fun `已绑定制作项目或工具未启用时不触发软提醒`() = runBlocking {
        val boundTracker = ProductionPanelNudgeTracker(
            hasProduction = { true },
            isDefineEnabled = { true },
        )
        assertThat(boundTracker.hintIfNeeded("s3", isVideoOrPostProcess = true)).isNull()

        val disabledTracker = ProductionPanelNudgeTracker(
            hasProduction = { false },
            isDefineEnabled = { false },
        )
        assertThat(disabledTracker.hintIfNeeded("s4", isVideoOrPostProcess = true)).isNull()
    }
}
