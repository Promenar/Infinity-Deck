package com.promenar.infinity.ui.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import coil3.ImageLoader
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source

/** 解析结果不可用或不是可显示的图片/视频时，Coil 请求以该异常失败，卡片据此显示占位。 */
class WorkspaceMediaUnavailableException(
    val path: String,
    val reason: MediaUnavailableReason,
) : IllegalStateException("工作区媒体不可用：$path（$reason）")

/**
 * 经 [WorkspaceMediaResolver] 读取工作区媒体的 Coil 取数器。
 *
 * 真实文件直接以文件路径交给解码器，视频首帧解码器可随机读取；SAF 来源的视频以文件描述符交给首帧解码器，
 * 其余 SAF 内容经解析器的字节流读取。
 */
class WorkspaceMediaFetcher(
    private val model: WorkspaceMediaModel,
    private val resolver: WorkspaceMediaResolver,
    private val openContentDescriptor: (String) -> AssetFileDescriptor?,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        when (val resolution = resolver.resolve(model.sessionId, model.path)) {
            is MediaResolution.Unavailable -> throw WorkspaceMediaUnavailableException(resolution.path, resolution.reason)
            is MediaResolution.Available -> fetchAvailable(resolution)
        }
    }

    private suspend fun fetchAvailable(available: MediaResolution.Available): FetchResult {
        val ref = available.ref
        if (ref.kind != MediaKind.IMAGE && ref.kind != MediaKind.VIDEO) {
            throw WorkspaceMediaUnavailableException(ref.path, MediaUnavailableReason.UNREADABLE)
        }
        val mimeType = ref.mimeType.takeIf(String::isNotBlank)
        return when (val source = available.source) {
            is MediaSource.LocalFile -> SourceFetchResult(
                source = ImageSource(file = source.file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
                mimeType = mimeType,
                dataSource = DataSource.DISK,
            )
            is MediaSource.ContentUri -> if (ref.kind == MediaKind.VIDEO) {
                val descriptor = openContentDescriptor(source.uri)
                    ?: throw WorkspaceMediaUnavailableException(ref.path, MediaUnavailableReason.UNREADABLE)
                SourceFetchResult(
                    source = ImageSource(
                        source = descriptor.createInputStream().source().buffer(),
                        fileSystem = FileSystem.SYSTEM,
                        metadata = ContentMetadata(source.uri.toUri(), descriptor),
                    ),
                    mimeType = mimeType,
                    dataSource = DataSource.DISK,
                )
            } else {
                SourceFetchResult(
                    source = ImageSource(
                        source = resolver.open(available).source().buffer(),
                        fileSystem = FileSystem.SYSTEM,
                    ),
                    mimeType = mimeType,
                    dataSource = DataSource.DISK,
                )
            }
        }
    }

    class Factory(
        private val context: Context,
        private val resolver: () -> WorkspaceMediaResolver,
    ) : Fetcher.Factory<WorkspaceMediaModel> {
        override fun create(data: WorkspaceMediaModel, options: Options, imageLoader: ImageLoader): Fetcher =
            WorkspaceMediaFetcher(data, LabWorkspaceMediaResolvers.forSession(data.sessionId) ?: resolver()) { uri ->
                context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")
            }
    }
}

/** 内存缓存键包含会话、路径与内容身份；没有身份时不缓存，避免替换后显示旧图。 */
class WorkspaceMediaKeyer : Keyer<WorkspaceMediaModel> {
    override fun key(data: WorkspaceMediaModel, options: Options): String? =
        data.identity?.let { data.cacheKey() }
}

/** 让 `infinity-workspace://` 字符串（如查看器按字符串传入的地址）也走工作区取数器。 */
class WorkspaceMediaUriMapper : Mapper<String, WorkspaceMediaModel> {
    override fun map(data: String, options: Options): WorkspaceMediaModel? = WorkspaceMediaModel.parse(data)
}
