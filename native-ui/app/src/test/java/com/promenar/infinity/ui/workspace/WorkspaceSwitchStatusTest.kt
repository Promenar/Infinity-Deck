package com.promenar.infinity.ui.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import org.junit.Test

class WorkspaceSwitchStatusTest {
    private fun status(busy: Boolean, failed: Boolean, stop: (() -> Unit)? = null) =
        WorkspaceSwitchStatus(ExecutionMode.WORKSPACE, busy = busy, failed = failed, onOpenReach = {}, onStopGeneration = stop)

    @Test
    fun `切换说明条：失败优先，生成中停用时有入口才给停止生成`() {
        val stop = {}
        assertThat(workspaceSwitchHint(null)).isNull()
        assertThat(workspaceSwitchHint(status(busy = false, failed = false))).isNull()
        assertThat(workspaceSwitchHint(status(busy = true, failed = false, stop)))
            .isEqualTo(SwitchHint(SwitchHintKind.BUSY, showStop = true))
        assertThat(workspaceSwitchHint(status(busy = true, failed = false)))
            .isEqualTo(SwitchHint(SwitchHintKind.BUSY, showStop = false))
        assertThat(workspaceSwitchHint(status(busy = true, failed = true, stop)))
            .isEqualTo(SwitchHint(SwitchHintKind.FAILED, showStop = false))
        assertThat(workspaceSwitchHint(status(busy = false, failed = true)))
            .isEqualTo(SwitchHint(SwitchHintKind.FAILED, showStop = false))
    }

    @Test
    fun `切换受阻时只有当前工作区一行可点，登记进行中全部停用`() {
        val locked = WorkspacePickerUiState(switchLocked = true)
        assertThat(locked.pickable()).isFalse()
        assertThat(locked.pickable(selected = true)).isTrue()
        val idle = WorkspacePickerUiState()
        assertThat(idle.pickable()).isTrue()
        val registering = WorkspacePickerUiState(busy = true)
        assertThat(registering.pickable(selected = true)).isFalse()
    }
}
