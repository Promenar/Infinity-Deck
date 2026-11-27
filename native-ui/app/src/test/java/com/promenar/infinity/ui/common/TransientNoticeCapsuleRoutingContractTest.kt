package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/**
 * 方案 A 契约回归：确保五处一次性操作反馈在具备全局通知宿主时全部路由至顶部悬浮胶囊、
 * 零页面高度侵入，且在无宿主预览/独立弹窗窗口中保留安全兜底。
 */
class TransientNoticeCapsuleRoutingContractTest {

    private fun readSource(relative: String): String = String(
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/$relative")),
        Charsets.UTF_8,
    )

    @Test
    fun `模型管理页同步反馈与规格库完成提示均接入全局悬浮胶囊且保留空列表错误态`() {
        val source = readSource("settings/ProviderModelsScreen.kt")
        val content = source.substringAfter("internal fun ProviderModelsScreenContent(")
            .substringBefore("private fun ProviderModelsListPage(")
        val listPage = source.substringAfter("private fun ProviderModelsListPage(")
            .substringBefore("private fun ProviderModelsTopActions(")

        assertThat(content).contains("val globalNotice = LocalInfinityNoticeController.current")
        assertThat(content).contains("globalNotice.show(InfinityNoticeSeverity.SUCCESS, message)")
        assertThat(content).contains("if (globalNotice == null)")

        assertThat(listPage).contains("val globalNotice = LocalInfinityNoticeController.current")
        assertThat(listPage).contains("host.showStatus(active.severity.statusKind(), message)")
        assertThat(listPage).contains("reducedState != ProviderModelsListState.Error")
        assertThat(listPage).contains("if (globalNotice == null && effectiveSyncNotice != null)")
    }

    @Test
    fun `模型编辑页测速仅在状态跃迁后发往顶部悬浮胶囊且无宿主时退回内联条`() {
        val source = readSource("settings/ModelEditorScreen.kt")
        assertThat(source).contains("var lastObservedTestState by remember(model.id) { mutableStateOf(testState) }")
        assertThat(source).contains("if (previous == testState) return@LaunchedEffect")
        assertThat(source).contains("is ModelTestState.Success -> host.show(InfinityNoticeSeverity.SUCCESS, text)")
        assertThat(source).contains("is ModelTestState.Error -> host.show(InfinityNoticeSeverity.ERROR, text)")
        assertThat(source).contains("if (noticeHost == null)")
    }

    @Test
    fun `提供商表单底栏测试与保存失败终态改走顶部悬浮胶囊并保持测试按钮独立加载态`() {
        val source = readSource("settings/ProviderFormScreen.kt")
        val actions = source.substringAfter("private fun ProviderFormActionsSection(")
            .substringBefore("private fun ProviderFormNotice(")

        assertThat(actions).contains("val globalNotice = LocalInfinityNoticeController.current")
        assertThat(actions).contains("state.connectionTestState == ProviderConnectionTestState.Testing")
        assertThat(actions).contains("host.showStatus(kind, message)")
        assertThat(actions).contains("if (globalNotice == null)")
        assertThat(actions).contains("InfinityStatusStrip(")
    }

    @Test
    fun `空制作面板与 Linux 文件页一次性提示均接入顶部悬浮胶囊且目录选择弹窗内保留内联兜底`() {
        val production = readSource("production/ProductionPanel.kt")
            .substringAfter("internal fun ProductionPanelEmpty(")
            .substringBefore("private fun ProductionAttachCandidateRow(")
        assertThat(production).contains("val globalNotice = LocalInfinityNoticeController.current")
        assertThat(production).contains("host.show(InfinityNoticeSeverity.ERROR, active)")
        assertThat(production).contains("if (globalNotice == null && !error.isNullOrBlank())")

        val linux = readSource("linux/files/LinuxFilesScreen.kt")
        assertThat(linux).contains("LinuxFilesMessages(state, actions, allowGlobalNotice = picker == null)")
        val messages = linux.substringAfter("private fun LinuxFilesMessages(")
            .substringBefore("private fun LinuxEntriesList(")
        assertThat(messages).contains("val globalNotice = if (allowGlobalNotice) LocalInfinityNoticeController.current else null")
        assertThat(messages).contains("if (globalNotice != null) return")
    }

    @Test
    fun `宽屏设置双栏向全局悬浮通知上报右列起始避让确保胶囊在详情列居中`() {
        val twoPane = readSource("settings/SettingsTwoPane.kt")
        assertThat(twoPane).contains("ReportInfinityNoticeHorizontalClearance(")
        assertThat(twoPane).contains("coordinates.positionInRoot().x")
    }
}
