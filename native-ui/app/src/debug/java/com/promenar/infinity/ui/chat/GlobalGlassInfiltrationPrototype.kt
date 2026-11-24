package com.promenar.infinity.ui.chat

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityIceBlueSeed
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全局厚玻璃光效浸润 + 外观「界面特效」分组高保真原生 Compose 校准原型（v1，仅 debug 源码）。
 * 与交互式实时 Web 原型 `.agent/plans/prototypes/global-glass-infiltration-v1.html` 配套验证：
 * - 帧 1：展开内屏三栏 · STREAM 流式生成（克制微光浸润，不干扰右侧 Git 代码阅读）
 * - 帧 2：展开内屏三栏 · APPROVAL 等待审批（暖琥珀切面全内反射导光 + 次表面浸润加强，余光一眼感知）
 * - 帧 3：手机外屏竖屏 · 93% 熏黑上滑抽屉底缘受光浸润（APPROVAL 态）
 * - 帧 4：外观设置 › 「界面特效」分组（改名 + 独立开关「全局光效浸润」及环境光纯色模式自动置灰态）
 */

private enum class InfilEdge { LEFT, RIGHT, BOTTOM }

/** 纯 GPU 解析绘制厚玻璃受光切面亮边 + 次表面浸润（零 BlurMaskFilter、零离屏模糊重录）。 */
private fun Modifier.protoGlassInfiltration(
    color: Color,
    intensity: Float,
    edge: InfilEdge,
    anchorFraction: Float = 0.86f,
    isLight: Boolean = false,
): Modifier = if (intensity <= 0.004f) this else drawWithCache {
    val peakBleed = (if (isLight) 0.14f else 0.21f) * intensity
    val peakRim = (if (isLight) 0.56f else 0.78f) * intensity
    val coreHighlight = if (isLight) Color.White else lerp(color, Color.White, 0.45f)

    val bleedBrush: Brush
    val rimBrush: Brush
    val innerBounceBrush: Brush
    when (edge) {
        InfilEdge.LEFT -> {
            val cy = size.height * anchorFraction
            val radius = (size.width * 0.62f).coerceIn(72.dp.toPx(), 132.dp.toPx())
            bleedBrush = Brush.radialGradient(
                0f to color.copy(alpha = peakBleed),
                0.38f to color.copy(alpha = peakBleed * 0.48f),
                0.76f to color.copy(alpha = peakBleed * 0.12f),
                1f to Color.Transparent,
                center = Offset(0f, cy),
                radius = radius,
            )
            val span = 165.dp.toPx()
            rimBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.28f to color.copy(alpha = peakRim * 0.45f),
                0.5f to coreHighlight.copy(alpha = peakRim),
                0.72f to color.copy(alpha = peakRim * 0.45f),
                1f to Color.Transparent,
                startY = cy - span,
                endY = cy + span,
            )
            innerBounceBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = peakRim * 0.28f),
                1f to Color.Transparent,
                startY = cy - span * 0.72f,
                endY = cy + span * 0.72f,
            )
        }
        InfilEdge.RIGHT -> {
            val cy = size.height * anchorFraction
            val radius = (size.width * 0.72f).coerceIn(72.dp.toPx(), 126.dp.toPx())
            bleedBrush = Brush.radialGradient(
                0f to color.copy(alpha = peakBleed),
                0.38f to color.copy(alpha = peakBleed * 0.48f),
                0.76f to color.copy(alpha = peakBleed * 0.12f),
                1f to Color.Transparent,
                center = Offset(size.width, cy),
                radius = radius,
            )
            val span = 165.dp.toPx()
            rimBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.28f to color.copy(alpha = peakRim * 0.45f),
                0.5f to coreHighlight.copy(alpha = peakRim),
                0.72f to color.copy(alpha = peakRim * 0.45f),
                1f to Color.Transparent,
                startY = cy - span,
                endY = cy + span,
            )
            innerBounceBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = peakRim * 0.28f),
                1f to Color.Transparent,
                startY = cy - span * 0.72f,
                endY = cy + span * 0.72f,
            )
        }
        InfilEdge.BOTTOM -> {
            val cx = size.width * 0.54f
            val radius = (size.width * 0.65f).coerceIn(110.dp.toPx(), 220.dp.toPx())
            bleedBrush = Brush.radialGradient(
                0f to color.copy(alpha = peakBleed),
                0.42f to color.copy(alpha = peakBleed * 0.45f),
                0.78f to color.copy(alpha = peakBleed * 0.12f),
                1f to Color.Transparent,
                center = Offset(cx, size.height),
                radius = radius,
            )
            val span = 160.dp.toPx()
            rimBrush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.28f to color.copy(alpha = peakRim * 0.48f),
                0.5f to coreHighlight.copy(alpha = peakRim),
                0.72f to color.copy(alpha = peakRim * 0.48f),
                1f to Color.Transparent,
                startX = cx - span,
                endX = cx + span,
            )
            innerBounceBrush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = peakRim * 0.26f),
                1f to Color.Transparent,
                startX = cx - span * 0.72f,
                endX = cx + span * 0.72f,
            )
        }
    }

    onDrawWithContent {
        drawContent()
        drawRect(bleedBrush)
        val stroke = 2.dp.toPx()
        val bounce = 3.5.dp.toPx()
        when (edge) {
            InfilEdge.LEFT -> {
                drawLine(rimBrush, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), strokeWidth = stroke)
                drawLine(innerBounceBrush, Offset(bounce, 0f), Offset(bounce, size.height), strokeWidth = 2.5.dp.toPx())
            }
            InfilEdge.RIGHT -> {
                drawLine(rimBrush, Offset(size.width - stroke / 2f, 0f), Offset(size.width - stroke / 2f, size.height), strokeWidth = stroke)
                drawLine(innerBounceBrush, Offset(size.width - bounce, 0f), Offset(size.width - bounce, size.height), strokeWidth = 2.5.dp.toPx())
            }
            InfilEdge.BOTTOM -> {
                drawLine(rimBrush, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), strokeWidth = stroke)
                drawLine(innerBounceBrush, Offset(0f, size.height - bounce), Offset(size.width, size.height - bounce), strokeWidth = 2.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun InfilProtoTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = InfinityIceBlueSeed.value.toLong(),
            schemeStyle = InfinitySchemeStyle.BRIGHT,
            pureBlack = dark,
            motion = InfinityMotionStyles(surface = ComposerSurfaceStyle.FROSTED),
        ),
        content = content,
    )
}

