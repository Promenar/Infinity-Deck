package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ShotStatus
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySplitPillButton
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography

private enum class ProtoPrevisCameraView { CAMERA, FREE_ORBIT }
private enum class ProtoPrevisShaderMode { CLAY, DEPTH }
private enum class ProtoCutTab { PREVIS, SELECTED, VERSION_1 }

@Composable
private fun ProtoHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = InfinityTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProtoPrevisStageCanvas(
    cameraView: ProtoPrevisCameraView,
    shaderMode: ProtoPrevisShaderMode,
    showThirdsGrid: Boolean,
    showAxisGuide: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val safeGreen = Color(0xFF4ADE80)
    val warnRed = Color(0xFFF87171)
    val cyanGuide = Color(0xFF67E8F9)
    Canvas(modifier = modifier) {
        when (shaderMode) {
            ProtoPrevisShaderMode.DEPTH -> drawDepthPreviewScene(this)
            ProtoPrevisShaderMode.CLAY -> when (cameraView) {
                ProtoPrevisCameraView.CAMERA -> drawCameraClayScene(this, accent)
                ProtoPrevisCameraView.FREE_ORBIT -> drawFreeOrbitScene(this, accent, safeGreen, warnRed, showAxisGuide)
            }
        }
        if (showThirdsGrid) {
            val gridColor = cyanGuide.copy(alpha = 0.28f)
            val stroke = 1.dp.toPx()
            for (i in 1..2) {
                val x = size.width * i / 3f
                val y = size.height * i / 3f
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = 10.dp.toPx()
            drawLine(cyanGuide.copy(alpha = 0.48f), Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = stroke)
            drawLine(cyanGuide.copy(alpha = 0.48f), Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = stroke)
        }
    }
}

private fun drawPerspectiveGrid(scope: DrawScope, horizonRatio: Float, lineColor: Color) = with(scope) {
    val w = size.width
    val h = size.height
    val horizonY = h * horizonRatio
    val vpX = w * 0.5f
    val stroke = 1.dp.toPx()
    for (i in -6..6) {
        val bottomX = vpX + i * (w * 0.16f)
        drawLine(lineColor, Offset(vpX + i * 10.dp.toPx(), horizonY), Offset(bottomX, h), strokeWidth = stroke)
    }
    listOf(0.08f, 0.18f, 0.32f, 0.50f, 0.72f, 0.94f).forEach { t ->
        val y = horizonY + (h - horizonY) * t
        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke)
    }
}

