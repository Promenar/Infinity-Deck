package com.promenar.infinity.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import com.promenar.infinity.ui.wallpaper.ProceduralWallpaperPresetArtwork
import com.promenar.infinity.ui.wallpaper.WallpaperPresetId

/*
 * 壁纸工作室 v2 原型（隔离于 debug 源码）：
 * 1. 修复折叠屏内屏「无脑全屏」：纳入 SettingsPaneRouteBases，随设置右列竖屏详情栏渲染，左侧保留设置目录；
 * 2. 外部图片完整构图与内外屏双适配：支持直接双指缩放/拖动焦点 +「外屏竖屏 / 内屏展开」双画幅实时切换与独立保存构图；
 * 3. 已导入图片库管理：支持保留多张已导入壁纸、一键切换与单张安全删除（含确认弹窗与模糊缓存连带清理）；
 * 4. 底层导入修复：minSdk 31 统一走 ImageDecoder 单次解码（原生支持 HEIC/AVIF/ProXDR/EXIF）+ ARGB_8888 规范化落盘。
 */

private enum class ProtoScreenAspect { OUTER_PORTRAIT, INNER_WIDE }

@Composable
private fun WallpaperV2Shell(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

/** 模拟导入的夜景摄影作品，便于在静态预览中准确评估人像/主体构图、裁切与文字可读性。 */
@Composable
private fun ProtoSamplePhotoArtwork(
    variant: Int,
    zoom: Float = 1f,
    focusOffsetX: Float = 0f,
    focusOffsetY: Float = 0f,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * (0.5f + focusOffsetX * 0.25f)
        val cy = h * (0.44f + focusOffsetY * 0.25f)
        if (variant == 0) {
            // 雨夜灯笼与湖光倒影
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1424), Color(0xFF132238), Color(0xFF0A1019)),
                ),
            )
            // 远山剪影
            val ridge = Path().apply {
                moveTo(0f, h * 0.62f)
                cubicTo(w * 0.28f, h * 0.46f, w * 0.62f, h * 0.54f, w, h * 0.44f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(ridge, Color(0xFF0E1828))
            // 暖金灯笼主光斑
            val glowR = w * 0.42f * zoom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xD9F59E0B), Color(0x55EA580C), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = glowR,
                ),
                radius = glowR,
                center = Offset(cx, cy),
            )
            // 提灯本体
            drawRoundRect(
                color = Color(0xFFFDE68A),
                topLeft = Offset(cx - 10f * zoom, cy - 14f * zoom),
                size = Size(20f * zoom, 28f * zoom),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
            // 青色湖面反光
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x552DD4BF), Color.Transparent),
                    center = Offset(cx + w * 0.14f, h * 0.80f),
                    radius = w * 0.45f,
                ),
                radius = w * 0.45f,
                center = Offset(cx + w * 0.14f, h * 0.80f),
            )
        } else {
            // 晨雾山谷（第二张已导入照片）
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF1F2D3D), Color(0xFF3E5668), Color(0xFF1B2430)),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xBBFDBA74), Color(0x33FB923C), Color.Transparent),
                    center = Offset(w * 0.72f, h * 0.28f),
                    radius = w * 0.48f,
                ),
                radius = w * 0.48f,
                center = Offset(w * 0.72f, h * 0.28f),
            )
            val hill = Path().apply {
                moveTo(0f, h * 0.70f)
                cubicTo(w * 0.35f, h * 0.55f, w * 0.72f, h * 0.68f, w, h * 0.56f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(hill, Color(0xFF16202C))
        }
    }
}

