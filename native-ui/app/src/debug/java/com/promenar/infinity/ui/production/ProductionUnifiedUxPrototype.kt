package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.chat.AgentConfigReason
import com.promenar.infinity.ui.chat.ApprovalTag
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.FrostedChromeBackdropLayer
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinityShapes
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/*
 * 视频创作插件与全站通知胶囊协同原型（v2，隔离于 debug 源码）：
 * 1. 移除页面流内非常驻通知槽，制作面板与普通页面彻底告别通知出现时的布局高度跳变；
 * 2. 新增全站统一「顶部悬浮通知胶囊」（ProtoInfinityNoticeCapsule），提供两种与熏黑毛玻璃 / 冷白磨砂同源的气质选型：
 *    - 方案 A（推荐 · 熏黑微光指示舱）：左核带克制径向/横向语义微光漫射 + 26dp 半透圆核图标 + 亮边与顶缘高光；
 *    - 方案 B（极简纯墨磨砂胶囊）：纯熏黑/冷白基底 + 1dp 亮边 + 18dp 语义色图标，去除了微光与圆底；
 * 3. 新增 840dp 展开内屏全景（左会话列 + 顶栏 + 底浮岛 + 右侧制作检视器 + 顶部居中悬浮胶囊）与 411dp 外屏整页叠加对照；
 * 4. 完整保留并复用 v1 已验收的顶栏呼吸留白、内嵌剧本阅读卡、一步到位「审批」表单弹窗与锁定项 Diff 会话审批卡。
 */

private enum class ProtoTabKey { OVERVIEW, SCRIPT, ASSETS, STORYBOARD, OUTPUT, CUT }

private enum class ProtoReviewDecision { APPROVE, REJECT, SUGGEST }

private enum class ProtoNoticeSeverity { SUCCESS, INFO, WARNING, ERROR }

private enum class ProtoNoticeVisualStyle { OBSIDIAN_GLOW, PURE_MONOCHROME }

private data class ProtoFieldDiff(
    val label: String,
    val oldValue: String?,
    val newValue: String?,
)

private enum class ProtoLockOutcome(val text: String, val tone: ProductionTone) {
    KEEP_LOCKED("批准后保持锁定", ProductionTone.SUCCESS),
    RETURN_TO_REVIEW("批准后退回待审", ProductionTone.WARNING),
    PERMANENT_DELETE("永久移除", ProductionTone.ERROR),
}

private data class ProtoLockedItemDiff(
    val kindAndId: String,
    val title: String,
    val outcome: ProtoLockOutcome,
    val summary: String? = null,
    val fields: List<ProtoFieldDiff> = emptyList(),
)

@Composable
private fun UnifiedProtoTheme(
    mode: InfinityThemeMode,
    surface: ComposerSurfaceStyle = ComposerSurfaceStyle.FROSTED,
    sendCount: Int = 0,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
            motion = InfinityMotionStyles(surface = surface),
        ),
    ) {
        CompositionLocalProvider(
            LocalProductionSend provides ProductionSendState(
                count = sendCount,
                sending = false,
                queued = false,
                onSend = {},
            ),
        ) {
            content()
        }
    }
}

/**
 * 全站统一「顶部悬浮通知胶囊」原型（已确定采纳方案 A：熏黑微光指示舱）：
 * - 与输入浮岛、悬浮检视器共享同一套熏黑毛玻璃 / 冷白磨砂 / 环境光实底令牌；
 * - 脱离文档流，锚定在顶部 Header 栏下沿 + `8dp` 呼吸间距（常规 `64dp` Header 下为 `72dp`，
 *   实机运行时结合活跃 Header 实测高度或默认 `64dp + 8dp` 动态避让，沉浸式无顶栏页面回退为状态栏下 `12dp`），
 *   外屏限宽 `380dp`，保证返回键、标题、`发送` 胶囊与更多菜单完全不被遮挡；
 * - 展开内屏（`≥ 600dp`）水平避让：右侧浮动/固定检视器开启且左侧可用宽度 `≥ 320dp` 时，
 *   按右侧检视器占位（`inspectorWidth + inspectorEnd`）自动通过 `endInset` 避让，在左侧对话区居中（限宽 `380dp`）；
 *   检视器收起或其它全宽页面时，恢复在主舞台顶部居中（限宽 `420dp`）；
 * - 保留方案 A / 方案 B 对照函数以记录设计选型，正式实现采用方案 A；
 * - 实机运行时搭配 BackdropSource（20dp 实时背景模糊）使用 `0.88f`/`0.90f` 熏黑/冷白透底；
 *   `@Preview` 静态检查模式无硬件背景模糊，改用模拟熏黑实底以防背后标题锐利字迹透过。
 */