private fun drawWhiteModelActor(
    scope: DrawScope,
    foot: Offset,
    scale: Float,
    headColor: Color,
    bodyColor: Color,
    arrowColor: Color,
    facingRight: Boolean,
) = with(scope) {
    drawOval(
        color = Color.Black.copy(alpha = 0.34f),
        topLeft = Offset(foot.x - 16f * scale, foot.y - 4f * scale),
        size = Size(32f * scale, 9f * scale),
    )
    drawRoundRect(
        color = bodyColor,
        topLeft = Offset(foot.x - 11f * scale, foot.y - 46f * scale),
        size = Size(22f * scale, 42f * scale),
        cornerRadius = CornerRadius(7f * scale, 7f * scale),
    )
    drawCircle(
        color = headColor,
        radius = 10f * scale,
        center = Offset(foot.x, foot.y - 60f * scale),
    )
    val dir = if (facingRight) 1f else -1f
    val arrowY = foot.y - 6f * scale
    val endX = foot.x + dir * 28f * scale
    drawLine(
        color = arrowColor,
        start = Offset(foot.x + dir * 6f * scale, arrowY),
        end = Offset(endX, arrowY),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(arrowColor, radius = 3.dp.toPx(), center = Offset(endX, arrowY))
}

private fun drawCameraClayScene(scope: DrawScope, accent: Color) = with(scope) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF141923), Color(0xFF1E2533), Color(0xFF10131A))),
    )
    drawPerspectiveGrid(this, horizonRatio = 0.44f, lineColor = Color.White.copy(alpha = 0.11f))

    // 老街包子铺素模建筑体块与蒸笼台
    drawRoundRect(
        color = Color(0xFF93A1B8),
        topLeft = Offset(w * 0.50f, h * 0.18f),
        size = Size(w * 0.36f, h * 0.56f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFFB8C4D8),
        topLeft = Offset(w * 0.46f, h * 0.52f),
        size = Size(w * 0.34f, h * 0.22f),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
    // 蒸笼圆柱暗示
    drawRoundRect(
        color = Color(0xFFDDE5F2),
        topLeft = Offset(w * 0.52f, h * 0.40f),
        size = Size(w * 0.11f, h * 0.12f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    // 左侧行人路灯素模柱
    drawRect(
        color = Color(0xFF7B879B),
        topLeft = Offset(w * 0.16f, h * 0.24f),
        size = Size(6.dp.toPx(), h * 0.48f),
    )

    // 主角“陆昭”行走位与“掌柜”摊位站位
    drawWhiteModelActor(
        this,
        foot = Offset(w * 0.36f, h * 0.80f),
        scale = (h / 180f).coerceIn(0.8f, 2.2f),
        headColor = Color(0xFFEAF0FA),
        bodyColor = Color(0xFFC8D3E6),
        arrowColor = accent,
        facingRight = true,
    )
    drawWhiteModelActor(
        this,
        foot = Offset(w * 0.66f, h * 0.56f),
        scale = (h / 230f).coerceIn(0.65f, 1.8f),
        headColor = Color(0xFFDCE3EF),
        bodyColor = Color(0xFFAEBBD1),
        arrowColor = Color(0xFF93C5FD),
        facingRight = false,
    )
}

private fun drawDepthPreviewScene(scope: DrawScope) = with(scope) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF0A0A0A), Color(0xFF1E1E1E), Color(0xFF3E3E3E))),
    )
    // 线性深度映射：越近越亮（前景主角近白），越远越暗（建筑底灰）
    drawRoundRect(
        color = Color(0xFF5A5A5A),
        topLeft = Offset(w * 0.50f, h * 0.18f),
        size = Size(w * 0.36f, h * 0.56f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFF8C8C8C),
        topLeft = Offset(w * 0.46f, h * 0.52f),
        size = Size(w * 0.34f, h * 0.22f),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFFA8A8A8),
        topLeft = Offset(w * 0.52f, h * 0.40f),
        size = Size(w * 0.11f, h * 0.12f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawWhiteModelActor(
        this,
        foot = Offset(w * 0.36f, h * 0.80f),
        scale = (h / 180f).coerceIn(0.8f, 2.2f),
        headColor = Color(0xFFF3F3F3),
        bodyColor = Color(0xFFE5E5E5),
        arrowColor = Color(0xFFCCCCCC),
        facingRight = true,
    )
    drawWhiteModelActor(
        this,
        foot = Offset(w * 0.66f, h * 0.56f),
        scale = (h / 230f).coerceIn(0.65f, 1.8f),
        headColor = Color(0xFF9E9E9E),
        bodyColor = Color(0xFF8A8A8A),
        arrowColor = Color(0xFF777777),
        facingRight = false,
    )
}

private fun drawFreeOrbitScene(
    scope: DrawScope,
    accent: Color,
    safeGreen: Color,
    warnRed: Color,
    showAxisGuide: Boolean,
) = with(scope) {
    val w = size.width
    val h = size.height
    drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF10151F), Color(0xFF161D2B))))
    // 俯视斜轴网格
    val gridColor = Color.White.copy(alpha = 0.08f)
    for (i in 1..9) {
        val x = w * i / 10f
        drawLine(gridColor, Offset(x, h * 0.06f), Offset(x, h * 0.94f), strokeWidth = 1.dp.toPx())
    }
    for (i in 1..6) {
        val y = h * i / 7f
        drawLine(gridColor, Offset(w * 0.04f, y), Offset(w * 0.96f, y), strokeWidth = 1.dp.toPx())
    }

    // 俯视白模街景体块
    drawRoundRect(
        color = Color(0xFF64748B).copy(alpha = 0.72f),
        topLeft = Offset(w * 0.56f, h * 0.16f),
        size = Size(w * 0.28f, h * 0.24f),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFF94A3B8).copy(alpha = 0.85f),
        topLeft = Offset(w * 0.58f, h * 0.34f),
        size = Size(w * 0.24f, h * 0.09f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )

    val pActorA = Offset(w * 0.38f, h * 0.54f)
    val pActorB = Offset(w * 0.67f, h * 0.45f)

    // 180° 对话轴线与安全半区
    if (showAxisGuide) {
        drawArc(
            color = safeGreen.copy(alpha = 0.12f),
            startAngle = 15f,
            sweepAngle = 165f,
            useCenter = true,
            topLeft = Offset(w * 0.24f, h * 0.20f),
            size = Size(w * 0.56f, h * 0.56f),
        )
        drawArc(
            color = warnRed.copy(alpha = 0.10f),
            startAngle = 195f,
            sweepAngle = 165f,
            useCenter = true,
            topLeft = Offset(w * 0.24f, h * 0.20f),
            size = Size(w * 0.56f, h * 0.56f),
        )
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
        drawLine(
            color = safeGreen.copy(alpha = 0.85f),
            start = Offset(w * 0.22f, h * 0.59f),
            end = Offset(w * 0.84f, h * 0.39f),
            strokeWidth = 2.dp.toPx(),
            pathEffect = dash,
        )
    }

    // 主角移动路径虚线
    drawLine(
        color = accent.copy(alpha = 0.65f),
        start = Offset(w * 0.20f, h * 0.66f),
        end = pActorA,
        strokeWidth = 2.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
    )
    drawCircle(accent, radius = 4.dp.toPx(), center = Offset(w * 0.20f, h * 0.66f))

    // 俯视两位角色点位
    drawCircle(Color(0xFFE2E8F0), radius = 12.dp.toPx(), center = pActorA)
    drawCircle(accent, radius = 5.dp.toPx(), center = pActorA)
    drawCircle(Color(0xFFCBD5E1), radius = 11.dp.toPx(), center = pActorB)

    // 机位视锥棱线：SC01-01（非选中）与 SC01-02（当前高亮机位）
    val cam1 = Offset(w * 0.28f, h * 0.78f)
    val cam2 = Offset(w * 0.56f, h * 0.72f)
    drawFrustum(cam1, pActorA, Color.White.copy(alpha = 0.36f), active = false)
    drawFrustum(cam2, pActorB, accent, active = true)
}

