package com.promenar.infinity.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.repository.DailyTokenStats
import com.promenar.infinity.domain.repository.SessionTokenUsage
import com.promenar.infinity.ui.common.ConfirmDialog
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsSectionHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import com.promenar.infinity.ui.common.CacheSegmentBar
import com.promenar.infinity.ui.common.formatCacheHitRate
import com.promenar.infinity.domain.repository.CacheUsageAggregate

@Composable
fun TokenUsageScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: TokenUsageViewModel = viewModel(factory = TokenUsageViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.state.collectAsState()

    ConfirmDialog(
        show = state.showClearConfirm,
        onDismiss = { viewModel.dismissClearConfirm() },
        onConfirm = { viewModel.clearStats() },
        title = stringResource(R.string.token_clear_confirm_title),
        description = stringResource(R.string.token_clear_confirm_desc),
        confirmLabel = stringResource(R.string.token_clear_history),
        destructive = true
    )

    TokenUsageScreenContent(
        state = state,
        onNavigateBack = onNavigateBack,
        onRetry = {
            when (state.error) {
                TokenStatsErrorCode.LOAD_FAILED -> viewModel.loadStats()
                TokenStatsErrorCode.CLEAR_FAILED -> viewModel.clearStats()
                null -> Unit
            }
        },
        onClear = viewModel::showClearConfirm,
        onRetryMediaUsage = viewModel::retryMediaUsage,
    )
}

