package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 管理页共享原语与页面骨架的结构合同。
 */
class Md3ConvergencePrimitivesContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    private fun sourceFile(name: String) = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/common/$name.kt",
    )

    @Test
    fun `missing InfinitySettingsSection should be added for sectionized settings lists`() {
        val sourceFile = sourceFile("InfinitySettingsSection")
        assertThat(sourceFile.exists()).isTrue()
    }

    @Test
    fun `InfinitySettingsSection should use Bettbox surface container without glass card`() {
        val source = sourceFile("InfinitySettingsSection").readText()
        val group = sourceFile("BettboxListGroup").readText()
        assertThat(source).contains("SettingsSectionHeader")
        assertThat(source).contains("BettboxListGroup(")
        assertThat(source).contains("footer: String? = null")
        assertThat(source).contains("MaterialTheme.typography.labelMedium")
        assertThat(source).contains("InfinitySpacing.FooterTop")
        assertThat(group).contains("Surface(")
        assertThat(group).contains("surfaceContainerLow")
        assertThat(group).contains("RoundedCornerShape(20.dp)")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(group).contains("MaterialTheme.colorScheme")
        assertThat(source).doesNotContain("InfinityGlassCard(")
    }

    @Test
    fun `missing InfinitySearchTopBar should be added with query and active callbacks`() {
        val sourceFile = sourceFile("InfinitySearchTopBar")
        assertThat(sourceFile.exists()).isTrue()
    }

    @Test
    fun `InfinitySearchTopBar should expose back actions search actions and default action slot`() {
        val source = sourceFile("InfinitySearchTopBar").readText()
        assertThat(source).contains("fun InfinitySearchTopBar(")
        assertThat(source).contains("onBack: (() -> Unit)?")
        assertThat(source).contains("search: InfinityPageSearch")
        assertThat(source).contains("val onQueryChange: (String) -> Unit")
        assertThat(source).contains("val onActiveChange: (Boolean) -> Unit")
        assertThat(source).contains("title: String")
        assertThat(source).contains("actions: @Composable RowScope.() -> Unit = {}")
        assertThat(source).contains("Modifier.size(InfinitySpacing.MinimumTouchTarget)")
        assertThat(source).contains("FocusRequester")
        assertThat(source).contains("BackHandler(enabled = searching)")
        // 顶栏内的输入框复用主页同一个搜索框原子，外观一致。
        assertThat(source).contains("InfinitySearchBar(")
        assertThat(sourceFile("InfinitySearchBar").readText()).contains("ImeAction.Search")
    }

    @Test
    fun `InfinityPageLayout should own material insets and ime padding`() {
        val source = sourceFile("InfinityPageLayout").readText()
        assertThat(source).contains("WindowInsets.systemBars")
        assertThat(source).contains("imePadding()")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("containerColor = if (LocalFrostedChromeMaterial.current != null) Color.Transparent else MaterialTheme.colorScheme.background")
        assertThat(source).contains("Scaffold(")
    }
}
