package com.promenar.infinity.ui.canvas

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.promenar.infinity.R
import com.promenar.infinity.data.artifact.ArtifactFileSnapshot
import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.document.DocumentTextDecoding
import com.promenar.infinity.data.document.MarkdownChunks
import com.promenar.infinity.ui.document.DocumentBody
import com.promenar.infinity.ui.document.DocumentBodyState
import com.promenar.infinity.ui.document.DocumentViewerAction
import com.promenar.infinity.ui.document.DocumentViewerScaffold
import com.promenar.infinity.ui.document.external.excludingOwnViewer
import com.promenar.infinity.ui.media.MediaFileActions
import com.promenar.infinity.ui.renderer.ImageLightbox
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用内预览的文本上限；更大的文本只提供用其它应用打开。 */
private const val MAX_TEXT_PREVIEW_BYTES = 512L * 1024L

/** 文件产物在应用内的呈现方式。 */
internal enum class FileArtifactPreview { IMAGE, MARKDOWN, TEXT, PAGE, EXTERNAL }

internal fun fileArtifactPreview(kind: ArtifactKind, fileName: String, sizeBytes: Long): FileArtifactPreview {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val smallText = sizeBytes <= MAX_TEXT_PREVIEW_BYTES
    return when {
        kind == ArtifactKind.IMAGE && extension != "svg" -> FileArtifactPreview.IMAGE
        extension in setOf("md", "markdown") && smallText -> FileArtifactPreview.MARKDOWN
        extension in setOf("txt", "csv", "tsv", "json", "log", "yaml", "yml", "xml") && smallText -> FileArtifactPreview.TEXT
        extension in setOf("html", "htm", "svg") && sizeBytes <= 1_048_576L -> FileArtifactPreview.PAGE
        else -> FileArtifactPreview.EXTERNAL
    }
}

/**
 * 文件产物查看：图片进图片查看器，Markdown 与纯文本用共用的文档查看器外壳显示（大 Markdown 分块渲染），
 * HTML 与 SVG 用画布网页只读显示，其它类型（PDF、Office、压缩包等）直接交给其它应用打开。
 */
@Composable
internal fun FileArtifactViewer(
    snapshot: ArtifactFileSnapshot?,
    error: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openWith: () -> Unit = { snapshot?.let { scope.launch { openArtifactExternally(context, it) } } }
    val preview = snapshot?.let { fileArtifactPreview(it.summary.kind, it.summary.fileName ?: it.file.name, it.file.length()) }
    if (snapshot != null && preview == FileArtifactPreview.IMAGE) {
        ImageLightbox(
            imageUrl = Uri.fromFile(snapshot.file).toString(),
            model = snapshot.file,
            onDismiss = onClose,
            overlay = {
                FilledTonalButton(onClick = openWith, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = InfinitySpacing.XLarge)) {
                    Text(stringResource(R.string.artifact_open_with))
                }
            },
        )
        return
    }
    if (snapshot != null && preview == FileArtifactPreview.EXTERNAL) {
        LaunchedEffect(snapshot) { openArtifactExternally(context, snapshot); onClose() }
        return
    }
    var content by remember(snapshot) { mutableStateOf<FileArtifactContent?>(null) }
    var readFailed by remember(snapshot) { mutableStateOf(false) }
    LaunchedEffect(snapshot) {
        if (snapshot == null) return@LaunchedEffect
        try {
            content = withContext(Dispatchers.IO) {
                // 产物可能不是 UTF-8 或带 BOM；按 BOM 识别编码，无法解码的字节以替换字符显示。
                val text = DocumentTextDecoding.decodeLenient(snapshot.file.readBytes())
                FileArtifactContent(text, if (preview == FileArtifactPreview.MARKDOWN) MarkdownChunks.split(text) else null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readFailed = true
        }
    }
    val loaded = content
    val body = when {
        error || readFailed -> DocumentBodyState.Message(stringResource(R.string.artifact_file_load_error))
        loaded == null -> DocumentBodyState.Loading
        loaded.chunks != null -> DocumentBodyState.Markdown(loaded.chunks)
        else -> DocumentBodyState.Plain(loaded.text)
    }
    DocumentViewerScaffold(
        title = snapshot?.summary?.title.orEmpty(),
        subtitle = snapshot?.let { artifactMeta(it.summary) },
        onClose = onClose,
        modifier = Modifier.testTag(UiTags.ARTIFACT_FILE_VIEWER),
        topActions = listOf(
            DocumentViewerAction(
                label = stringResource(R.string.artifact_open_with),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = openWith,
                enabled = snapshot != null,
            ),
        ),
    ) { modifier ->
        if (preview == FileArtifactPreview.PAGE && loaded != null && body !is DocumentBodyState.Message) {
            CanvasWebView(html = loaded.text, modifier = modifier, interactive = true, readOnly = true)
        } else {
            DocumentBody(body, modifier)
        }
    }
}

/** 读入的产物正文；[chunks] 只在 Markdown 预览时生成。 */
private class FileArtifactContent(val text: String, val chunks: List<String>?)

/** 复制到分享缓存目录（文件名用产物原名），再以只读 content URI 交给其它应用。 */
internal suspend fun openArtifactExternally(context: Context, snapshot: ArtifactFileSnapshot) {
    val uri = try {
        withContext(Dispatchers.IO) {
            MediaFileActions.cleanupShareCache(context)
            val directory = File(MediaFileActions.shareDirectory(context), UUID.randomUUID().toString()).apply { mkdirs() }
            val name = (snapshot.summary.fileName ?: snapshot.file.name).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120).ifBlank { "artifact" }
            val copy = File(directory, name)
            snapshot.file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Toast.makeText(context, R.string.artifact_file_load_error, Toast.LENGTH_SHORT).show()
        return
    }
    val mime = snapshot.summary.mimeType?.takeIf { it.contains('/') } ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(snapshot.summary.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(view, null).excludingOwnViewer(context).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.artifact_no_app, Toast.LENGTH_SHORT).show()
    }
}
