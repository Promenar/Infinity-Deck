package com.promenar.infinity.ui.pet

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 绿幕抠像：按绿色占优程度算透明度并去掉溢色。 */
private const val CHROMA_GREEN_SHADER = """
uniform shader content;
half4 main(float2 p) {
    half4 c = content.eval(p);
    float d = c.g - max(c.r, c.b);
    float a = 1.0 - smoothstep(0.16, 0.47, d);
    float g = d > 0.0 ? max(c.r, c.b) : c.g;
    return half4(c.r * a, g * a, c.b * a, a);
}
"""

/** 左右拼接：左半是颜色，右半的亮度是透明度。 */
private const val SIDE_BY_SIDE_SHADER = """
uniform shader content;
uniform float half_width;
half4 main(float2 p) {
    half4 c = content.eval(p);
    float a = content.eval(float2(p.x + half_width, p.y)).r;
    return half4(c.rgb * a, a);
}
"""

/**
 * 播放视频片段（规格第 3.1 节）。视频渲染到 [TextureView]，再用着色器换出透明通道，
 * 所以只在 Android 13 及以上使用；更低版本由画布播放替补片段。
 *
 * 视频不做网格变形，只跟随宠物的位置。首帧渲染出来之前画布继续画替补片段。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun PetVideoLayer(runtime: PetRuntime, request: PetVideoRequest) {
    val context = LocalContext.current
    val clip = request.clip
    val sideBySide = clip.alpha == "side_by_side"
    val file = remember(request) { File(runtime.pet.directory, clip.file) }
    var aspect by remember(request) { mutableStateOf(1f) }
    val player = remember(request) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = if (clip.loop != false) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                runtime.videoReady = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val visibleWidth = if (sideBySide) videoSize.width / 2f else videoSize.width.toFloat()
                aspect = visibleWidth / videoSize.height
            }
        }
        player.addListener(listener)
        onDispose {
            runtime.videoReady = false
            player.removeListener(listener)
            player.release()
        }
    }
    val shader = remember(sideBySide) { RuntimeShader(if (sideBySide) SIDE_BY_SIDE_SHADER else CHROMA_GREEN_SHADER) }
    val density = context.resources.displayMetrics.density
    val height = runtime.pet.manifest.display.heightDp * clip.scale.toFloat() * density
    val visibleWidth = max(1f, height * aspect)
    val textureWidth = if (sideBySide) visibleWidth * 2f else visibleWidth
    val anchorU = clip.anchor.getOrNull(0)?.toFloat() ?: 0.5f
    val anchorV = clip.anchor.getOrNull(1)?.toFloat() ?: 1f
    Box(
        Modifier
            .layout { measurable, _ ->
                runtime.frame
                val placeable = measurable.measure(Constraints.fixed(visibleWidth.roundToInt(), height.roundToInt()))
                val left = runtime.headX - anchorU * visibleWidth
                val top = runtime.feetY - anchorV * height
                layout(0, 0) { placeable.place(left.roundToInt(), top.roundToInt()) }
            }
            .clipToBounds(),
    ) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    isOpaque = false
                    player.setVideoTextureView(this)
                }
            },
            modifier = Modifier
                .layout { measurable, _ ->
                    // 左右拼接时纹理是可见区域的两倍宽，右半被外层裁掉。
                    val placeable = measurable.measure(Constraints.fixed(textureWidth.roundToInt(), height.roundToInt()))
                    layout(visibleWidth.roundToInt(), height.roundToInt()) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    if (sideBySide) shader.setFloatUniform("half_width", visibleWidth)
                    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                },
        )
    }
}
