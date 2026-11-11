package com.promenar.infinity.ui.lab.fakes

import android.content.Context
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.domain.media.MediaRef
import java.io.File
import java.io.InputStream

/**
 * 实验室演示会话的工作区媒体：把脚本里的虚拟路径映射到 assets 里的演示图片。
 * 首次解析时复制到缓存目录，之后以真实文件交给取数器，与真实会话的本地文件路径一致。
 */
internal class LabMediaResolver(
    context: Context,
    /** 虚拟路径到媒体引用与 assets 文件名的映射。 */
    private val entries: Map<String, Pair<MediaRef, String>>,
) : WorkspaceMediaResolver {
    private val assets = context.applicationContext.assets
    private val directory = File(context.applicationContext.cacheDir, "lab-media")

    override suspend fun productionPath(sessionId: String, path: String): String = "/${path.trimStart('/')}"

    override suspend fun resolve(sessionId: String, path: String): MediaResolution {
        val (ref, asset) = entries[path] ?: return MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
        val file = File(directory, asset.substringAfterLast('/'))
        if (!file.isFile || file.length() != ref.sizeBytes) {
            directory.mkdirs()
            assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
        }
        return MediaResolution.Available(ref.copy(sizeBytes = file.length()), MediaSource.LocalFile(file))
    }

    override suspend fun open(resolution: MediaResolution.Available): InputStream =
        (resolution.source as MediaSource.LocalFile).file.inputStream()
}
