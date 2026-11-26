package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserUiState
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.pressFeedback
import com.promenar.infinity.ui.git.GitEntrySummary
import com.promenar.infinity.ui.git.gitSummaryLine
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 功能面板条目的图标；新增条目时在这里补上。 */
internal val ChatPage.hubIcon: ImageVector
    get() = when (this) {
        ChatPage.Run -> Icons.Rounded.Checklist
        ChatPage.Explorer -> Icons.Outlined.Folder
        ChatPage.Git -> Icons.Rounded.AccountTree
        ChatPage.Browser -> Icons.Rounded.Public
        ChatPage.Canvas -> Icons.Rounded.Dashboard
        ChatPage.State -> Icons.Rounded.TableChart
        ChatPage.Production -> com.promenar.infinity.ui.production.ProductionPanelEntry.icon
    }

/** 功能面板条目的名称；窄屏下拉菜单与无障碍朗读保留完整描述。 */
@Composable
internal fun ChatPage.hubLabel(): String = stringResource(
    when (this) {
        ChatPage.Run -> R.string.chat_inspector_tab_run
        ChatPage.Explorer -> R.string.chat_inspector_tab_files
        ChatPage.Git -> R.string.chat_inspector_tab_git
        ChatPage.Browser -> R.string.chat_inspector_tab_browser
        ChatPage.Canvas -> R.string.chat_inspector_tab_canvas
        ChatPage.State -> R.string.panel_hub_tab_state
        ChatPage.Production -> com.promenar.infinity.ui.production.ProductionPanelEntry.titleRes
    },
)

/** 宽窗口检视器底栏页签使用的短标题资源（中文上限 3 字，避免长标题挤占横滑视口）。 */
internal fun ChatPage.inspectorTabLabelRes(): Int = when (this) {
    ChatPage.Run -> R.string.chat_inspector_tab_run
    ChatPage.Explorer -> R.string.chat_inspector_tab_files
    ChatPage.Git -> R.string.chat_inspector_tab_git
    ChatPage.Browser -> R.string.chat_inspector_tab_browser
    ChatPage.Canvas -> R.string.chat_inspector_tab_canvas
    ChatPage.State -> R.string.chat_inspector_tab_state
    ChatPage.Production -> R.string.chat_inspector_tab_production
}

@Composable
internal fun ChatPage.inspectorTabLabel(): String = stringResource(inspectorTabLabelRes())

/** 条目的朗读文字：名称、摘要（不可用时为“未使用”）、待审批/待处理数、待发送数与运行中。 */
@Composable
internal fun PanelHubEntry.description(): String = listOfNotNull(
    page.hubLabel(),
    if (available) summary?.takeIf { it.isNotBlank() } else stringResource(R.string.panel_hub_unused),
    pending.takeIf { available && it > 0 }?.let {
        if (secondaryPending > 0 || page == ChatPage.Production) {
            stringResource(R.string.chat_inspector_badge_review_desc, it)
        } else {
            pluralStringResource(R.plurals.panel_hub_pending, it, it)
        }
    },
    secondaryPending.takeIf { available && it > 0 }?.let {
        stringResource(R.string.chat_inspector_badge_send_desc, it)
    },
    if (available && running && pending == 0 && secondaryPending == 0) stringResource(R.string.chat_inspector_running) else null,
).joinToString(", ")

/**
 * 条目图标与标记：运行页签在运行中换成转圈；[showPending] 为 true 时待审批数以警示色角标贴在图标右上角外侧，
 * 无待审批但有待发送项时以信息色角标展示，两者兼有时右上挂警示角标、右下补信息色小圆点；没有角标但运行中时挂主色圆点。
 * 只压住图标右缘 [PanelHubBadgeOverlap]，伸出右缘不超过 [PanelHubBadgeReach]；
 * [reserveBadge] 为 true 时把伸出部分计入宽度，后面的名称不会被压住。
 */
