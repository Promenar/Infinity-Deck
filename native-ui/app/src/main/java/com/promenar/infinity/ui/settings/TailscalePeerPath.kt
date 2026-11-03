package com.promenar.infinity.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPingResult
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.roundToInt

private val PathChipShape = RoundedCornerShape(50)

/** 中文界面时中转区域显示中文城市名。 */
@Composable
private fun rememberChineseLocale(): Boolean = LocalConfiguration.current.locales[0].language == "zh"

/** 路径种类的文字与颜色；颜色只区分“直连 / 中转 / 其它”三档。 */
@Composable
private fun tailscalePathKindText(kind: TailscalePathKind): String = stringResource(
    when (kind) {
        TailscalePathKind.DIRECT -> R.string.tailscale_path_direct
        TailscalePathKind.PEER_RELAY -> R.string.tailscale_path_peer_relay
        TailscalePathKind.DERP -> R.string.tailscale_path_derp
        TailscalePathKind.IDLE -> R.string.tailscale_path_idle
        TailscalePathKind.NO_REPLY -> R.string.tailscale_path_no_reply
    },
)

@Composable
private fun tailscalePathColor(kind: TailscalePathKind): Color = when (kind) {
    TailscalePathKind.DIRECT -> MaterialTheme.infinityDomainColors.success
    TailscalePathKind.PEER_RELAY, TailscalePathKind.DERP -> MaterialTheme.infinityDomainColors.warning
    TailscalePathKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    TailscalePathKind.NO_REPLY -> MaterialTheme.colorScheme.error
}

/** 设备行副标题末尾的路径标签：“直连 · 2 ms”“中转 · 香港 · 86 ms”“尚未建立连接”。 */
@Composable
internal fun TailscalePathChip(view: TailscalePathView, modifier: Modifier = Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    val (container, content) = when (view.kind) {
        TailscalePathKind.DIRECT -> domain.successContainer to domain.onSuccessContainer
        TailscalePathKind.PEER_RELAY, TailscalePathKind.DERP -> domain.warningContainer to domain.onWarningContainer
        TailscalePathKind.IDLE, TailscalePathKind.NO_REPLY ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val region = tailscaleRegionLabel(view.region, view.regionName, rememberChineseLocale())
    val latency = view.latencyMs?.let { stringResource(R.string.tailscale_latency_ms, tailscaleLatencyText(it)) }
    val text = listOfNotNull(tailscalePathKindText(view.kind), region, latency).joinToString(" · ") // wrap-check: allow 单行省略号标签
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(container, PathChipShape).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** 设备行副标题：地址与系统在前，在线设备后跟路径标签；窄屏时标签换到下一行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TailscalePeerSubtitle(meta: String?, path: TailscalePathView?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        meta?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        path?.let { TailscalePathChip(it, Modifier.testTag("tailscale_peer_path")) }
    }
}

/**
 * 点按在线设备打开的面板：连接路径、持续 Ping 的延迟曲线与暂停/继续，以及复制地址、SSH 主机入口。
 * 面板打开即开始检测（由调用方触发），关闭时停止。
 */
@Composable
internal fun TailscalePeerSheet(
    peer: TailnetPeer,
    run: TailscalePingRun?,
    lastPing: TailnetPingResult?,
    onPing: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    sshAction: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val address = preferredTailnetAddress(peer.ips)
    InfinityBottomSheet(show = true, onDismiss = onDismiss, scrollContent = true) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.ScreenHorizontal).padding(bottom = InfinitySpacing.Large)
                .testTag("tailscale_peer_sheet"),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TailscalePeerIcon(peer)
                Spacer(Modifier.width(InfinitySpacing.RowIconGap))
                Column(Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(tailscaleOsLabel(peer.os), peer.dnsName?.takeIf { it.isNotBlank() }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { // wrap-check: allow 单行省略号副标题
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            BettboxListGroup {
                TailscalePathCard(peer, run, lastPing, onPing, onStop)
            }
            BettboxListGroup {
                if (address != null) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.ContentCopy,
                        title = stringResource(R.string.tailscale_peer_copy_address),
                        subtitle = address,
                        subtitleIsIdentifier = true,
                        onClick = { copyTailnetAddress(context, address) },
                        modifier = Modifier.testTag("tailscale_peer_copy_address"),
                    )
                }
                sshAction?.invoke()
            }
        }
    }
}

