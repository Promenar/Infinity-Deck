package com.promenar.infinity.ui.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import android.content.Context
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import androidx.compose.ui.unit.IntOffset
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.pressFeedback
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPaneTags
import com.promenar.infinity.ui.startup.StartupNotice
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/** 检视器页签与边缘条上的角标；[gitAvailable] 为 false（工作区不是 Git 仓库）时不显示 Git 页签。 */
internal data class ChatInspectorBadges(
    val running: Boolean = false,
    val pendingApprovals: Int = 0,
    /** 有计划、后台任务或状态面板等可看的运行内容。 */
    val hasRunContent: Boolean = false,
    val gitAvailable: Boolean = false,
    val gitChanges: Int = 0,
    val canvases: Int = 0,
    /** 本会话的 Agent 浏览器状态；为空表示还没有打开网页。 */
    val browser: com.promenar.infinity.data.browser.BrowserMode? = null,
    /** 会话提供浏览器入口时浏览器页签常驻，未打开网页时显示空状态。 */
    val browserEntry: Boolean = false,
    /** 远程页签圆点：智能体正在使用 SSH（主色）或 Tailscale 需要登录、连接失败（橙色）。 */
    val remote: RemoteDot? = null,
) {
    fun pages(): List<ChatPage> = ChatPage.entries.filter {
        (it != ChatPage.Git || gitAvailable) && (it != ChatPage.Browser || browserEntry || browser != null)
    }
}

private val ChatPage.icon: ImageVector
    get() = when (this) {
        ChatPage.Run -> Icons.Rounded.Checklist
        ChatPage.Explorer -> Icons.Outlined.Folder
        ChatPage.Git -> Icons.Rounded.AccountTree
        ChatPage.Canvas -> Icons.Rounded.Dashboard
        ChatPage.Browser -> Icons.Rounded.Public
        ChatPage.Remote -> Icons.Rounded.Lan
    }

@Composable
private fun ChatPage.label(): String = stringResource(
    when (this) {
        ChatPage.Run -> R.string.chat_inspector_tab_run
        ChatPage.Explorer -> R.string.chat_inspector_tab_files
        ChatPage.Git -> R.string.chat_inspector_tab_git
        ChatPage.Canvas -> R.string.chat_inspector_tab_canvas
        ChatPage.Browser -> R.string.chat_inspector_tab_browser
        ChatPage.Remote -> R.string.remote_panel_title
    },
)

/** 页签名加角标含义，供只有图标的入口朗读。 */
@Composable
private fun ChatPage.description(badges: ChatInspectorBadges): String = listOfNotNull(
    label(),
    if (this == ChatPage.Run && badges.pendingApprovals > 0) {
        pluralStringResource(R.plurals.chat_inspector_pending_approvals, badges.pendingApprovals, badges.pendingApprovals)
    } else null,
    if (this == ChatPage.Run && badges.running) stringResource(R.string.chat_inspector_running) else null,
    if (this == ChatPage.Browser) browserDotDescription(badges.browser) else null,
    if (this == ChatPage.Remote) remoteDotDescription(badges.remote) else null,
    when (this) {
        ChatPage.Git -> badges.gitChanges
        ChatPage.Canvas -> badges.canvases
        else -> 0
    }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.chat_inspector_count, it, it) },
).joinToString(", ")

/** 页签图标：运行中换成转圈；待批准、Git 改动数与画布数挂在右上角。 */
/**
 * 页签图标与角标：角标贴在图标右上角外侧，只压住图标右缘 [BadgeOverlap]，数字变宽时向图标内侧多让一点，
 * 伸出图标右缘始终不超过 [BadgeReach]。[reserveBadge] 为 true 时把伸出部分计入宽度，后面的页签名称不会被压住。
 */
