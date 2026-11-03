package com.promenar.infinity.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.ImageGenerationActual
import com.promenar.infinity.data.model.ImageGenerationRequested
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.ui.chat.InlineToolRow
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

private const val PREVIEW_SESSION = "preview-session"

private val PreviewImage = MediaRef("/media/2026-10-20/gen-1432-a1b2c3.png", "image/png", MediaKind.IMAGE, 812_345, width = 1600, height = 900)
private val PreviewPortrait = MediaRef("/media/2026-10-20/gen-1433-d4e5f6.png", "image/png", MediaKind.IMAGE, 640_000, width = 900, height = 1600)
private val PreviewSquare = MediaRef("/media/2026-10-20/gen-1434-778899.webp", "image/webp", MediaKind.IMAGE, 320_000, width = 1024, height = 1024)
private val PreviewVideo = MediaRef("/media/2026-10-20/海边延时 摄影.mp4", "video/mp4", MediaKind.VIDEO, 18_400_000, width = 1920, height = 1080, durationMs = 83_000)
private val PreviewAudio = MediaRef("/media/2026-10-20/旁白-第一段 配音试听版本.m4a", "audio/mp4", MediaKind.AUDIO, 1_250_000, durationMs = 154_000)
private val PreviewFile = MediaRef("/docs/2026 第三季度经营分析报告（终版）.pdf", "application/pdf", MediaKind.FILE, 2_621_440)

private val PreviewWide = MediaRef("/media/2026-10-20/gen-1435-aabbcc.png", "image/png", MediaKind.IMAGE, 540_000, width = 1800, height = 1000)
private val PreviewTall = MediaRef("/media/2026-10-20/gen-1436-ddeeff.jpg", "image/jpeg", MediaKind.IMAGE, 610_000, width = 1000, height = 1500)

private val PreviewAvailable = listOf(PreviewImage, PreviewPortrait, PreviewSquare, PreviewVideo, PreviewAudio, PreviewFile, PreviewWide, PreviewTall)
private val PreviewUnavailable = mapOf(
    "/media/2026-10-19/已删除的图片.png" to MediaUnavailableReason.NOT_FOUND,
    "/mnt/相册/失效挂载/a.jpg" to MediaUnavailableReason.UNREADABLE,
)

/** 截图用假解析器：数据来自固定表，不读取任何真实文件；open 与真实实现同样只接受本解析器返回的对象。 */
private object PreviewResolver : WorkspaceMediaResolver {
    private val issued = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MediaResolution.Available, Boolean>())
    override suspend fun resolve(sessionId: String, path: String): MediaResolution = issue(previewResolution(path))
    fun issue(resolution: MediaResolution): MediaResolution = resolution.also { if (it is MediaResolution.Available) synchronized(issued) { issued += it } }
    override suspend fun open(resolution: MediaResolution.Available): InputStream {
        require(synchronized(issued) { resolution in issued }) { "open 只接受 resolve 返回的对象" }
        return ByteArrayInputStream(ByteArray(0))
    }
}

private fun previewResolution(path: String): MediaResolution =
    PreviewAvailable.firstOrNull { it.path == path }
        ?.let { MediaResolution.Available(it, MediaSource.LocalFile(File("/preview$path"))) }
        ?: MediaResolution.Unavailable(path, PreviewUnavailable[path] ?: MediaUnavailableReason.NOT_FOUND)

private fun previewCache(): WorkspaceMediaResolutionCache =
    WorkspaceMediaResolutionCache(revalidateAfterMs = Long.MAX_VALUE).apply {
        (PreviewAvailable.map { it.path } + PreviewUnavailable.keys).forEach { path ->
            val resolution = PreviewResolver.issue(previewResolution(path))
            put(PREVIEW_SESSION, path, ResolvedWorkspaceMedia(resolution, (resolution as? MediaResolution.Available)?.let { "sha256:preview" }))
        }
    }

private fun previewBitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
    Canvas(bitmap).drawRect(
        0f, 0f, width.toFloat(), height.toFloat(),
        Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF2E5C8A.toInt(), 0xFFE0A458.toInt(), Shader.TileMode.CLAMP)
        },
    )
}

