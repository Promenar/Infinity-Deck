package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class ResourceExplorerMaterialContractTest {

    private fun source(relativePath: String): String {
        val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        return projectRoot.resolve(relativePath).readText()
    }

    @Test
    fun `资源浏览器不恢复Glass表面`() {
        val explorer = source("src/main/java/com/promenar/infinity/ui/chat/ResourceExplorerSheet.kt")

        assertThat(explorer).doesNotContain("InfinityGlassCard")
    }

    @Test
    fun `资源状态不使用固定微字号`() {
        val explorer = source("src/main/java/com/promenar/infinity/ui/chat/ResourceExplorerSheet.kt")

        assertThat(explorer).doesNotContain("InfinityTypography.labelSmall")
    }

    @Test
    fun `资源管理器仅在二级页面层级中组合并按生命周期收集`() {
        val chatRoute = source("src/main/java/com/promenar/infinity/ui/chat/ChatRoute.kt")
        val explorer = source("src/main/java/com/promenar/infinity/ui/chat/ResourceExplorerSheet.kt")

        assertThat(chatRoute).contains("ChatPage.Explorer -> ResourceExplorerPage(")
        assertThat(explorer).contains("DisposableEffect(sessionId, viewModel)")
        assertThat(explorer).contains("viewModel.deactivateSession()")
        assertThat(explorer).contains("collectAsStateWithLifecycle()")
        assertThat(explorer).doesNotContain("LaunchedEffect(sessionId)")
        assertThat(explorer).doesNotContain("collectAsState()")
    }
}
