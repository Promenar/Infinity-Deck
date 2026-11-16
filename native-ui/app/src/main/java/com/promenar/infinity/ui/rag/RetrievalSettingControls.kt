package com.promenar.infinity.ui.rag

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityOptionItem
import com.promenar.infinity.ui.common.InfinityStepperSetting
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.NumericSettingKind
import com.promenar.infinity.ui.common.NumericSettingSpec
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor

/** 检索设置页共用的步进、策略选择与数值规格，全局“调试与高级”与智能体检索页都在用。 */
@Composable
internal fun RetrievalStepperSetting(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    InfinityStepperSetting(
        title = title,
        description = description,
        value = value.toString(),
        onDecrease = { onValueChange((value - 1).coerceIn(range)) },
        onIncrease = { onValueChange((value + 1).coerceIn(range)) },
        modifier = modifier,
        canDecrease = value > range.first,
        canIncrease = value < range.last,
        enabled = enabled,
    )
}

@Composable
internal fun RetrievalStrategySetting(
    strategy: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    var showPicker by remember { mutableStateOf(false) }
    val anchor = rememberInfinityPopoverAnchor()
    val options = listOf(
        "hyde" to stringResource(R.string.agent_retrieval_strategy_hyde),
        "multi-query" to stringResource(R.string.agent_retrieval_strategy_multi),
        "expansion" to stringResource(R.string.agent_retrieval_strategy_expansion),
    )
    InfinityValueRow(
        title = stringResource(R.string.agent_retrieval_strategy_label),
        value = options.firstOrNull { it.first == strategy }?.second ?: strategy,
        onClick = { if (!anchor.consumeRecentDismiss()) showPicker = true },
        modifier = Modifier.infinityPopoverAnchor(anchor),
        enabled = enabled,
    )
    InfinityBottomSheet(
        show = showPicker,
        scrollContent = true,
        onDismiss = { showPicker = false },
        title = stringResource(R.string.agent_retrieval_strategy_label),
        popover = InfinityPopoverSpec(anchor),
    ) {
        options.forEach { (id, label) ->
            val icon = when (id) {
                "hyde" -> Icons.Rounded.Psychology
                "multi-query" -> Icons.Rounded.Hub
                else -> Icons.Rounded.AutoAwesome
            }
            InfinityOptionItem(
                title = label,
                icon = icon,
                selected = strategy == id,
                onClick = {
                    onValueChange(id)
                    showPicker = false
                },
            )
        }
    }
}

/** 各页保留自身的数值范围和档位；连续参数的 0 步长表示原始无档位滑块。 */
internal object RetrievalSettingSpecs {
    val globalChunkSize = retrievalIntegerSpec(100, 2000, 800, listOf(100, 400, 800, 1200, 1600, 2000), step = 100)
    val globalChunkOverlap = retrievalIntegerSpec(0, 500, 100, listOf(0, 50, 100, 200, 300, 500), step = 50)
    val globalMemoryChunkSize = retrievalIntegerSpec(500, 3000, 1000, listOf(500, 1000, 1500, 2000, 3000), step = 100)
    val extractionTimeout = retrievalIntegerSpec(5, 120, 120, listOf(5, 30, 60, 90, 120))
    val globalMemoryLimit = retrievalIntegerSpec(1, 50, 5, listOf(1, 5, 10, 20, 30, 50))
    val globalDocLimit = retrievalIntegerSpec(1, 50, 8, listOf(1, 5, 8, 10, 20, 50))
    val globalRerankTopK = retrievalIntegerSpec(5, 100, 30, listOf(5, 10, 30, 50, 100))
    val rerankMaxPerCall = retrievalIntegerSpec(8, 200, 100, listOf(8, 32, 64, 100, 200))
    val globalMemoryThreshold = continuousSpec(0.0, 1.0, 0.7, listOf(0.0, 0.5, 0.7, 0.9, 1.0))
    val globalDocThreshold = continuousSpec(0.0, 1.0, 0.45, listOf(0.0, 0.3, 0.45, 0.7, 1.0))
    val globalVectorWeight = continuousSpec(0.0, 100.0, 60.0, listOf(0.0, 25.0, 50.0, 60.0, 100.0), unit = "%", decimalPlaces = 0)
    val globalBm25Boost = continuousSpec(0.5, 2.0, 1.0, listOf(0.5, 1.0, 1.5, 2.0), unit = "×", decimalPlaces = 1)
    val agentChunkSize = retrievalIntegerSpec(100, 2000, 800, listOf(100, 400, 800, 1200, 1600, 2000), step = 50)
    val agentChunkOverlap = retrievalIntegerSpec(0, 500, 100, listOf(0, 50, 100, 200, 300, 500), step = 10)
    val agentMemoryChunk = retrievalIntegerSpec(500, 3000, 1000, listOf(500, 1000, 1500, 2000, 3000), step = 100)
    val agentMemoryThreshold = continuousSpec(50.0, 95.0, 70.0, listOf(50.0, 60.0, 70.0, 80.0, 95.0), step = 5.0, unit = "%", decimalPlaces = 0)
    val agentDocThreshold = continuousSpec(30.0, 80.0, 45.0, listOf(30.0, 45.0, 60.0, 70.0, 80.0), step = 5.0, unit = "%", decimalPlaces = 0)
    val agentRerankTopK = retrievalIntegerSpec(10, 100, 30, listOf(10, 20, 30, 50, 100), step = 5)
    val agentVectorWeight = continuousSpec(0.0, 100.0, 60.0, listOf(0.0, 25.0, 50.0, 60.0, 100.0), step = 5.0, unit = "%", decimalPlaces = 0)
    val agentBm25Boost = continuousSpec(0.5, 2.0, 1.0, listOf(0.5, 1.0, 1.5, 2.0), step = 0.1, unit = "×", decimalPlaces = 1)
}

internal fun retrievalIntegerSpec(
    min: Int,
    max: Int,
    defaultValue: Int,
    presets: List<Int>,
    step: Int = 1,
): NumericSettingSpec = NumericSettingSpec(
    min = min.toDouble(),
    max = max.toDouble(),
    step = step.toDouble(),
    defaultValue = defaultValue.toDouble(),
    presets = presets.map { NumericSettingSpec.Preset(it.toDouble()) },
)

private fun continuousSpec(
    min: Double,
    max: Double,
    defaultValue: Double,
    presets: List<Double>,
    step: Double = 0.0,
    unit: String = "",
    decimalPlaces: Int = 2,
): NumericSettingSpec = NumericSettingSpec(
    min = min,
    max = max,
    step = step,
    defaultValue = defaultValue,
    presets = presets.map { NumericSettingSpec.Preset(it) },
    kind = NumericSettingKind.Continuous,
    unit = unit,
    decimalPlaces = decimalPlaces,
)
