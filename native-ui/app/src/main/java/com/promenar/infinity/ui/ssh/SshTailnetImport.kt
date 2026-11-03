package com.promenar.infinity.ui.ssh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.settings.TailscalePeerIcon
import com.promenar.infinity.ui.settings.preferredTailnetAddress
import com.promenar.infinity.ui.settings.sortedForDisplay
import com.promenar.infinity.ui.settings.tailscaleOsLabel
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.util.Locale

/** 编辑面板里“从 Tailscale 选择”的状态：未启用内置 Tailscale 时不显示入口，已启用但未连接时灰显并说明原因。 */
internal enum class SshTailnetAvailability { HIDDEN, DISCONNECTED, CONNECTED }

internal fun sshTailnetAvailability(available: Boolean, phase: TailnetPhase): SshTailnetAvailability = when {
    !available || phase == TailnetPhase.UNAVAILABLE || phase == TailnetPhase.DISABLED -> SshTailnetAvailability.HIDDEN
    phase == TailnetPhase.RUNNING -> SshTailnetAvailability.CONNECTED
    else -> SshTailnetAvailability.DISCONNECTED
}

/** 可导入的 tailnet 设备：[address] 为填入主机名的 tailnet IP；[existingAlias] 为已指向该设备的 SSH 主机。 */
internal data class SshTailnetCandidate(val peer: TailnetPeer, val address: String, val existingAlias: String?)

/**
 * 设备列表：没有 tailnet 地址的设备不列出；顺序与 Tailscale 设置页一致（在线、离线）。
 * 填入 IP 而不是 MagicDNS 名：SSH 主机经内置 Tailscale 按地址路由，IP 不受 MagicDNS 开关影响。
 */
internal fun sshTailnetCandidates(peers: List<TailnetPeer>, hosts: List<SshHost>): List<SshTailnetCandidate> =
    peers.sortedForDisplay().mapNotNull { peer ->
        val address = preferredTailnetAddress(peer.ips) ?: return@mapNotNull null
        val names = (peer.ips + listOfNotNull(peer.dnsName)).mapTo(mutableSetOf()) { it.normalizedHostKey() }
        SshTailnetCandidate(peer, address, hosts.firstOrNull { it.hostname.normalizedHostKey() in names }?.alias)
    }

private fun String.normalizedHostKey() = trim().trimEnd('.').lowercase(Locale.ROOT)

/** 选中设备后的编辑面板字段：主机名填 tailnet IP，主机 ID 留空时按设备名生成。 */
internal fun SshHostEditorFieldsTarget.applyTailnetCandidate(candidate: SshTailnetCandidate) {
    hostname = candidate.address
    tailnetPeer = candidate.peer
}

/** [applyTailnetCandidate] 写入的字段；由编辑面板的字段类实现，便于单元测试。 */
internal interface SshHostEditorFieldsTarget {
    var hostname: String
    var tailnetPeer: TailnetPeer?
}

/** “从 Tailscale 选择”底部面板：每台设备一行，标出已添加的主机。 */
@Composable
internal fun SshTailnetPickerSheet(
    candidates: List<SshTailnetCandidate>,
    tailnetName: String?,
    onPick: (SshTailnetCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.ssh_tailnet_pick_title),
        scrollContent = true,
    ) {
        SshTailnetPickerContent(candidates, tailnetName, onPick)
    }
}

@Composable
internal fun SshTailnetPickerContent(
    candidates: List<SshTailnetCandidate>,
    tailnetName: String?,
    onPick: (SshTailnetCandidate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Medium)) {
        SheetNote(
            listOfNotNull(tailnetName, stringResource(R.string.ssh_tailnet_pick_count, candidates.size)).joinToString(" · "),  // wrap-check: allow 单行说明
        )
        if (candidates.isEmpty()) {
            SheetNote(stringResource(R.string.ssh_tailnet_pick_empty))
        }
        candidates.forEach { candidate ->
            val peer = candidate.peer
            val onlineText = stringResource(if (peer.online) R.string.tailscale_peer_online else R.string.tailscale_peer_offline)
            // 与 Tailscale 设备列表同一行样式：地址、系统、在线状态写在副标题里。
            InfinitySettingsRowLayout(
                title = peer.name,
                subtitle = listOfNotNull(
                    candidate.address,
                    tailscaleOsLabel(peer.os),
                    if (peer.online) null else onlineText,
                    candidate.existingAlias?.let { stringResource(R.string.ssh_tailnet_added_as, it) },
                ).joinToString(" · "),  // wrap-check: allow 单行省略
                titleColor = if (peer.online) null else MaterialTheme.colorScheme.onSurfaceVariant,
                leadingContent = { TailscalePeerIcon(peer) },
                modifier = Modifier
                    .testTag(sshTailnetPeerTag(peer.name))
                    .selectable(selected = false, role = Role.Button) { onPick(candidate) }
                    .semantics { stateDescription = onlineText },
            )
        }
        SheetNote(stringResource(R.string.ssh_tailnet_pick_footer))
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
    )
}

internal fun sshTailnetPeerTag(name: String) = "ssh_tailnet_peer:$name"
