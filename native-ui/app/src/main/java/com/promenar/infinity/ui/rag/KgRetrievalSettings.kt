package com.promenar.infinity.ui.rag

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.ui.common.InfinityNumericSetting
import com.promenar.infinity.ui.common.SettingsToggle

/** 图谱检索参数行，放在“调试与高级 › 高级参数 › 图谱检索”下。 */
@Composable
internal fun ColumnScope.KgRetrievalRows(
    config: RagConfiguration,
    onChanged: (RagConfiguration.() -> RagConfiguration) -> Unit,
) {
    SettingsToggle(
        title = stringResource(R.string.kg_jit_fallback_title),
        description = stringResource(R.string.settings_retrieval_jit_short),
        checked = config.kgJitFallbackEnabled,
        onCheckedChange = { value -> onChanged { copy(kgJitFallbackEnabled = value) } },
        modifier = Modifier.testTag("kg_jit_fallback_toggle"),
        switchModifier = Modifier.testTag("kg_jit_fallback_switch"),
    )
    RetrievalStepperSetting(
        title = stringResource(R.string.kg_retrieval_hops),
        value = config.kgRetrievalHops,
        range = 1..2,
        onValueChange = { value -> onChanged { copy(kgRetrievalHops = value) } },
    )
    KgIntegerSetting(R.string.kg_seed_limit, config.kgMaxSeeds, 1, 32, 12, listOf(1, 8, 12, 16, 32)) { value ->
        onChanged { copy(kgMaxSeeds = value) }
    }
    KgIntegerSetting(R.string.kg_node_limit, config.kgMaxNodes, 2, 256, 64, listOf(2, 32, 64, 128, 256)) { value ->
        onChanged { copy(kgMaxNodes = value) }
    }
    KgIntegerSetting(R.string.kg_path_limit, config.kgMaxEdges, 1, 32, 24, listOf(1, 8, 16, 24, 32)) { value ->
        onChanged { copy(kgMaxEdges = value) }
    }
    KgIntegerSetting(R.string.settings_retrieval_kg_context_title, config.kgContextMaxTokens, 128, 16384, 2048, listOf(128, 1024, 2048, 8192, 16384)) { value ->
        onChanged { copy(kgContextMaxTokens = value) }
    }
    KgIntegerSetting(R.string.kg_jit_seed_threshold, config.kgJitMinSeeds, 1, 32, 2, listOf(1, 2, 8, 16, 32)) { value ->
        onChanged { copy(kgJitMinSeeds = value) }
    }
}

/** 单个文档的图谱抽取预算，显示的是配置中的当前值（默认值即代码默认值）。 */
@Composable
internal fun ColumnScope.KgBudgetRows(
    config: RagConfiguration,
    onChanged: (RagConfiguration.() -> RagConfiguration) -> Unit,
) {
    KgIntegerSetting(R.string.settings_retrieval_kg_calls_title, config.kgMaxCalls, 1, 1024, 32, listOf(1, 16, 32, 128, 1024)) { value ->
        onChanged { copy(kgMaxCalls = value) }
    }
    KgIntegerSetting(R.string.settings_retrieval_kg_output_title, config.kgMaxOutputTokens, 256, 1048576, 32768, listOf(256, 8192, 32768, 131072, 1048576)) { value ->
        onChanged { copy(kgMaxOutputTokens = value) }
    }
    KgIntegerSetting(R.string.settings_retrieval_kg_request_output_title, config.kgRequestMaxOutputTokens, 128, 16384, 4096, listOf(128, 1024, 2048, 4096, 8192, 16384)) { value ->
        onChanged { copy(kgRequestMaxOutputTokens = value) }
    }
    KgIntegerSetting(R.string.settings_retrieval_kg_duration_title, config.kgMaxDurationSeconds, 5, 3600, 180, listOf(5, 60, 180, 600, 3600)) { value ->
        onChanged { copy(kgMaxDurationSeconds = value) }
    }
    KgIntegerSetting(R.string.settings_retrieval_kg_confirm_title, config.kgLargeDocumentConfirmCalls, 1, 128, 16, listOf(1, 8, 16, 32, 128)) { value ->
        onChanged { copy(kgLargeDocumentConfirmCalls = value) }
    }
}

@Composable
private fun KgIntegerSetting(
    label: Int,
    value: Int,
    min: Int,
    max: Int,
    defaultValue: Int,
    presets: List<Int>,
    onChanged: (Int) -> Unit,
) {
    InfinityNumericSetting(
        title = stringResource(label),
        value = value,
        spec = retrievalIntegerSpec(min, max, defaultValue, presets),
        description = when (label) {
            R.string.settings_retrieval_kg_context_title -> stringResource(R.string.settings_retrieval_kg_context_footer)
            else -> null
        },
        onValueChange = { onChanged(it.toInt()) },
    )
}
