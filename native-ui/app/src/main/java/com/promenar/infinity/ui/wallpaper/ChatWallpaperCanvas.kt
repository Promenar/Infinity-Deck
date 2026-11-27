package com.promenar.infinity.ui.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

private val LightWallpaperScrimColor = Color(0xFFF8F7F5)

/**
 * 全站复用的聊天壁纸渲染层（对话底板、壁纸工作室实时预览、外观入口缩略图共用）。
 * - 预设壁纸通过零体积 Canvas 程序化绘制；
 * - 图片壁纸通过 [WallpaperAssetStore] 读取本地预烘焙模糊缓存文件，保证滑动帧率；
 * - 未来扩展动态着色器、循环视频或宠物场景时，直接在此处扩展 [WallpaperSource] 分支即可。
 */
@Composable
fun ChatWallpaperLayer(
    config: ChatWallpaperConfig,
    dark: Boolean,
    modifier: Modifier = Modifier,
    applyScrim: Boolean = true,
    customImageFileOverride: File? = null,
) {
    if (!config.isActive) return
    val blurDp = config.clampedBlurDp
    val dim = config.clampedDimFraction

    Box(modifier = modifier.clearAndSetSemantics {}) {
        when (val source = config.source) {
            WallpaperSource.None -> Unit

            is WallpaperSource.Preset -> {
                val blurMod = if (blurDp > 0) Modifier.blur(blurDp.dp) else Modifier
                ProceduralWallpaperPresetArtwork(
                    preset = source.id,
                    modifier = Modifier.fillMaxSize().then(blurMod),
                )
            }

            is WallpaperSource.Image -> {
                val context = LocalContext.current
                val inspection = LocalInspectionMode.current
                val resolvedFile by produceState<File?>(
                    initialValue = customImageFileOverride,
                    key1 = source.fileName,
                    key2 = blurDp,
                    key3 = customImageFileOverride,
                ) {
                    if (customImageFileOverride != null) {
                        value = customImageFileOverride
                    } else if (!inspection) {
                        val app = context.applicationContext as? InfinityApplication
                        // 未完成烘焙前先回退原图，消除切图时的黑屏闪动。
                        value = app?.wallpaperAssetStore?.resolveOriginalFile(source.fileName)
                        val baked = app?.wallpaperAssetStore?.resolveDisplayFile(source.fileName, blurDp)
                        if (baked != null) value = baked
                    }
                }
                if (resolvedFile != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(resolvedFile)
                            .crossfade(180)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ProceduralWallpaperPresetArtwork(
                        preset = WallpaperPresetId.AURORA,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (applyScrim && dim > 0f) {
            val scrimColor = if (dark) InfinityObsidianGlassTint.copy(alpha = dim)
            else LightWallpaperScrimColor.copy(alpha = dim)
            Box(Modifier.fillMaxSize().background(scrimColor))
        }
    }
}

/** 六套零包体程序化美学壁纸，支持任意折叠屏内外屏比例无损自适应。 */
@Composable
fun ProceduralWallpaperPresetArtwork(
    preset: WallpaperPresetId,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (preset) {
            WallpaperPresetId.AURORA -> {
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

            WallpaperPresetId.OBSIDIAN_TOPO -> {
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

            WallpaperPresetId.PINE_MIST -> {
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

            WallpaperPresetId.AMBER_DUSK -> {
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

            WallpaperPresetId.ABYSSAL_WAVE -> {
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

            WallpaperPresetId.WARM_PAPER -> {
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
}
