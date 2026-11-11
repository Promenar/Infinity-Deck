package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 设置页的短单选在宽窗口贴着所在行弹出；编辑表单与组合选择仍为居中对话框。 */
class SettingsPopoverContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity/ui")

    private fun source(relativePath: String): String = Files.readAllBytes(
        sourceRoot.resolve(relativePath),
    ).toString(Charsets.UTF_8)

    @Test
    fun `短单选面板带锚点并在点外部关闭后不被同一次点按重新打开`() {
        mapOf(
            "settings/ThemeScreen.kt" to "modesAnchor",
            "hub/UserSettingsHomeScreen.kt" to "languageAnchor",
            "settings/ProviderFormScreen.kt" to "anchor",
            "settings/ProviderApiSourceSection.kt" to "anchor",
            "settings/SkillsScreen.kt" to "depthAnchor",
            "rag/RetrievalSettingControls.kt" to "anchor",
            "rag/KnowledgeModelPicker.kt" to "anchor",
        ).forEach { (file, anchor) ->
            val text = source(file)
            assertWithMessage(file).that(text).contains("infinityPopoverAnchor($anchor)")
            assertWithMessage(file).that(text).contains("$anchor.consumeRecentDismiss()")
            assertWithMessage(file).that(text).contains("InfinityPopoverSpec($anchor")
        }
    }

    @Test
    fun `模型编辑页为页内全屏且类型为分段滑块不再弹出层`() {
        // 编辑器是页内全屏页，类型用单行分段滑块，能力用摘要行加多选面板，都不需要弹出层锚点。
        val editor = source("settings/ModelEditorScreen.kt")
        assertWithMessage("ModelEditorScreen").that(editor).contains("InfinitySettingsPageLayout(")
        assertWithMessage("ModelEditorScreen").that(editor).contains("InfinitySegmentedControl(")
        assertWithMessage("ModelEditorScreen").that(editor).contains("InfinityMultiChoiceSheet(")
        assertWithMessage("ModelEditorScreen").that(editor).doesNotContain("InfinityPopoverSpec(")
    }

    @Test
    fun `组合选择与编辑表单不改为弹出层`() {
        listOf("hub/AgentMemoryPolicySection.kt", "settings/SshSettingsScreen.kt", "settings/BackupSettingsScreen.kt")
            .forEach { file -> assertWithMessage(file).that(source(file)).doesNotContain("InfinityPopoverSpec(") }
    }
}