@Composable
internal fun PanelHubIcon(entry: PanelHubEntry, tint: Color, showPending: Boolean = true, reserveBadge: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val spinning = entry.page == ChatPage.Run && entry.running && entry.available
    val count = if (showPending && entry.available) entry.pending else 0
    val secondaryCount = if (showPending && entry.available) entry.secondaryPending else 0
    val dot = entry.available && entry.running && entry.pending == 0 && entry.secondaryPending == 0 && !spinning
    val showSecondaryDot = count > 0 && secondaryCount > 0
    Layout(
        content = {
            if (spinning) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.primary)
                }
            } else {
                Icon(entry.page.hubIcon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            when {
                count > 0 -> Badge(containerColor = scheme.tertiary, contentColor = scheme.onTertiary) { Text(count.toString()) }
                secondaryCount > 0 -> Badge(containerColor = domain.infoContainer, contentColor = domain.onInfoContainer) { Text(secondaryCount.toString()) }
                dot -> Badge(containerColor = scheme.primary)
            }
            if (showSecondaryDot) {
                Box(Modifier.size(6.dp).background(domain.info, CircleShape))
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val icon = measurables[0].measure(loose)
        val badge = measurables.getOrNull(1)?.measure(loose)
        val secondaryDot = measurables.getOrNull(2)?.measure(loose)
        val reach = PanelHubBadgeReach.roundToPx()
        val badgeX = if (badge == null) 0 else icon.width - maxOf(PanelHubBadgeOverlap.roundToPx(), badge.width - reach)
        val width = if (badge != null && reserveBadge) badgeX + badge.width else icon.width
        layout(width, icon.height) {
            icon.place(0, 0)
            badge?.place(badgeX, -PanelHubBadgeLift.roundToPx())
            secondaryDot?.place(icon.width - secondaryDot.width / 2, icon.height - secondaryDot.height / 2)
        }
    }
}

internal val PanelHubBadgeOverlap = 4.dp
internal val PanelHubBadgeReach = 12.dp
private val PanelHubBadgeLift = 6.dp

/**
 * 窄窗口顶栏 ⋮ 左侧的功能面板入口：有待处理时挂待处理总数，没有待处理但有运行中内容时挂运行圆点。
 * 点按弹出条目列表，每行是图标、名称、当前内容摘要与待处理数；不可用条目灰调显示、不可点按，摘要为“未使用”。
 */
@Composable
internal fun PanelHubButton(entries: List<PanelHubEntry>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalInfinityHaptics.current
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val badge = panelHubBadge(entries)
    val description = listOfNotNull(
        stringResource(R.string.panel_hub_title),
        when (badge) {
            is PanelHubBadge.Pending -> pluralStringResource(R.plurals.panel_hub_pending, badge.count, badge.count)
            is PanelHubBadge.SecondaryPending -> stringResource(R.string.chat_inspector_badge_send_desc, badge.count)
            PanelHubBadge.Running -> stringResource(R.string.chat_inspector_running)
            PanelHubBadge.None -> null
        },
    ).joinToString(", ")
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        // 不用 IconButton：它把内容裁成 40dp 圆，伸出图标的角标会被切掉；按压圆单独一层画。
        Box(
            modifier = Modifier
                .size(InfinitySpacing.MinimumTouchTarget)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = { haptics.toggle(true); expanded = true },
                )
                .semantics { contentDescription = description }
                .testTag(PanelHubTags.BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).pressFeedback(interaction, CircleShape))
            Box {
                Icon(Icons.Rounded.SpaceDashboard, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
                when (badge) {
                    is PanelHubBadge.Pending -> Badge(
                        containerColor = scheme.tertiary,
                        contentColor = scheme.onTertiary,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).testTag(PanelHubTags.BUTTON_PENDING),
                    ) { Text(if (badge.count > 99) "99+" else badge.count.toString()) }
                    is PanelHubBadge.SecondaryPending -> Badge(
                        containerColor = domain.infoContainer,
                        contentColor = domain.onInfoContainer,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).testTag(PanelHubTags.BUTTON_PENDING),
                    ) { Text(if (badge.count > 99) "99+" else badge.count.toString()) }
                    PanelHubBadge.Running -> Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(8.dp)
                            .background(scheme.primary, CircleShape)
                            .testTag(PanelHubTags.BUTTON_RUNNING),
                    )
                    PanelHubBadge.None -> Unit
                }
            }
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, minWidth = 240.dp) {
            entries.forEach { entry -> PanelHubMenuItem(entry, onClick = { expanded = false; entry.onOpen() }) }
        }
    }
}