@Composable
private fun ProtoInfinityNoticeCapsule(
    severity: ProtoNoticeSeverity,
    message: String,
    visualStyle: ProtoNoticeVisualStyle,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    showDismiss: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dark = domain.isDark
    val frosted = LocalInfinityMotionStyles.current.surface == ComposerSurfaceStyle.FROSTED
    val inspectionMode = LocalInspectionMode.current

    val accent = when (severity) {
        ProtoNoticeSeverity.SUCCESS -> domain.success
        ProtoNoticeSeverity.INFO -> scheme.primary
        ProtoNoticeSeverity.WARNING -> domain.warning
        ProtoNoticeSeverity.ERROR -> domain.ragError
    }
    val icon: ImageVector = when (severity) {
        ProtoNoticeSeverity.SUCCESS -> Icons.Rounded.CheckCircle
        ProtoNoticeSeverity.INFO -> Icons.Rounded.Info
        ProtoNoticeSeverity.WARNING -> Icons.Rounded.WarningAmber
        ProtoNoticeSeverity.ERROR -> Icons.Rounded.ErrorOutline
    }

    val containerColor = when {
        dark && frosted && inspectionMode -> Color(0xFF0A0A0E)
        dark && frosted -> InfinityObsidianGlassTint.copy(alpha = 0.88f)
        dark -> InfinityObsidianSolidContainer
        frosted && inspectionMode -> Color(0xFFFAFAFC)
        frosted -> InfinityPureWhite.copy(alpha = 0.90f)
        else -> scheme.surfaceContainerLowest
    }
    val borderColor = if (dark) {
        InfinityPureWhite.copy(alpha = if (frosted) 0.16f else 0.14f)
    } else {
        scheme.outlineVariant.copy(alpha = if (frosted) 0.58f else 0.62f)
    }
    val topHighlight = if (dark) {
        InfinityPureWhite.copy(alpha = 0.14f)
    } else {
        InfinityPureWhite.copy(alpha = 0.92f)
    }
    val shape = RoundedCornerShape(24.dp)

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 12.dp,
    ) {
        Box(
            Modifier
                .drawWithCache {
                    val hiPx = 18.dp.toPx().coerceAtMost(size.height)
                    val hiBrush = Brush.verticalGradient(
                        0f to topHighlight,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = hiPx,
                    )
                    val glowBrush = if (visualStyle == ProtoNoticeVisualStyle.OBSIDIAN_GLOW) {
                        val center = Offset(26.dp.toPx().coerceAtMost(size.width * 0.25f), size.height / 2f)
                        val radius = maxOf(size.height * 2.2f, 110.dp.toPx())
                        Brush.radialGradient(
                            0f to accent.copy(alpha = if (dark) 0.20f else 0.11f),
                            0.58f to accent.copy(alpha = if (dark) 0.06f else 0.03f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius,
                        )
                    } else null
                    onDrawBehind {
                        if (glowBrush != null) drawRect(brush = glowBrush)
                        drawRect(brush = hiBrush, size = Size(size.width, hiPx))
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .padding(
                        start = if (visualStyle == ProtoNoticeVisualStyle.OBSIDIAN_GLOW) 10.dp else 14.dp,
                        end = if (showDismiss) 4.dp else 12.dp,
                        top = 7.dp,
                        bottom = 7.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (visualStyle == ProtoNoticeVisualStyle.OBSIDIAN_GLOW) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = if (dark) 0.18f else 0.12f))
                            .border(0.5.dp, accent.copy(alpha = if (dark) 0.30f else 0.24f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = message.keepCjkTailTogether(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!actionLabel.isNullOrBlank()) {
                    // 胶囊内动作严格采用有明确填充与描边边界的紧凑胶囊按钮，不使用裸文字。
                    Surface(
                        onClick = {},
                        shape = CircleShape,
                        color = scheme.onSurface.copy(alpha = if (dark) 0.10f else 0.07f),
                        border = BorderStroke(0.5.dp, borderColor),
                        modifier = Modifier.heightIn(min = 30.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 11.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = actionLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (showDismiss) {
                    Surface(
                        onClick = {},
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭通知",
                                tint = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 模拟主会话窗口中的熏黑/冷白毛玻璃浮动检视器宿主，直观检验顶部圆角、高光与 16dp 基线。 */
@Composable
private fun ProtoInspectorBackdropHost(
    selectedTabLabel: String = "制作",
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = MaterialTheme.infinityDomainColors.isDark
    val chrome = rememberFrostedChromeMaterial(FrostedChromeRole.FLOATING_INSPECTOR)
    Box(
        modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF020203) else Color(0xFFEFF1F5)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.primary.copy(alpha = if (dark) 0.08f else 0.12f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.88f)
                    .height(110.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.tertiary.copy(alpha = if (dark) 0.05f else 0.09f)),
            )
        }
        ProtoFrostedInspectorSurface(
            chrome = chrome,
            selectedTabLabel = selectedTabLabel,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            content = content,
        )
    }
}

@Composable
private fun ProtoFrostedInspectorSurface(
    chrome: com.promenar.infinity.ui.common.FrostedChromeMaterial,
    selectedTabLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = chrome.frostedTint?.let { Color.Transparent } ?: chrome.container,
        border = chrome.border,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            FrostedChromeBackdropLayer(
                source = null,
                material = chrome,
                highlightHeight = 56.dp,
                modifier = Modifier.matchParentSize(),
            )
            CompositionLocalProvider(
                LocalFrostedChromeMaterial provides chrome,
                LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                    HorizontalDivider(color = chrome.edgeDivider.copy(alpha = 0.7f))
                    ProtoInspectorBottomBar(selectedTabLabel)
                }
            }
        }
    }
}

@Composable
private fun ProtoInspectorBottomBar(selectedLabel: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.onSurface.copy(alpha = 0.10f),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Movie, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                Text(selectedLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.KeyboardDoubleArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 需求 4 修正后的制作面板顶栏：
 * - 检视器根页（inInspector && onBack == null）：顶部补足 14dp 呼吸留白，左侧 16dp 与页签、卡片精确对齐；
 * - 检视器次级页（inInspector && onBack != null）：顶部补足 10dp 呼吸留白，避免返回键紧贴 20dp 圆角顶缘；
 * - 竖屏整页（!inInspector）：保留标准 56dp 栏高与紧凑边距。
 */
@Composable
private fun ProtoProductionTopBar(
    title: String,
    meta: List<String>,
    inInspector: Boolean,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    metaAccent: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val rowModifier = when {
        inInspector && onBack == null -> modifier
            .fillMaxWidth()
            .padding(top = 14.dp, start = 16.dp, end = 6.dp, bottom = 4.dp)
            .heightIn(min = 56.dp)
        inInspector && onBack != null -> modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 4.dp, end = 6.dp, bottom = 2.dp)
            .heightIn(min = 56.dp)
        else -> modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp)
            .heightIn(min = 56.dp)
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            InfinityBackButton(onClick = onBack)
            Spacer(Modifier.width(4.dp))
        }
        InfinityPaneHeaderTitle(
            title = title,
            modifier = Modifier.weight(1f),
            meta = if (meta.isEmpty() && metaAccent == null) null else ({
                Text(meta.joinToString(" $InfinityMetaSeparator "), maxLines = 1, softWrap = false) // wrap-check: allow 共享页头信息行固定单行横滑
                metaAccent?.let {
                    Text(
                        text = if (meta.isEmpty()) it else " $InfinityMetaSeparator $it",
                        color = MaterialTheme.infinityDomainColors.onWarningContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }),
        )
        LocalProductionSend.current?.let { send ->
            ProductionSendButton(
                state = send,
                modifier = Modifier.padding(start = InfinitySpacing.XSmall, end = 4.dp),
            )
        }
        actions()
    }
}

/** 需求 3 平滑结构化审批注释块：同时承载否决原因、批准附注与待发送建议。 */
@Composable
private fun ProtoReviewNotesBlock(
    rejectReason: String? = null,
    approveNote: String? = null,
    pendingSuggestion: String? = null,
    modifier: Modifier = Modifier,
) {
    if (rejectReason.isNullOrBlank() && approveNote.isNullOrBlank() && pendingSuggestion.isNullOrBlank()) return
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!rejectReason.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.errorContainer.copy(alpha = 0.24f),
                border = BorderStroke(0.5.dp, scheme.error.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "否决原因",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.error,
                    )
                    Text(rejectReason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                }
            }
        }
        if (!approveNote.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = domain.successContainer.copy(alpha = 0.28f),
                border = BorderStroke(0.5.dp, domain.success.copy(alpha = 0.30f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "批准附注",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = domain.success,
                    )
                    Text(approveNote, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                }
            }
        }
        if (!pendingSuggestion.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "待发送建议",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "未发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.78f),
                        )
                    }
                    Text(pendingSuggestion, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                }
            }
        }
    }
}

