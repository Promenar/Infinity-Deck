package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class ProviderModelsScreenContractTest {
    @Test
    fun `顶栏只放添加与溢出菜单且同步移到搜索栏下的状态条`() {
        val source = readProviderModelsSource()
        val topActions = extractBetween(
            source,
            "internal fun ProviderModelsTopActions(",
            "@Composable\ninternal fun ProviderModelsSyncBar(",
        )

        assertThat(topActions).contains("IconButton(")
        assertThat(topActions).doesNotContain("UiTags.PROVIDER_MODELS_ACTION_SYNC")
        assertThat(topActions).contains("InfinityDropdownMenu(")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_EDIT_PROVIDER")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_OVERFLOW")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL")
        val syncBar = extractBetween(
            source,
            "internal fun ProviderModelsSyncBar(",
            "@Composable\ninternal fun ProviderModelsModelRow(",
        )
        assertThat(syncBar).contains("FilledTonalButton(")
        assertThat(syncBar).contains("UiTags.PROVIDER_MODELS_ACTION_SYNC")
        assertThat(syncBar).contains("enabled = !isFetching")
        assertThat(syncBar).contains("UiTags.PROVIDER_MODELS_LAST_SYNC")
        assertThat(source).doesNotContain("ProviderModelsActionsGrid(")
        assertThat(source).doesNotContain("internal fun ActionChip(")
        assertThat(source).doesNotContain("InfinityBottomSheet")
        assertThat(source).doesNotContain("fillMaxHeight(0.7f)")

        val addForm = extractBetween(
            source,
            "internal fun AddCustomModelForm(",
            "@Composable\ninternal fun ProviderModelsTopActions(",
        )
        assertThat(addForm).contains("LazyColumn(")
        assertThat(addForm).contains("BettboxListGroup(")
        assertThat(addForm).contains("InfinityFieldRow(")
        assertThat(addForm).doesNotContain("OutlinedTextField(")
        assertThat(addForm).contains("onPreviewCatalogMatch")
        assertThat(addForm).contains("imePadding()")
    }

    @Test
    fun `规格库更新入口在溢出菜单且列表底部不再有目录脚注`() {
        val source = readProviderModelsSource()
        val topActions = extractBetween(
            source,
            "internal fun ProviderModelsTopActions(",
            "@Composable\ninternal fun ProviderModelsSyncBar(",
        )
        assertThat(topActions).contains("ModelSpecLibraryMenuItem(")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_SPEC_LIBRARY")
        assertThat(topActions).contains("specLibraryVersionText(")
        assertThat(source).doesNotContain("ModelCatalogStatusLine")
        assertThat(source).doesNotContain("item(\"catalog\")")
        assertThat(source).doesNotContain("R.string.model_catalog_")
        val notice = extractBetween(
            source,
            "internal fun ModelSpecLibraryFailureNotice(",
            "/** 同步反馈：四色状态条",
        )
        assertThat(notice).contains("specLibraryFailureText(failure)")
        assertThat(notice).contains("specLibraryKeptText(kept)")
        assertThat(notice).contains("UiTags.PROVIDER_MODELS_SPEC_RETRY")
    }

    @Test
    fun `列表行用紧凑能力图标组而非标签墙`() {
        val source = readProviderModelsSource()
        val row = extractBetween(
            source,
            "internal fun ProviderModelsModelRow(",
            "internal fun ModelSpecLibraryFailureNotice(",
        )

        // 能力摘要是一行图标组（读屏合并为一句描述），不是可换行的标签墙。
        assertThat(row).contains("ModelCapabilityBadges(")
        assertThat(row).contains("R.string.provider_models_capabilities_description")
        assertThat(row).contains("model.remoteModelId.ifBlank")
        assertThat(row).contains("UiTags.providerModelsModelCard(model.id)")
        assertThat(row).contains("UiTags.providerModelsRemoteId(model.id)")
        assertThat(row).contains("UiTags.providerModelsToggleAction(model.id)")
        assertThat(row).contains("onRowClick")
        assertThat(row).contains("onToggle")
        assertThat(row).doesNotContain("OutlinedTextField(")
        assertThat(row).doesNotContain("FilterChip(")
        assertThat(row).doesNotContain("KeyboardType.Number")
        assertThat(row).doesNotContain("AnimatedVisibility(")
        assertThat(row).doesNotContain("InfinityGlassCard(")
    }

    @Test
    fun `列表行点击打开页内编辑页且系统返回与预测性返回关闭编辑页`() {
        val source = readProviderModelsSource()
        val content = extractBetween(
            source,
            "internal fun ProviderModelsScreenContent(",
            "@OptIn(ExperimentalMaterial3Api::class)",
        )

        assertThat(content).contains("var selectedModelId by rememberSaveable")
        assertThat(content).contains("state.models.firstOrNull { it.id == selectedModelId }")
        assertThat(content).contains("ProviderModelsModelRow(")
        val rowClick = extractBetween(content, "onRowClick = { model ->", "            )")
        assertThat(rowClick).contains("focusManager.clearFocus()")
        assertThat(rowClick).contains("keyboardController?.hide()")
        assertThat(rowClick).contains("selectedModelId = model.id")
        assertThat(content).contains("onToggle = { actions.onToggle(model.id) }")
        assertThat(content).contains("ModelEditorScreen(")
        assertThat(content).contains("onClose = { selectedModelId = null }")
        assertThat(content).contains("PredictiveBackHandler(enabled = selectedModel != null)")
        // 同步删掉正在编辑的模型时自动退出编辑页。
        assertThat(content).contains("reconcileEditingModelId(selectedModelId, state.models)")
    }

    @Test
    fun `独立编辑面板承载编辑测试删除和真实用户覆盖来源`() {
        val source = readEditorSource()
        assertThat(source).contains("internal fun ModelEditorScreen(")
        val editor = source.substring(source.indexOf("internal fun ModelEditorScreen("))

        assertThat(editor).contains("var selectedType by remember(model.id)")
        assertThat(editor).contains("var editName by remember(model.id)")
        assertThat(editor).contains("var editContext by remember(model.id)")
        assertThat(editor).contains("var activeCaps by remember(model.id)")
        assertThat(editor).contains("LaunchedEffect(")
        assertThat(editor).contains("withRecordedUserEdits(model)")
        assertThat(editor).contains("model.userEditedFields")
        assertThat(editor).doesNotContain("autoMetadataFingerprint")
        assertThat(editor).contains("onTest")
        assertThat(editor).contains("onCancel")
        assertThat(editor).contains("onDelete")
        assertThat(editor).contains("UiTags.providerModelsEditorSheet(model.id)")
        assertThat(editor).contains("UiTags.providerModelsEditorSource(model.id)")
        assertThat(editor).contains("UiTags.providerModelsEditorDeleteAction(model.id)")
    }

    @Test
    fun `模型列表保持常驻搜索稳定 key 与独立 Bettbox 分组卡`() {
        val source = readProviderModelsSource()
        val content = extractBetween(
            source,
            "internal fun ProviderModelsScreenContent(",
            "@OptIn(ExperimentalMaterial3Api::class)",
        )

        assertThat(content).contains("rememberSaveable(state.providerId)")
        assertThat(content).contains("InfinitySearchBar(")
        assertThat(content).contains("UiTags.PROVIDER_MODELS_SEARCH_FIELD")
        assertThat(content).contains("it.name.contains(searchQuery, ignoreCase = true)")
        assertThat(content).contains("it.remoteModelId.contains(searchQuery, ignoreCase = true)")
        assertThat(content).contains("it.id.contains(searchQuery, ignoreCase = true)")
        assertThat(content).contains("verticalArrangement = Arrangement.spacedBy(0.dp)")
        assertThat(content).contains("itemsIndexed(")
        assertThat(content).contains("key = { _, model -> model.id }")
        assertThat(content).contains("ProviderModelsModelRow(")
        val row = extractBetween(source, "internal fun ProviderModelsModelRow(", "internal fun ModelSpecLibraryFailureNotice(")
        assertThat(row).contains("bettboxListGroupItem(groupIndex, groupSize)")
    }

    @Test
    fun `content contract contains stable state actions and no vm or context usage`() {
        val source = readProviderModelsSource()
        val content = extractBetween(
            source,
            "internal fun ProviderModelsScreenContent(",
            "@OptIn(ExperimentalMaterial3Api::class)",
        )

        assertThat(source).contains("data class ProviderModelsScreenState")
        assertThat(source).contains("data class ProviderModelsScreenActions")
        assertThat(content).doesNotContain("SettingsViewModel")
        assertThat(content).doesNotContain("LocalContext")
    }

    @Test
    fun `route still wires real settings viewmodel with provider filters and callbacks`() {
        val source = readProviderModelsSource()

        assertThat(source).contains("SettingsViewModel.factory(context.applicationContext as android.app.Application)")
        assertThat(source).contains("providers.find { it.id == providerId }")
        assertThat(source).contains("models.filter { it.providerId == providerId }")
        assertThat(source).contains("onRefresh = { viewModel.refreshProviderModels(providerId) }")
        assertThat(source).contains("onUpdate = { viewModel.updateUserModel(it) }")
        assertThat(source).contains("onAdd = { id, name -> viewModel.addCustomModel(providerId, id, name) }")
        assertThat(source).contains("onDisableAll = { viewModel.disableAllModels(providerId) }")
        assertThat(source).contains("onDeleteAll = { viewModel.deleteAllModels(providerId) }")
        assertThat(source).contains("onToggle = { modelId -> viewModel.toggleModel(modelId) }")
        assertThat(source).contains("onTest = { modelId -> viewModel.testModel(modelId) }")
        assertThat(source).contains("onCancelTest = { modelId -> viewModel.cancelModelTest(modelId) }")
        assertThat(source).contains("onDelete = { modelId -> viewModel.deleteModel(modelId) }")
        assertThat(source).contains("onClearNotice = { viewModel.clearSyncNotice() }")
    }

    @Test
    fun `route content tags include stable provider model identifiers`() {
        val tags = readUiTags()
        listOf(
            "PROVIDER_MODELS_SCREEN_ROOT",
            "PROVIDER_MODELS_SEARCH_FIELD",
            "PROVIDER_MODELS_ACTION_SYNC",
            "PROVIDER_MODELS_ACTION_ADD",
            "PROVIDER_MODELS_ACTION_OVERFLOW",
            "PROVIDER_MODELS_ACTION_DISABLE_ALL",
            "PROVIDER_MODELS_ACTION_DELETE_ALL",
            "PROVIDER_MODELS_ACTION_SPEC_LIBRARY",
            "PROVIDER_MODELS_SPEC_FAILURE",
            "PROVIDER_MODELS_SPEC_RETRY",
            "PROVIDER_MODELS_NOTICE",
            "PROVIDER_MODELS_LIST",
            "PROVIDER_MODELS_ADD_SHEET",
            "PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG",
            "providerModelsModelCard",
            "providerModelsToggleAction",
            "providerModelsRemoteId",
            "providerModelsEditorSheet",
            "providerModelsEditorList",
            "providerModelsEditorCloseAction",
            "providerModelsEditorTestAction",
            "providerModelsEditorSource",
            "providerModelsEditorDeleteAction",
            "providerModelsEditorDeleteConfirmDialog",
            "providerModelsEditorDeleteConfirmButton",
        ).forEach { assertThat(tags).contains(it) }
    }

    @Test
    fun `add sheet submit failure keeps sheet open and no close path in route callback`() {
        val source = readProviderModelsSource()
        assertThat(source).contains("if (added) {")
        assertThat(source).contains("showAddDialog = false")
    }

    @Test
    fun `同步反馈条复用统一状态条而非固定字号`() {
        val source = readProviderModelsSource()
        val notice = extractBetween(
            source,
            "internal fun ModelSyncNoticeBanner(",
            "private fun ProviderModelsListMessage(",
        )

        assertThat(notice).contains("InfinityStatusStrip(")
        assertThat(notice).contains("UiTags.PROVIDER_MODELS_NOTICE_DISMISS")
        assertThat(notice).doesNotContain("fontSize = 12.sp")
        assertThat(notice).doesNotContain("InfinityTypography")
    }

    private fun extractBetween(source: String, startMarker: String, endMarker: String): String {
        assertThat(source).contains(startMarker)
        assertThat(source).contains(endMarker)
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertThat(end).isGreaterThan(start)
        return source.substring(start, end)
    }

    private fun readProviderModelsSource(): String = read(
        "app/src/main/java/com/promenar/infinity/ui/settings/ProviderModelsScreen.kt",
    )

    private fun readEditorSource(): String = read(
        "app/src/main/java/com/promenar/infinity/ui/settings/ModelEditorScreen.kt",
    )

    private fun readUiTags(): String = read(
        "app/src/main/java/com/promenar/infinity/ui/testing/UiTags.kt",
    )

    private fun read(path: String): String = String(
        Files.readAllBytes(Path.of(path)),
        Charsets.UTF_8,
    )
}
