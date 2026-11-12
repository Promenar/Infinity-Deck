package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.media.download.WorkspaceMediaDownloader
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.mediagen.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface CloudMediaInputSource {
    suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput): String
    suspend fun durationSeconds(request: MediaGenerationRequest, input: MediaInput): Long? = null
}

/** 图片和音频以内联数据提交；视频需提供可访问 URL 或提供商素材 ID。 */
class WorkspaceCloudMediaInputSource(private val resolver: WorkspaceMediaResolver) : CloudMediaInputSource {
    override suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput): String = withContext(Dispatchers.IO) {
        if (input.location.startsWith("https://") || input.location.startsWith("http://") ||
            input.location.startsWith("asset://") || input.location.startsWith("file://")) {
            val uri = java.net.URI(input.location)
            require(uri.rawUserInfo == null && uri.rawFragment == null && uri.host != null) { "媒体输入地址无效" }
            // file:// 是 MiniMax V2 的平台 file_id 引用，拒绝本机文件路径。
            if (uri.scheme == "file") require(uri.host.matches(Regex("[0-9]+")) && uri.path.orEmpty().isEmpty())
            return@withContext input.location
        }
        if (input.location.startsWith("data:")) {
            require(input.kind != InputKind.VIDEO) { "云端参考视频不接受内联数据" }
            require(input.location.length <= MAX_INLINE_BYTES * 4 / 3 + 1024)
            require(input.location.startsWith(if (input.kind == InputKind.IMAGE) "data:image/" else "data:audio/"))
            require(";base64," in input.location)
            return@withContext input.location
        }
        require(input.kind != InputKind.VIDEO) { "云端参考视频需要公网 URL 或平台素材 ID" }
        val path = resolver.productionPath(request.sessionId, input.location)
        val available = resolver.resolve(request.sessionId, path) as? MediaResolution.Available ?: error("参考媒体不可读")
        val prefix = if (input.kind == InputKind.IMAGE) "image/" else "audio/"
        require(available.ref.mimeType.startsWith(prefix) && available.ref.sizeBytes <= MAX_INLINE_BYTES) { "参考媒体类型无效或超过内联大小上限" }
        val bytes = ByteArrayOutputStream()
        resolver.open(available).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                require(bytes.size() + n <= MAX_INLINE_BYTES) { "参考媒体超过内联大小上限" }
                bytes.write(buffer, 0, n)
            }
        }
        "data:${available.ref.mimeType};base64,${Base64.getEncoder().encodeToString(bytes.toByteArray())}"
    }
    override suspend fun durationSeconds(request: MediaGenerationRequest, input: MediaInput): Long? = withContext(Dispatchers.IO) {
        if (':' in input.location) return@withContext null
        val path = resolver.productionPath(request.sessionId, input.location)
        val ref = (resolver.resolve(request.sessionId, path) as? MediaResolution.Available)?.ref ?: return@withContext null
        ref.durationMs?.let { (it + 999L) / 1000L }
    }
    companion object { private const val MAX_INLINE_BYTES = 15 * 1024 * 1024 }
}

fun interface CloudMediaOutputStore {
    suspend fun save(request: MediaGenerationRequest, remoteId: String, index: Int, output: RemoteOutput): String
    suspend fun saveWithFormat(request: MediaGenerationRequest, remoteId: String, index: Int, output: RemoteOutput, extension: String): String = save(request, remoteId, index, output)
}

/** 下载复用统一 URL 策略、有界流和工作区的新建语义；返回制作清单的相对路径。 */
class WorkspaceCloudMediaOutputStore(
    private val downloader: WorkspaceMediaDownloader,
    private val store: WorkspaceMediaStore,
    private val resolver: WorkspaceMediaResolver,
) : CloudMediaOutputStore {
    override suspend fun save(request: MediaGenerationRequest, remoteId: String, index: Int, output: RemoteOutput): String =
        saveWithFormat(request, remoteId, index, output, if (output.kind == InputKind.AUDIO) "mp3" else if (output.kind == InputKind.VIDEO) "mp4" else "png")

    override suspend fun saveWithFormat(request: MediaGenerationRequest, remoteId: String, index: Int, output: RemoteOutput, extension: String): String = withContext(Dispatchers.IO) {
        val dir = request.outputDir.trimEnd('/')
        require(dir.isNotBlank() && !dir.startsWith('/') && !dir.contains('\\') && !dir.contains(':') && dir.split('/').none { it in setOf("", ".", "..") }) { "媒体输出目录必须为工作区相对路径" }
        val suffix = extension.also { require(it in when (output.kind) {
            InputKind.IMAGE -> setOf("png", "jpg", "webp")
            InputKind.VIDEO -> setOf("mp4")
            InputKind.AUDIO -> setOf("mp3", "wav", "flac")
        }) }
        val identity = UUID.nameUUIDFromBytes("${request.channelId}:$remoteId".toByteArray()).toString()
        val relative = "$dir/gen-$identity-$index.$suffix"
        val target = resolver.productionPath(request.sessionId, relative)
        val expected = when (output.kind) { InputKind.IMAGE -> MediaKind.IMAGE; InputKind.VIDEO -> MediaKind.VIDEO; InputKind.AUDIO -> MediaKind.AUDIO }
        val existing = resolver.resolve(request.sessionId, target)
        if (existing is MediaResolution.Available) {
            require(existing.ref.kind == expected && existing.ref.sizeBytes > 0) { "媒体目标已被其它文件占用" }
            return@withContext relative
        }
        val saved = downloader.save(output.location, request.sessionId, store, MediaOrigin.GENERATED, targetPath = target)
        require(saved.kind == expected) { "远端产物类型与通道不一致" }
        relative
    }
}