/** 需求 1 全站统一单入口审批按钮：无暂存结论时为主操作「审批」，已有结论时为中性「修改审批」。 */
@Composable
private fun ProtoSingleReviewEntryBar(
    hasSavedVerdict: Boolean,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        InfinityPillButton(
            text = if (hasSavedVerdict) "修改审批" else "审批",
            onClick = {},
            tone = if (hasSavedVerdict) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
            icon = Icons.Rounded.RateReview,
            compact = false,
        )
    }
}

/** 需求 1 剧本内嵌阅读卡片：标题与唯一状态徽章 + 文件路径与全屏查看 + 内嵌正文预览与展开全文 + 审批注释与单入口「审批」。 */
@Composable
private fun ProtoEmbeddedScriptCard(
    title: String,
    path: String,
    chip: ProductionChipSpec,
    rejectReason: String? = null,
    approveNote: String? = null,
    hasSavedVerdict: Boolean = true,
    compactExcerpt: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val chrome = LocalFrostedChromeMaterial.current
    val readingBg = if (chrome != null) {
        scheme.onSurface.copy(alpha = if (MaterialTheme.infinityDomainColors.isDark) 0.05f else 0.035f)
    } else {
        scheme.surfaceContainer
    }
    BettboxListGroup(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(chip.text, chip.tone)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InfinityPillButton(
                    text = "全屏查看",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.OpenInFull,
                    compact = true,
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = readingBg,
                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.40f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "第 1 场 · 日外 · 承天市城东新老街 · 包子铺门前",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                            )
                            Text(
                                "2099 年清晨，薄雾未散。老字号包子铺刚掀开蒸笼，白气漫过青石台阶。特勤组长陆昭身着哑光黑风衣走过街角，目光落在柜台旁的温度传感器上。",
                                style = MaterialTheme.typography.bodySmall.forReading(),
                                color = scheme.onSurface.copy(alpha = 0.90f),
                                maxLines = if (compactExcerpt) 3 else 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!compactExcerpt) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = scheme.surfaceContainerHigh.copy(alpha = 0.65f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            "包子铺掌柜（朝蒸笼一让，笑）",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = scheme.primary,
                                        )
                                        Text(
                                            "“陆组长早啊！刚出锅的鲜肉包，要不要顺手拿两个垫垫肚子？”",
                                            style = MaterialTheme.typography.bodySmall.forReading(),
                                            color = scheme.onSurface,
                                        )
                                    }
                                }
                                Text(
                                    "陆昭停步半息，抬起左腕示意不收，视线却在蒸笼旁一枚旧徽记上多停了一拍。",
                                    style = MaterialTheme.typography.bodySmall.forReading(),
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val fadeTarget = if (chrome != null) {
                            if (MaterialTheme.infinityDomainColors.isDark) Color(0xFF14151A) else scheme.surfaceContainerLow
                        } else {
                            scheme.surfaceContainerLow
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(30.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, fadeTarget.copy(alpha = 0.78f)),
                                    ),
                                ),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共 428 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(
                            text = "展开全文",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.UnfoldMore,
                            compact = true,
                        )
                    }
                }
            }
            ProtoReviewNotesBlock(rejectReason = rejectReason, approveNote = approveNote)
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            ProtoSingleReviewEntryBar(
                hasSavedVerdict = hasSavedVerdict,
                hint = if (hasSavedVerdict) "已暂存本轮意见，可随时调整" else "尚未提交审批结论",
            )
        }
    }
}

