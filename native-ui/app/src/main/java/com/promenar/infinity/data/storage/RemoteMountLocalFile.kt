package com.promenar.infinity.data.storage

import com.promenar.infinity.data.ssh.isSftpProtectedLocalPath
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.startup.StartupWriteBarrier
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

internal data class RemoteMountLocalFile(
    val file: File,
    val workspacePath: String,
    val identity: String,
    val revision: String,
    val size: Long?,
    val privateRootUuid: String?,
)

/** 传输只接收已解析的工作区内普通文件，沿途现有目录及目标均不得是链接。 */
internal suspend fun resolveRemoteMountLocalFile(
    sessionId: String,
    namespaceId: String,
    root: File,
    relative: String,
    workspacePath: String,
    privateRootUuid: String?,
    writing: Boolean,
): RemoteMountLocalFile {
    if (relative == "/" || isSftpProtectedLocalPath(relative) || isSftpProtectedLocalPath(workspacePath)) {
        throw ExternalFolderException("ACCESS_DENIED", "请选择工作区内的普通文件，技能与受保护路径不可用于远程挂载传输")
    }
    val base = root.toPath()
    if (!Files.isDirectory(base, NOFOLLOW_LINKS)) {
        throw ExternalFolderException("WORKSPACE_UNAVAILABLE", "当前工作区目录不可用")
    }
    val canonical = base.toRealPath()
    val file = canonical.resolve(relative.removePrefix("/")).normalize()
    if (file == canonical || !file.startsWith(canonical) || isSftpProtectedLocalPath(file.toString())) {
        throw ExternalFolderException("ACCESS_DENIED", "本地传输路径超出工作区范围")
    }
    if (writing) StartupWriteBarrier.requireWritable(rootId = privateRootUuid, sessionId = sessionId, path = file.toString())
    val parent = file.parent
    if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || parent.toRealPath() != parent) {
        throw ExternalFolderException("INVALID_LOCAL_PATH", "目标父目录须存在且不得经由符号链接")
    }
    val parentAttrs = Files.readAttributes(parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val identity = Sha256Utils.hash(listOf(namespaceId, file.toString(), parentAttrs.fileKey()).joinToString("\u0000"))
    val attributes = try {
        Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }
    if (attributes != null && !attributes.isRegularFile) {
        throw ExternalFolderException("UNSUPPORTED_FILE_TYPE", "本地传输仅支持普通文件")
    }
    if (!writing && attributes == null) throw ExternalFolderException("LOCAL_NOT_FOUND", "工作区源文件不存在")
    val revision = attributes?.let {
        Sha256Utils.hash(listOf(it.size(), it.lastModifiedTime(), it.fileKey()).joinToString("\u0000"))
    }.orEmpty()
    return RemoteMountLocalFile(file.toFile(), workspacePath, identity, revision, attributes?.size(), privateRootUuid)
}
