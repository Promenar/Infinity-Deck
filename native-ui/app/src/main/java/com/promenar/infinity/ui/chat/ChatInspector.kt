package com.promenar.infinity.ui.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.rememberCoroutineScope
import com.promenar.infinity.ui.common.FrostedChromeMaterial
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.BackdropSource
import com.promenar.infinity.ui.common.FrostedChromeBackdropLayer
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPaneTags
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.horizontalFadingEdges
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.common.panelTabScrollTarget
import com.promenar.infinity.ui.common.pressFeedback
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.startup.StartupNotice
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.rememberLastNonNull
import com.promenar.infinity.ui.theme.rememberLastPositive
import kotlin.math.roundToInt

private val InspectorCornerRadius = 20.dp
private val InspectorShape = RoundedCornerShape(InspectorCornerRadius)
private val InspectorStripCornerRadius = 24.dp

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
    floating: Boolean = false,
    backdrop: BackdropSource? = null,
    onLockTouch: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val paneLabel = stringResource(R.string.chat_inspector_label)
    val haptics = LocalInfinityHaptics.current
    // 固定分栏采用零模糊熏黑实底（DOCKED_INSPECTOR），扩大/覆盖态启用实时熏黑毛玻璃（FLOATING_INSPECTOR）。
    val chrome: FrostedChromeMaterial? = rememberFrostedChromeMaterial(
        if (floating) FrostedChromeRole.FLOATING_INSPECTOR else FrostedChromeRole.DOCKED_INSPECTOR,
    )
    Surface(
        modifier = modifier.semantics { paneTitle = paneLabel }.testTag(InfinityPaneTags.INSPECTOR),
        shape = InspectorShape,
        color = when {
            chrome == null -> MaterialTheme.colorScheme.background
            chrome.frostedTint != null -> Color.Transparent
            else -> chrome.container
        },
        // 透明底或自定义熏黑底不是 Material ColorScheme 键，必须显式提供 onSurface 作为内容色，防止子图标落回默认黑色。
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = chrome?.border ?: inspectorBorder(),
        shadowElevation = if (floating) 10.dp else 4.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (chrome != null) {
                FrostedChromeBackdropLayer(
                    source = if (floating) backdrop else null,
                    material = chrome,
                    highlightHeight = 56.dp,
                    cornerRadius = InspectorCornerRadius,
                    modifier = Modifier.matchParentSize(),
                )
            }
            CompositionLocalProvider(LocalFrostedChromeMaterial provides chrome) {
                Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector) {
                        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
                    }
                    HorizontalDivider(
                        // edgeDivider 自身已含基础透明度，用乘法收敛避免覆写为高不透明纯白线。
                        color = chrome?.edgeDivider?.let { it.copy(alpha = it.alpha * 0.7f) }
                            ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    // 页签与操作都在底栏：展开的内屏双手持握时拇指在下方，不必伸到屏幕顶部；收起留在最右，与边缘条底部的展开按钮同处。
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左侧横向滚动页签区，仅当前项展开短标题，其余保留图标与角标；右侧以竖线隔开固定操作区。
                        InspectorTabRow(
                            entries = entries,
                            root = root,
                            onSelect = { entry -> haptics.select(entry.page == root); entry.onOpen() },
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .width(1.dp)
                                .height(20.dp)
                                .background(
                                    chrome?.edgeDivider?.let { it.copy(alpha = it.alpha * 0.55f) }
                                        ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                ),
                        )
                        // 宽窗口没有运行坞，生成中的触摸锁放在检视器底栏；使用水平折叠与退场回调缓存避免挤跳页签。
                        val heldLock = rememberLastNonNull(onLockTouch, key = "inspector-card-lock")
                        AnimatedVisibility(
                            visible = onLockTouch != null && heldLock != null,
                            enter = expandHorizontally(tween(InfinityMotion.DurationShort)) + fadeIn(tween(InfinityMotion.DurationShort)),
                            exit = shrinkHorizontally(tween(InfinityMotion.DurationShort)) + fadeOut(tween(InfinityMotion.DurationShort)),
                        ) {
                            heldLock?.let { TouchLockButton(onLock = it) }
                        }
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
    }
}

