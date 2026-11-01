package com.promenar.infinity.ui.workspace

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class SharedFolderPickerTest {
    private val primary = File("/storage/emulated/0")
    private val card = File("/storage/1A2B-3C4D")

    @Test
    fun `已有路径在存储卷内时直接进入该目录`() {
        assertThat(initialSharedFolderPage(listOf(primary, card), "/storage/1A2B-3C4D/Music/"))
            .isEqualTo(WorkspacePickerPage.Shared(card, File("/storage/1A2B-3C4D/Music")))
    }

    @Test
    fun `空路径或卷外路径从首个存储卷根开始`() {
        val root = WorkspacePickerPage.Shared(primary, primary)
        assertThat(initialSharedFolderPage(listOf(primary, card), null)).isEqualTo(root)
        assertThat(initialSharedFolderPage(listOf(primary, card), "  ")).isEqualTo(root)
        assertThat(initialSharedFolderPage(listOf(primary), "/data/data/com.termux")).isEqualTo(root)
        // 相似前缀不算在卷内。
        assertThat(initialSharedFolderPage(listOf(primary), "/storage/emulated/01/a")).isEqualTo(root)
    }

    @Test
    fun `没有存储卷时停在存储卷列表`() {
        assertThat(initialSharedFolderPage(emptyList(), "/storage/emulated/0")).isEqualTo(WorkspacePickerPage.Home)
    }

    @Test
    fun `只有一个存储卷时回到列表即离开选择器`() {
        val sub = WorkspacePickerPage.Shared(primary, File(primary, "Documents"))
        assertThat(sharedFolderNavigation(WorkspacePickerPage.Home, volumeCount = 1)).isNull()
        assertThat(sharedFolderNavigation(WorkspacePickerPage.Home, volumeCount = 2)).isEqualTo(WorkspacePickerPage.Home)
        assertThat(sharedFolderNavigation(sub, volumeCount = 1)).isEqualTo(sub)
    }
}