/** 需求 1 统一一步到位审批表单弹窗原型，沿用全站 InfinityConfirmDialogCard 表面规格。 */
@Composable
private fun ProtoUnifiedReviewDialogCard(
    dialogTitle: String,
    statusChip: ProductionChipSpec,
    targetSegments: List<String>,
    selectedDecision: ProtoReviewDecision,
    noteLabel: String,
    noteText: String,
    notePlaceholder: String,
    retractLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(InfinityShapes.extraLarge)
            .border(0.5.dp, scheme.outlineVariant, InfinityShapes.extraLarge),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = scheme.onSurface,
        shape = InfinityShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = dialogTitle,
                    style = InfinityTypography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(statusChip.text, statusChip.tone)
            }
            Spacer(Modifier.height(6.dp))
            InfinityMetaLine(
                segments = targetSegments,
                style = InfinityTypography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment(
                        value = ProtoReviewDecision.APPROVE,
                        label = "批准",
                        leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(domain.success)) },
                    ),
                    InfinitySegment(
                        value = ProtoReviewDecision.REJECT,
                        label = "否决",
                        leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.error)) },
                    ),
                    InfinitySegment(
                        value = ProtoReviewDecision.SUGGEST,
                        label = "建议",
                        leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary)) },
                    ),
                ),
                selected = selectedDecision,
                onSelect = {},
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = noteText,
                onValueChange = {},
                label = { Text(noteLabel) },
                placeholder = { Text(notePlaceholder) },
                minLines = 3,
                textStyle = LocalTextStyle.current.forInput(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (retractLabel != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {},
                    shape = CircleShape,
                    border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(retractLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(18.dp))
            ProtoEqualButtonPair(
                cancelText = "取消",
                confirmText = "保存",
                confirmDestructive = false,
            )
        }
    }
}

/** 与 InfinityConfirmDialog 对齐的等宽双胶囊按钮组，大字号或窄宽度自动转为竖排。 */
@Composable
private fun ProtoEqualButtonPair(
    cancelText: String,
    confirmText: String,
    confirmDestructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val style = InfinityTypography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val gap = 10.dp
    val hPadding = 16.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val half = (maxWidth - gap) / 2 - hPadding * 2
        val widest = with(LocalDensity.current) {
            listOf(cancelText, confirmText).maxOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp()
        }
        val stacked = widest > half
        val confirm: @Composable (Modifier) -> Unit = { slot ->
            Button(
                onClick = {},
                modifier = slot.height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (confirmDestructive) scheme.error else scheme.primary,
                    contentColor = if (confirmDestructive) scheme.onError else scheme.onPrimary,
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = hPadding),
            ) {
                Text(confirmText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val cancel: @Composable (Modifier) -> Unit = { slot ->
            Button(
                onClick = {},
                modifier = slot.height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.onSurface.copy(alpha = 0.08f),
                    contentColor = scheme.onSurface,
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = hPadding),
            ) {
                Text(cancelText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                confirm(Modifier.fillMaxWidth())
                cancel(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                cancel(Modifier.weight(1f))
                confirm(Modifier.weight(1f))
            }
        }
    }
}

/** 需求 1 通用单入口审批卡（用于角色造型/版本、镜头候选、粗剪版本），已移除角色页冗余蓝框。 */
@Composable
private fun ProtoUnifiedReviewCard(
    title: String,
    chip: ProductionChipSpec,
    subtitle: String? = null,
    rejectReason: String? = null,
    approveNote: String? = null,
    pendingSuggestion: String? = null,
    hasSavedVerdict: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    BettboxListGroup {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(chip.text, chip.tone)
            }
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            ProtoReviewNotesBlock(rejectReason = rejectReason, approveNote = approveNote, pendingSuggestion = pendingSuggestion)
            ProtoSingleReviewEntryBar(hasSavedVerdict = hasSavedVerdict)
        }
    }
}

/** 需求 1 统一后的分镜场头：右侧操作统一为「审批 / 修改审批」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtoUnifiedSceneHeader(
    sceneNumber: Int,
    sceneCode: String,
    heading: String,
    meta: String,
    chip: ProductionChipSpec,
    hasSavedVerdict: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    BettboxListGroup {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = scheme.primaryContainer) {
                        Text(
                            text = "第 $sceneNumber 场 $InfinityMetaSeparator $sceneCode", // wrap-check: allow 场号胶囊固定单行
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    ProductionStatusChip(chip.text, chip.tone)
                }
                InfinityPillButton(
                    text = if (hasSavedVerdict) "修改审批" else "审批",
                    onClick = {},
                    tone = if (hasSavedVerdict) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.RateReview,
                    compact = true,
                )
            }
            Text(
                heading,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(meta, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 需求 2 Agent 修改已锁定制作项会话审批卡：沿用 ApprovalCard 容器与 ComfyTemplateApprovalBody 语法。 */