@Composable
private fun PanelHubMenuItem(entry: PanelHubEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val summary = if (entry.available) entry.summary?.takeIf { it.isNotBlank() } else stringResource(R.string.panel_hub_unused)
    val description = entry.description()
    InfinityMenuItem(
        text = {
            Column {
                Text(entry.page.hubLabel(), style = InfinityTypography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary != null) {
                    Text(
                        summary,
                        style = InfinityTypography.bodySmall,
                        color = if (entry.available) scheme.onSurfaceVariant else LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        enabled = entry.available,
        // 打开面板本身有页面过渡，菜单项不再给触感。
        haptic = null,
        leadingIcon = { PanelHubIcon(entry, LocalContentColor.current, showPending = false) },
        trailingIcon = if (entry.available && (entry.pending > 0 || entry.secondaryPending > 0)) {
            { PanelHubTrailingBadges(entry.pending, entry.secondaryPending) }
        } else null,
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(PanelHubTags.item(entry.page)),
    )
}

/** 弹出列表行尾角标：待审用警示色胶囊，待发送用信息色「发 N」胶囊，语义分离不混加。 */
@Composable
private fun PanelHubTrailingBadges(pending: Int, secondaryPending: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pending > 0) PanelHubCount(pending)
        if (secondaryPending > 0) PanelHubSecondaryCount(secondaryPending)
    }
}

/** 弹出列表行尾的待处理数：警示色胶囊，与顶栏入口角标同色。 */
@Composable
private fun PanelHubCount(count: Int) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .background(scheme.tertiary, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum"), color = scheme.onTertiary, maxLines = 1)
    }
}

/** 弹出列表行尾的待发送数：信息色胶囊（「发 N」），区别于需要审批的警示色。 */
@Composable
private fun PanelHubSecondaryCount(count: Int) {
    val domain = MaterialTheme.infinityDomainColors
    Box(
        Modifier
            .background(domain.infoContainer, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_inspector_badge_send, count),
            style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = domain.onInfoContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 浏览器条目的摘要：Agent 正在操作或需要你接手时写状态，否则写当前网页标题或地址，还没有打开网页时为“未打开”。 */
@Composable
internal fun panelHubBrowserSummary(browser: BrowserUiState?): String = when (browser?.mode) {
    BrowserMode.AGENT -> stringResource(R.string.chat_inspector_browser_agent)
    BrowserMode.HANDOFF -> stringResource(R.string.chat_inspector_browser_needs_you)
    else -> browser?.title?.takeIf { it.isNotBlank() } ?: browser?.url?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.panel_hub_browser_closed)
}

/**
 * Git 条目的摘要：读到仓库时为“分支 · 改动数”；状态还没读出时为“读取中”，不是仓库或读不出时为短状态。
 * 摘要行始终存在，读到后原地替换，弹出列表的行高不跳动。
 */
@Composable
internal fun panelHubGitSummary(entry: GitEntrySummary?): String = when (entry) {
    is GitEntrySummary.Repository -> gitSummaryLine(entry, compactChanges = true)
    null, GitEntrySummary.Loading -> stringResource(R.string.panel_hub_git_loading)
    GitEntrySummary.Hidden -> stringResource(R.string.panel_hub_git_none)
    GitEntrySummary.PythonUnavailable, GitEntrySummary.Failed -> stringResource(R.string.panel_hub_git_unavailable)
}

/** 产物数量、待认领数等多段摘要以“ · ”连成一行；全部为空时为 null。 */
internal fun panelHubJoin(parts: List<String?>): String? =
    parts.filterNot { it.isNullOrBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" $InfinityMetaSeparator ")

internal object PanelHubTags {
    const val BUTTON = "chat_panel_hub"
    const val BUTTON_PENDING = "chat_panel_hub_pending"
    const val BUTTON_RUNNING = "chat_panel_hub_running"
    fun item(page: ChatPage) = "chat_panel_hub_${page.token}"
}
