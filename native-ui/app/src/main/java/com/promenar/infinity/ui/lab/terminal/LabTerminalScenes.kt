package com.promenar.infinity.ui.lab.terminal

import androidx.compose.runtime.remember
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene
import java.time.ZonedDateTime

/** 终端目录下的版本场景。 */
internal val labTerminalScenes: Map<String, () -> LabScene> = mapOf(
    "terminal.state.fastfetch" to {
        LabStateScene { LabTerminalShell(onExit) { columns -> LabFastfetch.script(columns) } }
    },
    "terminal.state.ssh-web01" to {
        LabStateScene {
            // 修复后两分钟的复核：starlog-api 两分钟前重启。
            val now = remember { ZonedDateTime.now() }
            LabSshShell(LabSshWeb01.ALIAS, onExit) { LabSshWeb01.script(now.minusMinutes(2).minusSeconds(4), now) }
        }
    },
    "terminal.workflow.apk-add" to {
        LabWorkflowScene(
            timeline = { _ -> LabApkAdd.timeline() },
            content = { state: LabApkAddState -> LabTerminalShell(onExit) { LabApkAdd.script(state) } },
        )
    },
)
