package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class SshEditorSheetContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private fun source(relativePath: String): String = String(
        Files.readAllBytes(moduleRoot.resolve(relativePath)),
        Charsets.UTF_8,
    )

    @Test
    fun `主机编辑表单的内容滚动不接力拖动面板，避免交接抖动，面板仍可从拖动条下滑关闭`() {
        val sheetSource = source("src/main/java/com/promenar/infinity/ui/common/InfinityBottomSheet.kt")
        val editorSource = source("src/main/java/com/promenar/infinity/ui/settings/SshSettingsScreen.kt")

        assertThat(sheetSource).contains("sheetGesturesEnabled = dismissEnabled,")
        assertThat(sheetSource).contains("contentDragsSheet = dragGesturesEnabled")
        assertThat(sheetSource).contains("Modifier.nestedScroll(NoSheetHandoff)")
        assertThat(editorSource).contains("dragGesturesEnabled = false")
    }
}
