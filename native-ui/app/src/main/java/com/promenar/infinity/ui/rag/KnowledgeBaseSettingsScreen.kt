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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.JoinInner
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.RetrievalDepth
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 知识库设置：新会话默认、检索深度档位、相关模型与每轮抽取同在一页（H2、K1–K5）。
 * 原始检索参数收在“调试与高级”的折叠区。
 */
@Composable
fun KnowledgeBaseSettingsScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit,
    onOpenDefaultModels: () -> Unit = {},
) {
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as Application))
    val config by viewModel.config.collectAsState()
    val models by settingsViewModel.providerModels.collectAsState()
    val providerManager = ProviderManager.getInstance()
    val embeddingModelId by providerManager.embeddingModelId.collectAsState()
    val rerankModelId by providerManager.rerankModelId.collectAsState()
    KnowledgeBaseSettingsContent(
        state = KnowledgeBaseSettingsState(
            config = config,
            models = models,
            embeddingModelId = embeddingModelId,
            rerankModelId = rerankModelId,
        ),
        actions = KnowledgeBaseSettingsActions(
            onBack = onNavigateBack,
            onConfigChanged = { transform -> viewModel.updateConfig(transform) },
            onDepthSelected = viewModel::applyRetrievalDepth,
            onOpenDefaultModels = onOpenDefaultModels,
        ),
    )
}

internal data class KnowledgeBaseSettingsState(
    val config: RagConfiguration,
    val models: List<ModelInfo> = emptyList(),
    val embeddingModelId: String = "",
    val rerankModelId: String = "",
) {
    val rerankAvailable: Boolean get() = rerankModelId.isNotBlank()
}

internal data class KnowledgeBaseSettingsActions(
    val onBack: () -> Unit = {},
    val onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit = {},
    val onDepthSelected: (RetrievalDepth) -> Unit = {},
    val onOpenDefaultModels: () -> Unit = {},
)

