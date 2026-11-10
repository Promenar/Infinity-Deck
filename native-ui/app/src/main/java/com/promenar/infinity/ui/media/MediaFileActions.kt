package com.promenar.infinity.ui.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import com.promenar.infinity.ui.document.external.excludingOwnViewer

/** 保存到系统媒体库时的集合。 */
enum class MediaSaveCollection { PICTURES, MOVIES, DOWNLOADS }

/** 保存目标：集合、相对目录与写入的 MIME。 */
data class MediaSaveTarget(
    val collection: MediaSaveCollection,
    val relativePath: String,
    val mimeType: String,
)

private const val GENERIC_MIME = "application/octet-stream"

/** 目录与 Environment.DIRECTORY_PICTURES/MOVIES/DOWNLOADS 一致，写成常量以便 JVM 测试。图片、视频写入 `Pictures/Infinity Deck`、`Movies/Infinity Deck`；音频与其它文件（含类别与 MIME 不符者）写入 `Download/Infinity Deck`。 */
fun mediaSaveTarget(kind: MediaKind, mimeType: String): MediaSaveTarget {
    val mime = mimeType.trim().lowercase().takeIf { it.contains('/') && !it.contains('*') } ?: GENERIC_MIME
    return when {
        kind == MediaKind.IMAGE && mime.startsWith("image/") ->
            MediaSaveTarget(MediaSaveCollection.PICTURES, "Pictures/Infinity Deck", mime)
        kind == MediaKind.VIDEO && mime.startsWith("video/") ->
            MediaSaveTarget(MediaSaveCollection.MOVIES, "Movies/Infinity Deck", mime)
        else -> MediaSaveTarget(MediaSaveCollection.DOWNLOADS, "Download/Infinity Deck", mime)
    }
}

/** 分享与“用其它应用打开”的 content URI 来源。 */
sealed interface MediaShareRoute {
    /** FileProvider 已覆盖的真实文件，直接生成 content URI。 */
    data class Direct(val file: File) : MediaShareRoute

    /** 共享存储真实路径、SAF 等：先复制到缓存分享子目录。 */
    data object CopyToCache : MediaShareRoute
}

/** [providerRoots] 为 `file_paths.xml` 登记的目录（应用私有文件目录、分享缓存子目录等）。 */
fun mediaShareRoute(source: MediaSource, providerRoots: List<File>): MediaShareRoute {
    val file = (source as? MediaSource.LocalFile)?.file ?: return MediaShareRoute.CopyToCache
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return MediaShareRoute.CopyToCache
    val covered = providerRoots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        canonical.path.startsWith(canonicalRoot.path + File.separator)
    }
    return if (covered) MediaShareRoute.Direct(canonical) else MediaShareRoute.CopyToCache
}

/** 分享缓存里早于 [maxAgeMs] 的条目；分享目录按“随机子目录/原文件名”组织。 */
fun staleShareEntries(entries: List<File>, nowMs: Long, maxAgeMs: Long): List<File> =
    entries.filter { nowMs - it.lastModified() >= maxAgeMs }

enum class MediaActionFailure { UNAVAILABLE, READ_FAILED, WRITE_FAILED, NO_APP }

sealed interface MediaActionResult {
    data class Saved(val location: String) : MediaActionResult
    data object Launched : MediaActionResult
    data class Failed(val failure: MediaActionFailure, val detail: String? = null) : MediaActionResult
}