@Composable
private fun ProtoProductionLockApprovalCard(
    projectName: String,
    reason: String,
    lockedItems: List<ProtoLockedItemDiff>,
    unlockedSummary: String? = null,
    showsVisualRevertWarning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainerHigh)
                    .heightIn(min = 40.dp)
                    .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Movie, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "制作项目变更",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = " $InfinityMetaSeparator $projectName",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ApprovalTag("已锁定项")
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "需要批准：修改已锁定制作项",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "production_update",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AgentConfigReason(reason)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        lockedItems.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                            LockedItemDiffBlock(item)
                        }
                    }
                }
                if (!unlockedSummary.isNullOrBlank()) {
                    Text(
                        text = unlockedSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (showsVisualRevertWarning) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = domain.warningContainer.copy(alpha = 0.35f),
                        border = BorderStroke(0.5.dp, domain.warning.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = null, tint = domain.warning, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                            Text(
                                text = "涉及视觉参考图或剧本文件，批准写入后将自动转为「待审」，方便你在制作面板复看。",
                                style = MaterialTheme.typography.bodySmall.forReading(),
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
                ProtoEqualButtonPair(cancelText = "拒绝", confirmText = "允许修改", confirmDestructive = false)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LockedItemDiffBlock(item: ProtoLockedItemDiff) {
    val scheme = MaterialTheme.colorScheme
    val isDelete = item.outcome == ProtoLockOutcome.PERMANENT_DELETE
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = if (isDelete) Icons.Rounded.DeleteOutline else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (isDelete) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = item.kindAndId,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
            }
            ProductionStatusChip(item.outcome.text, item.outcome.tone)
        }
        if (item.title.isNotBlank()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        item.fields.forEach { field ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.28f),
                )
                Text(
                    text = fieldChangeAnnotated(field.oldValue, field.newValue),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    modifier = Modifier.weight(0.72f),
                )
            }
        }
        item.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = if (isDelete) scheme.error else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun fieldChangeAnnotated(oldValue: String?, newValue: String?): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    return buildAnnotatedString {
        oldValue?.let { old ->
            withStyle(SpanStyle(color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)) { append(old) }
            if (newValue != null) append("  →  ")
        }
        newValue?.let { new ->
            withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) { append(new) }
        }
    }
}

@Composable
private fun ProtoProductionTabsRow(selected: ProtoTabKey) {
    val scheme = MaterialTheme.colorScheme
    InfinityPanelTabs(
        tabs = listOf(
            InfinityPanelTab(ProtoTabKey.OVERVIEW, "总览"),
            InfinityPanelTab(ProtoTabKey.SCRIPT, "剧本"),
            InfinityPanelTab(ProtoTabKey.ASSETS, "资产", count = "2"),
            InfinityPanelTab(ProtoTabKey.STORYBOARD, "分镜", dot = scheme.primary),
            InfinityPanelTab(ProtoTabKey.OUTPUT, "出片", count = "1"),
            InfinityPanelTab(ProtoTabKey.CUT, "剪辑"),
        ),
        selected = selected,
        onSelect = {},
        contentInset = InfinitySpacing.Large,
        modifier = Modifier.padding(bottom = InfinitySpacing.XSmall),
    )
}

@Composable
private fun ProtoSceneRow(number: Int, heading: String, subtitle: String, chip: ProductionChipSpec) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = {})
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(heading, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ProductionStatusChip(chip.text, chip.tone)
    }
}

@Composable
private fun ProtoPrototypeCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ---------- v2 验收预览矩阵（共 8 组） ----------

@Preview(name = "1. 检视器顶栏呼吸留白+纯净剧本页(深色)", widthDp = 380, heightDp = 1040, locale = "zh-rCN")
@Composable
fun proto1InspectorHeaderNoticeScriptDarkPreview() = UnifiedProtoTheme(InfinityThemeMode.DARK, sendCount = 1) {
    ProtoInspectorBackdropHost {
        ProtoProductionTopBar(
            title = "陆昭晨间问候 20s 测试片",
            meta = listOf("电影", "4 个镜头", "16:9"),
            inInspector = true,
            onBack = null,
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        ProtoProductionTabsRow(selected = ProtoTabKey.SCRIPT)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            ProtoEmbeddedScriptCard(
                title = "第一集 · 晨间巡街",
                path = "development/ep01/script.md",
                chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                rejectReason = "台词设计太简短了，缺少角色口吻，整体略显赶工。",
                hasSavedVerdict = true,
            )
            ProductionSection("场次") {
                ProtoSceneRow(
                    number = 1,
                    heading = "日外 · 承天市城东新老街 · 包子铺门前",
                    subtitle = "4 镜 · 20.5 秒",
                    chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                )
                HorizontalDivider(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ProtoSceneRow(
                    number = 2,
                    heading = "日内 · 包子铺柜台前",
                    subtitle = "2 镜 · 8.0 秒",
                    chip = ProductionChipSpec("已批准", ProductionTone.SUCCESS),
                )
            }
        }
    }
}

@Preview(name = "2. 统一单入口一步审批弹窗双态对比(深色)", widthDp = 390, heightDp = 1260, locale = "zh-rCN")
@Composable
fun proto2UnifiedApprovalDialogStatesDarkPreview() = UnifiedProtoTheme(InfinityThemeMode.DARK, sendCount = 1) {
    Surface(color = Color(0xFF0A0A0D), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ProtoPrototypeCaption("状态 A · 首次审批（默认预选「批准」，支持附注一步通过）")
            ProtoUnifiedReviewDialogCard(
                dialogTitle = "审批",
                statusChip = ProductionChipSpec("待审", ProductionTone.WARNING),
                targetSegments = listOf("陆昭 · 玄鳞飞鱼服 V3", "正面造型图"),
                selectedDecision = ProtoReviewDecision.APPROVE,
                noteLabel = "审批附注（选填）",
                noteText = "",
                notePlaceholder = "可补充通过备注，如材质细节或微调建议…",
                retractLabel = null,
            )
            ProtoPrototypeCaption("状态 B · 修改已暂存否决（原地改判或撤回当前结论）")
            ProtoUnifiedReviewDialogCard(
                dialogTitle = "修改审批",
                statusChip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                targetSegments = listOf("第一集 · 晨间巡街", "development/ep01/script.md"),
                selectedDecision = ProtoReviewDecision.REJECT,
                noteLabel = "否决原因（选填）",
                noteText = "台词设计太简短了，缺少角色口吻，需要重写开场铺垫。",
                notePlaceholder = "说明需要重做或调整的问题…",
                retractLabel = "撤回当前否决",
            )
        }
    }
}

