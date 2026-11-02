package com.promenar.infinity.ui.chat.runmode

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.linux.LinuxRecoveryAction
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.CANCEL
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.DONE
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.EXPORT
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.EXPORT_EXTERNAL
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.EXPORT_PRIVATE
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.INSTALL
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.LATER
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.REPAIR
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.RESET
import com.promenar.infinity.ui.chat.runmode.RunModeCheckpointAction.USE_BUILTIN
import org.junit.Test

class RunModeCheckpointActionsTest {
    private val leavableStages: List<RunModeCheckpointStage> =
        RunModeUnavailableReason.entries.map { RunModeCheckpointStage.Unavailable(it) } +
            listOf(true, false).map { RunModeCheckpointStage.RepairFailed(failure = null, loss = null, canReset = it) } +
            listOf(RunModeCheckpointStage.ExportChoice, RunModeCheckpointStage.ExportOffer)

    @Test
    fun 除选择进行中与已导出外每个阶段都能稍后或取消离开() {
        listOf(true, false).forEach { canExport ->
            leavableStages.forEach { stage ->
                assertThat(runModeCheckpointActions(stage, canExport).dismiss).isAnyOf(LATER, CANCEL)
            }
        }
    }

    @Test
    fun 不可用时按原因给安装或修复_不支持与忙碌时没有主操作() {
        val primaries = RunModeUnavailableReason.entries.associateWith {
            runModeCheckpointActions(RunModeCheckpointStage.Unavailable(it), canExport = false).primary
        }
        assertThat(primaries).containsExactly(
            RunModeUnavailableReason.NOT_INSTALLED, INSTALL,
            RunModeUnavailableReason.INTERRUPTED, REPAIR,
            RunModeUnavailableReason.BROKEN, REPAIR,
            RunModeUnavailableReason.BUSY, null,
            RunModeUnavailableReason.UNSUPPORTED, null,
        )
    }

    @Test
    fun 不可用与修复失败时总能改用内置_能导出时另有导出() {
        RunModeUnavailableReason.entries.forEach { reason ->
            val stage = RunModeCheckpointStage.Unavailable(reason)
            assertThat(runModeCheckpointActions(stage, canExport = true).alternatives).containsExactly(USE_BUILTIN, EXPORT).inOrder()
            assertThat(runModeCheckpointActions(stage, canExport = false).alternatives).containsExactly(USE_BUILTIN)
        }
        listOf(true, false).forEach { canReset ->
            val stage = RunModeCheckpointStage.RepairFailed(failure = null, loss = null, canReset = canReset)
            assertThat(runModeCheckpointActions(stage, canExport = true).alternatives).containsExactly(EXPORT, USE_BUILTIN).inOrder()
            assertThat(runModeCheckpointActions(stage, canExport = false).alternatives).containsExactly(USE_BUILTIN)
        }
    }

    @Test
    fun 重置只在修复失败且仍可重置时作为主操作出现() {
        assertThat(runModeCheckpointActions(RunModeCheckpointStage.RepairFailed(null, null, canReset = true), canExport = true).primary)
            .isEqualTo(RESET)
        assertThat(runModeCheckpointActions(RunModeCheckpointStage.RepairFailed(null, null, canReset = false), canExport = true).primary)
            .isNull()
        val otherStages = leavableStages.filterNot { it is RunModeCheckpointStage.RepairFailed } +
            RunModeCheckpointStage.Exported(report = null, privateTarget = true)
        otherStages.forEach { stage ->
            val set = runModeCheckpointActions(stage, canExport = true)
            assertThat(set.alternatives + listOfNotNull(set.dismiss, set.primary)).doesNotContain(RESET)
        }
    }

    @Test
    fun 导出相关阶段的操作() {
        assertThat(runModeCheckpointActions(RunModeCheckpointStage.ExportChoice, canExport = true))
            .isEqualTo(RunModeCheckpointActionSet(listOf(EXPORT_EXTERNAL), dismiss = CANCEL, primary = EXPORT_PRIVATE))
        assertThat(runModeCheckpointActions(RunModeCheckpointStage.ExportOffer, canExport = false))
            .isEqualTo(RunModeCheckpointActionSet(dismiss = LATER, primary = EXPORT))
        assertThat(runModeCheckpointActions(RunModeCheckpointStage.Exported(report = null, privateTarget = false), canExport = true))
            .isEqualTo(RunModeCheckpointActionSet(primary = DONE))
    }

    @Test
    fun 选择与进行中阶段没有操作区按钮() {
        listOf(
            RunModeCheckpointStage.Select(canChooseLinux = true),
            RunModeCheckpointStage.Working(LinuxRecoveryAction.REPAIR),
        ).forEach { stage ->
            assertThat(runModeCheckpointActions(stage, canExport = true)).isEqualTo(RunModeCheckpointActionSet())
        }
    }
}
