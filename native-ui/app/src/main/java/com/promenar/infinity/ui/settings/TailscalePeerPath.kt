package com.promenar.infinity.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
    val text = listOfNotNull(tailscalePathKindText(view.kind), region, latency).joinToString(" · ")
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
 * 点按在线设备打开的面板：连接路径、逐次 Ping 记录与停止/重测，以及复制地址、SSH 主机入口。
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
                    listOfNotNull(tailscaleOsLabel(peer.os), peer.dnsName?.takeIf { it.isNotBlank() }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
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
    val description = when (view.kind) {
        TailscalePathKind.DIRECT -> stringResource(
            R.string.tailscale_path_direct_desc,
            stringResource(if (view.lan) R.string.tailscale_path_lan else R.string.tailscale_path_wan),
        )
        TailscalePathKind.DERP -> stringResource(R.string.tailscale_path_derp_desc, region ?: "")
        TailscalePathKind.PEER_RELAY -> stringResource(R.string.tailscale_path_peer_relay_desc)
        TailscalePathKind.IDLE -> stringResource(R.string.tailscale_path_idle_desc)
        TailscalePathKind.NO_REPLY -> stringResource(R.string.tailscale_path_no_reply_desc)
    }
    Column(Modifier.fillMaxWidth().padding(InfinitySpacing.Large).testTag("tailscale_peer_path_card")) {
        Text(stringResource(R.string.tailscale_peer_path_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
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
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        val samples = run?.samples.orEmpty()
        if (samples.isNotEmpty()) {
            Column(Modifier.padding(top = InfinitySpacing.Medium).testTag("tailscale_ping_samples")) {
                samples.forEachIndexed { index, sample ->
                    Text(
                        tailscalePingSampleText(index + 1, sample, chinese),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tailscalePingNote(run),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = InfinitySpacing.Small),
            )
            val running = run?.running == true
            InfinityPillButton(
                text = stringResource(
                    when {
                        running -> R.string.tailscale_ping_stop
                        run?.samples.isNullOrEmpty() -> R.string.tailscale_ping
                        else -> R.string.tailscale_ping_again
                    },
                ),
                onClick = if (running) onStop else onPing,
                icon = if (running) Icons.Rounded.Stop else Icons.Rounded.NetworkPing,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
                modifier = Modifier.testTag("tailscale_ping_button"),
            )
        }
    }
}

@Composable
private fun tailscalePingSampleText(index: Int, sample: TailnetPingResult, chinese: Boolean): String {
    val ms = sample.latencyMs?.let(::tailscaleLatencyText) ?: "-"
    return when (sample.path) {
        TailnetPaths.DIRECT -> stringResource(
            R.string.tailscale_ping_sample_direct, index,
            stringResource(if (sample.directLan) R.string.tailscale_path_lan else R.string.tailscale_path_wan), ms,
        )
        TailnetPaths.DERP -> stringResource(
            R.string.tailscale_ping_sample_derp, index,
            tailscaleRegionLabel(sample.relayRegion, sample.relayRegionName, chinese) ?: "", ms,
        )
        TailnetPaths.PEER_RELAY -> stringResource(R.string.tailscale_ping_sample_peer_relay, index, ms)
        else -> stringResource(R.string.tailscale_ping_sample_timeout, index)
    }
}

@Composable
private fun tailscalePingNote(run: TailscalePingRun?): String = when (run?.finish) {
    null -> stringResource(if (run == null) R.string.tailscale_path_idle_desc else R.string.tailscale_ping_running)
    TailscalePingFinish.DIRECT -> stringResource(R.string.tailscale_ping_done_direct)
    TailscalePingFinish.EXHAUSTED -> stringResource(R.string.tailscale_ping_done_exhausted, TAILSCALE_PING_MAX_ATTEMPTS)
    TailscalePingFinish.UNREACHABLE -> stringResource(R.string.tailscale_ping_done_unreachable, TAILSCALE_PING_MAX_FAILURES)
    TailscalePingFinish.UNAVAILABLE -> stringResource(R.string.tailscale_ping_unavailable)
    TailscalePingFinish.STOPPED -> stringResource(R.string.tailscale_ping_done_stopped)
}

private fun copyTailnetAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.tailscale_peer_address_label), address))
}
