package com.promenar.infinity.domain.media

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream

/** 媒体类别：决定前端卡片样式与模型可查看的模态。 */
@Serializable
enum class MediaKind { IMAGE, VIDEO, AUDIO, FILE }

/**
 * 工作区媒体引用。会话与工具结果只保存引用，不保存字节；打开会话时按 [path] 在会话当前工作区重新解析。
 *
 * [path] 是模型可见的工作区虚拟路径（以 `/` 开头，如 `/media/2026-10-20/gen-1432-a1b2c3.png`、`/mnt/相册/a.jpg`）。
 */
@Serializable
data class MediaRef(
    val path: String,
    val mimeType: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

/** 媒体字节的可读来源。播放器与分享需要可随机读取的来源，因此不只提供流。 */
sealed interface MediaSource {
    /** 应用可直接读取的真实文件（私有工作区、真实路径挂载）。 */
    data class LocalFile(val file: File) : MediaSource

    /** 只能经 ContentResolver 读取的来源（SAF 挂载），[uri] 为 `content://` 字符串。 */
    data class ContentUri(val uri: String) : MediaSource
}

enum class MediaUnavailableReason {
    /** 路径格式无效或越出会话可见范围（含本机真实绝对路径）。 */
    INVALID_PATH,
    NOT_FOUND,
    IS_DIRECTORY,
    /** 挂载失效、权限被撤销等。 */
    UNREADABLE,
}

sealed interface MediaResolution {
    data class Available(val ref: MediaRef, val source: MediaSource) : MediaResolution
    data class Unavailable(val path: String, val reason: MediaUnavailableReason) : MediaResolution
}

/** 按会话把工作区虚拟路径解析为媒体，范围与会话当前工作区的文件工具一致。 */
interface WorkspaceMediaResolver {
    /** 将制作清单的相对路径呈现为当前会话可解析的路径。 */
    suspend fun productionPath(sessionId: String, path: String): String = throw UnsupportedOperationException("制作路径映射不可用")

    /** 解析 [path]；相对路径按工作区根解析。不读取全部字节，只读取识别类型与元数据所需的部分。 */
    suspend fun resolve(sessionId: String, path: String): MediaResolution

    /** 打开 [resolution] 的字节流，调用方负责关闭。 */
    suspend fun open(resolution: MediaResolution.Available): InputStream
}

/** 媒体来源，用作默认文件名前缀。 */
enum class MediaOrigin(val prefix: String) {
    GENERATED("gen"),
    MCP("mcp"),
    DOWNLOAD("dl"),
    FRAME("frame"),
    ANNOTATION("annot"),
}

/** 写入工作区的媒体内容。 */
sealed interface MediaPayload {
    class Bytes(val bytes: ByteArray) : MediaPayload

    /** 由调用方提供的流，写入完成后由存储关闭。 */
    class Stream(val open: () -> InputStream) : MediaPayload
}

/** 把媒体写入会话工作区，返回其引用。 */
interface WorkspaceMediaStore {
    /** 按媒体默认落点核验当前写入条件；保存时仍会重新核验。 */
    suspend fun canSave(sessionId: String): Boolean = false

    /**
     * 写入 [payload]。[targetPath] 为空时写入默认目录 `media/<yyyy-MM-dd>/`，文件名为
     * “来源前缀-时分-短哈希.扩展名”，只新建不覆盖；[suggestedName] 只用于推断扩展名。
     * [mimeTypeHint] 与文件头不一致时以文件头为准。
     */
    suspend fun save(
        sessionId: String,
        payload: MediaPayload,
        origin: MediaOrigin,
        mimeTypeHint: String? = null,
        suggestedName: String? = null,
        targetPath: String? = null,
    ): MediaRef
}