private fun DrawScope.drawFrustum(origin: Offset, target: Offset, color: Color, active: Boolean) {
    val dx = target.x - origin.x
    val dy = target.y - origin.y
    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
    val ux = dx / len
    val uy = dy / len
    val px = -uy
    val py = ux
    val reach = (len * 0.42f).coerceIn(36f, 96f)
    val spread = if (active) 28f else 22f
    val leftPt = Offset(origin.x + ux * reach + px * spread, origin.y + uy * reach + py * spread)
    val rightPt = Offset(origin.x + ux * reach - px * spread, origin.y + uy * reach - py * spread)
    val path = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(leftPt.x, leftPt.y)
        lineTo(rightPt.x, rightPt.y)
        close()
    }
    drawPath(path, color = color.copy(alpha = if (active) 0.22f else 0.10f))
    drawPath(path, color = color, style = Stroke(width = if (active) 2.dp.toPx() else 1.2.dp.toPx()))
    drawCircle(color = color, radius = if (active) 6.dp.toPx() else 4.5.dp.toPx(), center = origin)
}

@Composable
private fun OverlayBadgePill(
    text: String,
    highlighted: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlighted) scheme.primaryContainer.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.58f),
        border = BorderStroke(0.5.dp, if (highlighted) scheme.primary.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.16f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = if (highlighted) scheme.onPrimaryContainer else Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OverlayIconBadge(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) scheme.primaryContainer.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.58f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) scheme.onPrimaryContainer else Color.White,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun ProtoUnifiedPrevisMonitor(
    shotNumber: Int,
    totalShots: Int,
    playing: Boolean,
    clockText: String,
    cameraTelemetry: String,
    cameraView: ProtoPrevisCameraView,
    shaderMode: ProtoPrevisShaderMode,
    showThirdsGrid: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 16:9 Three.js WebGL 视口 + 紧凑状态浮层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF10151F)),
            ) {
                ProtoPrevisStageCanvas(
                    cameraView = cameraView,
                    shaderMode = shaderMode,
                    showThirdsGrid = showThirdsGrid,
                    showAxisGuide = true,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlayBadgePill("第 $shotNumber/$totalShots 镜", highlighted = false)
                        OverlayBadgePill(
                            text = if (shaderMode == ProtoPrevisShaderMode.DEPTH) "深度图" else "3D 白模",
                            highlighted = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlayBadgePill(
                            text = if (cameraView == ProtoPrevisCameraView.CAMERA) "机位 · 自由" else "自由 · 机位",
                            highlighted = cameraView == ProtoPrevisCameraView.FREE_ORBIT,
                        )
                        OverlayIconBadge(Icons.Rounded.GridView, active = showThirdsGrid, contentDescription = "构图网格")
                        OverlayIconBadge(Icons.Rounded.Fullscreen, active = false, contentDescription = "全屏预演")
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    OverlayBadgePill(cameraTelemetry)
                }
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            // 常驻 48dp 一体化播控条：首末镜/播停态原地切换 enabled 与图标，零高度跳变、零按钮挤出
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val canPrev = shotNumber > 1
                val canNext = shotNumber < totalShots
                IconButton(onClick = {}, enabled = canPrev, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "上一镜",
                        tint = if (canPrev) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
                    )
                }
                Surface(
                    onClick = {},
                    shape = RoundedCornerShape(50),
                    color = if (playing) scheme.primaryContainer else scheme.surfaceContainerHighest,
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (playing) scheme.onPrimaryContainer else scheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (playing) "停止" else "连播",
                            style = InfinityTypography.labelLarge,
                            color = if (playing) scheme.onPrimaryContainer else scheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                IconButton(onClick = {}, enabled = canNext, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一镜",
                        tint = if (canNext) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = clockText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = InfinityMonospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProtoTimelineCard(activeIndex: Int) {
    val scheme = MaterialTheme.colorScheme
    val clips = listOf(
        "经过" to "4.5s",
        "招呼" to "5s",
        "婉拒" to "5.2s",
        "回应" to "5.9s",
    )
    ProductionSection(null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "陆昭晨间问候 20s 测试片",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "20.5 秒",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                clips.forEachIndexed { index, (title, dur) ->
                    val active = index == activeIndex
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(50.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHighest)
                            .then(
                                if (active) Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(7.dp))
                                else Modifier,
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = dur,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) scheme.primary else scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoPrevisActionBar(
    exporting: Boolean,
    primaryLabel: String = "导出视频",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfinitySplitPillButton(
            text = "导出帧",
            onClick = {},
            moreContentDescription = "更多导出帧格式",
            modifier = Modifier.weight(1f),
            tone = InfinityPillTone.NEUTRAL,
            enabled = !exporting,
            icon = Icons.Rounded.PhotoCamera,
            menuContent = {},
        )
        InfinityPillButton(
            text = if (exporting) "取消导出" else primaryLabel,
            onClick = {},
            modifier = Modifier.weight(1f),
            tone = if (exporting) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
            icon = Icons.Rounded.Movie,
        )
    }
}

@Preview(name = "剪辑页 3D 白模预演 深色 360dp", widthDp = 392, heightDp = 1280, locale = "zh-rCN")
@Composable
fun previsCutPageDark360Preview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProtoHeading(
                    title = "1. 总览「分镜预演」入口（有 3D 剧本 vs 可选跳过）",
                    subtitle = "已有 3D 剧本点击直达剪辑预演；无剧本时保留跳过开关",
                )
                ProductionSection(null) {
                    ProductionRow(
                        title = "分镜预演",
                        icon = Icons.Rounded.Slideshow,
                        subtitle = "4/4 镜 · 3D 白模",
                        chip = ProductionChipSpec("3D 预演", ProductionTone.INFO),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "分镜预演",
                        icon = Icons.Rounded.Slideshow,
                        subtitle = "0/4 镜 · 可选环节",
                        chevron = false,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("跳过", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                InfinitySwitch(checked = false, onCheckedChange = null)
                            }
                        },
                        onClick = {},
                    )
                }

                ProtoHeading(
                    title = "2. 剪辑页「分镜预演」一体化监视器 + 固定底栏",
                    subtitle = "播放控制收进 48dp 常驻底栏，播放/停止与首末镜零高度跳变",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(ProtoCutTab.PREVIS, "分镜预演"),
                            InfinitySegment(ProtoCutTab.SELECTED, "已选定"),
                            InfinitySegment(ProtoCutTab.VERSION_1, "第 1 版"),
                        ),
                        selected = ProtoCutTab.PREVIS,
                        onSelect = {},
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {}, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建粗剪版本")
                    }
                }

                ProtoUnifiedPrevisMonitor(
                    shotNumber = 1,
                    totalShots = 4,
                    playing = true,
                    clockText = "01.8s / 20.5s",
                    cameraTelemetry = "35mm · 中景 · 缓慢推镜",
                    cameraView = ProtoPrevisCameraView.CAMERA,
                    shaderMode = ProtoPrevisShaderMode.CLAY,
                    showThirdsGrid = true,
                )

                ProtoTimelineCard(activeIndex = 0)

                ProductionSection(null) {
                    ProductionRow("预演覆盖", value = "4/4 镜已建 3D 机位")
                    ProductionRow("导出规格", valueSegments = listOf("1280×720", "24fps", "H.264"))
                }

                ProtoPrevisActionBar(exporting = false)

                ProtoHeading(
                    title = "3. 导出进行中与「第 1 版粗剪」等宽双按钮对比",
                    subtitle = "淘汰单按钮孤立右对齐与 3 按钮折三行，进度原地展开不顶跳",
                )
                ProductionSection(null) {
                    Column(
                        Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row {
                            Text("正在导出预演视频", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("42%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ProductionProgressBar(0.42f)
                    }
                }
                ProtoPrevisActionBar(exporting = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    InfinityPillButton(
                        text = "分享",
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.Share,
                    )
                    InfinityPillButton(
                        text = "重新导出",
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        tone = InfinityPillTone.PRIMARY,
                    )
                }
            }
        }
    }
}