/** 保存、分享、用其它应用打开。字节复制在 IO 线程流式进行，可处理大文件。 */
class MediaFileActions(
    private val context: Context,
    private val resolver: WorkspaceMediaResolver,
    private val sessionId: String,
) {
    private val appContext = context.applicationContext ?: context

    suspend fun save(available: MediaResolution.Available): MediaActionResult = withContext(Dispatchers.IO) {
        val ref = available.ref
        val target = mediaSaveTarget(ref.kind, ref.mimeType)
        val collection = when (target.collection) {
            MediaSaveCollection.PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaSaveCollection.MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaSaveCollection.DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, mediaDisplayName(ref.path))
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val contentResolver = appContext.contentResolver
        val uri = runCatching { contentResolver.insert(collection, values) }.getOrNull()
            ?: return@withContext MediaActionResult.Failed(MediaActionFailure.WRITE_FAILED)
        try {
            openSource(available).use { input ->
                val output = contentResolver.openOutputStream(uri) ?: throw IOException("无法写入媒体库")
                output.use { input.copyTo(it, COPY_BUFFER) }
            }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            MediaActionResult.Saved(target.relativePath)
        } catch (error: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            if (error is kotlinx.coroutines.CancellationException) throw error
            MediaActionResult.Failed(
                if (error is ReadFailure) MediaActionFailure.READ_FAILED else MediaActionFailure.WRITE_FAILED,
                error.message,
            )
        }
    }

    suspend fun share(available: MediaResolution.Available): MediaActionResult {
        val uri = shareableUri(available) ?: return MediaActionResult.Failed(MediaActionFailure.READ_FAILED)
        val mime = available.ref.mimeType.takeIf { it.contains('/') } ?: GENERIC_MIME
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(mediaDisplayName(available.ref.path), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(Intent.createChooser(send, null))
    }

    suspend fun openWith(available: MediaResolution.Available): MediaActionResult {
        val uri = shareableUri(available) ?: return MediaActionResult.Failed(MediaActionFailure.READ_FAILED)
        val mime = available.ref.mimeType.takeIf { it.contains('/') } ?: GENERIC_MIME
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri(mediaDisplayName(available.ref.path), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(Intent.createChooser(view, null).excludingOwnViewer(context))
    }

    private fun launch(intent: Intent): MediaActionResult = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        MediaActionResult.Launched
    } catch (_: ActivityNotFoundException) {
        MediaActionResult.Failed(MediaActionFailure.NO_APP)
    }

    /** FileProvider 可覆盖时直接生成 content URI，否则复制到缓存分享子目录。 */
    private suspend fun shareableUri(available: MediaResolution.Available): Uri? = withContext(Dispatchers.IO) {
        val authority = "${appContext.packageName}.fileprovider"
        val shareRoot = shareDirectory(appContext)
        val roots = listOf(appContext.filesDir, shareRoot, File(appContext.cacheDir, "legacy-attachment-preview"))
        runCatching {
            when (val route = mediaShareRoute(available.source, roots)) {
                is MediaShareRoute.Direct -> FileProvider.getUriForFile(appContext, authority, route.file)
                MediaShareRoute.CopyToCache -> {
                    cleanupShareCache(appContext)
                    val directory = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                    val copy = File(directory, safeFileName(mediaDisplayName(available.ref.path)))
                    openSource(available).use { input -> copy.outputStream().use { input.copyTo(it, COPY_BUFFER) } }
                    FileProvider.getUriForFile(appContext, authority, copy)
                }
            }
        }.getOrNull()
    }

    /**
     * 真实文件直接读取；其它来源每次先重新解析再 open：解析器按对象身份绑定会话与来源，
     * 只接受 resolve() 返回的对象。
     */
    private suspend fun openSource(available: MediaResolution.Available): InputStream = try {
        when (val source = available.source) {
            is MediaSource.LocalFile -> source.file.inputStream()
            is MediaSource.ContentUri -> {
                val fresh = resolver.resolve(sessionId, available.ref.path) as? MediaResolution.Available
                    ?: throw IOException("文件已不可用")
                if (fresh.source is MediaSource.LocalFile) fresh.source.file.inputStream() else resolver.open(fresh)
            }
        }
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        throw ReadFailure(error)
    }

    private class ReadFailure(cause: Throwable) : IOException(cause.message, cause)

    companion object {
        private const val COPY_BUFFER = 64 * 1024
        private const val SHARE_DIR = "workspace-media-share"
        private const val SHARE_MAX_AGE_MS = 60 * 60 * 1000L

        fun shareDirectory(context: Context): File = File(context.cacheDir, SHARE_DIR)

        /** 删除分享缓存中超过一小时的副本；分享时与进程内首次进入会话时调用。 */
        fun cleanupShareCache(context: Context, maxAgeMs: Long = SHARE_MAX_AGE_MS) {
            val entries = shareDirectory(context).listFiles()?.toList().orEmpty()
            staleShareEntries(entries, System.currentTimeMillis(), maxAgeMs).forEach { it.deleteRecursively() }
        }

        private fun safeFileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120).ifBlank { "media" }
    }
}