@Composable
internal fun KnowledgeBaseSettingsContent(
    state: KnowledgeBaseSettingsState,
    actions: KnowledgeBaseSettingsActions = KnowledgeBaseSettingsActions(),
) {
    val config = state.config
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_ia_kb_settings_title),
        onBack = actions.onBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .testTag("kb_settings_content"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_new_session_section),
                footer = stringResource(R.string.settings_ia_new_session_footer),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.sheet_settings_memory),
                    icon = Icons.Rounded.History,
                    checked = config.enableMemory,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableMemory = value) } },
                    modifier = Modifier.testTag("kb_default_memory"),
                )
                SettingsToggle(
                    title = stringResource(R.string.sheet_settings_docs),
                    icon = Icons.Rounded.Description,
                    checked = config.enableDocs,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableDocs = value) } },
                    modifier = Modifier.testTag("kb_default_docs"),
                )
                SettingsToggle(
                    title = stringResource(R.string.settings_ia_rerank),
                    description = if (state.rerankAvailable) null else stringResource(R.string.settings_ia_rerank_no_model),
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    checked = state.rerankAvailable && config.enableRerank,
                    enabled = state.rerankAvailable,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableRerank = value) } },
                    modifier = Modifier.testTag("kb_default_rerank"),
                )
                SettingsToggle(
                    title = stringResource(R.string.sheet_settings_kg),
                    icon = Icons.Rounded.AccountTree,
                    checked = config.enableKnowledgeGraph,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableKnowledgeGraph = value) } },
                    modifier = Modifier.testTag("kb_default_kg"),
                )
            }

            val custom = selectedRetrievalDepthChoice(config) == RetrievalDepthChoice.Custom
            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_retrieval_section),
                footer = if (custom) stringResource(R.string.settings_ia_retrieval_footer_custom) else null,
            ) {
                RetrievalDepthControl(config, actions.onDepthSelected)
                SettingsToggle(
                    title = stringResource(R.string.settings_ia_hybrid_title),
                    description = stringResource(R.string.settings_ia_hybrid_desc),
                    icon = Icons.Rounded.JoinInner,
                    checked = config.enableHybridSearch,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableHybridSearch = value) } },
                    modifier = Modifier.testTag("kb_hybrid_toggle"),
                    switchModifier = Modifier.testTag("kb_hybrid_switch"),
                )
                SettingsToggle(
                    title = stringResource(R.string.settings_ia_rewrite_title),
                    description = stringResource(R.string.ux2_rewrite_footnote),
                    icon = Icons.Rounded.EditNote,
                    checked = config.enableQueryRewrite,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableQueryRewrite = value) } },
                    modifier = Modifier.testTag("kb_rewrite_toggle"),
                    switchModifier = Modifier.testTag("kb_rewrite_switch"),
                )
            }

            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_section_models),
                footer = stringResource(R.string.settings_ia_models_footer),
            ) {
                val unset = stringResource(R.string.settings_ia_model_unset)
                InfinityValueRow(
                    title = stringResource(R.string.settings_ia_embedding_model),
                    value = knowledgeModelLabel(state.embeddingModelId, state.models) ?: unset,
                    icon = Icons.Rounded.ScatterPlot,
                    onClick = actions.onOpenDefaultModels,
                    modifier = Modifier.testTag("kb_embedding_model_row"),
                )
                InfinityValueRow(
                    title = stringResource(R.string.settings_ia_rerank_model),
                    value = knowledgeModelLabel(state.rerankModelId, state.models) ?: unset,
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    onClick = actions.onOpenDefaultModels,
                    modifier = Modifier.testTag("kb_rerank_model_row"),
                )
                KnowledgeModelRow(
                    title = stringResource(R.string.settings_ia_kg_model),
                    modelId = config.kgExtractionModel,
                    models = state.models,
                    icon = Icons.Rounded.AccountTree,
                    onSelect = { id -> actions.onConfigChanged { copy(kgExtractionModel = id) } },
                    testTagPrefix = "kb_kg_model",
                )
                KnowledgeModelRow(
                    title = stringResource(R.string.settings_ia_rewrite_model),
                    modelId = config.queryRewriteModel,
                    models = state.models,
                    icon = Icons.Rounded.EditNote,
                    enabled = config.enableQueryRewrite,
                    onSelect = { id -> actions.onConfigChanged { copy(queryRewriteModel = id) } },
                    testTagPrefix = "kb_rewrite_model",
                )
            }

            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_extraction_section),
                footer = stringResource(R.string.settings_ia_extraction_footer),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.memory_state_title),
                    description = stringResource(R.string.settings_ia_extra_call),
                    icon = Icons.AutoMirrored.Rounded.EventNote,
                    checked = config.memoryStateExtractionEnabled,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(memoryStateExtractionEnabled = value) } },
                    modifier = Modifier.testTag("memory_state_toggle"),
                    switchModifier = Modifier.testTag("memory_state_switch"),
                )
                SettingsToggle(
                    title = stringResource(R.string.kg_session_extraction_title),
                    description = stringResource(R.string.settings_ia_extra_call),
                    icon = Icons.Rounded.Share,
                    checked = config.kgSessionExtractionEnabled,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(kgSessionExtractionEnabled = value) } },
                    modifier = Modifier.testTag("kg_session_extraction_toggle"),
                    switchModifier = Modifier.testTag("kg_session_extraction_switch"),
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/** 检索深度：三段点选即套用；自定义段只在原始值不匹配档位时出现，显示选中且不可点（K1）。下方一行列出四个原始值。 */
@Composable
private fun RetrievalDepthControl(config: RagConfiguration, onSelect: (RetrievalDepth) -> Unit) {
    val segments = retrievalDepthChoices(config).map { choice ->
        when (choice) {
            is RetrievalDepthChoice.Level -> InfinitySegment<RetrievalDepthChoice>(
                value = choice,
                label = stringResource(choice.depth.labelRes),
                tag = "kb_depth_${choice.depth.name.lowercase()}",
            )
            RetrievalDepthChoice.Custom -> InfinitySegment<RetrievalDepthChoice>(
                value = choice,
                label = stringResource(R.string.settings_ia_depth_custom),
                tag = "kb_depth_custom",
                clickable = false,
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small)) {
        Text(
            text = stringResource(R.string.settings_ia_depth_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
        )
        InfinitySegmentedControl(
            segments = segments,
            selected = selectedRetrievalDepthChoice(config),
            onSelect = { choice -> (choice as? RetrievalDepthChoice.Level)?.let { onSelect(it.depth) } },
            fillWidth = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            modifier = Modifier.testTag("kb_depth_control"),
        )
        Text(
            text = stringResource(
                R.string.settings_ia_depth_values,
                config.memoryLimit, config.docLimit, config.rerankTopK, config.rerankFinalK,
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = InfinitySpacing.RowHorizontal)
                .padding(bottom = InfinitySpacing.Medium)
                .testTag("kb_depth_values"),
        )
    }
}

internal val RetrievalDepth.labelRes: Int
    get() = when (this) {
        RetrievalDepth.LEAN -> R.string.settings_ia_depth_lean
        RetrievalDepth.BALANCED -> R.string.settings_ia_depth_balanced
        RetrievalDepth.COMPREHENSIVE -> R.string.settings_ia_depth_comprehensive
    }