@Composable
internal fun TailscalePathCard(
    peer: TailnetPeer,
    run: TailscalePingRun?,
    lastPing: TailnetPingResult?,
    onPing: () -> Unit,
    onStop: () -> Unit,
) {
    val chinese = rememberChineseLocale()
    // 检测中用最新一次结果；还没有结果时沿用列表行的路径。
    val view = run?.samples?.lastOrNull()?.pathView() ?: peer.pathView(lastPing) ?: TailscalePathView(TailscalePathKind.IDLE)
    val region = tailscaleRegionLabel(view.region, view.regionName, chinese)
    val unavailable = run?.finish == TailscalePingFinish.UNAVAILABLE
    val description = when {
        unavailable -> stringResource(R.string.tailscale_ping_unavailable)
        view.kind == TailscalePathKind.DIRECT -> stringResource(
            R.string.tailscale_path_direct_desc,
            stringResource(if (view.lan) R.string.tailscale_path_lan else R.string.tailscale_path_wan),
        )
        view.kind == TailscalePathKind.DERP -> stringResource(R.string.tailscale_path_derp_desc, region ?: "")
        view.kind == TailscalePathKind.PEER_RELAY -> stringResource(R.string.tailscale_path_peer_relay_desc)
        view.kind == TailscalePathKind.IDLE -> stringResource(R.string.tailscale_path_idle_desc)
        else -> stringResource(R.string.tailscale_path_no_reply_desc)
    }
    val samples = run?.samples.orEmpty()
    Column(Modifier.fillMaxWidth().padding(InfinitySpacing.Large).testTag("tailscale_peer_path_card")) {
        Text(stringResource(R.string.tailscale_peer_path_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(tailscalePathKindText(view.kind), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                view.latencyMs?.let {
                    Text(
                        stringResource(R.string.tailscale_latency_ms, tailscaleLatencyText(it)),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                        color = tailscalePathColor(view.kind),
                        maxLines = 1,
                    )
                }
            }
            val running = run?.running == true
            InfinityPillButton(
                text = stringResource(
                    when {
                        running -> R.string.tailscale_ping_pause
                        run == null -> R.string.tailscale_ping
                        else -> R.string.tailscale_ping_resume
                    },
                ),
                onClick = if (running) onStop else onPing,
                icon = if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
                modifier = Modifier.testTag("tailscale_ping_button"),
            )
        }
        // 预留两行，路径种类变化时面板高度不跳。
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )
        TailscalePingChart(samples, Modifier.padding(top = InfinitySpacing.Medium).fillMaxWidth().height(TailscalePingChartHeight))
        TailscalePingStats(run, Modifier.padding(top = InfinitySpacing.Small))
        TailscalePingLegend(Modifier.padding(top = InfinitySpacing.Small))
    }
}

private val TailscalePingChartHeight = 110.dp

/** 纵轴上限取覆盖窗口内最大延迟的整齐刻度，至少 20 ms。 */
internal fun tailscalePingScaleMax(samples: List<TailnetPingResult>): Double {
    val target = (samples.filter { it.path != null }.mapNotNull { it.latencyMs }.maxOrNull() ?: 0.0) * 1.15
    return TailscalePingScaleSteps.firstOrNull { it >= target } ?: (kotlin.math.ceil(target / 5_000) * 5_000)
}

private val TailscalePingScaleSteps = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0)

/**
 * 最近 [TAILSCALE_PING_WINDOW] 次的延迟曲线：最新一次在最右，按路径着色，超时在底部画红色刻线并断开曲线。
 * 画布高度固定，新结果不改变面板高度。
 */
