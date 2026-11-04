package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 工作区状态与切换放在会话标题下方的状态行，文件页只负责浏览文件。 */
class WorkspaceStatusLineContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity/ui")

    private fun source(relativePath: String): String =
        Files.readAllBytes(sourceRoot.resolve(relativePath)).toString(Charsets.UTF_8)

    @Test
    fun `顶栏状态行显示工作区并打开工作区选择器`() {
        val screen = source("chat/ChatScreen.kt")
        assertThat(screen).contains("UiTags.CHAT_WORKSPACE_LINE")
        assertThat(screen).contains("onWorkspaceStatus")
        val route = source("chat/ChatRoute.kt")
        assertThat(route).contains("AgentReachSheet(")
        assertThat(route).contains("WorkspacePickerSheet(")
        val openStatus = route.substringAfter("onOpenWorkspaceStatus = {").substringBefore("},")
        assertThat(openStatus).contains("pickingWorkspace = true")
        assertThat(openStatus).doesNotContain("showAgentReach")
        assertThat(route).contains("onOpenReach = { showAgentReach = true }")
    }

    @Test
    fun `文件页不再有顶部工作区栏`() {
        val explorer = source("chat/ResourceExplorerSheet.kt")
        assertThat(explorer).doesNotContain("SessionWorkspaceBar")
        assertThat(explorer).doesNotContain("WorkspacePickerSheet(")
        assertThat(explorer).doesNotContain("AgentReachSheet(")
    }

    @Test
    fun `切换只在选择器中进行，可达范围面板不再提供切换`() {
        val reach = source("workspace/AgentReachSheet.kt")
        assertThat(reach).doesNotContain("workspaceSwitch")
        val picker = source("workspace/WorkspacePicker.kt")
        assertThat(picker).contains("WorkspaceSessionBar(")
    }
}