@OptIn(ExperimentalCoilApi::class)
@Composable
private fun MediaPreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    val environment = remember { WorkspaceMediaEnvironment(PREVIEW_SESSION, PreviewResolver, previewCache(), onLocate = {}) }
    val handler = remember {
        AsyncImagePreviewHandler { request ->
            val model = request.data as? WorkspaceMediaModel
            val ref = PreviewAvailable.firstOrNull { it.path == model?.path }
            previewBitmap((ref?.width ?: 1600) / 8, (ref?.height ?: 900) / 8).asImage()
        }
    }
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        CompositionLocalProvider(
            LocalWorkspaceMedia provides environment,
            LocalAsyncImagePreviewHandler provides handler,
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }
}

private val MarkdownMediaSample = """
生成的海报与素材如下：

![海边日落](${PreviewImage.path})

![延时视频](</media/2026-10-20/海边延时 摄影.mp4>)

![旁白](</media/2026-10-20/旁白-第一段 配音试听版本.m4a>)

完整报告见：

[季度报告](</docs/2026 第三季度经营分析报告（终版）.pdf>)

![](/media/2026-10-19/已删除的图片.png)

![](/mnt/相册/失效挂载/a.jpg)
""".trimIndent()

@Composable
private fun MarkdownMediaCards() {
    MarkdownText(markdown = MarkdownMediaSample, fontSize = 15)
}

@PreviewTest
@Preview(name = "正文媒体卡片 浅色中文", widthDp = 411, heightDp = 1300, locale = "zh-rCN")
@Composable
fun workspaceMediaMarkdownLight() = MediaPreviewFrame(dark = false) { MarkdownMediaCards() }

@PreviewTest
@Preview(name = "正文媒体卡片 深色中文", widthDp = 411, heightDp = 1300, locale = "zh-rCN")
@Composable
fun workspaceMediaMarkdownDark() = MediaPreviewFrame(dark = true) { MarkdownMediaCards() }

