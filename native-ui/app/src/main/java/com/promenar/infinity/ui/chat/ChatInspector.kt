package com.promenar.infinity.ui.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import android.content.Context
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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

/** 远程条目的状态：智能体正在使用 SSH，或 Tailscale 需要登录、连接失败；两种情况同时出现时需要用户处理的优先。 */
enum class RemoteDot { AGENT, ATTENTION }

internal fun remoteDot(agentUsingSsh: Boolean, tailscaleNeedsAttention: Boolean): RemoteDot? = when {
    tailscaleNeedsAttention -> RemoteDot.ATTENTION
    agentUsingSsh -> RemoteDot.AGENT
    else -> null
}

@Composable
internal fun remoteDotDescription(dot: RemoteDot?): String? = when (dot) {
    RemoteDot.AGENT -> stringResource(R.string.remote_panel_dot_agent)
    RemoteDot.ATTENTION -> stringResource(R.string.remote_panel_dot_attention)
    null -> null
}

private val InspectorShape = RoundedCornerShape(20.dp)

@Composable
private fun inspectorBorder() = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * 宽窗口会话右侧的浮动检视器：独立圆角卡片，不以竖线切开会话。
 * 底栏左侧是功能面板页签（与窄窗口顶栏入口同一份 [entries]），右侧是加宽与收起；当前页签显示图标与名称，其余只显示图标与角标。
 * 内容以 [InfinityPanePresentation.Inspector] 承载：页面不再贴系统栏留白，根层不重复画顶栏。
 */
@Composable
internal fun ChatInspectorCard(
    root: ChatPage,
    entries: List<PanelHubEntry>,
    widened: Boolean,
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
                // 页签区只用操作按钮之外的宽度：放得下就全部显示（当前页签带名称），放不下时队尾的页签收进“更多”菜单。
                // 不横向滚动：固定模式下底栏紧贴屏幕底边，横滑会与系统手势冲突，滚动视口也会把最后一个页签切成半截。
                InspectorTabRow(
                    entries = entries,
                    root = root,
                    onSelect = { entry -> haptics.select(entry.page == root); entry.onOpen() },
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
 * 按可用宽度（dp）决定底栏页签怎么放（[pages] 已按功能面板入口排好序，不可用的在队尾）：
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

/** 条目带角标或圆点：用于给“更多”按钮挂提示圆点，以及估算当前页签名称占用的宽度。 */
private fun PanelHubEntry.hasMark(): Boolean = available && (pending > 0 || running)

@Composable
private fun InspectorTabRow(
    entries: List<PanelHubEntry>,
    root: ChatPage,
    onSelect: (PanelHubEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val label = root.hubLabel()
    val labelStyle = InfinityTypography.labelLarge
    val rootMarked = entries.firstOrNull { it.page == root }?.hasMark() == true
    // 带名称时比只显示图标多出的宽度：名称、图标与名称的间距、两侧内边距差，以及角标伸出图标右缘的部分。
    val labelExtra = remember(label, labelStyle, density, rootMarked) {
        val text = with(density) { measurer.measure(label, labelStyle, maxLines = 1).size.width.toDp() }
        text + InspectorTabLabelStart + 20.dp + InspectorTabLabelGap + InspectorTabLabelEnd - InspectorTabWidth +
            (if (rootMarked) PanelHubBadgeReach else 0.dp)
    }
    val byPage = entries.associateBy { it.page }
    BoxWithConstraints(modifier) {
        val fit = inspectorTabFit(entries.map { it.page }, root, maxWidth.value, labelExtra.value)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InspectorTabGap),
        ) {
            fit.visible.forEach { page ->
                val entry = byPage.getValue(page)
                InspectorTab(
                    entry = entry,
                    selected = page == root,
                    showLabel = page == root && fit.showSelectedLabel,
                    onClick = { onSelect(entry) },
                )
            }
            if (fit.overflow.isNotEmpty()) InspectorOverflowTabs(fit.overflow.map(byPage::getValue), onSelect)
        }
    }
}

@Composable
private fun InspectorTab(entry: PanelHubEntry, selected: Boolean, showLabel: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        !entry.available -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    val description = entry.description()
    // 可点的 Surface 自带最小触控尺寸：可见底色 40dp 高，触控高度补足 48dp；宽度至少 48dp。
    // 未被 Agent 调用过的条目灰调显示、不可点按。
    Surface(
        onClick = onClick,
        enabled = entry.available,
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
            .testTag(ChatInspectorTags.tab(entry.page)),
    ) {
        if (showLabel) {
            Row(
                modifier = Modifier.padding(start = InspectorTabLabelStart, end = InspectorTabLabelEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InspectorTabLabelGap),
            ) {
                PanelHubIcon(entry, tint, reserveBadge = true)
                Text(entry.page.hubLabel(), style = InfinityTypography.labelLarge, color = tint, maxLines = 1)
            }
        } else {
            // 只显示图标时图标居中，角标伸出图标右缘的部分仍落在 48dp 宽度内。
            Box(Modifier.width(InspectorTabWidth), contentAlignment = Alignment.Center) {
                PanelHubIcon(entry, tint)
            }
        }
    }
}

/**
 * 放不下的页签收进的“更多”按钮与菜单；收进去的可用条目有角标或运行圆点时按钮挂一个圆点。
 * 菜单里通常是队尾未使用的条目，与底栏一样灰调不可点。
 */
@Composable
private fun InspectorOverflowTabs(entries: List<PanelHubEntry>, onSelect: (PanelHubEntry) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = listOf(stringResource(R.string.chat_inspector_more_tabs)) + entries.map { it.page.hubLabel() }
    val marked = entries.any { it.hasMark() }
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
            entries.forEach { entry ->
                val itemDescription = entry.description()
                InfinityMenuItem(
                    text = { Text(entry.page.hubLabel()) },
                    onClick = { expanded = false; onSelect(entry) },
                    enabled = entry.available,
                    // 选择页签的触感由 onSelect 统一给出，菜单项不再重复。
                    haptic = null,
                    leadingIcon = { PanelHubIcon(entry, LocalContentColor.current) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) { contentDescription = itemDescription }
                        .testTag(ChatInspectorTags.tab(entry.page)),
                )
            }
        }
    }
}