@Preview(name = "3. 二级子页顶栏留白与全页签单入口审批统一(深色)", widthDp = 380, heightDp = 1220, locale = "zh-rCN")
@Composable
fun proto3AllTabsUnifiedApprovalDarkPreview() = UnifiedProtoTheme(InfinityThemeMode.DARK, sendCount = 2) {
    ProtoInspectorBackdropHost(selectedTabLabel = "制作") {
        ProtoProductionTopBar(
            title = "陆昭 · 新神州玄鳞飞鱼服 V3",
            meta = listOf("角色", "4 个镜头引用"),
            inInspector = true,
            onBack = {},
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProtoPrototypeCaption("角色造型页 · 唯一徽章 + 批准附注 + 移除冗余蓝条")
            BettboxListGroup {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("版本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text("v1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("v2", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                        ProductionStatusChip("已批准 · 未发送", ProductionTone.SUCCESS)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().height(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Text("正面造型图 · look-v2-front.png", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ProtoReviewNotesBlock(approveNote = "护肩金纹与哑光黑风衣质感达标，正面造型锁版入参。")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ProtoSingleReviewEntryBar(hasSavedVerdict = true)
                }
            }

            ProtoPrototypeCaption("分镜场头 · 单入口审批")
            ProtoUnifiedSceneHeader(
                sceneNumber = 1,
                sceneCode = "SC01",
                heading = "日外 · 承天市城东新老街 · 包子铺门前",
                meta = "4 镜 · 20.5 秒",
                chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                hasSavedVerdict = false,
            )

            ProtoPrototypeCaption("镜头候选 1 · 单入口审批")
            ProtoUnifiedReviewCard(
                title = "候选 1 · 审核",
                chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                rejectReason = "第 2 秒处手腕与竹屉轻微穿模，建议固定右手持杯动作。",
                hasSavedVerdict = true,
            )

            ProtoPrototypeCaption("剪辑粗剪卡 · 单入口审批")
            ProtoUnifiedReviewCard(
                title = "粗剪 v1 · 审核",
                chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                subtitle = "总长 20.5 秒 · 4 段无缝衔接",
                hasSavedVerdict = false,
            )
        }
    }
}

@Preview(name = "4. Agent 修改已锁定制作项会话审批卡(深色)", widthDp = 411, heightDp = 1140, locale = "zh-rCN")
@Composable
fun proto4LockedAssetApprovalCardDarkPreview() = UnifiedProtoTheme(InfinityThemeMode.DARK) {
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ProtoPrototypeCaption("卡片 A · 改名保持锁定 + 永久移除已锁定道具 + 同批未锁定摘要")
            ProtoProductionLockApprovalCard(
                projectName = "陆昭晨间问候 20s",
                reason = "按要求将剧情从糕点铺统一改为包子铺，需要同步修改已锁定场景名称并移除废弃的纸包道具。",
                lockedItems = listOf(
                    ProtoLockedItemDiff(
                        kindAndId = "场景 · asset-sc-03b",
                        title = "日外街道主要背景建筑",
                        outcome = ProtoLockOutcome.KEEP_LOCKED,
                        fields = listOf(
                            ProtoFieldDiff("名称", "糕点铺门前 · 晨", "包子铺门前 · 晨"),
                            ProtoFieldDiff("Canon 描述", "门前木牌写“承天糕点”", "门前木牌写“承天包子铺”"),
                        ),
                    ),
                    ProtoLockedItemDiff(
                        kindAndId = "道具 · asset-prop-02",
                        title = "油纸点心包",
                        outcome = ProtoLockOutcome.PERMANENT_DELETE,
                        summary = "永久删除已锁定道具（已确认无锁定镜头引用）。",
                    ),
                ),
                unlockedSummary = "另包含 2 项未锁定更新：更新 SC01 场头、更新 S01 镜头画面描述",
                showsVisualRevertWarning = false,
            )

            ProtoPrototypeCaption("卡片 B · 替换已锁定造型参考图（批准写入后退回待审）")
            ProtoProductionLockApprovalCard(
                projectName = "陆昭晨间问候 20s",
                reason = "为贴合包子铺晨雾光线，替换已锁定造型的正面参考图并补充侧逆光材质说明。",
                lockedItems = listOf(
                    ProtoLockedItemDiff(
                        kindAndId = "角色造型 · 陆昭 / 玄鳞飞鱼服",
                        title = "正面全身标准参考卡",
                        outcome = ProtoLockOutcome.RETURN_TO_REVIEW,
                        fields = listOf(
                            ProtoFieldDiff("正面参考", "assets/lz-front-v2.png", "assets/lz-front-v3.png"),
                            ProtoFieldDiff("造型说明", "玄铁扣微反光", "清晨暖雾侧逆光细节"),
                        ),
                    ),
                ),
                showsVisualRevertWarning = true,
            )
        }
    }
}

@Preview(name = "5. 全站悬浮通知胶囊视觉气质选型(方案A vs 方案B · 深/浅对比)", widthDp = 420, heightDp = 1220, locale = "zh-rCN")
@Composable
fun proto5GlobalNoticeStylesComparePreview() {
    Column(Modifier.fillMaxSize()) {
        UnifiedProtoTheme(InfinityThemeMode.DARK, ComposerSurfaceStyle.FROSTED) {
            Surface(color = Color(0xFF040406), modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProtoPrototypeCaption("方案 A（推荐 · 熏黑微光指示舱 · 四级语义 + 动作胶囊）")
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.SUCCESS,
                        message = "第 2 轮审核意见（1 条）已发送给智能体",
                        visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                    )
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.INFO,
                        message = "已将造型审批附注暂存到本轮待发送列表",
                        visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                        actionLabel = "撤回",
                    )
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.WARNING,
                        message = "还有 1 个镜头未分配生成通道，发送后将提示模型",
                        visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                        actionLabel = "查看",
                    )
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.ERROR,
                        message = "发送审核失败：与当前服务端修订版本冲突，请刷新后重试",
                        subtitle = "项目修订号已更新至 r42",
                        visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProtoPrototypeCaption("方案 A · 环境光省电实底（AMBIENT 降级对照）")
                    UnifiedProtoTheme(InfinityThemeMode.DARK, ComposerSurfaceStyle.AMBIENT) {
                        ProtoInfinityNoticeCapsule(
                            severity = ProtoNoticeSeverity.SUCCESS,
                            message = "已保存到本轮待发送清单",
                            visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                            actionLabel = "撤销",
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    ProtoPrototypeCaption("方案 B · 极简纯墨磨砂胶囊（无微光无圆底）")
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.SUCCESS,
                        message = "第 2 轮审核意见（1 条）已发送给智能体",
                        visualStyle = ProtoNoticeVisualStyle.PURE_MONOCHROME,
                    )
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.ERROR,
                        message = "发送审核失败：当前有任务正在等待工具审批",
                        visualStyle = ProtoNoticeVisualStyle.PURE_MONOCHROME,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            UnifiedProtoTheme(InfinityThemeMode.LIGHT, ComposerSurfaceStyle.FROSTED) {
                Surface(color = Color(0xFFEFF1F5), modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProtoPrototypeCaption("浅色冷白磨砂 · 方案 A（左微光）vs 方案 B（纯静谧）")
                        ProtoInfinityNoticeCapsule(
                            severity = ProtoNoticeSeverity.SUCCESS,
                            message = "第 2 轮审核意见（1 条）已发送给智能体",
                            visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                        )
                        ProtoInfinityNoticeCapsule(
                            severity = ProtoNoticeSeverity.WARNING,
                            message = "还有 1 个镜头未分配生成通道",
                            visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                            actionLabel = "去处理",
                        )
                        ProtoInfinityNoticeCapsule(
                            severity = ProtoNoticeSeverity.INFO,
                            message = "已暂存审批意见，点击发送立即提交（方案 B）",
                            visualStyle = ProtoNoticeVisualStyle.PURE_MONOCHROME,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "6. 840dp展开内屏全景 · 全站顶部悬浮胶囊(方案A)", widthDp = 840, heightDp = 880, locale = "zh-rCN")
@Composable
fun proto6InnerScreenNoticeContextDarkPreview() = UnifiedProtoTheme(InfinityThemeMode.DARK, sendCount = 1) {
    val scheme = MaterialTheme.colorScheme
    val inspectorChrome = rememberFrostedChromeMaterial(FrostedChromeRole.FLOATING_INSPECTOR)
    val topBarChrome = rememberFrostedChromeMaterial(FrostedChromeRole.TOP_BAR)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            // 左侧 40dp 极简会话条
            Column(
                Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF0A0A0C))
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(shape = RoundedCornerShape(10.dp), color = scheme.onSurface.copy(alpha = 0.08f), modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ViewSidebar, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primary))
                Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.outlineVariant))
                Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.outlineVariant))
            }
            // 右侧主工作舞台（对话列表 + 顶栏 + 底部输入浮岛 + 右侧浮动检视器）
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    // 主对话顶栏（位于检视器上方，与 ChatTopBar 带状态行时 expandedHeight = 64.dp 对齐）
                    Surface(
                        color = topBarChrome.frostedTint ?: topBarChrome.container,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            InfinityBackButton(onClick = {})
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "陆昭晨间问候 20s 测试片：素材盘点与制作方案",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                InfinityMetaLine(
                                    segments = listOf("Linux", "Personal-Archive", "main"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.PushPin, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    // 对话正文列与右侧浮动检视器共享区
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier
                                .widthIn(max = 420.dp)
                                .fillMaxHeight()
                                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = scheme.surfaceContainerHigh,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(
                                    "把四镜的包子铺场景定稿，把这一轮审核意见发给面板执行。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = scheme.surfaceContainerLow,
                                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Rounded.Terminal, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                                    Text("production_update · 已读取最新审稿状态", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                "已收到第 2 轮审核意见，我会基于修改建议重新调整第一场台词密度，并保持其余分镜结构不变。",
                                style = MaterialTheme.typography.bodyMedium.forReading(),
                                color = scheme.onSurface,
                            )
                        }
                        // 右侧 360dp 熏黑毛玻璃制作检视器（紧邻顶栏下方、浮岛上方）
                        ProtoFrostedInspectorSurface(
                            chrome = inspectorChrome,
                            selectedTabLabel = "制作",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 12.dp, bottom = 92.dp)
                                .width(360.dp)
                                .fillMaxHeight(),
                        ) {
                            ProtoProductionTopBar(
                                title = "陆昭晨间问候 20s 测试片",
                                meta = listOf("电影", "4 个镜头", "16:9"),
                                inInspector = true,
                                onBack = null,
                                actions = {
                                    IconButton(onClick = {}) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = scheme.onSurfaceVariant)
                                    }
                                },
                            )
                            ProtoProductionTabsRow(selected = ProtoTabKey.SCRIPT)
                            Column(Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
                                ProtoEmbeddedScriptCard(
                                    title = "第一集 · 晨间巡街",
                                    path = "development/ep01/script.md",
                                    chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                                    rejectReason = "台词设计太简短了，缺少角色口吻。",
                                    hasSavedVerdict = true,
                                    compactExcerpt = true,
                                )
                            }
                        }
                        // 底部熏黑输入浮岛
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = InfinityObsidianGlassTint.copy(alpha = 0.88f),
                            border = BorderStroke(1.dp, InfinityPureWhite.copy(alpha = 0.14f)),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .height(64.dp),
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(shape = CircleShape, color = scheme.onSurface.copy(alpha = 0.08f), modifier = Modifier.size(34.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(
                                    "给 Infinity 发消息",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(34.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                // 右侧浮动检视器开启时（360dp + 12dp 右边距 + 12dp 安全间距 = 384dp），自动避让并在左侧对话区居中
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 72.dp, start = 16.dp, end = 384.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ProtoInfinityNoticeCapsule(
                        severity = ProtoNoticeSeverity.SUCCESS,
                        message = "第 2 轮审核意见（1 条）已发送给智能体",
                        visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                        modifier = Modifier.widthIn(min = 220.dp, max = 380.dp),
                    )
                }
            }
        }
    }
}

