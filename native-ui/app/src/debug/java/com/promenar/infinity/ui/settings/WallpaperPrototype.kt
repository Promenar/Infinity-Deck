package com.promenar.infinity.ui.settings

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import kotlin.math.PI
import kotlin.math.sin

/*
 * 聊天壁纸系统（Wallpaper Studio & Chat Surface）高保真原型（隔离于 debug 源码）：
 * 1. 默认关闭，开启后自动启用「背景模糊 + 主题适配遮罩 + 助手消息可选半透阅读衬底」三重可读性护航；
 * 2. 内置 6 套零体积程序化美学预设 + 相册自选 + 看图灯箱一键「设为壁纸」；
 * 3. 一键「提取壁纸主色」联动现有 ThemePreferenceStore 种子色与配色风格；
 * 4. 折叠屏宽屏自适应为「左栏固定实时预览 + 右栏参数调节」双栏工作室布局。
 */

private enum class ProtoWallpaperKind(
    val label: String,
    val extractedAccent: Color,
) {
    NONE("纯净", Color(0xFF8AB4F8)),
    CUSTOM_PHOTO("夜景相册", Color(0xFF7CB7FF)),
    AURORA("极夜流光", Color(0xFF64B5F6)),
    OBSIDIAN_TOPO("黑曜星纹", Color(0xFF9FA8DA)),
    PINE_MIST("青岚雾松", Color(0xFF4DB6AC)),
    AMBER_DUSK("琥珀暮光", Color(0xFFFFB74D)),
    ABYSSAL_WAVE("深海微澜", Color(0xFF5C8DF6)),
    WARM_PAPER("暖砂纸纹", Color(0xFFC88A55)),
}

