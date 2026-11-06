package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class SettingsVisualPrimitivesContractTest {
    private fun source(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/ui/common/$fileName"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `settings page layout owns the settings app bar background and standard content padding`() {
        val source = source("InfinitySettingsPageLayout.kt")

        assertThat(source).contains("fun InfinitySettingsPageLayout(")
        assertThat(source).contains("TopAppBar(")
        assertThat(source).contains("InfinityBackButton(")
        assertThat(source).contains("containerColor = MaterialTheme.colorScheme.background")
        assertThat(source).contains("modifier = modifier.fillMaxSize()")
        assertThat(source).contains("MaterialTheme.typography.titleLarge")
        assertThat(source).doesNotContain("parentTypography.copy(")
        assertThat(source).contains("InfinitySpacing.ScreenHorizontal")
        assertThat(source).contains("content: @Composable (PaddingValues) -> Unit")
    }

    @Test
    fun `settings rows use standard md3 text roles without a default decorative chevron`() {
        val source = source("InfinitySettingsItem.kt")

        assertThat(source).contains("InfinitySettingsRowLayout(")
        assertThat(source).doesNotContain("ListItem(")
        assertThat(source).contains("MaterialTheme.typography.bodyLarge")
        assertThat(source).contains("MaterialTheme.typography.bodySmall")
        assertThat(source).contains("subtitleMaxLines: Int = 1")
        assertThat(source).contains("maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis")
        assertThat(source).contains("showChevron: Boolean = false")
        assertThat(source).doesNotContain("fontSize = 13.sp")
        assertThat(source).doesNotContain("fontSize = 12.sp")
        assertThat(source).contains("titleTextStyle: TextStyle? = null")
    }

    @Test
    fun `单行标题、副标题与取值放不下时横滑渐隐，不用省略号`() {
        val item = source("InfinitySettingsItem.kt")
        assertThat(item).contains("InfinityScrollingLine(Modifier.fillMaxWidth())")
        assertThat(item).contains("InfinityScrollingLine(subtitleModifier.fillMaxWidth())")
        val value = source("InfinityValueRow.kt").substringAfter("fun InfinityValueRow(").substringBefore("fun InfinityNumericSetting(")
        assertThat(value).contains("InfinityScrollingLine(")
        assertThat(value).doesNotContain("TextOverflow.Ellipsis")
    }

    @Test
    fun `settings rows preserve an optional localized click label`() {
        val source = source("InfinitySettingsItem.kt")

        assertThat(source).contains("onClickLabel: String? = null")
        assertThat(source).contains("onClickLabel = onClickLabel")
    }

    @Test
    fun `settings section uses primary small heading and a rounded filled group`() {
        val section = source("InfinitySettingsSection.kt")
        val header = source("SettingsSectionHeader.kt")
        val group = source("BettboxListGroup.kt")

        assertThat(header).contains("color = MaterialTheme.colorScheme.primary")
        assertThat(header).contains("MaterialTheme.typography.labelMedium")
        assertThat(section).contains("footer: String? = null")
        assertThat(section).contains("InfinitySpacing.SectionGap")
        assertThat(section).contains("BettboxListGroup(")
        assertThat(group).contains("RoundedCornerShape(20.dp)")
        assertThat(group).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(section).doesNotContain("InfinitySpacing.ScreenHorizontal +")
    }
}