/**
 * 检视器收起后的边缘条：贴在会话右缘、底部对齐的竖向胶囊，与底栏同一份 [entries]、同一顺序，只有图标与角标，点按以该页签展开。
 * 未使用的条目同样灰调不可点；条目多于可用高度时上下滚动，展开按钮始终在底部。
 */
@Composable
internal fun ChatInspectorStrip(
    entries: List<PanelHubEntry>,
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
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                entries.forEach { entry ->
                    val description = entry.description()
                    val interaction = remember { MutableInteractionSource() }
                    val tint = if (entry.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    // 不用 IconButton：它把内容裁成 40dp 圆，伸出图标的角标会被切掉；按压圆单独一层画。
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                enabled = entry.available,
                                role = Role.Button,
                                onClick = { haptics.toggle(true); entry.onOpen() },
                            )
                            .semantics { contentDescription = description }
                            .testTag(ChatInspectorTags.stripItem(entry.page)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp).pressFeedback(interaction, CircleShape))
                        PanelHubIcon(entry, tint)
                    }
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
    icon = ChatPage.Canvas.hubIcon,
    title = stringResource(R.string.canvas_empty_title),
)

/** 状态面板页签在面板被移除后的空状态。 */
@Composable
internal fun ChatInspectorStateEmpty() = InspectorEmpty(
    icon = ChatPage.State.hubIcon,
    title = stringResource(R.string.state_removed),
)

/** 制作面板页签没有数据源时的空状态。 */
@Composable
internal fun ChatInspectorProductionEmpty() = InspectorEmpty(
    icon = ChatPage.Production.hubIcon,
    title = stringResource(R.string.production_panel_empty),
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
