package com.promenar.infinity.ui.lab

import com.promenar.infinity.ui.lab.browser.labBrowserScenes
import com.promenar.infinity.ui.lab.chat.labChatScenes
import com.promenar.infinity.ui.lab.chat.labComposerScenes
import com.promenar.infinity.ui.lab.chat.labChatStateScenes
import com.promenar.infinity.ui.lab.library.labLibraryScenes
import com.promenar.infinity.ui.lab.settings.labSettingsScenes
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.terminal.labTerminalScenes

/** 版本 id 到场景的注册表，按领域分文件登记；目录里尚未接入场景的版本显示占位页。 */
object LabScenes {
    private val scenes: Map<String, () -> LabScene> = labChatScenes + labChatStateScenes + labComposerScenes + labTerminalScenes + labBrowserScenes + labLibraryScenes + labSettingsScenes

    fun scene(id: String): LabScene? = scenes[id]?.invoke()

    val ids: Set<String> get() = scenes.keys
}