@PreviewTest
@Preview(name = "正文媒体卡片 窄屏大字", widthDp = 360, heightDp = 1500, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun workspaceMediaMarkdownLargeFont() = MediaPreviewFrame(dark = true) { MarkdownMediaCards() }

private val ToolMediaSteps = listOf(
    ExecutionStep("gen-call", "tool_call", "generate_image", "{\"prompt\":\"海边日落海报\",\"n\":3}", "gen-call", timestamp = 1_000),
    ExecutionStep(
        "gen-result", "tool_result", "generate_image", toolCallId = "gen-call", timestamp = 9_400,
        content = "已生成 3 张图片、1 段视频与配音", media = listOf(PreviewImage, PreviewPortrait, PreviewSquare, PreviewVideo, PreviewAudio, PreviewFile),
    ),
)

@Composable
private fun ToolMediaStep() {
    InlineToolRow(ToolMediaSteps, isExecuting = false, fontSize = 15, initiallyExpanded = true)
}

private val ImageToolCardSteps = listOf(
    listOf(
        ExecutionStep("g1", "tool_call", "generate_image",
            "{\"prompt\":\"黄昏的海边灯塔，暖色逆光，胶片质感，远处有归航的渔船\",\"size\":\"1536x1024\",\"quality\":\"high\"}", "g1", timestamp = 1_000),
        ExecutionStep("g1r", "tool_result", "generate_image", toolCallId = "g1", timestamp = 19_400,
            content = "${PreviewImage.path}（1600×900，812345 字节）\n回复中用 ![说明](工作区路径) 引用图片。",
            data = ImageGenerationResultData(mode = "generate", model = "gpt-image-2",
                requested = ImageGenerationRequested(size = "1536x1024", quality = "high"),
                actual = ImageGenerationActual(size = "1536x1024", quality = "high", outputFormat = "png")).encode(),
            media = listOf(PreviewImage)),
    ),
    listOf(
        ExecutionStep("e1", "tool_call", "generate_image",
            "{\"prompt\":\"把人物换到雨后的街道，保留发型与外套\",\"images\":[\"${PreviewSquare.path}\",\"${PreviewWide.path}\"]}", "e1", timestamp = 2_000),
        ExecutionStep("e1r", "tool_result", "generate_image", toolCallId = "e1", timestamp = 25_000,
            content = "${PreviewTall.path}（1000×1500，610000 字节）",
            data = ImageGenerationResultData(mode = "edit", model = "gpt-image-2",
                actual = ImageGenerationActual(size = "1024x1536", quality = "medium", outputFormat = "jpeg"),
                inputImages = listOf(PreviewSquare.path, PreviewWide.path)).encode(),
            media = listOf(PreviewTall)),
    ),
    listOf(
        ExecutionStep("v1", "tool_call", "view_media",
            "{\"path\":\"${PreviewPortrait.path}\",\"question\":\"海报上的标题文字是否清晰、有无错字？\"}", "v1", timestamp = 3_000),
        ExecutionStep("v1r", "tool_result", "view_media", toolCallId = "v1", timestamp = 3_800,
            content = "已附上 ${PreviewPortrait.path} 的图像内容（900×1600，image/png）。", media = listOf(PreviewPortrait)),
    ),
)

@Composable
private fun ImageToolCards() {
    ImageToolCardSteps.forEach { steps -> InlineToolRow(steps, isExecuting = false, fontSize = 15, initiallyExpanded = true) }
}

@PreviewTest
@Preview(name = "生图与查看媒体卡片 浅色中文", widthDp = 411, heightDp = 1500, locale = "zh-rCN")
@Composable
fun imageToolCardsLight() = MediaPreviewFrame(dark = false) { ImageToolCards() }

@PreviewTest
@Preview(name = "生图与查看媒体卡片 深色中文", widthDp = 411, heightDp = 1500, locale = "zh-rCN")
@Composable
fun imageToolCardsDark() = MediaPreviewFrame(dark = true) { ImageToolCards() }

@PreviewTest
@Preview(name = "Image tool cards English 360dp font130", widthDp = 360, heightDp = 1800, locale = "en", fontScale = 1.3f)
@Composable
fun imageToolCardsLargeFont() = MediaPreviewFrame(dark = false) { ImageToolCards() }

@PreviewTest
@Preview(name = "工具步骤 多媒体 浅色中文", widthDp = 411, heightDp = 1250, locale = "zh-rCN")
@Composable
fun workspaceMediaToolStepLight() = MediaPreviewFrame(dark = false) { ToolMediaStep() }

@PreviewTest
@Preview(name = "工具步骤 多媒体 深色中文", widthDp = 411, heightDp = 1250, locale = "zh-rCN")
@Composable
fun workspaceMediaToolStepDark() = MediaPreviewFrame(dark = true) { ToolMediaStep() }

@PreviewTest
@Preview(name = "Tool step media English 360dp font130", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f)
@Composable
fun workspaceMediaToolStepLargeFont() = MediaPreviewFrame(dark = false) { ToolMediaStep() }

private val MarkdownGallerySample = """
三张海报并排对比：

```gallery
![横版](${PreviewImage.path})
![竖版](${PreviewPortrait.path})
![方形](${PreviewSquare.path})
```

全部素材：

```gallery
![横版](${PreviewImage.path})
![竖版](${PreviewPortrait.path})
![方形](${PreviewSquare.path})
![延时视频](</media/2026-10-20/海边延时 摄影.mp4>)
![宽幅](${PreviewWide.path})
![长图](${PreviewTall.path})
![旁白](</media/2026-10-20/旁白-第一段 配音试听版本.m4a>)
```
""".trimIndent()

private val MarkdownCarouselSample = """
按顺序翻看分镜：

```carousel
![第一镜：远景海面与落日，镜头缓慢推近到礁石边缘的人物剪影](${PreviewImage.path})
![第二镜：人物特写](${PreviewPortrait.path})
![第三镜：延时](</media/2026-10-20/海边延时 摄影.mp4>)
![第四镜](${PreviewSquare.path})
![第五镜](${PreviewWide.path})
```
""".trimIndent()

@PreviewTest
@Preview(name = "正文媒体矩阵 三项与六项 浅色中文", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun workspaceMediaGalleryLight() = MediaPreviewFrame(dark = false) { MarkdownText(markdown = MarkdownGallerySample, fontSize = 15) }

@PreviewTest
@Preview(name = "正文媒体矩阵 三项与六项 深色中文", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun workspaceMediaGalleryDark() = MediaPreviewFrame(dark = true) { MarkdownText(markdown = MarkdownGallerySample, fontSize = 15) }

@PreviewTest
@Preview(name = "正文内嵌查看器 浅色中文", widthDp = 411, heightDp = 600, locale = "zh-rCN")
@Composable
fun workspaceMediaCarouselLight() = MediaPreviewFrame(dark = false) { MarkdownText(markdown = MarkdownCarouselSample, fontSize = 15) }

@PreviewTest
@Preview(name = "Inline carousel English 360dp font130", widthDp = 360, heightDp = 600, locale = "en", fontScale = 1.3f)
@Composable
fun workspaceMediaCarouselLargeFont() = MediaPreviewFrame(dark = true) { MarkdownText(markdown = MarkdownCarouselSample, fontSize = 15) }
