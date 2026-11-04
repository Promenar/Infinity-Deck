package com.promenar.infinity.domain.linux

import java.io.File

/**
 * 以容器内路径（`/root/...`、`/usr/...`）浏览和操作 rootfs。宿主侧直接读写文件，不经过 proot，
 * 但对符号链接按容器根解析，并把 `--link2symlink` 生成的硬链接表示还原为普通文件。
 * `/workspace` 与 `/mnt/<别名>` 是运行时绑定，不在 rootfs 中；指向它们的链接 [LinuxFileEntry.symlinkTarget] 照常给出。
 * 路径越界、目标不存在等错误抛出 [LinuxFileException]。
 */
interface LinuxFileSystem {
    suspend fun list(path: String): List<LinuxFileEntry>

    suspend fun stat(path: String): LinuxFileEntry?

    /** 读取开头最多 [maxBytes] 字节并分析编码、换行与是否二进制。 */
    suspend fun readText(path: String, maxBytes: Int = 1 shl 20): LinuxTextContent

    suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray

    suspend fun availableBytes(parent: String): Long = throw UnsupportedOperationException("空间查询不可用")

    /** 有界流式读取，回调结束前保持文件描述符与版本校验。 */
    suspend fun <T> readStreaming(path: String, maxBytes: Long, reader: (java.io.InputStream) -> T): T =
        throw UnsupportedOperationException("流式读取不可用")

    /** 流式写入临时文件，重新读取 SHA-256 核对 writer 返回的源摘要后原子发布，再同步调用 onCommitted。 */
    suspend fun writeStreaming(path: String, size: Long, expected: LinuxFileEntry?, check: () -> Unit = {}, beforeCommit: () -> Unit = {}, onCommitted: () -> Unit = {}, writer: (java.io.OutputStream) -> String): LinuxFileEntry =
        throw UnsupportedOperationException("流式写入不可用")

    /** 覆盖写入；[expectedModifiedMs] 非 null 且与当前修改时间不同时以 [LinuxFileErrorCode.CHANGED] 失败。保留权限位。 */
    suspend fun writeText(path: String, text: String, expectedModifiedMs: Long? = null): LinuxFileEntry

    suspend fun createFile(parent: String, name: String): LinuxFileEntry

    suspend fun createDirectory(parent: String, name: String): LinuxFileEntry

    suspend fun rename(path: String, newName: String): LinuxFileEntry

    /** 逐项复制；同名自动改为“名称 (2)”。返回失败项。 */
    suspend fun copy(paths: List<String>, targetDirectory: String): Map<String, LinuxFileErrorCode>

    /** 逐项移动；同名时该项失败。 */
    suspend fun move(paths: List<String>, targetDirectory: String): Map<String, LinuxFileErrorCode>

    /** 永久删除文件或目录。 */
    suspend fun delete(paths: List<String>): Map<String, LinuxFileErrorCode>

    /** 打包为 `.tar.gz`，放在第一项所在目录。 */
    suspend fun compress(paths: List<String>, archiveName: String): LinuxFileEntry

    suspend fun chmod(path: String, mode: Int): LinuxFileEntry

    /** 容器内路径对应的宿主文件，用于分享或导出；越界或位于绑定路径时为 null。 */
    fun hostFile(path: String): File?
}

enum class LinuxFileType { FILE, DIRECTORY, SYMLINK, OTHER }

data class LinuxFileEntry(
    /** 容器内绝对路径。 */
    val path: String,
    val name: String,
    val type: LinuxFileType,
    val size: Long,
    val modifiedMs: Long,
    /** 权限位（低 12 位，含 setuid/setgid/sticky）。 */
    val mode: Int,
    /** proot 以 root 身份运行，应用 UID 拥有的文件显示为 root。 */
    val owner: String,
    val group: String,
    /** 符号链接的原始目标（容器内语义）。 */
    val symlinkTarget: String? = null,
    /** 符号链接解析后是否为目录；目标不存在或位于绑定路径时为 false。 */
    val targetIsDirectory: Boolean = false,
    val isHidden: Boolean = name.startsWith("."),
)

data class LinuxTextContent(
    val bytes: ByteArray,
    /** 文件实际大小；大于 [bytes] 长度表示被截断。 */
    val totalSize: Long,
    val isBinary: Boolean,
    /** 例如 "UTF-8"；二进制为 null。 */
    val encoding: String?,
    val lineEnding: LinuxLineEnding?,
    val lineCount: Int?,
    val modifiedMs: Long,
) {
    val truncated: Boolean get() = totalSize > bytes.size
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

enum class LinuxLineEnding { LF, CRLF, CR, MIXED }

enum class LinuxFileErrorCode { NAME_INVALID, NAME_EXISTS, NOT_FOUND, OUTSIDE_ROOTFS, TARGET_INSIDE_SOURCE, CHANGED, NOT_READY, IO }

open class LinuxFileException(val code: LinuxFileErrorCode, cause: Throwable? = null) : Exception(code.name, cause)

/** 流式传输的稳定失败原因，不扩展界面使用的文件错误枚举。 */
class LinuxStreamingException(val failureCode: String) : LinuxFileException(LinuxFileErrorCode.IO)