@Composable
private fun WallpaperProtoTheme(
    mode: InfinityThemeMode,
    accentSeed: Color? = null,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = accentSeed?.value?.toLong() ?: InfinityThemePreferences().primaryColor,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

@Composable
private fun ProtoWallpaperArtwork(
    kind: ProtoWallpaperKind,
    dimFraction: Float,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (kind) {
                ProtoWallpaperKind.NONE -> {
                    drawRect(if (dark) Color(0xFF000000) else Color(0xFFFAF9FB))
                }

                ProtoWallpaperKind.CUSTOM_PHOTO -> {
                    // 模拟高质感黄昏山脊 + 湖面星辉实拍图，用来真实检验照片壁纸上的气泡/文字可读性
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0B132B),
                                Color(0xFF1C2541),
                                Color(0xFF3A506B),
                                Color(0xFF6B4E71),
                                Color(0xFF1A1E33),
                            ),
                        ),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x99F6AE65), Color(0x33D9777F), Color.Transparent),
                            center = Offset(w * 0.72f, h * 0.34f),
                            radius = w * 0.65f,
                        ),
                        radius = w * 0.65f,
                        center = Offset(w * 0.72f, h * 0.34f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x665BC0BE), Color.Transparent),
                            center = Offset(w * 0.22f, h * 0.22f),
                            radius = w * 0.55f,
                        ),
                        radius = w * 0.55f,
                        center = Offset(w * 0.22f, h * 0.22f),
                    )
                    // 远山轮廓
                    val farRidge = Path().apply {
                        moveTo(0f, h * 0.62f)
                        cubicTo(w * 0.22f, h * 0.48f, w * 0.42f, h * 0.57f, w * 0.65f, h * 0.49f)
                        cubicTo(w * 0.82f, h * 0.43f, w * 0.92f, h * 0.52f, w, h * 0.56f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(farRidge, Color(0xCC161B33))
                    // 近山剪影
                    val nearRidge = Path().apply {
                        moveTo(0f, h * 0.74f)
                        cubicTo(w * 0.28f, h * 0.64f, w * 0.54f, h * 0.76f, w * 0.78f, h * 0.66f)
                        cubicTo(w * 0.90f, h * 0.61f, w * 0.96f, h * 0.66f, w, h * 0.70f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(nearRidge, Color(0xE60B0F1F))
                    // 零星光点（城市微光 / 星点）
                    val stars = listOf(
                        Offset(0.16f, 0.14f), Offset(0.34f, 0.19f), Offset(0.58f, 0.12f),
                        Offset(0.81f, 0.17f), Offset(0.45f, 0.27f), Offset(0.26f, 0.31f),
                    )
                    stars.forEachIndexed { idx, p ->
                        drawCircle(
                            color = Color.White.copy(alpha = if (idx % 2 == 0) 0.55f else 0.30f),
                            radius = if (idx % 2 == 0) 2.2f else 1.5f,
                            center = Offset(w * p.x, h * p.y),
                        )
                    }
                }

                ProtoWallpaperKind.AURORA -> {
                    drawRect(Color(0xFF060913))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x883B82F6), Color(0x222563EB), Color.Transparent),
                            center = Offset(w * 0.20f, h * 0.18f),
                            radius = w * 0.85f,
                        ),
                        radius = w * 0.85f,
                        center = Offset(w * 0.20f, h * 0.18f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x702DD4BF), Color(0x1814B8A6), Color.Transparent),
                            center = Offset(w * 0.86f, h * 0.46f),
                            radius = w * 0.78f,
                        ),
                        radius = w * 0.78f,
                        center = Offset(w * 0.86f, h * 0.46f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x557C3AED), Color.Transparent),
                            center = Offset(w * 0.32f, h * 0.85f),
                            radius = w * 0.72f,
                        ),
                        radius = w * 0.72f,
                        center = Offset(w * 0.32f, h * 0.85f),
                    )
                }

                ProtoWallpaperKind.OBSIDIAN_TOPO -> {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF0B0D14), Color(0xFF111522), Color(0xFF090B10)),
                        ),
                    )
                    for (i in 0..8) {
                        val yBase = h * (0.12f + i * 0.10f)
                        val wave = Path().apply {
                            moveTo(0f, yBase)
                            var x = 0f
                            val step = (w / 24f).coerceAtLeast(8f)
                            while (x <= w + step) {
                                val phase = (x / w) * 2f * PI.toFloat() + i * 0.55f
                                val dy = sin(phase) * (12f + i * 2.2f)
                                lineTo(x, yBase + dy)
                                x += step
                            }
                        }
                        drawPath(
                            path = wave,
                            color = Color(0xFF94A3B8).copy(alpha = 0.085f),
                            style = Stroke(width = 1.2f, cap = StrokeCap.Round),
                        )
                    }
                }

                ProtoWallpaperKind.PINE_MIST -> {
                    drawRect(Color(0xFF071316))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x7714B8A6), Color.Transparent),
                            center = Offset(w * 0.78f, h * 0.22f),
                            radius = w * 0.82f,
                        ),
                        radius = w * 0.82f,
                        center = Offset(w * 0.78f, h * 0.22f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x660F766E), Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.78f),
                            radius = w * 0.78f,
                        ),
                        radius = w * 0.78f,
                        center = Offset(w * 0.18f, h * 0.78f),
                    )
                }

                ProtoWallpaperKind.AMBER_DUSK -> {
                    drawRect(Color(0xFF120B08))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x78D97706), Color(0x2292400E), Color.Transparent),
                            center = Offset(w * 0.76f, h * 0.24f),
                            radius = w * 0.80f,
                        ),
                        radius = w * 0.80f,
                        center = Offset(w * 0.76f, h * 0.24f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x55BE185D), Color.Transparent),
                            center = Offset(w * 0.20f, h * 0.80f),
                            radius = w * 0.72f,
                        ),
                        radius = w * 0.72f,
                        center = Offset(w * 0.20f, h * 0.80f),
                    )
                }

                ProtoWallpaperKind.ABYSSAL_WAVE -> {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF060E21), Color(0xFF0C1C3D), Color(0xFF050A18)),
                        ),
                    )
                    for (layer in 0..3) {
                        val base = h * (0.42f + layer * 0.14f)
                        val p = Path().apply {
                            moveTo(0f, base)
                            cubicTo(
                                w * 0.30f, base - 38f + layer * 8f,
                                w * 0.68f, base + 42f - layer * 6f,
                                w, base - 10f,
                            )
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(p, Color(0xFF3B82F6).copy(alpha = 0.08f + layer * 0.03f))
                    }
                }

                ProtoWallpaperKind.WARM_PAPER -> {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFF7F2EA), Color(0xFFEFE7DC), Color(0xFFF6F1EA)),
                        ),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x66E7C9A9), Color.Transparent),
                            center = Offset(w * 0.82f, h * 0.18f),
                            radius = w * 0.76f,
                        ),
                        radius = w * 0.76f,
                        center = Offset(w * 0.82f, h * 0.18f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x50CBD5E1), Color.Transparent),
                            center = Offset(w * 0.16f, h * 0.78f),
                            radius = w * 0.74f,
                        ),
                        radius = w * 0.74f,
                        center = Offset(w * 0.16f, h * 0.78f),
                    )
                    for (i in 0..6) {
                        val yBase = h * (0.16f + i * 0.12f)
                        val line = Path().apply {
                            moveTo(0f, yBase)
                            cubicTo(w * 0.35f, yBase - 14f, w * 0.7f, yBase + 16f, w, yBase - 4f)
                        }
                        drawPath(line, Color(0xFF9C7E63).copy(alpha = 0.09f), style = Stroke(width = 1.1f))
                    }
                }
            }
        }
        // 可读性护航遮罩：深色用黑曜石压暗，浅色用柔白雾面提亮
        if (kind != ProtoWallpaperKind.NONE && dimFraction > 0f) {
            val scrimColor = if (dark) InfinityObsidianGlassTint.copy(alpha = dimFraction)
            else Color(0xFFF8F7F5).copy(alpha = dimFraction)
            Box(Modifier.fillMaxSize().background(scrimColor))
        }
    }
}