/** 未选中页签的槽位宽度：20dp 图标居中后两侧各留 13dp，可容纳向右探出 12dp 的角标并让 360dp 起始位露头渐隐。 */
private val InspectorTabWidth = 46.dp
private val InspectorTabGap = 0.dp
/** 保持 48dp 横滑视口纵高，避免 -6dp 上探角标或大字号徽章被 Offscreen 渐隐层裁切。 */
private val InspectorTabTrackHeight = 48.dp
private val InspectorTabTrackInset = 4.dp
private val InspectorTabFadeWidth = 22.dp
private val InspectorTabLabelStart = 12.dp
private val InspectorTabLabelGap = 6.dp
private val InspectorTabLabelEnd = 12.dp
private val InspectorTabLabelVerticalPadding = 8.dp

/** 计算自动滚动后选中页签两侧的安全留白：优先完整避开渐隐边缘，极窄视口下收敛以防震荡。 */
internal fun inspectorTabScrollSafeEdge(viewport: Int, tabWidth: Int, fadePx: Int): Int =
    minOf(fadePx, ((viewport - tabWidth) / 2).coerceAtLeast(0))

@Composable
private fun InspectorTabRow(
    entries: List<PanelHubEntry>,
    root: ChatPage,
    onSelect: (PanelHubEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val bounds = remember { mutableStateMapOf<ChatPage, Pair<Int, Int>>() }
    var viewport by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fadePx = with(density) { InspectorTabFadeWidth.roundToPx() }
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val target = bounds[root]

    // 仅在切换页签、其布局尺寸变化或视口宽度改变时定位；不监听 scrollState.value，避免手动横滑浏览时抢回位置。
    LaunchedEffect(root, target, viewport) {
        val (x, width) = target ?: return@LaunchedEffect
        val safeEdge = inspectorTabScrollSafeEdge(viewport, width, fadePx)
        val next = panelTabScrollTarget(x, x + width, scrollState.value, viewport, safeEdge) ?: return@LaunchedEffect
        if (animate) scrollState.animateScrollTo(next) else scrollState.scrollTo(next)
    }

    Box(
        modifier = modifier
            .height(InspectorTabTrackHeight)
            .onSizeChanged { viewport = it.width }
            .horizontalFadingEdges(scrollState, length = InspectorTabFadeWidth)
            .horizontalScroll(scrollState),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .height(InspectorTabTrackHeight)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InspectorTabGap),
        ) {
            Spacer(Modifier.width(InspectorTabTrackInset))
            entries.forEach { entry ->
                InspectorTab(
                    entry = entry,
                    selected = entry.page == root,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        bounds[entry.page] = coords.positionInParent().x.roundToInt() to coords.size.width
                    },
                )
            }
            Spacer(Modifier.width(InspectorTabTrackInset))
        }
    }
}

