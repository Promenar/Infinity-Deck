package com.promenar.infinity.ui.lab.terminal

import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene

/** 终端目录下的版本场景。 */
internal val labTerminalScenes: Map<String, () -> LabScene> = mapOf(
    "terminal.state.fastfetch" to {
        LabStateScene { LabTerminalShell(onExit) { columns -> LabFastfetch.script(columns) } }
    },
    "terminal.workflow.apk-add" to {
        LabWorkflowScene(
            timeline = { _ -> LabApkAdd.timeline() },
            content = { state: LabApkAddState -> LabTerminalShell(onExit) { LabApkAdd.script(state) } },
        )
    },
)