@Composable
private fun TailscalePingChart(samples: List<TailnetPingResult>, modifier: Modifier) {
    val direct = MaterialTheme.infinityDomainColors.success
    val relay = MaterialTheme.infinityDomainColors.warning
    val timeout = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val maxMs = tailscalePingScaleMax(samples)
    val topLabel = stringResource(R.string.tailscale_latency_ms, tailscaleLatencyText(maxMs))
    val midLabel = stringResource(R.string.tailscale_latency_ms, tailscaleLatencyText(maxMs / 2))
    val description = stringResource(R.string.tailscale_ping_chart_cd, samples.size)
    Canvas(modifier.testTag("tailscale_ping_chart").semantics { contentDescription = description }) {
        val top = measurer.measure(topLabel, labelStyle)
        val mid = measurer.measure(midLabel, labelStyle)
        val labelGap = 6.dp.toPx()
        val plotRight = size.width - maxOf(top.size.width, mid.size.width) - labelGap
        val plotTop = top.size.height / 2f
        val plotBottom = size.height - 1.dp.toPx()
        val plotHeight = plotBottom - plotTop
        listOf(plotTop to top, plotTop + plotHeight / 2 to mid, plotBottom to null).forEach { (y, label) ->
            drawLine(grid, Offset(0f, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
            label?.let { drawText(it, topLeft = Offset(plotRight + labelGap, y - it.size.height / 2f)) }
        }
        val step = plotRight / (TAILSCALE_PING_WINDOW - 1)
        val offset = TAILSCALE_PING_WINDOW - samples.size
        fun x(index: Int) = (offset + index) * step
        fun y(ms: Double) = plotBottom - (ms / maxMs).coerceIn(0.0, 1.0).toFloat() * plotHeight
        fun color(sample: TailnetPingResult) = if (sample.path == TailnetPaths.DIRECT) direct else relay
        val stroke = 2.dp.toPx()
        samples.forEachIndexed { index, sample ->
            val ms = sample.latencyMs?.takeIf { sample.path != null }
            if (ms == null) {
                drawLine(timeout, Offset(x(index), plotBottom), Offset(x(index), plotBottom - 8.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                return@forEachIndexed
            }
            val previous = samples.getOrNull(index - 1)?.takeIf { it.path != null }?.latencyMs
            if (previous != null) {
                drawLine(color(sample), Offset(x(index - 1), y(previous)), Offset(x(index), y(ms)), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                drawCircle(color(sample), radius = stroke, center = Offset(x(index), y(ms)))
            }
        }
    }
}

/** 窗口内的最低、平均与丢包，以及本次打开面板以来的总次数；四项各占一格，单行。 */
@Composable
private fun TailscalePingStats(run: TailscalePingRun?, modifier: Modifier) {
    val samples = run?.samples.orEmpty()
    val replies = samples.filter { it.path != null }.mapNotNull { it.latencyMs }
    val none = stringResource(R.string.tailscale_ping_none)
    val min = replies.minOrNull()?.let { stringResource(R.string.tailscale_latency_ms, tailscaleLatencyText(it)) } ?: none
    val avg = replies.takeIf { it.isNotEmpty() }?.average()?.let { stringResource(R.string.tailscale_latency_ms, tailscaleLatencyText(it)) } ?: none
    val loss = if (samples.isEmpty()) 0 else ((samples.size - replies.size) * 100.0 / samples.size).roundToInt()
    val style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().testTag("tailscale_ping_stats"), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.tailscale_ping_min, min), style = style, color = color, maxLines = 1)
        Text(stringResource(R.string.tailscale_ping_avg, avg), style = style, color = color, maxLines = 1)
        Text(stringResource(R.string.tailscale_ping_loss, loss), style = style, color = color, maxLines = 1)
        Text(stringResource(R.string.tailscale_ping_sent, run?.sent ?: 0), style = style, color = color, maxLines = 1)
    }
}

@Composable
private fun TailscalePingLegend(modifier: Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
        TailscalePingLegendItem(domain.success, R.string.tailscale_path_direct)
        TailscalePingLegendItem(domain.warning, R.string.tailscale_ping_legend_relay)
        TailscalePingLegendItem(MaterialTheme.colorScheme.error, R.string.tailscale_ping_legend_timeout)
    }
}

@Composable
private fun TailscalePingLegendItem(color: Color, label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun copyTailnetAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.tailscale_peer_address_label), address))
}
