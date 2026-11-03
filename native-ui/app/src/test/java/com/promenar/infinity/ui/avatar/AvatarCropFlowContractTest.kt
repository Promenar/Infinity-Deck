package com.promenar.infinity.ui.avatar

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class AvatarCropFlowContractTest {
    private fun file(path: String): String =
        Files.readAllBytes(Path.of(path)).toString(Charsets.UTF_8)

    private fun source(path: String): String = file("app/src/main/java/com/promenar/infinity/$path")

    private val agentEdit = source("ui/hub/AgentEditScreen.kt")
    private val userSettings = source("ui/hub/UserSettingsHomeScreen.kt")
    private val agentAvatar = source("ui/common/AgentAvatar.kt")
    private val launcher = source("ui/avatar/AvatarCropLauncher.kt")
    private val editorScreen = source("ui/avatar/AvatarEditorScreen.kt")
    private val editorActivity = source("ui/avatar/AvatarEditorActivity.kt")

    @Test
    fun `agent and user avatar imports share the same crop launcher`() {
        assertThat(agentEdit).contains("rememberAvatarCropLauncher(")
        assertThat(userSettings).contains("rememberAvatarCropLauncher(")
        assertThat(agentEdit).doesNotContain("ActivityResultContracts.GetContent()")
        assertThat(launcher).contains("AvatarEditorContract()")
        assertThat(launcher).contains("PickVisualMedia.ImageOnly")
    }

    @Test
    fun `custom agent image always fills the circular avatar`() {
        val customImageBranch = agentAvatar.substringAfter("if (customImageUri != null)")
            .substringBefore("} else if (icon != null)")

        assertThat(customImageBranch).contains("contentScale = ContentScale.Crop")
    }

    @Test
    fun `editor exposes explicit completion, zoom controls, tools and live previews`() {
        assertThat(editorScreen).contains("R.string.avatar_editor_done")
        assertThat(editorScreen).contains("R.string.avatar_editor_close")
        assertThat(editorScreen).contains("InfinitySlider(")
        listOf("rotateClockwise", "mirrorHorizontally", "initialState()", "toggleZoom").forEach {
            assertThat(editorScreen).contains(it)
        }
        listOf(72, 48, 28).forEach { assertThat(editorScreen).contains("$it.dp") }
        // 网格只在拖动时出现，并且裁在圆内。
        assertThat(editorScreen).contains("if (gridAlpha > 0f)")
        assertThat(editorScreen).contains("clipPath(Path().apply { addOval(circle) })")
    }

    @Test
    fun `editor follows the app theme and replaces the photo through the system picker`() {
        assertThat(editorActivity).contains("enableEdgeToEdge()")
        assertThat(editorActivity).contains("InfinityTheme(preferences = preferences)")
        assertThat(editorActivity).contains("LocaleHelper.applyLanguage")
        assertThat(editorActivity).contains("ActivityResultContracts.PickVisualMedia()")
    }

    @Test
    fun `third party cropper is fully removed`() {
        val buildScript = file("app/build.gradle.kts")
        val manifest = file("app/src/main/AndroidManifest.xml")

        assertThat(buildScript).doesNotContain("ucrop")
        assertThat(manifest).doesNotContain("AvatarCropActivity")
        assertThat(manifest).contains("android:name=\".ui.avatar.AvatarEditorActivity\"")
        listOf(launcher, editorScreen, editorActivity).forEach { assertThat(it).doesNotContain("yalantis") }
    }
}