@Preview(name = "全屏 3D 预演 自由视角与轴线检查", widthDp = 760, heightDp = 640, locale = "zh-rCN")
@Composable
fun previsFullscreenAndFreeOrbitDarkPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = Color(0xFF0B0E14), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 全屏顶栏：返回 + 镜名 + 双视角/通道/轴线控制
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出全屏", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "第 2/4 镜 · SC01-02 招呼",
                            style = InfinityTypography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        InfinityMetaLine(
                            segments = listOf("50mm", "中近景", "180° 轴线安全", "角色走位 A→B"),
                            style = InfinityTypography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                    OverlayBadgePill("自由视角", highlighted = true)
                    OverlayBadgePill("白模通道")
                    OverlayBadgePill("180° 轴线", highlighted = true)
                    OverlayIconBadge(Icons.Rounded.GridView, active = true, contentDescription = "三分线")
                }

                // 中央 3D 自由俯瞰白模舞台
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                ) {
                    ProtoPrevisStageCanvas(
                        cameraView = ProtoPrevisCameraView.FREE_ORBIT,
                        shaderMode = ProtoPrevisShaderMode.CLAY,
                        showThirdsGrid = false,
                        showAxisGuide = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OverlayBadgePill("SC01-01 机位")
                        OverlayBadgePill("SC01-02 当前机位", highlighted = true)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    ) {
                        OverlayBadgePill("单指旋转 · 双指缩放 · 双击复位机位")
                    }
                }

                // 底部常驻分镜切换条 + 播控 + 快捷导出/提意见
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("1 经过 4.5s", "2 招呼 5.0s", "3 婉拒 5.2s", "4 回应 5.9s").forEachIndexed { idx, label ->
                            val selected = idx == 1
                            Surface(
                                onClick = {},
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color(0xFF1A202C),
                                border = BorderStroke(
                                    width = if (selected) 1.dp else 0.5.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                                ),
                                modifier = Modifier.weight(1f).height(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                                    Text(
                                        text = label,
                                        style = InfinityTypography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.82f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一镜", tint = Color.White)
                        }
                        InfinityPillButton(
                            text = "停止 · 05.9s",
                            onClick = {},
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.Stop,
                        )
                        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "下一镜", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        InfinityPillButton(text = "抓构图", onClick = {}, icon = Icons.Rounded.PhotoCamera, compact = true)
                        InfinityPillButton(text = "抓深度", onClick = {}, icon = Icons.Rounded.Layers, compact = true)
                        InfinityPillButton(text = "提意见", onClick = {}, icon = Icons.Rounded.RateReview, compact = true)
                    }
                }
            }
        }
    }
}

