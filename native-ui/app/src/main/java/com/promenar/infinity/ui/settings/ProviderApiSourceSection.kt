package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetEndpoints
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.common.InfinityPopoverAlign
import com.promenar.infinity.ui.common.InfinityCapsuleTabRow
import com.promenar.infinity.ui.common.InfinityTabItem

/** 内置 Tailscale 状态行所需的本机信息。 */
data class ProviderTailnetInfo(
    val selfName: String? = null,
    val tailnetName: String? = null,
    val peers: List<TailnetPeer> = emptyList(),
)

/** 独立的“接口来源”分组；提供商表单里改用 [ProviderApiSourceRows] 并入“接口”分组卡片。 */
@Composable
internal fun ProviderApiSourceSection(
    source: ProviderApiSourceState,
    tailnet: ProviderTailnetInfo,
    onSourceSelected: (ProviderApiSource) -> Unit,
    onOpenTailscaleSettings: () -> Unit,
) {
    InfinitySettingsSection(
        title = stringResource(R.string.provider_api_source_title),
        footer = stringResource(R.string.provider_api_source_footer),
    ) {
        ProviderApiSourceRows(source, tailnet, onSourceSelected, onOpenTailscaleSettings)
    }
}

/** 公网 | Tailscale 内网 分段选择；选中 Tailscale 时下方展开内置 Tailscale 状态行。用于分组卡片内。 */
@Composable
internal fun ProviderApiSourceRows(
    source: ProviderApiSourceState,
    tailnet: ProviderTailnetInfo,
    onSourceSelected: (ProviderApiSource) -> Unit,
    onOpenTailscaleSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val options = listOf(
            Triple(ProviderApiSource.PUBLIC, R.string.provider_api_source_public, Icons.Rounded.Language),
            Triple(ProviderApiSource.TAILNET, R.string.provider_api_source_tailnet, Icons.Rounded.Lan),
        )
        InfinityCapsuleTabRow(
            tabs = options.map { (option, label, icon) ->
                InfinityTabItem(title = stringResource(label), icon = icon, testTag = providerApiSourceOptionTag(option))
            },
            selectedIndex = options.indexOfFirst { it.first == source.selected },
            onTabSelected = { index -> onSourceSelected(options[index].first) },
            modifier = Modifier
                .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Medium)
                .testTag(PROVIDER_API_SOURCE_TAG),
            itemRole = Role.RadioButton,
        )
        AnimatedVisibility(
            visible = source.selected == ProviderApiSource.TAILNET,
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            Column {
                InfinityGroupDivider()
                Box(Modifier.padding(start = InfinitySpacing.RowHorizontal, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    BuiltInTailscaleRow(source.builtIn, tailnet, onOpenTailscaleSettings)
                }
            }
        }
    }
}