@Composable
private fun ProtoAspectSwitcher(
    selected: ProtoScreenAspect,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.6.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier.height(32.dp),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(
                Triple(ProtoScreenAspect.OUTER_PORTRAIT, Icons.Rounded.PhoneAndroid, "外屏竖屏"),
                Triple(ProtoScreenAspect.INNER_WIDE, Icons.Rounded.TabletAndroid, "内屏展开"),
            ).forEach { (aspect, icon, label) ->
                val active = aspect == selected
                Surface(
                    shape = CircleShape,
                    color = if (active) scheme.primaryContainer else Color.Transparent,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoInteractiveWallpaperStage(
    aspect: ProtoScreenAspect,
    dark: Boolean,
    showBubbles: Boolean,
    zoomLabel: String,
    blurDp: Int,
    dimPercent: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val stageBg = if (dark) Color(0xFF0A0C10) else scheme.surfaceContainerLow
    val frameBorder = if (dark) InfinityPureWhite.copy(alpha = 0.16f) else scheme.outlineVariant.copy(alpha = 0.65f)
    val dimOverlay = if (dark) InfinityObsidianGlassTint.copy(alpha = dimPercent / 100f)
    else Color(0xFFF8F7F5).copy(alpha = dimPercent / 100f)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = stageBg,
        border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶栏：内外屏画幅切换 + 气泡显示开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ProtoAspectSwitcher(selected = aspect)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        onClick = {},
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(
                            imageVector = if (showBubbles) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (showBubbles) "纯构图" else "显气泡",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    FilledTonalButton(
                        onClick = {},
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(Icons.Rounded.CenterFocusStrong, null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("居中", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 中央实比例取景画布：外屏窄竖框 vs 内屏方屏框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(234.dp),
                contentAlignment = Alignment.Center,
            ) {
                val frameRatio = if (aspect == ProtoScreenAspect.OUTER_PORTRAIT) 0.56f else 1.04f
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.2.dp, frameBorder),
                    color = scheme.background,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(frameRatio),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ProtoSamplePhotoArtwork(
                            variant = 0,
                            zoom = if (aspect == ProtoScreenAspect.OUTER_PORTRAIT) 1.25f else 1.10f,
                            focusOffsetX = if (aspect == ProtoScreenAspect.OUTER_PORTRAIT) 0.08f else -0.05f,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.fillMaxSize().background(dimOverlay))

                        if (!showBubbles) {
                            // 三分构图辅助线 + 焦点十字
                            Canvas(Modifier.fillMaxSize()) {
                                val lineColor = InfinityPureWhite.copy(alpha = 0.24f)
                                drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1f)
                                drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1f)
                                drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1f)
                                drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1f)
                                // 中央焦点圈
                                val center = Offset(size.width * 0.52f, size.height * 0.44f)
                                drawCircle(InfinityPureWhite.copy(alpha = 0.85f), radius = 14f, center = center, style = Stroke(2f))
                                drawCircle(InfinityPureWhite, radius = 3.5f, center = center)
                            }
                        } else {
                            // 模拟真实对话顶栏、气泡与输入浮岛
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (dark) InfinityObsidianGlassTint.copy(alpha = 0.72f)
                                            else InfinityPureWhite.copy(alpha = 0.78f),
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "对话预览",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${blurDp}dp · $dimPercent%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        Surface(
                                            shape = RoundedCornerShape(11.dp),
                                            color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f)
                                            else scheme.primaryContainer.copy(alpha = 0.90f),
                                        ) {
                                            Text(
                                                text = "换一张夜景壁纸试试",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                                color = if (dark) scheme.onSurface else scheme.onPrimaryContainer,
                                                maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(11.dp),
                                        color = if (dark) InfinityObsidianDialogContainer.copy(alpha = 0.78f)
                                        else InfinityPureWhite.copy(alpha = 0.84f),
                                        border = BorderStroke(
                                            0.5.dp,
                                            if (dark) InfinityPureWhite.copy(alpha = 0.10f)
                                            else scheme.outlineVariant.copy(alpha = 0.45f),
                                        ),
                                    ) {
                                        Text(
                                            text = "叠加轻雾衬底，复杂照片下依然清晰。",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 13.sp),
                                            color = scheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.76f)
                                        else InfinityPureWhite.copy(alpha = 0.84f),
                                        border = BorderStroke(0.6.dp, frameBorder),
                                        modifier = Modifier.fillMaxWidth().height(24.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text("输入消息…", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = scheme.onSurfaceVariant)
                                            Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(16.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.AutoMirrored.Rounded.Send, null, tint = scheme.onPrimary, modifier = Modifier.size(9.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 底部交互手势状态提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.OpenWith, null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                    Text(
                        text = "双指缩放与拖动构图 · 内外屏独立记忆",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = scheme.primary.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = zoomLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace, fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtoWallpaperPortraitStudioPane(
    dark: Boolean,
    aspect: ProtoScreenAspect,
    showBubbles: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 顶栏（与其它设置二级页一致）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
                Text(
                    text = "聊天壁纸",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                )
            }
            FilledTonalButton(
                onClick = {},
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("恢复默认", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 实时构图与内外屏双画幅预览舞台
        ProtoInteractiveWallpaperStage(
            aspect = aspect,
            dark = dark,
            showBubbles = showBubbles,
            zoomLabel = if (aspect == ProtoScreenAspect.OUTER_PORTRAIT) "1.25×" else "1.10×",
            blurDp = 6,
            dimPercent = if (dark) 42 else 36,
        )

        // 我的壁纸来源（支持多张已导入图片切换与删除）
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "壁纸来源",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {},
                    shape = CircleShape,
                    border = BorderStroke(0.8.dp, scheme.error.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("删除当前图", style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 纯净
                ProtoGalleryTile("纯净", selected = false) {
                    Box(Modifier.fillMaxSize().background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Block, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                // 导入入口
                ProtoGalleryTile("相册导入", selected = false) {
                    Box(Modifier.fillMaxSize().background(scheme.primaryContainer.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                // 我的图片 1（当前选中 + 右上角删除角标）
                ProtoGalleryTile("我的图 1", selected = true, showDeleteBadge = true) {
                    ProtoSamplePhotoArtwork(variant = 0, modifier = Modifier.fillMaxSize())
                }
                // 我的图片 2（历史导入保留）
                ProtoGalleryTile("我的图 2", selected = false, showDeleteBadge = false) {
                    ProtoSamplePhotoArtwork(variant = 1, modifier = Modifier.fillMaxSize())
                }
                // 预设壁纸
                listOf(
                    WallpaperPresetId.AURORA to "极夜流光",
                    WallpaperPresetId.OBSIDIAN_TOPO to "黑曜等高",
                    WallpaperPresetId.PINE_MIST to "深林雾松",
                ).forEach { (preset, label) ->
                    ProtoGalleryTile(label, selected = false) {
                        ProceduralWallpaperPresetArtwork(preset, Modifier.fillMaxSize())
                    }
                }
            }
        }

        // 精细调节
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "视觉与可读性",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
            )
            BettboxListGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.BlurOn, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text("背景模糊", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        }
                        Text("6 dp", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.primary)
                    }
                    Slider(
                        value = 6f,
                        onValueChange = {},
                        valueRange = 0f..24f,
                        colors = SliderDefaults.colors(thumbColor = scheme.primary, activeTrackColor = scheme.primary),
                        modifier = Modifier.height(30.dp),
                    )
                }
                InfinityGroupDivider(startInset = 16.dp)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Contrast, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text("阅读遮罩", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        }
                        Text(if (dark) "42%" else "36%", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.primary)
                    }
                    Slider(
                        value = if (dark) 0.42f else 0.36f,
                        onValueChange = {},
                        valueRange = 0f..0.85f,
                        colors = SliderDefaults.colors(thumbColor = scheme.primary, activeTrackColor = scheme.primary),
                        modifier = Modifier.height(30.dp),
                    )
                }
                InfinityGroupDivider(startInset = 16.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("助手消息阅读底托", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                        Text("为长文本回复叠加轻雾衬底，兼顾图片美感与清晰度", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    InfinitySwitch(checked = true, onCheckedChange = {})
                }
                InfinityGroupDivider(startInset = 16.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B))
                            .border(1.5.dp, scheme.outlineVariant, CircleShape),
                    )
                    Column(Modifier.weight(1f)) {
                        Text("从当前壁纸取色", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                        Text("同步为主题强调色", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    FilledTonalButton(
                        onClick = {},
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("应用主色", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoGalleryTile(
    label: String,
    selected: Boolean,
    showDeleteBadge: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.width(66.dp),
    ) {
        Box(modifier = Modifier.size(66.dp)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    width = if (selected) 2.dp else 0.8.dp,
                    color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.55f),
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    if (selected) {
                        Surface(
                            shape = CircleShape,
                            color = scheme.primary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(18.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    if (showDeleteBadge) {
                        Surface(
                            shape = CircleShape,
                            color = InfinityObsidianDialogContainer.copy(alpha = 0.86f),
                            border = BorderStroke(0.6.dp, InfinityPureWhite.copy(alpha = 0.24f)),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(18.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, null, tint = InfinityPureWhite, modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 11.sp,
            ),
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProtoSettingsSidebarItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (selected) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Preview(name = "1_WallpaperV2_FoldableRightPane", widthDp = 820, heightDp = 910, showBackground = true)
@Composable
fun WallpaperV2FoldableRightPanePreview() {
    WallpaperV2Shell(dark = true) {
        val scheme = MaterialTheme.colorScheme
        Row(Modifier.fillMaxSize()) {
            // 折叠屏内屏左列：设置主目录（保持与其它设置页完全一致的双栏体验）
            Column(
                modifier = Modifier
                    .width(290.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("外观与个性", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    ProtoSettingsSidebarItem(Icons.Rounded.Palette, "外观与主题", "深色模式 · 聊天壁纸已开启", selected = true)
                    ProtoSettingsSidebarItem(Icons.Rounded.Pets, "桌面宠物", "已启用 · 交互挂件", selected = false)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("模型与服务", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    ProtoSettingsSidebarItem(Icons.Rounded.Hub, "AI 提供商", "已配置 6 家服务商", selected = false)
                    ProtoSettingsSidebarItem(Icons.Rounded.Tune, "默认模型", "对话、图像与语音", selected = false)
                }
            }

            VerticalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

            // 折叠屏内屏右列：竖屏聊天壁纸详情栏（不再全屏覆盖）
            ProtoWallpaperPortraitStudioPane(
                dark = true,
                aspect = ProtoScreenAspect.OUTER_PORTRAIT,
                showBubbles = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(name = "2_WallpaperV2_InnerAspect_Active", widthDp = 390, heightDp = 910, showBackground = true)
@Composable
fun WallpaperV2InnerAspectActivePreview() {
    WallpaperV2Shell(dark = true) {
        ProtoWallpaperPortraitStudioPane(
            dark = true,
            aspect = ProtoScreenAspect.INNER_WIDE,
            showBubbles = true,
        )
    }
}

@Preview(name = "3_WallpaperV2_Framing_And_Delete", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun WallpaperV2FramingAndDeletePreview() {
    WallpaperV2Shell(dark = true) {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "状态 A：纯构图微调（隐藏气泡 + 三分参考线）",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
            ProtoInteractiveWallpaperStage(
                aspect = ProtoScreenAspect.OUTER_PORTRAIT,
                dark = true,
                showBubbles = false,
                zoomLabel = "1.40×",
                blurDp = 0,
                dimPercent = 20,
            )

            Text(
                text = "状态 B：删除已导入壁纸确认弹层",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.error,
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = InfinityObsidianDialogContainer,
                border = BorderStroke(0.8.dp, InfinityPureWhite.copy(alpha = 0.14f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "删除这张已导入壁纸？",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "将从本机壁纸库移除该图片及其模糊缓存；如正在使用，聊天背景将自动切换为纯净模式。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {},
                            shape = CircleShape,
                            modifier = Modifier.weight(1f).height(42.dp),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = {},
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.error,
                                contentColor = scheme.onError,
                            ),
                            modifier = Modifier.weight(1f).height(42.dp),
                        ) {
                            Text("确认删除")
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "4_WallpaperV2_LightTheme_RightPane", widthDp = 440, heightDp = 910, showBackground = true)
@Composable
fun WallpaperV2LightThemeRightPanePreview() {
    WallpaperV2Shell(dark = false) {
        ProtoWallpaperPortraitStudioPane(
            dark = false,
            aspect = ProtoScreenAspect.OUTER_PORTRAIT,
            showBubbles = true,
        )
    }
}