@Preview(name = "7. 411dp手机外屏整页 · 顶部悬浮通知胶囊叠加(深色)", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun proto7PortraitNoticeOverlayDarkPreview() = UnifiedProtoTheme(InfinityThemeMode.DARK, sendCount = 1) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ProtoProductionTopBar(
                    title = "陆昭晨间问候 20s 测试片",
                    meta = listOf("电影", "4 个镜头", "16:9"),
                    inInspector = false,
                    onBack = {},
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
                ProtoProductionTabsRow(selected = ProtoTabKey.SCRIPT)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    ProtoEmbeddedScriptCard(
                        title = "第一集 · 晨间巡街",
                        path = "AI-Assets/Productions/lu-zhao-morning-greeting-20s/script.md",
                        chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                        hasSavedVerdict = false,
                    )
                    ProductionSection("场次") {
                        ProtoSceneRow(
                            number = 1,
                            heading = "日外 · 承天市城东新老街 · 包子铺门前",
                            subtitle = "4 镜 · 20.5 秒",
                            chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                        )
                        HorizontalDivider(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ProtoSceneRow(
                            number = 2,
                            heading = "日内 · 灵调科外勤巡车内",
                            subtitle = "2 镜 · 6.5 秒",
                            chip = ProductionChipSpec("已批准", ProductionTone.SUCCESS),
                        )
                    }
                }
            }
            ProtoInfinityNoticeCapsule(
                severity = ProtoNoticeSeverity.WARNING,
                message = "还有 1 个镜头未分配通道，发送后将提示模型",
                visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                actionLabel = "查看",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = 70.dp)
                    .widthIn(min = 180.dp, max = 380.dp),
            )
        }
    }
}