@Composable
private fun ProtoMiniChatPreview(
    kind: ProtoWallpaperKind,
    dimFraction: Float,
    blurDp: Int,
    assistantCard: Boolean,
    dark: Boolean,
    height: Dp = 236.dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.6f)
    Surface(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, borderColor),
        color = scheme.background,
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        Box(Modifier.fillMaxSize()) {
            ProtoWallpaperArtwork(
                kind = kind,
                dimFraction = dimFraction,
                dark = dark,
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.fillMaxSize()) {
                // 迷你毛玻璃顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (dark) InfinityObsidianGlassTint.copy(alpha = 0.72f)
                            else InfinityPureWhite.copy(alpha = 0.76f),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "建筑摄影与材质笔记",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (dark) InfinityPureWhite.copy(alpha = 0.10f) else scheme.onSurface.copy(alpha = 0.06f),
                    ) {
                        Text(
                            text = if (blurDp > 0) "模糊 ${blurDp}dp · 遮罩 ${(dimFraction * 100).toInt()}%" else "遮罩 ${(dimFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                // 对话预览区
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 用户消息气泡
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f)
                            else scheme.primaryContainer.copy(alpha = 0.88f),
                            border = BorderStroke(
                                0.5.dp,
                                if (dark) InfinityPureWhite.copy(alpha = 0.11f) else scheme.outlineVariant.copy(alpha = 0.4f),
                            ),
                        ) {
                            Text(
                                text = "把这张雾港夜景设为壁纸后，文字还能看清吗？",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                color = if (dark) scheme.onSurface else scheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }

                    // 助手消息（有衬底 vs 无衬底）
                    if (assistantCard) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (dark) InfinityObsidianDialogContainer.copy(alpha = 0.76f)
                            else InfinityPureWhite.copy(alpha = 0.80f),
                            border = BorderStroke(
                                0.5.dp,
                                if (dark) InfinityPureWhite.copy(alpha = 0.09f) else scheme.outlineVariant.copy(alpha = 0.45f),
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "已开启半透阅读衬底与暗幕护航，正文、行内代码与审批卡片均维持高对比度。",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "关闭衬底后正文直接悬浮于极简底纹之上，适合低干扰渐变预设。",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                // 迷你毛玻璃悬浮输入浮岛
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.76f)
                        else InfinityPureWhite.copy(alpha = 0.84f),
                        border = BorderStroke(
                            0.8.dp,
                            if (dark) InfinityPureWhite.copy(alpha = 0.14f) else scheme.outlineVariant.copy(alpha = 0.55f),
                        ),
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "输入消息...",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = scheme.onSurfaceVariant,
                            )
                            Surface(
                                shape = CircleShape,
                                color = scheme.primary,
                                modifier = Modifier.size(22.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = null,
                                        tint = scheme.onPrimary,
                                        modifier = Modifier.size(12.dp),
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

@Composable
private fun WallpaperSourceGallery(
    selected: ProtoWallpaperKind,
    dark: Boolean,
    horizontalPadding: Dp = 16.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. 关闭（纯净背景）
        WallpaperGalleryTile(
            label = "纯净",
            selected = selected == ProtoWallpaperKind.NONE,
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (dark) Color(0xFF0A0B0F) else Color(0xFFF2F3F6)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Block,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        // 2. 相册导入 / 当前相册图
        WallpaperGalleryTile(
            label = if (selected == ProtoWallpaperKind.CUSTOM_PHOTO) "相册图片" else "相册导入",
            selected = selected == ProtoWallpaperKind.CUSTOM_PHOTO,
            content = {
                if (selected == ProtoWallpaperKind.CUSTOM_PHOTO) {
                    Box(Modifier.fillMaxSize()) {
                        ProtoWallpaperArtwork(
                            kind = ProtoWallpaperKind.CUSTOM_PHOTO,
                            dimFraction = 0.15f,
                            dark = dark,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            shape = CircleShape,
                            color = InfinityObsidianGlassTint.copy(alpha = 0.65f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    tint = InfinityPureWhite,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(scheme.primaryContainer.copy(alpha = if (dark) 0.35f else 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            },
        )
        // 3. 六套程序化官方预设（浅色下把「暖砂纸纹」前置，便于首屏即见）
        val presets = if (dark) {
            listOf(
                ProtoWallpaperKind.AURORA,
                ProtoWallpaperKind.OBSIDIAN_TOPO,
                ProtoWallpaperKind.PINE_MIST,
                ProtoWallpaperKind.AMBER_DUSK,
                ProtoWallpaperKind.ABYSSAL_WAVE,
                ProtoWallpaperKind.WARM_PAPER,
            )
        } else {
            listOf(
                ProtoWallpaperKind.WARM_PAPER,
                ProtoWallpaperKind.AURORA,
                ProtoWallpaperKind.OBSIDIAN_TOPO,
                ProtoWallpaperKind.PINE_MIST,
                ProtoWallpaperKind.AMBER_DUSK,
                ProtoWallpaperKind.ABYSSAL_WAVE,
            )
        }
        presets.forEach { preset ->
            WallpaperGalleryTile(
                label = preset.label,
                selected = selected == preset,
                content = {
                    ProtoWallpaperArtwork(
                        kind = preset,
                        dimFraction = 0f,
                        dark = dark,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }
}

@Composable
private fun WallpaperGalleryTile(
    label: String,
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(68.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 68.dp, height = 92.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (selected) 2.dp else 0.8.dp,
                    color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            content()
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = scheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 11.5.sp,
            ),
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WallpaperSliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    valueText: String,
    value: Float,
    enabled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace),
                color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
        Slider(
            value = value,
            onValueChange = {},
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.surfaceContainerHighest,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun WallpaperStudioControlsColumn(
    selected: ProtoWallpaperKind,
    blurDp: Int,
    dimFraction: Float,
    assistantCard: Boolean,
    dark: Boolean,
    horizontalPadding: Dp = 16.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val enabled = selected != ProtoWallpaperKind.NONE
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. 壁纸来源
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "壁纸来源",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                )
                if (selected == ProtoWallpaperKind.CUSTOM_PHOTO) {
                    FilledTonalButton(
                        onClick = {},
                        shape = CircleShape,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("更换图片", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            WallpaperSourceGallery(selected = selected, dark = dark, horizontalPadding = horizontalPadding)
        }

        // 2. 可读性与视觉调节
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "可读性调节",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
            )
            BettboxListGroup {
                WallpaperSliderRow(
                    icon = Icons.Rounded.BlurOn,
                    title = "背景模糊",
                    valueText = "${blurDp}dp",
                    value = blurDp / 24f,
                    enabled = enabled,
                )
                InfinityGroupDivider()
                WallpaperSliderRow(
                    icon = Icons.Rounded.Contrast,
                    title = if (dark) "压暗遮罩" else "柔光遮罩",
                    valueText = "${(dimFraction * 100).toInt()}%",
                    value = dimFraction / 0.85f,
                    enabled = enabled,
                )
                InfinityGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "助手正文半透衬底",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                        )
                        Text(
                            text = "为回复正文叠加毛玻璃阅读底板，复杂照片下也能保持清晰",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    InfinitySwitch(checked = assistantCard, onCheckedChange = {}, enabled = enabled)
                }
            }
        }

        // 3. 配色联动
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "主题联动",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
            )
            BettboxListGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(selected.extractedAccent.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(selected.extractedAccent)
                                .border(1.dp, InfinityPureWhite.copy(alpha = 0.45f), CircleShape),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "提取壁纸主色为强调色",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                        )
                        Text(
                            text = "自动从当前壁纸取色并同步全局按钮与光效",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = {},
                        enabled = enabled,
                        shape = CircleShape,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Rounded.Colorize, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("应用", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // 无框排印脚注
            Text(
                text = "开启壁纸后会话底板将替换纯黑背景；代码块、工具卡与审批卡片维持高对比实底。模糊效果将在本地预烘焙缓存，不影响滑动帧率。",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ProtoWallpaperStudioScreen(
    mode: InfinityThemeMode,
    selected: ProtoWallpaperKind,
    blurDp: Int,
    dimFraction: Float,
    assistantCard: Boolean,
) {
    val dark = mode == InfinityThemeMode.DARK
    WallpaperProtoTheme(mode = mode, accentSeed = selected.extractedAccent) {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = "聊天壁纸",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                FilledTonalButton(
                    onClick = {},
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("重置", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 顶部实时会话预览卡
            ProtoMiniChatPreview(
                kind = selected,
                dimFraction = dimFraction,
                blurDp = blurDp,
                assistantCard = assistantCard,
                dark = dark,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Spacer(Modifier.height(14.dp))
            WallpaperStudioControlsColumn(
                selected = selected,
                blurDp = blurDp,
                dimFraction = dimFraction,
                assistantCard = assistantCard,
                dark = dark,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProtoFullChatWithWallpaper(
    mode: InfinityThemeMode,
    kind: ProtoWallpaperKind,
    dimFraction: Float,
    assistantCard: Boolean,
) {
    val dark = mode == InfinityThemeMode.DARK
    WallpaperProtoTheme(mode = mode, accentSeed = kind.extractedAccent) {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize()) {
            // 全屏壁纸底板
            ProtoWallpaperArtwork(
                kind = kind,
                dimFraction = dimFraction,
                dark = dark,
                modifier = Modifier.fillMaxSize(),
            )

            // 对话正文流
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp, bottom = 92.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 用户气泡 1
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f)
                        else scheme.primaryContainer.copy(alpha = 0.90f),
                        border = BorderStroke(
                            0.5.dp,
                            if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.widthIn(max = 290.dp),
                    ) {
                        Text(
                            text = "帮我把这套毛玻璃聊天壁纸的层次感拆解一下，既要通透又不能影响读代码。",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = if (dark) scheme.onSurface else scheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                        )
                    }
                }

                // 助手回复（含 Markdown + 高对比实底代码块）
                val assistantBody: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "核心是「三层视觉分工」：壁纸负责氛围，遮罩与衬底负责对比度，代码块与审批卡保持高不透明实底。",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = scheme.onSurface,
                        )
                        // 代码块始终保持实底清晰对比
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (dark) Color(0xF00B0D12) else Color(0xF5F4F5F7),
                            border = BorderStroke(
                                0.6.dp,
                                if (dark) InfinityPureWhite.copy(alpha = 0.10f) else scheme.outlineVariant.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "kotlin",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    text = "val scrim = if (dark) 0.48f else 0.42f\nval blur = bakeBlurCache(photo, radius = 8.dp)",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = InfinityMonospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    ),
                                    color = scheme.onSurface,
                                )
                            }
                        }
                        // 底部极简操作行
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Text(
                                text = "Gemini 3.8 Flash · 刚刚",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (assistantCard) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (dark) InfinityObsidianDialogContainer.copy(alpha = 0.78f)
                        else InfinityPureWhite.copy(alpha = 0.82f),
                        border = BorderStroke(
                            0.6.dp,
                            if (dark) InfinityPureWhite.copy(alpha = 0.09f) else scheme.outlineVariant.copy(alpha = 0.45f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                            assistantBody()
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                        assistantBody()
                    }
                }

                // 用户气泡 2
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f)
                        else scheme.primaryContainer.copy(alpha = 0.90f),
                        border = BorderStroke(
                            0.5.dp,
                            if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text(
                            text = "挺高级的，而且看图灯箱还能一键把 AI 生成的图设为壁纸。",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = if (dark) scheme.onSurface else scheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // 顶部熏黑/冷白毛玻璃顶栏
            Surface(
                color = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.74f) else InfinityPureWhite.copy(alpha = 0.78f),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(
                                text = "建筑摄影与材质笔记",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Gemini 3.8 Flash · 工作区就绪",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(19.dp))
                        }
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(19.dp))
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(if (dark) InfinityPureWhite.copy(alpha = 0.10f) else scheme.outlineVariant.copy(alpha = 0.45f)),
                    )
                }
            }

            // 底部熏黑/冷白毛玻璃悬浮输入浮岛
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.78f) else InfinityPureWhite.copy(alpha = 0.84f),
                border = BorderStroke(
                    0.9.dp,
                    if (dark) InfinityPureWhite.copy(alpha = 0.14f) else scheme.outlineVariant.copy(alpha = 0.55f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.surfaceContainerHigh,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        text = "向 Infinity Deck 提问...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = CircleShape,
                        color = scheme.primary,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoFoldableWideAndEntryBoard() {
    val kind = ProtoWallpaperKind.CUSTOM_PHOTO
    WallpaperProtoTheme(mode = InfinityThemeMode.DARK, accentSeed = kind.extractedAccent) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 上部：展示「设置 → 外观」里的壁纸入口行样式
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "入口预览：设置 → 外观",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.primary,
                )
                BettboxListGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(0.8.dp, InfinityPureWhite.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                        ) {
                            ProtoWallpaperArtwork(kind = kind, dimFraction = 0.30f, dark = true, modifier = Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "聊天壁纸",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = "自定义图片 · 模糊 8dp · 遮罩 48%",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = scheme.primaryContainer.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = "已开启",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = scheme.primary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 下部：折叠屏内屏（>=600dp）双栏壁纸工作室
            Text(
                text = "折叠屏宽屏自适应：左栏常驻实时预览 + 右栏参数调节",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.primary,
            )
            Surface(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, InfinityPureWhite.copy(alpha = 0.10f)),
                color = scheme.background,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Wallpaper, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                text = "实时对话效果",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                            )
                        }
                        ProtoMiniChatPreview(
                            kind = kind,
                            dimFraction = 0.48f,
                            blurDp = 8,
                            assistantCard = true,
                            dark = true,
                            height = 460.dp,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    ) {
                        WallpaperStudioControlsColumn(
                            selected = kind,
                            blurDp = 8,
                            dimFraction = 0.48f,
                            assistantCard = true,
                            dark = true,
                            horizontalPadding = 0.dp,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "1_Studio_Dark_Photo", widthDp = 390, heightDp = 960, showBackground = true)
@Composable
fun WallpaperStudioDarkPreview() {
    ProtoWallpaperStudioScreen(
        mode = InfinityThemeMode.DARK,
        selected = ProtoWallpaperKind.CUSTOM_PHOTO,
        blurDp = 8,
        dimFraction = 0.48f,
        assistantCard = true,
    )
}

@Preview(name = "2_Studio_Light_Preset", widthDp = 390, heightDp = 960, showBackground = true)
@Composable
fun WallpaperStudioLightPreview() {
    ProtoWallpaperStudioScreen(
        mode = InfinityThemeMode.LIGHT,
        selected = ProtoWallpaperKind.WARM_PAPER,
        blurDp = 0,
        dimFraction = 0.25f,
        assistantCard = true,
    )
}

@Preview(name = "3_Chat_Dark_PhotoCard", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun WallpaperChatDarkPhotoPreview() {
    ProtoFullChatWithWallpaper(
        mode = InfinityThemeMode.DARK,
        kind = ProtoWallpaperKind.CUSTOM_PHOTO,
        dimFraction = 0.48f,
        assistantCard = true,
    )
}

@Preview(name = "4_Chat_Dark_AuroraEditorial", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun WallpaperChatDarkMinimalPreview() {
    ProtoFullChatWithWallpaper(
        mode = InfinityThemeMode.DARK,
        kind = ProtoWallpaperKind.AURORA,
        dimFraction = 0.28f,
        assistantCard = false,
    )
}

@Preview(name = "5_Chat_Light_WarmPaper", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun WallpaperChatLightPreview() {
    ProtoFullChatWithWallpaper(
        mode = InfinityThemeMode.LIGHT,
        kind = ProtoWallpaperKind.WARM_PAPER,
        dimFraction = 0.22f,
        assistantCard = true,
    )
}

@Preview(name = "6_ThemeEntry_And_FoldableWide", widthDp = 840, heightDp = 830, showBackground = true)
@Composable
fun WallpaperThemeEntryAndWidePreview() {
    ProtoFoldableWideAndEntryBoard()
}