@Preview(name = "浅色模式 + 1.3x 大字号 + 深度通道", widthDp = 392, heightDp = 1120, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun previsCutPageLightLargeFontPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProtoHeading(
                    title = "浅色大字号 1.3x｜深度图模式与等宽导出栏",
                    subtitle = "检验顶部角标、常驻播控条及双按钮在大字号下无折行截断",
                )
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment(ProtoCutTab.PREVIS, "分镜预演"),
                        InfinitySegment(ProtoCutTab.SELECTED, "已选定"),
                        InfinitySegment(ProtoCutTab.VERSION_1, "第 1 版"),
                    ),
                    selected = ProtoCutTab.PREVIS,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                ProtoUnifiedPrevisMonitor(
                    shotNumber = 1,
                    totalShots = 4,
                    playing = false,
                    clockText = "00.0s / 20.5s",
                    cameraTelemetry = "35mm · 深度图预览",
                    cameraView = ProtoPrevisCameraView.CAMERA,
                    shaderMode = ProtoPrevisShaderMode.DEPTH,
                    showThirdsGrid = true,
                )
                ProtoTimelineCard(activeIndex = 0)
                ProductionSection(null) {
                    ProductionRow("预演覆盖", value = "4/4 镜 · 3D 白模")
                    ProductionRow("导出格式", valueSegments = listOf("1280×720", "MP4"))
                }
                ProtoPrevisActionBar(exporting = false)
            }
        }
    }
}