@Preview(name = "8. 浅色主题+360dp窄屏+1.3x大字号极限适配(悬浮胶囊)", widthDp = 360, heightDp = 2080, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun proto8LightLargeFontAdaptPreview() = UnifiedProtoTheme(InfinityThemeMode.LIGHT, sendCount = 1) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        ProtoProductionTopBar(
                            title = "陆昭晨间问候 20s 测试片",
                            meta = listOf("电影", "4 个镜头", "16:9"),
                            inInspector = true,
                            onBack = null,
                            actions = {
                                IconButton(onClick = {}) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                        ProtoProductionTabsRow(selected = ProtoTabKey.SCRIPT)
                        Column(Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
                            ProtoEmbeddedScriptCard(
                                title = "第一集 · 晨间巡街",
                                path = "development/ep01/script.md",
                                chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                                rejectReason = "台词设计太简短，缺少生活口吻。",
                                hasSavedVerdict = true,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                ProtoUnifiedReviewDialogCard(
                    dialogTitle = "修改审批",
                    statusChip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                    targetSegments = listOf("第一集 · 晨间巡街"),
                    selectedDecision = ProtoReviewDecision.REJECT,
                    noteLabel = "否决原因（选填）",
                    noteText = "台词偏短，缺少角色感。",
                    notePlaceholder = "输入修改意见…",
                    retractLabel = "撤回当前否决",
                )

                ProtoProductionLockApprovalCard(
                    projectName = "陆昭晨间问候 20s",
                    reason = "替换已锁定造型的正面参考图以匹配包子铺晨雾光线。",
                    lockedItems = listOf(
                        ProtoLockedItemDiff(
                            kindAndId = "角色造型 · 陆昭 / 玄鳞飞鱼服",
                            title = "正面全身标准参考卡",
                            outcome = ProtoLockOutcome.RETURN_TO_REVIEW,
                            fields = listOf(
                                ProtoFieldDiff("正面参考", "lz-v2.png", "lz-v3.png"),
                            ),
                        ),
                    ),
                    showsVisualRevertWarning = true,
                )
            }
            ProtoInfinityNoticeCapsule(
                severity = ProtoNoticeSeverity.SUCCESS,
                message = "第 2 轮审核意见已发送",
                visualStyle = ProtoNoticeVisualStyle.OBSIDIAN_GLOW,
                actionLabel = "撤销",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = 98.dp)
                    .widthIn(min = 180.dp, max = 380.dp),
            )
        }
    }
}