@Composable
internal fun TokenUsageScreenContent(
    state: TokenStatsState,
    onNavigateBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onClear: () -> Unit = {},
    onRetryMediaUsage: () -> Unit = {},
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.token_title),
        onBack = onNavigateBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
        state.error?.let { error ->
            Text(
                text = stringResource(
                    when (error) {
                        TokenStatsErrorCode.LOAD_FAILED -> R.string.token_load_failed
                        TokenStatsErrorCode.CLEAR_FAILED -> R.string.token_clear_failed
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
            InfinityRetryButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (state.operation == TokenStatsOperation.LOADING) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
        } else {
            GlobalStatsCard(state)

            if (state.mediaUsageUnavailable) {
                Text(stringResource(R.string.media_usage_load_failed), color = MaterialTheme.colorScheme.error)
                InfinityRetryButton(onClick = onRetryMediaUsage)
            }
            if (state.mediaUsage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                MediaUsageSection(state.mediaUsage)
            }

            if (state.globalCache.requests > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                CacheUsageCard(state.globalCache)
            }

            if (state.categoryBreakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                InfinitySettingsSection(
                    title = stringResource(R.string.token_usage_categories),
                    footer = if (state.categoryBreakdown.any { it.usage.estimated }) stringResource(R.string.kg_usage_estimated) else null,
                ) {
                    state.categoryBreakdown.forEach { category ->
                        InfinitySettingsRowLayout(
                            title = stringResource(
                                when (category.category) {
                                    "kg" -> R.string.token_usage_kg
                                    "subagent" -> R.string.token_usage_subagent
                                    "pet" -> R.string.token_usage_pet
                                    else -> R.string.token_usage_chat
                                },
                            ),
                            subtitle = stringResource(R.string.token_category_counts,
                                formatTokenCount(category.usage.inputTokens), formatTokenCount(category.usage.outputTokens)),
                                                        trailingContent = {
                                if (category.category == "kg") Text(
                                    stringResource(R.string.kg_usage_calls, category.calls),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                )
                            },
                        )
                    }
                }
            }

            if (state.categoryBreakdown.isEmpty()) Spacer(modifier = Modifier.height(24.dp))

            if (state.topSessions.isNotEmpty()) {
                InfinitySettingsSection(title = stringResource(R.string.token_top_sessions)) {
                    state.topSessions.forEachIndexed { index, session -> SessionRankingRow(index + 1, session) }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (state.dailyTrend.isNotEmpty()) {
                SettingsSectionHeader(stringResource(R.string.token_7day_trend))
                TrendChart(state.dailyTrend)
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (state.modelBreakdown.isNotEmpty()) {
                // 按模型汇总：一行一个模型，费用或“无定价”写在副标题。
                InfinitySettingsSection(title = stringResource(R.string.token_by_model)) {
                    state.modelBreakdown.forEach { model -> ModelBreakdownRow(model) }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (state.globalInput == 0L && state.globalOutput == 0L) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.token_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.operation != TokenStatsOperation.LOADING) {
            InfinitySettingsSection(title = stringResource(R.string.settings_retrieval_data_section)) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.DeleteOutline,
                    title = stringResource(R.string.token_clear_history),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onClear,
                    enabled = state.operation == TokenStatsOperation.READY,
                )
            }
        }
        }
    }
}

@Composable
private fun GlobalStatsCard(state: TokenStatsState) {
    val totalTokens = state.globalInput + state.globalOutput

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.token_total_usage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (state.globalHasEstimated) {
                    Text(
                        text = "≈ ",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = formatTokenCount(totalTokens),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFeatureSettings = "tnum",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.token_tokens),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.token_in_short, formatTokenCount(state.globalInput)),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Text(
                    text = stringResource(R.string.token_out_short, formatTokenCount(state.globalOutput)),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (state.globalCostUSD > 0.0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.token_est_cost, state.globalCostUSD),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.tertiary
                )
                if (state.globalCacheDiscountMissing) {
                    Text(
                        text = stringResource(R.string.token_cost_partial_cache_discount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 主模型输入缓存：命中率、读取 / 写入 / 未缓存分段条与报告覆盖情况。 */
@Composable
private fun CacheUsageCard(cache: CacheUsageAggregate) {
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TOKEN_USAGE_CACHE_CARD_TAG),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.token_cache_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val coverage = cache.coverage
                if (coverage != null && cache.reportedRequests in 1 until cache.requests) {
                    Text(
                        text = stringResource(R.string.token_cache_coverage, java.text.NumberFormat.getPercentInstance(locale).format(coverage)),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val rate = cache.hitRate
            if (rate == null) {
                Text(
                    text = stringResource(R.string.token_cache_none_reported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                return@Column
            }
            val rateText = formatCacheHitRate(rate, locale)
            val rateDescription = stringResource(R.string.token_cache_hit_short, rateText)
            Text(
                text = rateText,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
                color = colors.primary,
                modifier = Modifier.clearAndSetSemantics { contentDescription = rateDescription },
            )
            CacheSegmentBar(
                readTokens = cache.cacheReadTokens,
                writeTokens = cache.cacheWriteTokens,
                uncachedTokens = (cache.reportedInputTokens - cache.cacheReadTokens - cache.cacheWriteTokens).coerceAtLeast(0),
                formatCount = ::formatTokenCount,
            )
            val unreported = (cache.requests - cache.reportedRequests).coerceAtLeast(0)
            if (unreported > 0) {
                Text(
                    text = pluralStringResource(R.plurals.token_cache_unreported_requests, unreported.toInt(), unreported.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionRankingRow(rank: Int, session: SessionTokenUsage) {
    InfinitySettingsRowLayout(
        title = session.title ?: session.sessionId.take(8),
        subtitle = stringResource(R.string.token_in_out, formatTokenCount(session.inputTokens), formatTokenCount(session.outputTokens)),
        leadingContent = {
            Text(rank.toString(), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
        },
                trailingContent = {
            Text(
                text = formatTokenCount(session.totalTokens),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun TrendChart(dailyTrend: List<DailyTokenStats>) {
    if (dailyTrend.isEmpty()) return

    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cacheColor = com.promenar.infinity.ui.common.CacheSegmentColors.read()
    val hasCache = dailyTrend.any { it.cache.reportedRequests > 0 }
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val hitLabels = dailyTrend.map { day -> day.cache.hitRate?.let { java.text.NumberFormat.getPercentInstance(locale).format(it) } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(16.dp)
            ) {
                val maxTokens = dailyTrend.maxOf { it.totalTokens }.coerceAtLeast(1)
                val chartWidth = size.width
                val chartHeight = size.height - 24.dp.toPx()

                val stepX = if (dailyTrend.size > 1) chartWidth / (dailyTrend.size - 1) else chartWidth

                for (i in 1..4) {
                    val y = chartHeight - (chartHeight * i / 4f)
                    drawLine(
                        color = outlineVariantColor.copy(alpha = 0.3f),
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1f
                    )
                }

                val inputPath = Path()
                val outputPath = Path()
                dailyTrend.forEachIndexed { index, stats ->
                    val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                    val inputY = chartHeight - (stats.inputTokens.toFloat() / maxTokens * chartHeight)
                    val outputY = chartHeight - (stats.outputTokens.toFloat() / maxTokens * chartHeight)
                    if (index == 0) {
                        inputPath.moveTo(x, inputY)
                        outputPath.moveTo(x, outputY)
                    } else {
                        inputPath.lineTo(x, inputY)
                        outputPath.lineTo(x, outputY)
                    }
                }

                // 缓存读取与输入同一刻度，画成输入线下方的面积；只连接报告了缓存的日子。
                if (hasCache) {
                    // 未报告缓存的日子断开面积，避免画成“缓存为 0”。
                    var run = mutableListOf<Offset>()
                    fun flush() {
                        if (run.isNotEmpty()) {
                            val path = Path().apply {
                                moveTo(run.first().x, chartHeight)
                                run.forEach { lineTo(it.x, it.y) }
                                lineTo(run.last().x, chartHeight)
                                close()
                            }
                            if (run.size == 1) {
                                drawLine(cacheColor.copy(alpha = 0.4f), Offset(run[0].x, chartHeight), run[0], strokeWidth = 4.dp.toPx())
                            } else {
                                drawPath(path = path, color = cacheColor.copy(alpha = 0.22f))
                            }
                        }
                        run = mutableListOf()
                    }
                    dailyTrend.forEachIndexed { index, stats ->
                        if (stats.cache.reportedRequests == 0L) {
                            flush()
                        } else {
                            val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                            run.add(Offset(x, chartHeight - (stats.cache.cacheReadTokens.toFloat() / maxTokens * chartHeight)))
                        }
                    }
                    flush()
                }

                drawPath(
                    path = outputPath,
                    color = tertiaryColor.copy(alpha = 0.6f),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawPath(
                    path = inputPath,
                    color = primaryColor,
                    style = Stroke(width = 2.dp.toPx())
                )

                dailyTrend.forEachIndexed { index, stats ->
                    val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                    val inputY = chartHeight - (stats.inputTokens.toFloat() / maxTokens * chartHeight)
                    drawCircle(
                        color = primaryColor,
                        radius = 3.dp.toPx(),
                        center = Offset(x, inputY)
                    )
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = onSurfaceVariantColor.copy(alpha = 0.7f).toArgb()
                        textSize = 10.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val hitPaint = android.graphics.Paint(paint).apply { color = primaryColor.toArgb() }
                    dailyTrend.forEachIndexed { index, stats ->
                        val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                        val day = stats.day.takeLast(5)
                        val half = paint.measureText(day) / 2
                        drawText(day, x.coerceIn(half, chartWidth - half), size.height, paint)
                    }
                    // 当日命中率标在输入点上方。
                    dailyTrend.forEachIndexed { index, stats ->
                        val label = hitLabels[index] ?: return@forEachIndexed
                        val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                        val inputY = chartHeight - (stats.inputTokens.toFloat() / maxTokens * chartHeight)
                        val labelX = x.coerceIn(hitPaint.measureText(label) / 2, chartWidth - hitPaint.measureText(label) / 2)
                        drawText(label, labelX, (inputY - 6.dp.toPx()).coerceAtLeast(10.dp.toPx()), hitPaint)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.token_input_label),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = onSurfaceVariantColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tertiaryColor.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.token_output_label),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = onSurfaceVariantColor
                )
                if (hasCache) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(cacheColor.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.token_trend_cache_read),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = onSurfaceVariantColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelBreakdownRow(model: ModelCostInfo) {
    val cost = when {
        model.pricingAvailable && model.costUSD > 0.0 -> "$%.4f".format(model.costUSD)
        !model.pricingAvailable -> stringResource(R.string.token_pricing_unavailable)
        else -> null
    }?.let { if (model.cacheDiscountMissing) it + " " + stringResource(R.string.token_cost_no_cache_discount) else it }
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val cacheHit = model.cache.hitRate?.let { stringResource(R.string.token_cache_hit_short, formatCacheHitRate(it, locale)) }
    InfinitySettingsRowLayout(
        title = if (model.modelId == com.promenar.infinity.data.rag.KgUsageStore.OTHER_MODEL_ID) stringResource(R.string.kg_usage_other_models) else model.name,
        subtitleSegments = listOfNotNull(
            stringResource(R.string.token_in_out, formatTokenCount(model.inputTokens), formatTokenCount(model.outputTokens)),
            cacheHit,
            cost,
        ),
        subtitleMaxLines = 3,
        trailingContent = {
            Text(
                text = formatTokenCount(model.inputTokens + model.outputTokens),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

internal const val TOKEN_USAGE_CACHE_CARD_TAG = "TOKEN_USAGE_CACHE_CARD"

private fun formatTokenCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}