@Composable
private fun BuiltInTailscaleRow(
    status: BuiltInTailscaleStatus,
    tailnet: ProviderTailnetInfo,
    onOpenTailscaleSettings: () -> Unit,
) {
    val colors = MaterialTheme.infinityDomainColors
    val (dotColor, filled) = when (status) {
        BuiltInTailscaleStatus.CONNECTED -> colors.success to true
        BuiltInTailscaleStatus.CONNECTING -> colors.warning to false
        BuiltInTailscaleStatus.NEEDS_LOGIN -> colors.warning to true
        BuiltInTailscaleStatus.FAILED -> MaterialTheme.colorScheme.error to true
        BuiltInTailscaleStatus.DISABLED, BuiltInTailscaleStatus.UNAVAILABLE -> MaterialTheme.colorScheme.outline to false
    }
    val title = stringResource(
        when (status) {
            BuiltInTailscaleStatus.CONNECTED -> R.string.provider_tailnet_status_connected
            BuiltInTailscaleStatus.CONNECTING -> R.string.provider_tailnet_status_connecting
            BuiltInTailscaleStatus.NEEDS_LOGIN -> R.string.provider_tailnet_status_needs_login
            BuiltInTailscaleStatus.FAILED -> R.string.provider_tailnet_status_failed
            BuiltInTailscaleStatus.DISABLED -> R.string.provider_tailnet_status_disabled
            BuiltInTailscaleStatus.UNAVAILABLE -> R.string.provider_tailnet_status_unavailable
        },
    )
    val detail = when (status) {
        BuiltInTailscaleStatus.CONNECTED ->
            listOfNotNull(tailnet.selfName, tailnet.tailnetName).joinToString(" · ").takeIf { it.isNotBlank() } // wrap-check: allow 单行省略
        BuiltInTailscaleStatus.UNAVAILABLE -> stringResource(R.string.provider_tailnet_status_unavailable_detail)
        else -> null
    }
    val action = when (status) {
        BuiltInTailscaleStatus.DISABLED -> R.string.provider_tailnet_action_enable
        BuiltInTailscaleStatus.NEEDS_LOGIN -> R.string.provider_tailnet_action_login
        BuiltInTailscaleStatus.UNAVAILABLE -> null
        else -> R.string.provider_tailnet_action_settings
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .testTag(PROVIDER_TAILNET_STATUS_TAG)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .then(
                    if (filled) Modifier.background(dotColor, CircleShape)
                    else Modifier.border(1.5.dp, dotColor, CircleShape),
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.let { style ->
                        if (status == BuiltInTailscaleStatus.CONNECTED) style.copy(fontFamily = InfinityMonospace) else style
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (status == BuiltInTailscaleStatus.CONNECTED) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.let {
            InfinityPillButton(
                text = stringResource(it),
                onClick = onOpenTailscaleSettings,
                compact = true,
                modifier = Modifier.testTag(PROVIDER_TAILNET_ACTION_TAG),
            )
        }
    }
}

/** 接口地址下方的访问路径提示。 */
@Composable
internal fun ProviderAddressHint(
    hint: ProviderApiSourceHint?,
    onSourceSelected: (ProviderApiSource) -> Unit,
) {
    when (hint) {
        ProviderApiSourceHint.RECOGNIZED_TAILNET -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(PROVIDER_ADDRESS_HINT_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.provider_address_hint_recognized),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ProviderApiSourceHint.ADDRESS_IS_TAILNET, ProviderApiSourceHint.ADDRESS_IS_PUBLIC -> {
            val toTailnet = hint == ProviderApiSourceHint.ADDRESS_IS_TAILNET
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .testTag(PROVIDER_ADDRESS_HINT_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.infinityDomainColors.warning,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(
                        if (toTailnet) R.string.provider_address_hint_is_tailnet else R.string.provider_address_hint_is_public,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                InfinityPillButton(
                    text = stringResource(
                        if (toTailnet) R.string.provider_address_hint_use_tailnet else R.string.provider_address_hint_use_public,
                    ),
                    onClick = { onSourceSelected(if (toTailnet) ProviderApiSource.TAILNET else ProviderApiSource.PUBLIC) },
                    compact = true,
                    modifier = Modifier.padding(end = 8.dp).testTag(PROVIDER_ADDRESS_HINT_FIX_TAG),
                )
            }
        }
        null -> Unit
    }
}

/** 可填入的 tailnet 设备：有 MagicDNS 全名（否则签不出 HTTPS 证书），在线的排在前面。 */
internal fun selectableTailnetPeers(peers: List<TailnetPeer>): List<TailnetPeer> =
    peers.filter { TailnetEndpoints.peerHost(it) != null }.sortedForDisplay()

/**
 * “tailnet 设备”选择行：显示接口地址当前对应的设备；点按打开底部面板选择设备，
 * 选中后把接口地址换成该设备的 https 全名。
 */
@Composable
internal fun TailnetPeerPicker(
    peers: List<TailnetPeer>,
    baseUrl: String,
    onPeerSelected: (TailnetPeer) -> Unit,
) {
    val usable = selectableTailnetPeers(peers)
    if (usable.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val anchor = rememberInfinityPopoverAnchor()
    val selected = usable.firstOrNull { TailnetEndpoints.matchesPeer(baseUrl, it) }
    val openLabel = stringResource(R.string.provider_tailnet_peer_sheet_title)
    InfinitySettingsRowLayout(
        title = stringResource(R.string.provider_tailnet_peer_field_label),
        subtitle = selected?.let { peerDetail(it) } ?: stringResource(R.string.provider_tailnet_peer_field_placeholder),
        leadingContent = selected?.let { peer -> { TailscalePeerIcon(peer) } },
        modifier = Modifier
            .infinityPopoverAnchor(anchor)
            .testTag(PROVIDER_TAILNET_PEERS_TAG)
            .clickable(role = Role.DropdownList, onClickLabel = openLabel) {
                if (!anchor.consumeRecentDismiss()) open = true
            }
            .semantics { selected?.name?.let { stateDescription = it } },
        trailingMaxWidthFraction = 0.45f,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selected?.name.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    InfinityBottomSheet(
        show = open,
        onDismiss = { open = false },
        title = openLabel,
        scrollContent = true,
        popover = InfinityPopoverSpec(anchor, align = InfinityPopoverAlign.Start),
    ) {
        TailnetPeerOptions(peers = usable, selected = selected) { peer ->
            onPeerSelected(peer)
            open = false
        }
    }
}

/** 设备面板的内容：设备行（图标带在线角标、全名与系统）与说明；选中项打勾。 */
@Composable
internal fun TailnetPeerOptions(
    peers: List<TailnetPeer>,
    selected: TailnetPeer?,
    onSelect: (TailnetPeer) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Medium)) {
        peers.forEach { peer ->
            val isSelected = peer == selected
            val onlineText = stringResource(if (peer.online) R.string.tailscale_peer_online else R.string.tailscale_peer_offline)
            InfinitySettingsRowLayout(
                title = peer.name,
                subtitle = peerDetail(peer),
                titleColor = if (peer.online) null else MaterialTheme.colorScheme.onSurfaceVariant,
                leadingContent = { TailscalePeerIcon(peer) },
                modifier = Modifier
                    .testTag(providerTailnetPeerTag(peer.name))
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(peer) }
                    .semantics { stateDescription = onlineText },
            ) {
                if (isSelected) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(
            text = stringResource(R.string.provider_tailnet_peer_sheet_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
        )
    }
}

/** 设备副标题：MagicDNS 全名 · 系统，离线时注明。 */
@Composable
private fun peerDetail(peer: TailnetPeer): String = listOfNotNull(
    // 离线放在最前，长全名在大字号下被截断时也不会丢掉状态
    if (peer.online) null else stringResource(R.string.provider_tailnet_peer_offline),
    TailnetEndpoints.peerHost(peer),
    tailscaleOsLabel(peer.os),
).joinToString(" · ")  // wrap-check: allow 调用处为单行省略

internal const val PROVIDER_API_SOURCE_TAG = "provider_api_source"
internal const val PROVIDER_TAILNET_STATUS_TAG = "provider_tailnet_status"
internal const val PROVIDER_TAILNET_ACTION_TAG = "provider_tailnet_action"
internal const val PROVIDER_ADDRESS_HINT_TAG = "provider_address_hint"
internal const val PROVIDER_ADDRESS_HINT_FIX_TAG = "provider_address_hint_fix"
internal const val PROVIDER_TAILNET_PEERS_TAG = "provider_tailnet_peers"
internal fun providerApiSourceOptionTag(source: ProviderApiSource) = "provider_api_source:${source.name.lowercase()}"
internal fun providerTailnetPeerTag(name: String) = "provider_tailnet_peer:$name"