@Composable
private fun InspectorIcon(page: ChatPage, badges: ChatInspectorBadges, tint: Color, reserveBadge: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val count = when (page) {
        ChatPage.Run -> badges.pendingApprovals
        ChatPage.Git -> badges.gitChanges
        ChatPage.Canvas -> badges.canvases
        ChatPage.Explorer, ChatPage.Browser, ChatPage.Remote -> 0
    }
    // 浏览器与远程页签不计数，只用圆点区分 Agent 操作中（主色）与需要用户处理（橙色）。
    val dot = when (page) {
        ChatPage.Browser -> browserDotColor(badges.browser)
        ChatPage.Remote -> remoteDotColor(badges.remote)
        else -> null
    }
    val badgeColor = if (page == ChatPage.Run) scheme.tertiary else scheme.primary
    val badgeContent = if (page == ChatPage.Run) scheme.onTertiary else scheme.onPrimary
    Layout(
        content = {
            if (page == ChatPage.Run && badges.running) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.primary)
                }
            } else {
                Icon(page.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            if (count > 0) {
                Badge(containerColor = badgeColor, contentColor = badgeContent) { Text(count.toString()) }
            } else if (dot != null) {
                Badge(containerColor = dot)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val icon = measurables[0].measure(loose)
        val badge = measurables.getOrNull(1)?.measure(loose)
        val reach = BadgeReach.roundToPx()
        val badgeX = if (badge == null) 0 else icon.width - maxOf(BadgeOverlap.roundToPx(), badge.width - reach)
        val width = if (badge != null && reserveBadge) badgeX + badge.width else icon.width
        layout(width, icon.height) {
            icon.place(0, 0)
            badge?.place(badgeX, -BadgeLift.roundToPx())
        }
    }
}

@Composable
private fun browserDotColor(mode: com.promenar.infinity.data.browser.BrowserMode?): Color? = when (mode) {
    com.promenar.infinity.data.browser.BrowserMode.AGENT -> MaterialTheme.colorScheme.primary
    com.promenar.infinity.data.browser.BrowserMode.HANDOFF -> MaterialTheme.colorScheme.tertiary
    else -> null
}

@Composable
private fun browserDotDescription(mode: com.promenar.infinity.data.browser.BrowserMode?): String? = when (mode) {
    com.promenar.infinity.data.browser.BrowserMode.AGENT -> stringResource(R.string.chat_inspector_browser_agent)
    com.promenar.infinity.data.browser.BrowserMode.HANDOFF -> stringResource(R.string.chat_inspector_browser_needs_you)
    else -> null
}

/** 远程页签圆点；两种情况同时出现时需要用户处理的橙点优先。 */
enum class RemoteDot { AGENT, ATTENTION }

internal fun remoteDot(agentUsingSsh: Boolean, tailscaleNeedsAttention: Boolean): RemoteDot? = when {
    tailscaleNeedsAttention -> RemoteDot.ATTENTION
    agentUsingSsh -> RemoteDot.AGENT
    else -> null
}

@Composable
private fun remoteDotColor(dot: RemoteDot?): Color? = when (dot) {
    RemoteDot.AGENT -> MaterialTheme.colorScheme.primary
    RemoteDot.ATTENTION -> MaterialTheme.colorScheme.tertiary
    null -> null
}

@Composable
private fun remoteDotDescription(dot: RemoteDot?): String? = when (dot) {
    RemoteDot.AGENT -> stringResource(R.string.remote_panel_dot_agent)
    RemoteDot.ATTENTION -> stringResource(R.string.remote_panel_dot_attention)
    null -> null
}

private val BadgeOverlap = 4.dp
private val BadgeReach = 12.dp
private val BadgeLift = 6.dp

private val InspectorShape = RoundedCornerShape(20.dp)

@Composable
private fun inspectorBorder() = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * 宽窗口会话右侧的浮动检视器：独立圆角卡片，不以竖线切开会话。
 * 底栏左侧是页签，右侧是加宽与收起；当前页签显示图标与名称，其余只显示图标与角标。
 * 内容以 [InfinityPanePresentation.Inspector] 承载：页面不再贴系统栏留白，根层不重复画顶栏。
 */
@Composable
internal fun ChatInspectorCard(
    root: ChatPage,
    badges: ChatInspectorBadges,
    widened: Boolean,
    onSelect: (ChatPage) -> Unit,
    onToggleWiden: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onLockTouch: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val paneLabel = stringResource(R.string.chat_inspector_label)
    val haptics = LocalInfinityHaptics.current
    Surface(
        modifier = modifier.semantics { paneTitle = paneLabel }.testTag(InfinityPaneTags.INSPECTOR),
        shape = InspectorShape,
        color = MaterialTheme.colorScheme.background,
        border = inspectorBorder(),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector) {
                Box(Modifier.weight(1f).fillMaxWidth(), content = content)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            // 页签与操作都在底栏：展开的内屏双手持握时拇指在下方，不必伸到屏幕顶部；收起留在最右，与边缘条底部的展开按钮同处。
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 页签区只用操作按钮之外的宽度：放得下就全部显示（当前页签带名称），放不下时其余页签收进“更多”菜单，
                // 不再横向滚动——滚动视口会把最后一个页签切成半截，看起来像一个残缺的图标。
                InspectorTabRow(
                    pages = badges.pages(),
                    root = root,
                    badges = badges,
                    onSelect = { page -> haptics.select(page == root); onSelect(page) },
                    modifier = Modifier.weight(1f),
                )
                // 宽窗口没有运行坞，生成中的触摸锁放在检视器底栏。
                onLockTouch?.let { TouchLockButton(onLock = it) }
                IconButton(onClick = { haptics.toggle(!widened); onToggleWiden() }, modifier = Modifier.testTag(ChatInspectorTags.WIDEN)) {
                    Icon(
                        if (widened) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(if (widened) R.string.chat_inspector_restore_width else R.string.chat_inspector_widen),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { haptics.toggle(false); onCollapse() }, modifier = Modifier.testTag(ChatInspectorTags.COLLAPSE)) {
                    Icon(
                        Icons.Rounded.KeyboardDoubleArrowRight,
                        contentDescription = stringResource(R.string.chat_inspector_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 底栏页签的触控宽度：只显示图标时正好是最小触控目标，角标落在这个宽度内。 */
private val InspectorTabWidth = 48.dp
/** 页签之间不留间距：48dp 触控目标相邻即可，360dp 检视器的底栏才能完整放下五个页签。 */
private val InspectorTabGap = 0.dp
/** 带名称的页签：图标左侧留白与只显示图标时一致，名称后再留右侧内边距。 */
private val InspectorTabLabelStart = 14.dp
private val InspectorTabLabelGap = 6.dp
private val InspectorTabLabelEnd = 12.dp

/** 底栏页签的排布：[visible] 留在底栏，[overflow] 收进“更多”菜单；[showSelectedLabel] 为 true 时当前页签带名称。 */
internal data class InspectorTabFit(
    val visible: List<ChatPage>,
    val overflow: List<ChatPage>,
    val showSelectedLabel: Boolean,
)

/**
 * 按可用宽度（dp）决定底栏页签怎么放：
 * 1. 全部图标加当前页签名称放得下时照常显示；
 * 2. 名称放不下时只显示图标；
 * 3. 图标也放不下时，末尾留一个“更多”按钮，放不下的页签按原顺序收进菜单，当前页签始终留在底栏。
 * 每个页签与“更多”按钮都占 [tabWidth]，保证触控目标不小于 48dp。
 */
internal fun inspectorTabFit(
    pages: List<ChatPage>,
    selected: ChatPage?,
    availableWidth: Float,
    selectedLabelExtra: Float,
    tabWidth: Float = InspectorTabWidth.value,
    gap: Float = InspectorTabGap.value,
): InspectorTabFit {
    fun rowWidth(count: Int) = if (count <= 0) 0f else count * tabWidth + (count - 1) * gap
    val hasSelected = selected != null && selected in pages
    val icons = rowWidth(pages.size)
    if (hasSelected && icons + selectedLabelExtra <= availableWidth) return InspectorTabFit(pages, emptyList(), showSelectedLabel = true)
    if (icons <= availableWidth || pages.size <= 1) return InspectorTabFit(pages, emptyList(), showSelectedLabel = false)
    // 留在底栏的页签数 n 满足 n 个页签加“更多”按钮放得下：(n + 1) * tab + n * gap <= available。
    val slots = ((availableWidth - tabWidth) / (tabWidth + gap)).toInt().coerceIn(1, pages.size - 1)
    val head = pages.take(slots)
    val visible = if (!hasSelected || selected in head) head else {
        val keep = pages.take(slots - 1).toSet() + selected!!
        pages.filter { it in keep }
    }
    return InspectorTabFit(visible, pages.filter { it !in visible }, showSelectedLabel = false)
}

/** 页签角标或圆点是否存在：用于给“更多”按钮挂提示圆点，以及估算当前页签名称占用的宽度。 */
private fun ChatPage.hasMark(badges: ChatInspectorBadges): Boolean = when (this) {
    ChatPage.Run -> badges.pendingApprovals > 0
    ChatPage.Git -> badges.gitChanges > 0
    ChatPage.Canvas -> badges.canvases > 0
    ChatPage.Browser -> badges.browser == com.promenar.infinity.data.browser.BrowserMode.AGENT ||
        badges.browser == com.promenar.infinity.data.browser.BrowserMode.HANDOFF
    ChatPage.Remote -> badges.remote != null
    ChatPage.Explorer -> false
}

@Composable
private fun InspectorTabRow(
    pages: List<ChatPage>,
    root: ChatPage,
    badges: ChatInspectorBadges,
    onSelect: (ChatPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val label = root.label()
    val labelStyle = InfinityTypography.labelLarge
    // 带名称时比只显示图标多出的宽度：名称、图标与名称的间距、两侧内边距差，以及角标伸出图标右缘的部分。
    val labelExtra = remember(label, labelStyle, density, root, badges) {
        val text = with(density) { measurer.measure(label, labelStyle, maxLines = 1).size.width.toDp() }
        text + InspectorTabLabelStart + 20.dp + InspectorTabLabelGap + InspectorTabLabelEnd - InspectorTabWidth +
            (if (root.hasMark(badges)) BadgeReach else 0.dp)
    }
    BoxWithConstraints(modifier) {
        val fit = inspectorTabFit(pages, root, maxWidth.value, labelExtra.value)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InspectorTabGap),
        ) {
            fit.visible.forEach { page ->
                InspectorTab(
                    page = page,
                    selected = page == root,
                    showLabel = page == root && fit.showSelectedLabel,
                    badges = badges,
                    onClick = { onSelect(page) },
                )
            }
            if (fit.overflow.isNotEmpty()) InspectorOverflowTabs(fit.overflow, badges, onSelect)
        }
    }
}

@Composable
private fun InspectorTab(page: ChatPage, selected: Boolean, showLabel: Boolean, badges: ChatInspectorBadges, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant
    val description = page.description(badges)
    // 可点的 Surface 自带最小触控尺寸：可见底色 40dp 高，触控高度补足 48dp；宽度至少 48dp。
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.surfaceContainerHigh else Color.Transparent,
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = InspectorTabWidth)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            }
            .testTag(ChatInspectorTags.tab(page)),
    ) {
        if (showLabel) {
            Row(
                modifier = Modifier.padding(start = InspectorTabLabelStart, end = InspectorTabLabelEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InspectorTabLabelGap),
            ) {
                InspectorIcon(page, badges, tint, reserveBadge = true)
                Text(page.label(), style = InfinityTypography.labelLarge, color = tint, maxLines = 1)
            }
        } else {
            // 只显示图标时图标居中，角标伸出图标右缘的部分仍落在 48dp 宽度内。
            Box(Modifier.width(InspectorTabWidth), contentAlignment = Alignment.Center) {
                InspectorIcon(page, badges, tint)
            }
        }
    }
}

/** 放不下的页签收进的“更多”按钮与菜单；收进去的页签有角标时按钮挂一个圆点。 */
@Composable
private fun InspectorOverflowTabs(pages: List<ChatPage>, badges: ChatInspectorBadges, onSelect: (ChatPage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = listOf(stringResource(R.string.chat_inspector_more_tabs)) + pages.map { it.label() }
    val marked = pages.any { it.hasMark(badges) }
    val interaction = remember { MutableInteractionSource() }
    Box {
        Box(
            modifier = Modifier
                .size(InspectorTabWidth)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = { expanded = true },
                )
                .semantics { contentDescription = description.joinToString(", ") }
                .testTag(ChatInspectorTags.MORE),
            contentAlignment = Alignment.Center,
        ) {
            // 按压反馈与页签同为 40dp 高的 12dp 圆角块，单独一层画，角标不被裁切。
            Box(Modifier.size(40.dp).pressFeedback(interaction, RoundedCornerShape(12.dp)))
            Box {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                if (marked) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                    )
                }
            }
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            pages.forEach { page ->
                InfinityMenuItem(
                    text = { Text(page.label()) },
                    onClick = { expanded = false; onSelect(page) },
                    // 选择页签的触感由 onSelect 统一给出，菜单项不再重复。
                    haptic = null,
                    leadingIcon = { InspectorIcon(page, badges, MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ChatInspectorTags.tab(page)),
                )
            }
        }
    }
}

/**
 * 检视器收起后的边缘条：贴在会话右缘、底部对齐的竖向胶囊，只有页签图标与角标，点按以该页签展开。
 * 没有任何内容时整个检视器隐藏，边缘条也不出现。
 */
@Composable
internal fun ChatInspectorStrip(
    badges: ChatInspectorBadges,
    onOpen: (ChatPage) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onLockTouch: (() -> Unit)? = null,
) {
    val haptics = LocalInfinityHaptics.current
    Surface(
        modifier = modifier.testTag(InfinityPaneTags.INSPECTOR_STRIP),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.background,
        border = inspectorBorder(),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            badges.pages().forEach { page ->
                val description = page.description(badges)
                val interaction = remember { MutableInteractionSource() }
                // 不用 IconButton：它把内容裁成 40dp 圆，伸出图标的角标会被切掉；按压圆单独一层画。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClick = { haptics.toggle(true); onOpen(page) },
                        )
                        .semantics { contentDescription = description }
                        .testTag(ChatInspectorTags.stripItem(page)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(40.dp).pressFeedback(interaction, CircleShape))
                    InspectorIcon(page, badges, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(
                modifier = Modifier.width(24.dp).padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            onLockTouch?.let { TouchLockButton(onLock = it) }
            IconButton(onClick = { haptics.toggle(true); onExpand() }, modifier = Modifier.testTag(ChatInspectorTags.EXPAND)) {
                Icon(
                    Icons.Rounded.KeyboardDoubleArrowLeft,
                    contentDescription = stringResource(R.string.chat_inspector_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 拖动 [dragY] 像素后边缘条的上移比例：0 贴底，1 顶到可移动范围上沿；没有可移动范围时保持不变。 */
internal fun inspectorStripLift(fraction: Float, dragY: Float, maxLiftPx: Float): Float =
    if (maxLiftPx <= 0f) fraction else (fraction - dragY / maxLiftPx).coerceIn(0f, 1f)

/**
 * 边缘条长按后上下拖动，挪开被它挡住的内容；[maxLiftPx] 为可上移的最大距离。
 * 位置按比例记住且全局共用，换会话、折叠屏开合或输入区变高后仍落在可用范围内。
 */
@Composable
internal fun Modifier.inspectorStripDrag(maxLiftPx: Float): Modifier {
    val context = LocalContext.current
    // 实验室沙箱里只在内存中拖动，不读写真实的界面偏好。
    val sandboxed = com.promenar.infinity.ui.common.LocalLabSandbox.current
    val prefs = remember(context, sandboxed) {
        if (sandboxed) null else context.applicationContext.getSharedPreferences("infinity_ui_state", Context.MODE_PRIVATE)
    }
    var fraction by remember { mutableFloatStateOf(prefs?.getFloat("chat_inspector_strip_lift", 0f)?.coerceIn(0f, 1f) ?: 0f) }
    val maxLift by rememberUpdatedState(maxLiftPx)
    val haptics by rememberUpdatedState(LocalInfinityHaptics.current)
    val save = { prefs?.edit()?.putFloat("chat_inspector_strip_lift", fraction)?.apply(); Unit }
    return this
        .offset { IntOffset(0, -(fraction * maxLift.coerceAtLeast(0f)).roundToInt()) }
        .pointerInput(prefs) {
            detectDragGesturesAfterLongPress(
                onDragStart = { haptics.perform(InfinityHapticEvent.SELECTION) },
                onDragEnd = save,
                onDragCancel = save,
            ) { change, drag ->
                change.consume()
                fraction = inspectorStripLift(fraction, drag.y, maxLift)
            }
        }
}

/**
 * 检视器图钉：按下时检视器固定在会话右侧、会话让位；松开时浮在会话之上。
 * 全局共用一份偏好，默认固定；视觉实验室沙箱里只在内存中切换。
 */
@Composable
internal fun rememberChatInspectorPinned(): MutableState<Boolean> {
    val context = LocalContext.current
    val sandboxed = com.promenar.infinity.ui.common.LocalLabSandbox.current
    val prefs = remember(context, sandboxed) {
        if (sandboxed) null else context.applicationContext.getSharedPreferences("infinity_ui_state", Context.MODE_PRIVATE)
    }
    val state = remember(prefs) { mutableStateOf(prefs?.getBoolean(CHAT_INSPECTOR_PINNED_KEY, true) ?: true) }
    LaunchedEffect(prefs, state.value) { prefs?.edit()?.putBoolean(CHAT_INSPECTOR_PINNED_KEY, state.value)?.apply() }
    return state
}

private const val CHAT_INSPECTOR_PINNED_KEY = "chat_inspector_pinned"

/** 运行页签：有待批准的操作时先给出跳转入口；没有运行内容时显示空状态。 */
@Composable
internal fun ChatInspectorRunTab(
    pendingApprovals: Int,
    onJumpToApproval: () -> Unit,
    hasRunContent: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (pendingApprovals > 0) {
            StartupNotice(
                title = pluralStringResource(R.plurals.chat_inspector_pending_approvals, pendingApprovals, pendingApprovals),
                detail = stringResource(R.string.chat_inspector_approval_detail),
                actionLabel = stringResource(R.string.chat_inspector_approval_action),
                icon = Icons.Rounded.VerifiedUser,
                onClick = onJumpToApproval,
                // 提示卡自带上下 4dp，再加 12dp，与左右一样距卡片边缘 16dp。
                modifier = Modifier.padding(top = InfinitySpacing.Medium).testTag(ChatInspectorTags.APPROVAL),
                titleMaxLines = 2,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasRunContent) content() else InspectorEmpty(
                icon = Icons.Rounded.Checklist,
                title = stringResource(R.string.chat_inspector_run_empty_title),
            )
        }
    }
}

/** 画布页签没有画布时的空状态，与运行、Git 页签一样在检视器中居中。 */
@Composable
internal fun ChatInspectorCanvasEmpty() = InspectorEmpty(
    icon = ChatPage.Canvas.icon,
    title = stringResource(R.string.canvas_empty_title),
)

@Composable
private fun InspectorEmpty(icon: ImageVector, title: String, detail: String? = null, iconSize: Dp = 32.dp) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = InfinitySpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(iconSize))
        Spacer(Modifier.height(InfinitySpacing.Medium))
        Text(title, style = InfinityTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(InfinitySpacing.XSmall))
            Text(
                detail,
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal object ChatInspectorTags {
    const val WIDEN = "chat_inspector_widen"
    const val COLLAPSE = "chat_inspector_collapse"
    const val EXPAND = "chat_inspector_expand"
    const val APPROVAL = "chat_inspector_approval"
    const val PIN = "chat_inspector_pin"
    const val MORE = "chat_inspector_more"
    fun tab(page: ChatPage) = "chat_inspector_tab_${page.token}"
    fun stripItem(page: ChatPage) = "chat_inspector_strip_${page.token}"
}
