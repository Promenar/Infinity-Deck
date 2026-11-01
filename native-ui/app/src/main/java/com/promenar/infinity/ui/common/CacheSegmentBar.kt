package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityTypography
import java.text.NumberFormat
import java.util.Locale

/** 输入缓存命中率，保留一位小数的本地化百分比。 */
fun formatCacheHitRate(rate: Double, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(rate.coerceAtLeast(0.0))

/** 缓存分段条的三种输入，颜色在生成统计卡片与用量页之间保持一致。 */
object CacheSegmentColors {
    @Composable fun read(): Color = MaterialTheme.colorScheme.primary
    @Composable fun write(): Color = MaterialTheme.colorScheme.tertiary
    @Composable fun uncached(): Color = MaterialTheme.colorScheme.outlineVariant
}

/**
 * 输入缓存的读取 / 写入 / 未缓存分段条与图例；[formatCount] 负责数字格式（卡片用完整数字，用量页用缩写）。
 * 写入为 0 时不画写入段也不列图例，避免对不报告写入的服务商显示空项。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CacheSegmentBar(
    readTokens: Long,
    writeTokens: Long,
    uncachedTokens: Long,
    formatCount: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val segments = listOfNotNull(
        Triple(R.string.cache_segment_read, readTokens.coerceAtLeast(0), CacheSegmentColors.read()),
        writeTokens.takeIf { it > 0 }?.let { Triple(R.string.cache_segment_write, it, CacheSegmentColors.write()) },
        Triple(R.string.cache_segment_uncached, uncachedTokens.coerceAtLeast(0), CacheSegmentColors.uncached()),
    )
    val total = segments.sumOf { it.second }
    val labels = segments.map { (labelRes, value, _) -> stringResource(R.string.cache_segment_item, stringResource(labelRes), formatCount(value)) }
    val description = labels.joinToString(stringResource(R.string.cache_segment_separator))
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceContainerHighest),
        ) {
            if (total > 0) {
                segments.forEach { (_, value, color) ->
                    if (value > 0) Box(Modifier.weight(value.toFloat() / total).height(8.dp).background(color))
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.forEachIndexed { index, (_, _, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Text(labels[index], style = InfinityTypography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
