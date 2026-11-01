package com.promenar.infinity.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import com.promenar.infinity.ui.testing.UiTags
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.theme.InfinityTypography
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.rememberStreamAnimationsEnabled

/** 相邻步骤依靠行高分隔，正文边界由消息容器添加间距。 */
internal val ChatStepSpacing = 0.dp

/** 渐隐只作用于推理预览，不叠加不透明遮罩，适配浅色与深色主题。 */
@Composable
internal fun Modifier.fadingReasoningTail(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val mask = MaterialTheme.colorScheme.onSurface.copy(alpha = 1f)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val edge = (24.dp.toPx() / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.horizontalGradient(0f to mask.copy(alpha = 0f), edge to mask, 1f to mask),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** 微光扫过一次的基准时长；[speed] 为倍率时按它换算。 */
private const val StepShimmerBaseMillis = 1200f

@Composable
private fun Modifier.stepShimmer(active: Boolean, speed: (() -> Float)? = null): Modifier {
    if (!active || rememberStreamAnimationsEnabled() != true) return this
    if (speed != null) {
        // 速度随调用方给的倍率连续变化：逐帧累加相位，改变倍率时不会重启动画而跳变。
        var phase by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { nanos ->
                    if (last != 0L) phase = (phase + (nanos - last) / 1_000_000f * speed() / StepShimmerBaseMillis) % 1f
                    last = nanos
                }
            }
        }
        val highlight = MaterialTheme.colorScheme.onSurface
        return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val band = 48.dp.toPx()
            val center = -band + (size.width + band * 2) * phase
            drawRect(
                brush = Brush.linearGradient(
                    listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(center - band, 0f), end = Offset(center + band, 0f),
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "stepShimmer")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "stepHighlight")
    val highlight = MaterialTheme.colorScheme.onSurface
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val band = 48.dp.toPx()
        val center = -band + (size.width + band * 2) * phase.value
        drawRect(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(center - band, 0f), end = Offset(center + band, 0f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/** 步骤行可见高度。整行可点，触控范围由 Compose 最小触控尺寸扩展到 48dp。 */
internal val CompactStepRowHeight = 36.dp

/**
 * 正文旁的轻量步骤行：放大字号时按文字行高增高。
 * [expanded] 为 null 表示点击打开面板（如检索结果），不显示箭头；收起时也不显示，
 * 只有展开后才淡入向下箭头，提示可再次点击收起。
 */
@Composable
internal fun CompactStepRow(
    /** 摘要段；可传带样式的 [AnnotatedString]（如文件改动的红绿行数）。 */
    segments: List<CharSequence>,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    active: Boolean = false,
    progress: Float? = null,
    expanded: Boolean? = null,
    state: String = "",
    clickLabel: String? = null,
    tag: String? = null,
    tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    preview: (@Composable RowScope.() -> Unit)? = null,
    /** 非 null 时摘要微光按此倍率变速（1 为基准 1.2 秒一次）；用于思考标题随速率加快。 */
    shimmerSpeed: (() -> Float)? = null,
    failureText: String? = null,
    /** 摘要前的状态图标，例如检索进行中的放大镜；默认不显示图标。 */
    leading: (@Composable RowScope.() -> Unit)? = null,
    /** 紧跟摘要之后、不随摘要截断的附加内容，例如进行中步骤的计时。 */
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val style = InfinityTypography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
    // 整步失败时（tone 为 error）整行用错误色；部分失败只把失败段标红，摘要保持中性。
    val textColor = if (tone == MaterialTheme.colorScheme.error) {
        MaterialTheme.colorScheme.error
    } else tone.copy(alpha = 0.72f)
    val visibleSegments = segments.filter { it.isNotBlank() }
    val description = (visibleSegments.map(CharSequence::toString) + listOfNotNull(failureText?.takeIf(String::isNotBlank)))
        .joinToString(stringResource(R.string.chat_step_summary_separator))
    val haptics = LocalInfinityHaptics.current
    // 步骤行没有容器形状，水波纹会画成溢出文字的灰色矩形；按下改为整行轻微变淡，配合触感反馈。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(if (pressed) 0.5f else 1f, tween(if (pressed) 60 else 160), label = "stepPress")
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CompactStepRowHeight)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = clickLabel) {
                if (expanded != null) haptics.toggle(!expanded) else haptics.perform(InfinityHapticEvent.SELECTION)
                onClick()
            }
            .graphicsLayer { alpha = pressAlpha }
            .semantics(mergeDescendants = true) {
                if (tag != null) testTag = tag
                contentDescription = description
                stateDescription = state
                if (active) {
                    progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                }
            },
    ) {
        val summaryMaxWidth = (maxWidth - 58.dp).coerceAtLeast(0.dp)
        val arrowVisible = expanded == true
        Row(Modifier.fillMaxWidth().align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke(this)
            val summaryText = buildAnnotatedString {
                visibleSegments.forEachIndexed { index, segment ->
                    if (index > 0) append(" $InfinityMetaSeparator ")
                    if (segment is AnnotatedString) append(segment) else append(segment.toString())
                }
            }
            if (preview == null) {
                // 摘要按内容宽度排布并先截断，失败段紧跟其后且不收缩，展开箭头固定在行尾。
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summaryText,
                        style = style,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).stepShimmer(active, shimmerSpeed).testTag("CHAT_STEP_SUMMARY"),
                    )
                    trailing?.invoke(this)
                    if (!failureText.isNullOrBlank()) {
                        Text(
                            text = " $InfinityMetaSeparator $failureText",
                            style = style,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("CHAT_STEP_FAILURE"),
                        )
                    }
                }
            } else {
                Text(
                    text = summaryText,
                    style = style,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = summaryMaxWidth).stepShimmer(active, shimmerSpeed).testTag("CHAT_STEP_SUMMARY"),
                )
                trailing?.invoke(this)
            }
            if (preview != null) {
                Spacer(Modifier.width(12.dp))
                preview.invoke(this)
            }
            AnimatedVisibility(
                visible = arrowVisible,
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.6f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.padding(start = 6.dp).size(16.dp).testTag("CHAT_STEP_ARROW"),
                )
            }
        }
    }
}

/**
 * 检索进行中的放大镜：沿小圆周巡游并轻微摆动，像在逐行扫视；系统关闭动画或截图预览时静止。
 * 只在检索进行中出现，结束后由调用方收起，摘要文字随之左移归位。
 */
@Composable
internal fun RetrievalScanIcon(tint: Color, modifier: Modifier = Modifier) {
    val animate = rememberStreamAnimationsEnabled() == true
    val phase = if (animate) {
        rememberInfiniteTransition(label = "retrievalScan")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "retrievalScanPhase")
    } else null
    Icon(
        imageVector = Icons.Rounded.Search,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .padding(end = 6.dp)
            .size(16.dp)
            .graphicsLayer {
                val t = phase?.value ?: return@graphicsLayer
                val angle = t * 2f * PI.toFloat()
                val radius = 2.dp.toPx()
                translationX = cos(angle) * radius
                translationY = sin(angle) * radius * 0.6f
                rotationZ = sin(angle) * 10f
            }
            .testTag(UiTags.RAG_SCAN_ICON),
    )
}
