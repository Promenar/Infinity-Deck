package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.ui.chat.components.ResourceExplorerWorkspaceMode
import com.promenar.infinity.ui.files.FileManagerPane
import com.promenar.infinity.ui.files.state.FileManagerController
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ResourceExplorerInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun workspaceUnavailableHidesManagerAndRetries() {
        val retries = AtomicInteger()
        val exits = AtomicInteger()
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                ResourceExplorerBody(
                    workspaceMode = ResourceExplorerWorkspaceMode.Unavailable("ws", null, "目录不可用"),
                    isLoading = false, hasLoadError = false,
                    onRetryLoad = { retries.incrementAndGet() },
                    onBack = { exits.incrementAndGet() },
                ) { Box(Modifier.testTag("test_file_manager")) }
            }
        }
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_WORKSPACE_UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithTag("test_file_manager").assertDoesNotExist()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD).performClick()
        assertThat(retries.get()).isEqualTo(1)
        rule.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_cd_back)).performClick()
        assertThat(exits.get()).isEqualTo(1)
    }

    @Test
    fun gitStripOpensHostGitPage() {
        val opens = AtomicInteger()
        showGitStrip(location(gitBranch = "main"), opens)
        rule.onNodeWithTag(UiTags.FM_GIT_STRIP).assertIsDisplayed().performClick()
        assertThat(opens.get()).isEqualTo(1)
    }

    @Test
    fun detachedHeadStillShowsGitStrip() {
        val opens = AtomicInteger()
        showGitStrip(location(gitBranch = null).copy(gitDetached = true, gitHeadShortSha = "0123456"), opens)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.git_panel_branch_detached, "0123456")
        rule.onNodeWithText(label, substring = true).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.FM_GIT_STRIP).performClick()
        assertThat(opens.get()).isEqualTo(1)
    }

    private fun location(gitBranch: String?) = FileLocation(
        id = "session:s", kind = FileLocationKind.SESSION_WORKSPACE, title = "Session",
        capabilities = FileLocationCapabilities(writable = true, deletable = true, archive = true, git = true),
        gitBranch = gitBranch, gitChangeCount = 2,
    )

    private fun showGitStrip(location: FileLocation, opens: AtomicInteger) {
        val state = MutableStateFlow(FileManagerUiState(locations = listOf(location), location = location))
        val controller = Proxy.newProxyInstance(
            FileManagerController::class.java.classLoader,
            arrayOf(FileManagerController::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getState" -> state
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TestFileManagerController"
                else -> null
            }
        } as FileManagerController
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                ResourceExplorerBody(
                    workspaceMode = ResourceExplorerWorkspaceMode.Private,
                    isLoading = false, hasLoadError = false,
                    onRetryLoad = {},
                ) {
                    FileManagerPane(controller, resourceExplorerHost(null, { opens.incrementAndGet() }, emptyList()))
                }
            }
        }
    }
}