@Composable
private fun InspectorTab(
    entry: PanelHubEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val targetTint = when {
        !entry.available -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(if (animate) InfinityMotion.DurationShort else 0),
        label = "inspector_tab_tint",
    )
    val chrome = LocalFrostedChromeMaterial.current
    val targetBg = if (selected) (chrome?.innerCard ?: scheme.surfaceContainerHigh) else Color.Transparent
    val backgroundColor by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(if (animate) InfinityMotion.DurationShort else 0),
        label = "inspector_tab_bg",
    )
    val startPad by animateDpAsState(
        targetValue = if (selected) InspectorTabLabelStart else 13.dp,
        animationSpec = tween(if (animate) InfinityMotion.DurationMedium else 0, easing = InfinityMotion.EmphasizedDecelerate),
        label = "inspector_tab_start_pad",
    )
    val endPad by animateDpAsState(
        targetValue = if (selected) InspectorTabLabelEnd else 13.dp,
        animationSpec = tween(if (animate) InfinityMotion.DurationMedium else 0, easing = InfinityMotion.EmphasizedDecelerate),
        label = "inspector_tab_end_pad",
    )
    val description = entry.description()
    // 未被 Agent 调用过的条目灰调显示、不可点按；统一单一 Row 结构，选中态水平展开短标题与底色。
    Surface(
        onClick = onClick,
        enabled = entry.available,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = if (selected) chrome?.innerCardBorder else null,
        modifier = modifier
            .heightIn(min = 40.dp)
            .widthIn(min = InspectorTabWidth)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            }
            .testTag(ChatInspectorTags.tab(entry.page)),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(
                    start = startPad,
                    end = endPad,
                    top = InspectorTabLabelVerticalPadding,
                    bottom = InspectorTabLabelVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelHubIcon(entry, tint, reserveBadge = selected)
            AnimatedVisibility(
                visible = selected,
                enter = expandHorizontally(tween(if (animate) InfinityMotion.DurationMedium else 0, easing = InfinityMotion.EmphasizedDecelerate)) +
                    fadeIn(tween(if (animate) InfinityMotion.DurationShort else 0)),
                exit = shrinkHorizontally(tween(if (animate) InfinityMotion.DurationShort else 0)) +
                    fadeOut(tween(if (animate) InfinityMotion.DurationFadeOut else 0)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(InspectorTabLabelGap))
                    Text(
                        text = entry.page.inspectorTabLabel(),
                        style = InfinityTypography.labelLarge,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
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
    backdrop: BackdropSource? = null,
    onLockTouch: (() -> Unit)? = null,
) {
    val haptics = LocalInfinityHaptics.current
    val chrome = rememberFrostedChromeMaterial(FrostedChromeRole.FLOATING_STRIP)
    Surface(
        modifier = modifier.testTag(InfinityPaneTags.INSPECTOR_STRIP),
        shape = RoundedCornerShape(InspectorStripCornerRadius),
        color = if (chrome.frostedTint != null) Color.Transparent else chrome.container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = chrome.border,
        shadowElevation = 6.dp,
    ) {
        Box {
            FrostedChromeBackdropLayer(
                source = backdrop,
                material = chrome,
                highlightHeight = 36.dp,
                cornerRadius = InspectorStripCornerRadius,
                modifier = Modifier.matchParentSize(),
            )
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
                    color = chrome.edgeDivider,
                )
                val heldStripLock = rememberLastNonNull(onLockTouch, key = "inspector-strip-lock")
                AnimatedVisibility(
                    visible = onLockTouch != null && heldStripLock != null,
                    enter = InfinityMotion.expandIn,
                    exit = InfinityMotion.shrinkOut,
                ) {
                    heldStripLock?.let { TouchLockButton(onLock = it) }
                }
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
        val heldApprovals = rememberLastPositive(pendingApprovals, key = "run-pending-approvals")
        AnimatedVisibility(
            visible = pendingApprovals > 0 && heldApprovals > 0,
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            StartupNotice(
                title = pluralStringResource(R.plurals.chat_inspector_pending_approvals, heldApprovals, heldApprovals),
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

/**
 * 竖屏同窗口 93% 全高熏黑毛玻璃上滑抽屉：
 * - 与宽屏检视器共享同一份面板正文、两档坐标契约与底栏页签；
 * - 顶部居中拖拽把手区独占下滑关闭手势（内部列表、WebView、终端滚动不抢拖关闭）；
 * - 弹起软键盘或推入栈式子页时自动平滑折叠底部页签条，把完整垂直空间让给终端/输入区。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatPortraitObsidianDrawer(
    root: ChatPage,
    entries: List<PanelHubEntry>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showBottomBar: Boolean = true,
    backdrop: BackdropSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val paneLabel = stringResource(R.string.chat_inspector_label)
    val haptics = LocalInfinityHaptics.current
    val chrome = rememberFrostedChromeMaterial(FrostedChromeRole.PORTRAIT_DRAWER)
    val dark = MaterialTheme.infinityDomainColors.isDark
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(density) { 110.dp.toPx() }
    val imeVisible = WindowInsets.isImeVisible
    val drawerShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Surface(
        modifier = modifier
            .offset { IntOffset(0, dragOffsetY.value.roundToInt().coerceAtLeast(0)) }
            .semantics { paneTitle = paneLabel }
            .testTag(ChatInspectorTags.PORTRAIT_DRAWER),
        shape = drawerShape,
        color = if (chrome.frostedTint != null) Color.Transparent else chrome.container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = chrome.border,
        shadowElevation = 14.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            FrostedChromeBackdropLayer(
                source = backdrop,
                material = chrome,
                highlightHeight = 56.dp,
                cornerRadius = 24.dp,
                modifier = Modifier.matchParentSize(),
            )
            CompositionLocalProvider(LocalFrostedChromeMaterial provides chrome) {
                Column(Modifier.fillMaxSize()) {
                    // 仅顶部拖拽把手条响应下滑关闭手势，避免内容列表/WebView/终端误关面板。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(onClose) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        scope.launch {
                                            dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceAtLeast(0f))
                                        }
                                    },
                                    onDragEnd = {
                                        scope.launch {
                                            if (dragOffsetY.value >= dismissThresholdPx) {
                                                haptics.toggle(false)
                                                onClose()
                                                dragOffsetY.snapTo(0f)
                                            } else {
                                                dragOffsetY.animateTo(
                                                    0f,
                                                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                                )
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            dragOffsetY.animateTo(
                                                0f,
                                                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                    },
                                )
                            }
                            .padding(top = 10.dp, bottom = 6.dp)
                            .testTag(ChatInspectorTags.PORTRAIT_HANDLE),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    if (dark) InfinityPureWhite.copy(alpha = 0.24f) else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                        )
                    }
                    CompositionLocalProvider(LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector) {
                        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
                    }
                    AnimatedVisibility(
                        visible = showBottomBar && !imeVisible,
                        enter = expandVertically(tween(InfinityMotion.DurationShort)) + fadeIn(tween(InfinityMotion.DurationShort)),
                        exit = shrinkVertically(tween(InfinityMotion.DurationShort)) + fadeOut(tween(InfinityMotion.DurationShort)),
                    ) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                            HorizontalDivider(color = chrome.edgeDivider.copy(alpha = chrome.edgeDivider.alpha * 0.7f))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                InspectorTabRow(
                                    entries = entries,
                                    root = root,
                                    onSelect = { entry -> haptics.select(entry.page == root); entry.onOpen() },
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(1.dp)
                                        .height(20.dp)
                                        .background(chrome.edgeDivider.copy(alpha = chrome.edgeDivider.alpha * 0.55f)),
                                )
                                IconButton(
                                    onClick = { haptics.toggle(false); onClose() },
                                    modifier = Modifier.testTag(ChatInspectorTags.COLLAPSE),
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.chat_inspector_collapse),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object ChatInspectorTags {
    const val WIDEN = "chat_inspector_widen"
    const val COLLAPSE = "chat_inspector_collapse"
    const val EXPAND = "chat_inspector_expand"
    const val APPROVAL = "chat_inspector_approval"
    const val SCRIM = "chat_inspector_scrim"
    const val PORTRAIT_DRAWER = "chat_portrait_drawer"
    const val PORTRAIT_HANDLE = "chat_portrait_drawer_handle"
    fun tab(page: ChatPage) = "chat_inspector_tab_${page.token}"
    fun stripItem(page: ChatPage) = "chat_inspector_strip_${page.token}"
}
