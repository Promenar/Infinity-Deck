package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class SettingsAiAppearanceVisualContractTest {
    @Test
    fun `外观页使用统一设置骨架分组和扁平控件`() {
        val source = source("ThemeScreen.kt")

        assertThat(source).contains("InfinitySettingsPageLayout(")
        assertThat(source).contains("InfinitySettingsSection(")
        assertThat(source).contains("SettingsToggle(")
        assertThat(source).doesNotContain("ListItem(")
        assertThat(source).doesNotContain("Switch(")
    }

    @Test
    fun `默认模型深层页保持无图标文本层级和必要尾部控件`() {
        val source = source("DefaultModelsScreen.kt")
        val content = source.substringAfter("fun DefaultModelsScreenContent(")

        assertThat(content).contains("InfinitySettingsPageLayout(")
        assertThat(content).contains("InfinitySettingsSection(")
        assertThat(content).contains("InfinitySettingsItem(")
        assertThat(content).contains("onClickLabel = selectModelLabel")
        assertThat(content).doesNotContain("leadingContent")
        assertThat(source.substringAfter("enum class DefaultModelRole(").substringBefore("\n}"))
            .doesNotContain("val icon: ImageVector")
    }

    @Test
    fun `提供商链路复用统一设置骨架且列表不使用彩色图标底板`() {
        val list = source("ProviderListScreen.kt")
        val form = source("ProviderFormScreen.kt")
        val models = source("ProviderModelsScreen.kt")

        assertThat(list).contains("InfinitySettingsPageLayout(")
        assertThat(list).contains("BettboxListGroup(")
        assertThat(list).doesNotContain("ListItem(")
        assertThat(list).doesNotContain("leadingContent = leadingContent")
        assertThat(list).doesNotContain("val providerIcon")
        assertThat(list).doesNotContain("iconContainerColor")
        assertThat(list).doesNotContain(".background(iconContainerColor)")
        assertThat(form.substringAfter("fun ProviderFormContent(")).contains("InfinitySettingsPageLayout(")
        assertThat(form).contains("InfinitySettingsSection(")
        assertThat(models.substringAfter("fun ProviderModelsScreenContent(")).contains(
            "InfinitySettingsPageLayout(",
        )
    }

    @Test
    fun `模型编辑页使用小号紫色分组标题并保持透明内容表面`() {
        val source = source("ModelEditorScreen.kt")

        assertThat(source).contains("InfinitySettingsSection(")
        // 顶栏透明，由毛玻璃容器提供页面底色。
        assertThat(source).contains("headerFrosted = true")
        assertThat(source).contains("containerColor = Color.Transparent")
        assertThat(source).doesNotContain("style = MaterialTheme.typography.titleSmall")
    }

    private fun source(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/ui/settings/$fileName"),
    ).toString(Charsets.UTF_8)
}
