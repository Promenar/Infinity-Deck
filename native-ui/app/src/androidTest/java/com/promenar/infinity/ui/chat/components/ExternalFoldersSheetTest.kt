package com.promenar.infinity.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.IExternalFolderManager
import com.promenar.infinity.ui.theme.InfinityTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/** 外部文件夹在同一个面板内逐页切换：管理、添加、浏览与回收区都能逐级返回到管理页。 */
class ExternalFoldersSheetTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun openSheet() {
        rule.setContent {
            InfinityTheme {
                ExternalFoldersPanel(sessionId = "s1", workspaceRootUuid = null, manager = FakeManager(), onReindex = null)
            }
        }
        rule.onNodeWithTag("external_folders_entry").performClick()
        rule.onNodeWithText(text(R.string.mount_section_mounted)).assertIsDisplayed()
    }

    private fun waitForText(value: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText(value)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addFormOpensInsideSheetAndHeaderBackReturnsToManager() {
        openSheet()
        rule.onNodeWithTag("external_mount_add_saf").performClick()
        rule.onNodeWithText(text(R.string.mount_form_title)).assertIsDisplayed()
        rule.onNodeWithTag("external_mount_alias").assertIsDisplayed()

        rule.onNodeWithContentDescription(text(R.string.common_cd_back)).performClick()
        rule.onNodeWithText(text(R.string.mount_section_mounted)).assertIsDisplayed()
    }

    @Test
    fun browserUsesOneBackButtonForParentFolderAndThenManager() {
        openSheet()
        rule.onNodeWithText("photos").performClick()
        waitForText("2026")
        rule.onNodeWithText("2026").performClick()
        waitForText("cover.txt")

        // 子目录内只有一个返回按钮：先回上级目录。
        rule.onNodeWithTag("external_mount_back").performClick()
        waitForText("2026")
        rule.onNodeWithText("2026").assertIsDisplayed()

        // 回到挂载根目录后，同一个按钮回到管理页。
        rule.onNodeWithTag("external_mount_back").performClick()
        rule.onNodeWithText(text(R.string.mount_section_mounted)).assertIsDisplayed()
    }

    @Test
    fun managerOnlyOpensWithoutInlineEntryAndDismisses() {
        var dismissed = false
        rule.setContent {
            InfinityTheme {
                ExternalFoldersPanel(
                    sessionId = "s1", workspaceRootUuid = null, manager = FakeManager(), onReindex = null,
                    managerOnly = true, onManagerDismiss = { dismissed = true },
                )
            }
        }
        rule.onNodeWithTag("external_folders_entry").assertDoesNotExist()
        rule.onNodeWithText(text(R.string.mount_section_mounted)).assertIsDisplayed()
        Espresso.pressBack()
        rule.runOnIdle { check(dismissed) }
    }

    private class FakeManager : IExternalFolderManager {
        private val mount = ExternalMount("m1", "photos", "saf_tree", "content://tree", "s1", createdAt = 0)
        override fun observeMounts(sessionId: String): Flow<List<ExternalMount>> = flowOf(listOf(mount))
        override suspend fun addSaf(alias: String, sessionId: String, treeUri: String, grantFlags: Int) = error("unused")
        override suspend fun addRealPath(alias: String, sessionId: String, source: String) = error("unused")
        override suspend fun remove(mountId: String) = error("unused")
        override suspend fun reauthorize(mountId: String, treeUri: String, grantFlags: Int) = error("unused")
        override suspend fun refresh(sessionId: String) = Unit
        override suspend fun list(sessionId: String, virtualPath: String) = when (virtualPath) {
            "/mnt/photos" -> listOf(ExternalFolderEntry("/mnt/photos/2026", "2026", true, 0, 0))
            "/mnt/photos/2026" -> listOf(ExternalFolderEntry("/mnt/photos/2026/cover.txt", "cover.txt", false, 12, 0))
            else -> emptyList()
        }
        override suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long): ByteArray = "hello".toByteArray()
        override suspend fun importSnapshot(sessionId: String, workspaceRootUuid: String, virtualPath: String) = error("unused")
        override suspend fun snapshotChanged(sessionId: String, virtualPath: String) = false
    }
}
