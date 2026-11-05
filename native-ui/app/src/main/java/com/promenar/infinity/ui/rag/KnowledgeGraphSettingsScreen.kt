package com.promenar.infinity.ui.rag

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.settings.SettingsViewModel

/**
 * 知识图谱：查看图谱、抽取模型（与知识库设置 › 模型同一设置，G1）与单个文档的抽取预算。
 * 抽取超时与提示词在“调试与高级”中调整。
 */
@Composable
fun KnowledgeGraphSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGraph: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(context.applicationContext as Application))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as Application))
    val config by viewModel.config.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val models by settingsViewModel.providerModels.collectAsState()
    KnowledgeGraphSettingsContent(
        state = KnowledgeGraphSettingsState(config = config, models = models, entityCount = stats.graphEntityCount),
        actions = KnowledgeGraphSettingsActions(
            onBack = onNavigateBack,
            onNavigateToGraph = onNavigateToGraph,
            onNavigateToDebug = onNavigateToDebug,
            onConfigChanged = { transform -> viewModel.updateConfig(transform) },
        ),
    )
}

internal data class KnowledgeGraphSettingsState(
    val config: RagConfiguration,
    val models: List<ModelInfo> = emptyList(),
    val entityCount: Int = 0,
)

internal data class KnowledgeGraphSettingsActions(
    val onBack: () -> Unit = {},
    val onNavigateToGraph: () -> Unit = {},
    val onNavigateToDebug: () -> Unit = {},
    val onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit = {},
)

@Composable
internal fun KnowledgeGraphSettingsContent(
    state: KnowledgeGraphSettingsState,
    actions: KnowledgeGraphSettingsActions = KnowledgeGraphSettingsActions(),
) {
    val config = state.config
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_ia_kg_title),
        onBack = actions.onBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .testTag("kg_settings_content"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            InfinitySettingsSection(title = stringResource(R.string.settings_ia_kg_title)) {
                InfinityValueRow(
                    title = stringResource(R.string.rag_advanced_view_graph),
                    value = pluralStringResource(R.plurals.settings_ia_kg_entities, state.entityCount, state.entityCount),
                    icon = Icons.Rounded.Hub,
                    onClick = actions.onNavigateToGraph,
                    modifier = Modifier.testTag("rag_advanced_graph_row"),
                )
                KnowledgeModelRow(
                    title = stringResource(R.string.settings_ia_kg_extraction_model),
                    modelId = config.kgExtractionModel,
                    models = state.models,
                    icon = Icons.Rounded.AccountTree,
                    onSelect = { id -> actions.onConfigChanged { copy(kgExtractionModel = id) } },
                    testTagPrefix = "rag_advanced_model",
                )
            }

            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_kg_window_section),
                footer = stringResource(R.string.settings_ia_kg_window_footer),
            ) {
                KgWindowRows(config, actions.onConfigChanged)
            }

            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_kg_budget_section),
                footer = stringResource(R.string.settings_ia_kg_budget_footer),
            ) {
                KgBudgetRows(config, actions.onConfigChanged)
            }

            InfinitySettingsSection(title = stringResource(R.string.settings_ia_debug_title)) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_ia_kg_more),
                    subtitle = stringResource(R.string.settings_ia_kg_more_desc),
                    onClick = actions.onNavigateToDebug,
                    showChevron = true,
                    modifier = Modifier.testTag("kg_settings_debug_row"),
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
