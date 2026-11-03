package com.promenar.infinity.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.SheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography

/**
 * 全站上滑面板的高度策略：
 * - [Full]：内容面板、浏览器、编辑器与长表单，展开即占满屏幕（状态栏以下）；
 * - [Content]：单选列表、目标选择与短表单，按内容高度显示，超出屏幕时由内容自行滚动。
 */
enum class InfinitySheetHeight { Full, Content }

/**
 * 全站统一的上滑面板外壳：同一形状、底色与拖动条，始终直接完全展开（不停在半屏）。
 * 只提供外壳，不加标题与内边距；需要标题与标准内边距时用 [InfinityBottomSheet]。
 *
 * 宽窗口（[rememberInfinityWideWindow]，折叠屏内屏、平板）不从底边升起整屏面板，改为居中的圆角对话框：
 * 最宽 640dp，[InfinitySheetHeight.Full] 占窗口高度的 88%，[InfinitySheetHeight.Content] 贴合内容；
 * 点外部与返回按 [properties] 关闭，没有拖动关闭。
 * 给出 [popover] 且已量到触发控件位置时，宽窗口改为贴着控件的弹出层（[InfinityPopover]），窄窗口不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinityModalSheet(
    onDismissRequest: () -> Unit,
    height: InfinitySheetHeight,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetGesturesEnabled: Boolean = true,
    /**
     * 为 false 时内容滚到边界后不接力拖动面板：内部列表、表单滚动与面板拖动不再在边界来回争抢位移（上下抖动），
     * 面板仍可从拖动条、标题等不滚动的区域下滑关闭。
     */
    contentDragsSheet: Boolean = true,
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    popover: InfinityPopoverSpec? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (infinitySheetPresentation(rememberInfinityWideWindow(), popover?.anchor?.bounds != null)) {
        InfinitySheetPresentation.Popover -> {
            val spec = requireNotNull(popover)
            InfinityPopover(
                show = true,
                anchor = spec.anchor,
                onDismissRequest = onDismissRequest,
                side = spec.side,
                align = spec.align,
                width = spec.width,
                dismissible = properties.shouldDismissOnBackPress || properties.shouldDismissOnClickOutside,
                modifier = modifier,
            ) {
                when (height) {
                    InfinitySheetHeight.Full -> Column(Modifier.fillMaxWidth().weight(1f, fill = false), content = content)
                    InfinitySheetHeight.Content -> content()
                }
            }
            return
        }
        InfinitySheetPresentation.CenteredDialog -> {
            InfinityCenteredSheet(onDismissRequest, height, modifier, properties, containerColor, content)
            return
        }
        InfinitySheetPresentation.BottomSheet -> Unit
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        containerColor = containerColor,
        properties = properties,
        dragHandle = { InfinitySheetDragHandle() },
    ) {
        InfinityWindowBackScope {
            val handoff = if (contentDragsSheet) Modifier else Modifier.nestedScroll(NoSheetHandoff)
            when (height) {
                InfinitySheetHeight.Full -> Column(Modifier.fillMaxWidth().fillMaxHeight().then(handoff), content = content)
                InfinitySheetHeight.Content -> if (contentDragsSheet) content() else Column(Modifier.fillMaxWidth().then(handoff), content = content)
            }
        }
    }
}

/** 吞掉内容滚动剩下的纵向位移与惯性，使面板只响应从不滚动区域开始的拖动。 */
private object NoSheetHandoff : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

/** 宽窗口判定与会话检视器一致：宽不小于 600dp 且高不小于 480dp。 */
@Composable
fun rememberInfinityWideWindow(): Boolean {
    val configuration = LocalConfiguration.current
    return infinityInspectorAvailable(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
}

/** 居中的大尺寸对话框，宽窗口下承载原本的上滑面板内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfinityCenteredSheet(
    onDismissRequest: () -> Unit,
    height: InfinitySheetHeight,
    modifier: Modifier,
    properties: ModalBottomSheetProperties,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    val dismissOutside = properties.shouldDismissOnClickOutside
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.shouldDismissOnBackPress,
            dismissOnClickOutside = dismissOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        InfinityWindowBackScope {
            // 对话框窗口占满宽度；两侧留白点按同样视为点外部。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = dismissOutside,
                        onClick = onDismissRequest,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = containerColor,
                    modifier = modifier
                        .padding(horizontal = 32.dp)
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .then(if (height == InfinitySheetHeight.Full) Modifier.height(maxHeight) else Modifier.heightIn(max = maxHeight))
                        .testTag(InfinitySheetTags.CENTERED),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (height == InfinitySheetHeight.Full) Modifier.fillMaxHeight() else Modifier)
                            .padding(top = 20.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

object InfinitySheetTags {
    const val CENTERED = "infinity_centered_sheet"
}

@Composable
private fun InfinitySheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 16.dp)
            .width(40.dp)
            .height(4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinityBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    height: InfinitySheetHeight = INFINITY_BOTTOM_SHEET_DEFAULT_HEIGHT,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    scrollContent: Boolean = false,
    /**
     * 内容滚动到边界后是否接力拖动面板。内部自带滚动的列表、表单面板应关闭：
     * 内容滚动与面板拖动在边界交接时会来回争抢位移，表现为上下抖动；关闭后仍可从拖动条与标题区下滑关闭。
     */
    dragGesturesEnabled: Boolean = true,
    /** 宽窗口下贴着触发控件弹出；窄窗口仍是底部上滑面板。 */
    popover: InfinityPopoverSpec? = null,
    content: @Composable () -> Unit
) {
    val wide = rememberInfinityWideWindow()
    if (infinitySheetPresentation(wide, popover?.anchor?.bounds != null) == InfinitySheetPresentation.Popover) {
        val spec = requireNotNull(popover)
        InfinityPopover(
            show = show,
            anchor = spec.anchor,
            onDismissRequest = { if (dismissEnabled) onDismiss() },
            side = spec.side,
            align = spec.align,
            width = spec.width,
            title = title,
            dismissible = dismissEnabled,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (title != null) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                content()
            }
        }
        return
    }
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { currentDismissEnabled || it != SheetValue.Hidden },
    )
    // 调用方在选择完成等时机直接把 show 置为 false 时，先播放下滑退场再移除；
    // 拖动、返回与点外部关闭时面板已自行收起，这里的 hide 会立即返回。
    var composed by remember { mutableStateOf(show) }
    // 宽窗口是居中对话框，没有下滑退场，直接移除。
    LaunchedEffect(show) {
        if (show) {
            composed = true
        } else if (composed && wide) {
            composed = false
        } else if (composed) {
            try {
                sheetState.hide()
            } finally {
                composed = false
            }
        }
    }
    if (!show && !composed) return

    InfinityModalSheet(
        onDismissRequest = { if (currentDismissEnabled) onDismiss() },
        height = height,
        sheetState = sheetState,
        sheetGesturesEnabled = dismissEnabled,
        contentDragsSheet = dragGesturesEnabled,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = dismissEnabled,
            shouldDismissOnClickOutside = dismissEnabled,
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (height == InfinitySheetHeight.Full) Modifier.weight(1f) else Modifier)
                .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            content()
        }
    }
}

internal val INFINITY_BOTTOM_SHEET_DEFAULT_HEIGHT = InfinitySheetHeight.Content

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinityBottomSheetPreview() {
    InfinityTheme {
        InfinityBottomSheet(
            show = true,
            onDismiss = {},
            title = "Sheet Title"
        ) {
            Text(
                text = "Bottom sheet content goes here",
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
