package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class OverlayRowHeightIsolationContractTest {
    private val uiCommonDir = listOf(
        File("src/main/java/com/promenar/infinity/ui/common"),
        File("app/src/main/java/com/promenar/infinity/ui/common"),
        File("native-ui/app/src/main/java/com/promenar/infinity/ui/common"),
    ).first { it.isDirectory }

    @Test
    fun `所有浮层容器均重置 LocalRowHeightGroup，防止弹层内多行副标题导致父卡片高度跳变`() {
        val overlayFiles = listOf(
            "InfinityBottomSheet.kt",
            "InfinityPopover.kt",
            "InfinityDropdownMenu.kt",
            "InfinityConfirmDialog.kt",
            "InfinityFullscreenDialog.kt",
        )
        overlayFiles.forEach { fileName ->
            val source = File(uiCommonDir, fileName).readText()
            assertThat(source).contains("LocalRowHeightGroup provides null")
        }
    }

    @Test
    fun `RowHeightGroup 添加与移除多行副标题计数保持对称不泄漏`() {
        val group = RowHeightGroup(forced = false)
        assertThat(group.twoLine).isFalse()
        assertThat(group.hasMultiLineSubtitle).isFalse()

        group.add(multiLineSubtitle = false)
        assertThat(group.twoLine).isTrue()
        assertThat(group.hasMultiLineSubtitle).isFalse()

        group.add(multiLineSubtitle = true)
        assertThat(group.hasMultiLineSubtitle).isTrue()

        group.remove(multiLineSubtitle = true)
        assertThat(group.twoLine).isTrue()
        assertThat(group.hasMultiLineSubtitle).isFalse()

        group.remove(multiLineSubtitle = false)
        assertThat(group.twoLine).isFalse()
        assertThat(group.hasMultiLineSubtitle).isFalse()
    }
}