@Composable
private fun UnfoldedThreePaneGlassPreviewBody(
    phaseLabel: String,
    glyph: String,
    lightColor: Color,
    sideIntensity: Float,
    dark: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val glassBg = if (dark) InfinityObsidianSolidContainer else scheme.surfaceContainerLowest
    val borderCol = if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.5f)

    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Row(Modifier.fillMaxSize()) {
            // Left Session Pane (receiving light on RIGHT edge)
            Column(
                modifier = Modifier
                    .width(184.dp)
                    .fillMaxHeight()
                    .background(if (dark) Color(0xFF06070A) else scheme.surfaceContainerLow)
                    .protoGlassInfiltration(lightColor, sideIntensity, InfilEdge.RIGHT, anchorFraction = 0.87f, isLight = !dark)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("对话", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Box(Modifier.size(28.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.primary.copy(alpha = 0.14f),
                    border = BorderStroke(0.75.dp, scheme.primary.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Codex CLI · 光效浸润", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Infinity · $phaseLabel", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (dark) InfinityObsidianSolidChip else scheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("内置代理拖拽排序", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                        Text("Infinity · 18:02", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }

            // Center Chat Pane with Composer Island (Primary Light Source)
            Box(Modifier.weight(1f).fillMaxHeight().background(scheme.background)) {
                // Top Bar (far from composer -> distance falloff = 0 -> zero bleed!)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(if (dark) InfinityObsidianGlassTint.copy(alpha = 0.85f) else InfinityPureWhite.copy(alpha = 0.85f))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Codex CLI", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    Icon(Icons.Rounded.ViewSidebar, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 62.dp, start = 14.dp, end = 14.dp, bottom = 128.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.primary.copy(alpha = 0.16f),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            "核验厚玻璃切面全内反射与次表面浸润质感",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        "顶栏距浮岛约 520dp，按物理距离衰减自动为 0；左右相邻玻璃面在浮岛同高处承接切面聚光与磨砂内壁浸润。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }

                // Glowing Composer Island
                val islandShape = RoundedCornerShape(20.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(islandShape)
                        .background(if (dark) InfinityObsidianGlassTint.copy(alpha = 0.90f) else InfinityPureWhite.copy(alpha = 0.92f))
                        .drawWithCache {
                            val wash = Brush.radialGradient(
                                0f to lightColor.copy(alpha = if (dark) 0.34f else 0.20f),
                                0.6f to lightColor.copy(alpha = if (dark) 0.11f else 0.06f),
                                1f to Color.Transparent,
                                center = Offset(size.width * 0.78f, size.height * 0.28f),
                                radius = size.width * 0.82f,
                            )
                            onDrawBehind { drawRect(wash) }
                        }
                        .border(1.25.dp, lightColor.copy(alpha = 0.62f), islandShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(phaseLabel, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
                            Text(
                                glyph,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace, fontWeight = FontWeight.Bold),
                                color = lightColor,
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        0.5f to lightColor.copy(alpha = 0.85f),
                                        1f to Color.Transparent,
                                    ),
                                ),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("输入消息…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(scheme.onSurface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null, tint = scheme.surface, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Right 360dp Docked Obsidian Inspector (receiving light on LEFT edge)
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(glassBg)
                    .protoGlassInfiltration(lightColor, sideIntensity, InfilEdge.LEFT, anchorFraction = 0.87f, isLight = !dark)
                    .border(BorderStroke(0.75.dp, borderCol))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Git · main", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    Text("360dp 固定分栏", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (dark) InfinityObsidianSolidChip else scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("FrostedBackdrop.kt", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                        Text("+ drawGlassEdgeCaustic(light)", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = domain.signalOn)
                        Text("+ drawSubsurfaceBleed(light)", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = domain.signalOn)
                        Text("  // 文字与代码对比度无损", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (dark) InfinityObsidianSolidChip else scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("MotionStyleSettings.kt", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                        Text("+ section = \"界面特效\"", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = domain.signalOn)
                        Text("+ toggle = \"全局光效浸润\"", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = domain.signalOn)
                    }
                }
            }
        }
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "1. 内屏三栏 · STREAM 克制微浸润（深色）", widthDp = 760, heightDp = 520)
@Composable
fun GlassInfiltrationStreamDarkPreview() {
    InfilProtoTheme(dark = true) {
        UnfoldedThreePaneGlassPreviewBody(
            phaseLabel = "流式生成中（0.56× 克制）",
            glyph = "24.8",
            lightColor = Color(0xFF0EA5E9),
            sideIntensity = 0.56f,
            dark = true,
        )
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "2. 内屏三栏 · APPROVAL 琥珀加强（深色）", widthDp = 760, heightDp = 520)
@Composable
fun GlassInfiltrationApprovalDarkPreview() {
    InfilProtoTheme(dark = true) {
        UnfoldedThreePaneGlassPreviewBody(
            phaseLabel = "等待审批（1.18× 琥珀余光提示）",
            glyph = " ! ",
            lightColor = Color(0xFFFBA92C),
            sideIntensity = 1.18f,
            dark = true,
        )
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "3. 外屏竖屏 · 93% 熏黑抽屉底缘渗光（APPROVAL）", widthDp = 360, heightDp = 640)
@Composable
fun GlassInfiltrationPortraitDrawerPreview() {
    InfilProtoTheme(dark = true) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val amber = Color(0xFFFBA92C)
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Box(Modifier.fillMaxSize()) {
                // Composer glowing underneath at bottom
                val islandShape = RoundedCornerShape(20.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .height(82.dp)
                        .clip(islandShape)
                        .background(InfinityObsidianGlassTint.copy(alpha = 0.92f))
                        .drawWithCache {
                            val wash = Brush.radialGradient(
                                0f to amber.copy(alpha = 0.38f),
                                1f to Color.Transparent,
                                center = Offset(size.width * 0.6f, 0f),
                                radius = size.width * 0.75f,
                            )
                            onDrawBehind { drawRect(wash) }
                        }
                        .border(1.25.dp, amber.copy(alpha = 0.65f), islandShape)
                        .padding(14.dp),
                ) {
                    Text("等待审批 · 点击批准命令", style = MaterialTheme.typography.labelLarge, color = amber)
                }

                // Top 80% Obsidian Portrait Drawer catching warm amber on its BOTTOM cut edge
                val drawerShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.81f)
                        .clip(drawerShape)
                        .background(InfinityObsidianSolidContainer)
                        .protoGlassInfiltration(amber, 1.15f, InfilEdge.BOTTOM, isLight = false)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Git · 改动", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = InfinityObsidianSolidChip,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("FrostedBackdrop.kt", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                            Text("+ 抽屉底缘承接下方待审批琥珀光", style = MaterialTheme.typography.bodySmall, color = domain.signalOn)
                            Text("  翻阅文件/浏览器抽屉时一眼察觉后台在等你点确认", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 38.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(InfinityPureWhite.copy(alpha = 0.28f)),
                    )
                }
            }
        }
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "4. 外观 › 界面特效（含全局光效浸润开关）", widthDp = 360, heightDp = 640)
@Composable
fun InterfaceEffectsSettingsSectionPreview() {
    InfilProtoTheme(dark = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 16.dp),
            ) {
                InfinitySettingsSection(title = "界面特效") {
                    InfinityValueRow(title = "输入框流光", value = "网孔背光", onClick = {})
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("浮岛材质", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(ComposerSurfaceStyle.FROSTED, "毛玻璃"),
                                InfinitySegment(ComposerSurfaceStyle.AMBIENT, "环境光"),
                            ),
                            selected = ComposerSurfaceStyle.FROSTED,
                            onSelect = {},
                            fillWidth = true,
                            compact = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    SettingsToggle(
                        title = "全局光效浸润",
                        description = "让光效自然浸润相邻毛玻璃边缘",
                        checked = true,
                        enabled = true,
                        onCheckedChange = {},
                    )
                    SettingsToggle(
                        title = "运行坞点阵屏",
                        description = "在浮岛右上角显示点阵状态屏",
                        checked = true,
                        enabled = true,
                        onCheckedChange = {},
                    )
                }

                // 对比：切到「环境光」纯色省电档时自动置灰
                InfinitySettingsSection(title = "界面特效（环境光省电档对照）") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("浮岛材质", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(ComposerSurfaceStyle.FROSTED, "毛玻璃"),
                                InfinitySegment(ComposerSurfaceStyle.AMBIENT, "环境光"),
                            ),
                            selected = ComposerSurfaceStyle.AMBIENT,
                            onSelect = {},
                            fillWidth = true,
                            compact = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    SettingsToggle(
                        title = "全局光效浸润",
                        description = "仅在毛玻璃材质下生效",
                        checked = false,
                        enabled = false,
                        onCheckedChange = {},
                    )
                }
            }
        }
    }
}
