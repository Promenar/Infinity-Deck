package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 锁定 Bettbox 圆角分组面必须贯通设置深层页与两类首页业务列表。 */
class BettboxGroupedSurfacesContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity/ui")

    private fun source(relativePath: String): String = Files.readAllBytes(
        sourceRoot.resolve(relativePath),
    ).toString(Charsets.UTF_8)

    @Test
    fun `共享分组面应使用无描边填色与二十 dp 圆角`() {
        val group = source("common/BettboxListGroup.kt")

        assertThat(group).contains("RoundedCornerShape(20.dp)")
        assertThat(group).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(group).doesNotContain("BorderStroke")
        assertThat(group).doesNotContain(".border(")
    }

    @Test
    fun `账户身份头部不应再显示账户分区标签`() {
        val settings = source("hub/UserSettingsHomeScreen.kt")

        val accountBlock = settings.substringAfter("UserSettingsHomeScreenContent(")
            .substringBefore("settings_section_general")
        assertThat(accountBlock).doesNotContain("settings_section_account")
    }

    @Test
    fun `设置二三级页面应显式消费 Bettbox 分组面而非裸列表`() {
        val deepPages = listOf(
            "settings/BackupSettingsScreen.kt",
            "settings/LocalModelsScreen.kt",
            "settings/SkillsScreen.kt",
            "settings/TokenUsageScreen.kt",
            "settings/ProviderFormScreen.kt",
            "settings/ProviderListScreen.kt",
            "settings/ProviderModelsScreen.kt",
            "settings/DefaultModelsScreen.kt",
            "settings/ThemeScreen.kt",
            "hub/AgentEditScreen.kt",
            "rag/KnowledgeBaseSettingsScreen.kt",
            "rag/KnowledgeDebugSettingsScreen.kt",
            "rag/KnowledgeGraphSettingsScreen.kt",
            "rag/RagDebugScreen.kt",
            "rag/KnowledgeGraphScreen.kt",
        )

        deepPages.forEach { path ->
            val page = source(path)
            assertWithMessage(path).that(
                page.contains("BettboxListGroup(") ||
                    page.contains("BettboxListGroup {") ||
                    page.contains("bettboxListGroup()") ||
                    page.contains("InfinitySettingsSection(") ||
                    page.contains("InfinitySettingsGroup("),
            ).isTrue()
        }
    }

    @Test
    fun `智能体页面按设置页的分组面组织`() {
        val agentHub = source("hub/AgentHubScreen.kt")

        assertThat(agentHub).contains("InfinitySettingsSection(")
        assertThat(agentHub).contains("headerStartPadding = InfinitySpacing.ScreenHorizontal")
    }

    @Test
    fun `记忆设置应按语义使用多个分组面且不使用装饰分割线`() {
        val globalRag = source("rag/KnowledgeBaseSettingsScreen.kt")
        val content = globalRag.substringAfter("internal fun KnowledgeBaseSettingsContent(")

        assertThat(globalRag).doesNotContain("HorizontalDivider")
        assertThat(content).contains("InfinitySettingsSection(")
        listOf(
            "settings_ia_new_session_section",
            "settings_ia_retrieval_section",
            "settings_ia_section_models",
            "settings_ia_extraction_section",
        ).forEach {
            assertThat(content).contains(it)
        }
        assertThat(globalRag).doesNotContain(".verticalScroll(rememberScrollState())\n                .padding(contentPadding)\n                .bettboxListGroup()")
    }
}
