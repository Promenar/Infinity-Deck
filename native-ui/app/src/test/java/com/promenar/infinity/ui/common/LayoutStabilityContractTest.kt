package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 锁定全站布局稳定性与常驻控件契约，防止退化为裸字按钮、高度骤跳与重复内联提示。 */
class LayoutStabilityContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity")

    private fun source(relativePath: String): String =
        Files.readAllBytes(sourceRoot.resolve(relativePath)).toString(Charsets.UTF_8)

    @Test
    fun `动效工具与折叠行遵循全站连续过渡约定`() {
        val motion = source("ui/theme/Motion.kt")
        assertThat(motion).contains("fun <T : Any> rememberLastNonNull(")
        assertThat(motion).contains("fun rememberLastPositive(")
        assertThat(motion).contains("fun contentSizeSpec()")

        val disclosure = source("ui/common/InfinityDisclosureRow.kt")
        assertThat(disclosure).contains("Icons.Rounded.KeyboardArrowDown")
        assertThat(disclosure).doesNotContain("Icons.Rounded.KeyboardArrowUp")
        assertThat(disclosure).contains("animateFloatAsState")
    }

    @Test
    fun `已改造动作表面不退化为裸文字按钮`() {
        listOf(
            "recording/RecordingAsrControls.kt",
            "recording/RecordingContent.kt",
            "ui/library/KgCollectionSheets.kt",
            "ui/library/LibraryAskPage.kt",
            "ui/settings/LocalModelsScreen.kt",
            "ui/settings/MediaSettingsComponents.kt",
        ).forEach { path ->
            assertWithMessage(path).that(source(path)).doesNotContain("TextButton(")
        }
    }

    @Test
    fun `录音模型进度与工作进度条维持取消胶囊在顶部右侧平行`() {
        val controls = source("recording/RecordingAsrControls.kt")
        assertThat(controls).contains("AnimatedContent(")
        assertThat(controls).contains("InfinityPillButton(")
        assertThat(controls).contains("LinearProgressIndicator(modifier = Modifier.fillMaxWidth())")

        val recordingContent = source("recording/RecordingContent.kt")
        assertThat(recordingContent).contains("private val PaneHeaderHeight = 48.dp")
        assertThat(recordingContent).contains("InfinityPillTone.DANGER")
    }

    @Test
    fun `媒体卡片与文件列表保持槽位常驻不重排`() {
        val mediaCards = source("ui/media/WorkspaceMediaCards.kt")
        assertThat(mediaCards).contains("compactOverlay = true")
        assertThat(mediaCards).contains("rememberPlayerIsPlaying(")
        assertThat(mediaCards).contains("label = \"audio_play_slot\"")
        assertThat(mediaCards).contains("MediaProgressSlider(")

        val fileItems = source("ui/files/FileManagerItems.kt")
        assertThat(fileItems).contains("RowMenuButton(item, enabled = !selecting, menu = menu)")
        assertThat(fileItems).contains("Crossfade(")

        val filePane = source("ui/files/FileManagerPane.kt")
        assertThat(filePane).contains("LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))")
        assertThat(filePane).doesNotContain("item(key = \"running\")")
    }

    @Test
    fun `画板历史只读不重复且备份与媒体操作栏不推挤列表`() {
        val canvasComponents = source("ui/canvas/CanvasComponents.kt")
        val prototypeBoard = source("ui/canvas/PrototypeBoard.kt")
        assertThat(canvasComponents).doesNotContain("canvas_history_notice")
        assertThat(prototypeBoard).doesNotContain("canvas_history_notice")

        val backup = source("ui/settings/BackupSettingsScreen.kt")
        assertThat(backup).contains("listState.animateScrollToItem(0)")
        assertThat(backup).doesNotContain("listState.scrollToItem(")

        val mediaComponents = source("ui/settings/MediaSettingsComponents.kt")
        assertThat(mediaComponents).contains("Box(Modifier.fillMaxWidth().height(3.dp))")

        val agentEdit = source("ui/hub/AgentEditScreen.kt")
        assertThat(agentEdit).contains("SnackbarHost(")
        assertThat(agentEdit).doesNotContain("key = \"agent_edit_notice\"")
    }

    @Test
    fun `本地模型列表等待首轮加载后再渲染下载与空态且不混用项位移动画`() {
        val screen = source("ui/settings/LocalModelsScreen.kt")
        assertThat(screen).contains("modelsLoaded: Boolean = true")
        assertThat(screen).doesNotContain("animateItem()")

        val vm = source("ui/settings/LocalModelsViewModel.kt")
        assertThat(vm).contains("_modelsLoaded.value = true")
    }
}
