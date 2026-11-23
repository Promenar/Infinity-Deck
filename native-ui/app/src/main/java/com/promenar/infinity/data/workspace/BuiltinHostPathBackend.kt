package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.storage.ExternalDirectoryDiagnostics
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.tool.ShellCommandSafetyClassifier
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * 内置模式下直接访问本机真实 Android 路径的轻量后端（用于工作区外的自诊断只读目录及经 L1 审批的外部真实路径）。
 *
 * 安全门禁：
 * - `infinity_encrypted_secrets.xml*` 读写列举全部硬拒绝；
 * - 关键运行时数据目录（`databases/`、非密钥 `shared_prefs/`、`no_backup/`、诊断日志）开放只读自诊断，所有写入与删除硬拒绝；
 * - 禁止删除任何系统/存储/应用关键根目录。
 */
internal class BuiltinHostPathBackend(
    private val appDataDir: File,
) : ExternalFolderBackend {
    private val appDataPath = BuiltinRealPathContext.canonicalOrAbs(appDataDir)

    override suspend fun stat(path: String): ExternalFolderEntry? = io {
        val clean = normalizeAbs(path)
        requireNotSecret(clean)
        val file = File(clean)
        val attrs = readAttributesOrNull(file) ?: return@io null
        val isDir = attrs.isDirectory
        val size = if (isDir) 0L else attrs.size()
        val hash = if (!isDir && size <= SMALL_HASH_BYTES) {
            runCatching { Sha256Utils.hash(file.readBytes()) }.getOrNull()
        } else null
        ExternalFolderEntry(
            path = clean,
            name = if (clean == "/") "" else file.name,
            isDirectory = isDir,
            size = size,
            lastModified = attrs.lastModifiedTime().toMillis(),
            sha256 = hash,
            identity = attrs.fileKey()?.toString(),
        )
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> = io {
        val clean = normalizeAbs(path)
        requireNotSecret(clean)
        val dir = File(clean)
        val attrs = readAttributesOrNull(dir) ?: fail("NOT_FOUND", "目录不存在：$clean")
        if (!attrs.isDirectory) fail("NOT_DIRECTORY", "不是目录：$clean")
        val names = try {
            dir.list()?.toList() ?: fail("ACCESS_DENIED", "系统权限不允许列举目录：$clean")
        } catch (denied: SecurityException) {
            fail("ACCESS_DENIED", "系统权限不允许列举目录：$clean")
        }
        names.mapNotNull { name ->
            currentCoroutineContext().ensureActive()
            val childPath = BuiltinRealPathPaths.joinPath(clean, "/$name")
            if (isSecretTarget(childPath)) return@mapNotNull null
            try {
                val childFile = File(childPath)
                val childAttrs = readAttributesOrNull(childFile) ?: return@mapNotNull null
                ExternalFolderEntry(
                    path = childPath,
                    name = name,
                    isDirectory = childAttrs.isDirectory,
                    size = if (childAttrs.isDirectory) 0L else childAttrs.size(),
                    lastModified = childAttrs.lastModifiedTime().toMillis(),
                    identity = childAttrs.fileKey()?.toString(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext()[ExternalDirectoryDiagnostics]?.skipped?.invoke(name, failure)
                null
            }
        }.sortedWith(compareByDescending<ExternalFolderEntry> { it.isDirectory }.thenBy { it.name })
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = io {
        if (maxBytes < 0 || maxBytes > Int.MAX_VALUE - 8L) fail("INVALID_ARGUMENT", "读取上限无效")
        val clean = normalizeAbs(path)
        requireNotSecret(clean)
        val file = requireReadableFile(clean)
        try {
            file.inputStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) fail("TOO_LARGE", "文件大小超过读取上限")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (denied: AccessDeniedException) {
            fail("ACCESS_DENIED", "系统权限不允许读取文件：$clean")
        } catch (denied: SecurityException) {
            fail("ACCESS_DENIED", "系统权限不允许读取文件：$clean")
        }
    }

    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T = io {
        if (maxBytes < 0) fail("INVALID_ARGUMENT", "读取上限无效")
        val clean = normalizeAbs(path)
        requireNotSecret(clean)
        val file = requireReadableFile(clean)
        val size = file.length()
        if (size > maxBytes) fail("TOO_LARGE", "文件大小超过读取上限")
        try {
            file.inputStream().use { consume(it) }
        } catch (denied: AccessDeniedException) {
            fail("ACCESS_DENIED", "系统权限不允许读取文件：$clean")
        } catch (denied: SecurityException) {
            fail("ACCESS_DENIED", "系统权限不允许读取文件：$clean")
        }
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = io {
        val clean = normalizeAbs(path)
        requireWritableHostPath(clean)
        val file = File(clean)
        val parent = file.parentFile ?: fail("INVALID_PATH", "不能直接写入根目录")
        if (!parent.isDirectory) fail("NOT_FOUND", "父目录不存在：${parent.path}")
        val exists = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (createOnly && exists) fail("NAME_EXISTS", "目标文件已存在：$clean")
        if (!createOnly && !exists) fail("NOT_FOUND", "文件不存在：$clean")
        if (exists && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) fail("IS_DIRECTORY", "目标是目录：$clean")
        if (expectedSha256 != null && exists) {
            val actual = Sha256Utils.hash(file.readBytes())
            if (actual != expectedSha256) fail("CONFLICT", "文件版本已变化，请重新读取后再写入")
        }
        val tmp = File(parent, ".infinity-host-tmp-${UUID.randomUUID()}")
        try {
            tmp.writeBytes(content)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (denied: AccessDeniedException) {
            fail("ACCESS_DENIED", "系统权限不允许写入：$clean")
        } catch (denied: SecurityException) {
            fail("ACCESS_DENIED", "系统权限不允许写入：$clean")
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
        stat(clean) ?: fail("NOT_FOUND", "写入后文件不可用：$clean")
    }

    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit): ExternalFolderEntry {
        val buffer = ByteArrayOutputStream()
        produce(buffer)
        return write(path, buffer.toByteArray(), expectedSha256 = null, createOnly = createOnly)
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry = io {
        val clean = normalizeAbs(path)
        requireWritableHostPath(clean)
        val file = File(clean)
        val parent = file.parentFile ?: fail("INVALID_PATH", "不能创建根目录")
        if (!parent.isDirectory) fail("NOT_FOUND", "父目录不存在：${parent.path}")
        if (file.exists()) fail("NAME_EXISTS", "目标已存在：$clean")
        try {
            Files.createDirectory(file.toPath())
        } catch (denied: AccessDeniedException) {
            fail("ACCESS_DENIED", "系统权限不允许创建目录：$clean")
        } catch (denied: SecurityException) {
            fail("ACCESS_DENIED", "系统权限不允许创建目录：$clean")
        }
        stat(clean) ?: fail("NOT_FOUND", "创建后目录不可用：$clean")
    }

    override suspend fun delete(path: String, expectedSha256: String?) = io {
        val clean = normalizeAbs(path)
        requireWritableHostPath(clean)
        if (ShellCommandSafetyClassifier.classifyShell("rm -rf '$clean'", appDataDir = appDataPath).verdict ==
            com.promenar.infinity.domain.tool.CommandSafetyVerdict.BLOCKED_CATASTROPHIC
        ) {
            fail("PROTECTED_RUNTIME_DATA", "禁止删除系统、存储或应用受保护根目录：$clean")
        }
        val file = File(clean)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) fail("NOT_FOUND", "条目不存在：$clean")
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (expectedSha256 != null) fail("INVALID_ARGUMENT", "目录不支持文件版本参数")
            if (!file.deleteRecursively()) fail("ACCESS_DENIED", "无法删除目录：$clean")
        } else {
            if (expectedSha256 != null && Sha256Utils.hash(file.readBytes()) != expectedSha256) {
                fail("CONFLICT", "文件版本已变化，未执行删除")
            }
            if (!file.delete()) fail("ACCESS_DENIED", "无法删除文件：$clean")
        }
    }

    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) =
        delete(path, expectedSha256)

    override suspend fun move(sourcePath: String, targetPath: String) = io {
        val src = normalizeAbs(sourcePath)
        val dst = normalizeAbs(targetPath)
        requireWritableHostPath(src)
        requireWritableHostPath(dst)
        val srcFile = File(src)
        if (!srcFile.exists()) fail("NOT_FOUND", "源条目不存在：$src")
        val dstFile = File(dst)
        if (dstFile.exists()) fail("NAME_EXISTS", "目标已存在：$dst")
        try {
            Files.move(srcFile.toPath(), dstFile.toPath())
        } catch (denied: AccessDeniedException) {
            fail("ACCESS_DENIED", "系统权限不允许移动：$src → $dst")
        } catch (denied: SecurityException) {
            fail("ACCESS_DENIED", "系统权限不允许移动：$src → $dst")
        }
        Unit
    }

    override suspend fun availableBytes(path: String): Long? = io {
        val clean = normalizeAbs(path)
        runCatching { File(clean).usableSpace }.getOrNull()
    }

    override suspend fun validateTree(path: String) = io {
        val clean = normalizeAbs(path)
        requireNotSecret(clean)
        if (!File(clean).exists()) fail("NOT_FOUND", "路径不存在：$clean")
    }

    fun isWritable(path: String): Boolean {
        val clean = runCatching { normalizeAbs(path) }.getOrNull() ?: return false
        if (isSecretTarget(clean) || isProtectedRuntimeTarget(clean)) return false
        val file = File(clean)
        val target = if (file.exists()) file else file.parentFile ?: return false
        return runCatching { target.canWrite() }.getOrDefault(false)
    }

    private fun isSecretTarget(clean: String): Boolean =
        resolvedCandidates(clean).any { BuiltinRealPathPaths.isSecretStorePath(it, appDataPath) }

    private fun isProtectedRuntimeTarget(clean: String): Boolean =
        resolvedCandidates(clean).any { BuiltinRealPathPaths.isProtectedRuntimeDataPath(it, appDataPath) }

    private fun resolvedCandidates(clean: String): List<String> {
        val file = File(clean)
        val resolved = runCatching {
            val canonical = if (file.exists()) {
                file.canonicalPath
            } else {
                val parentCanonical = file.parentFile?.canonicalPath ?: return@runCatching null
                BuiltinRealPathPaths.joinPath(parentCanonical, "/${file.name}")
            }
            BuiltinRealPathPaths.canonicalizeAppDataAlias(canonical, appDataPath, appDataDir.absolutePath)
        }.getOrNull()
        return if (resolved != null && resolved != clean) listOf(clean, resolved) else listOf(clean)
    }

    private fun requireNotSecret(clean: String) {
        if (isSecretTarget(clean)) {
            fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：$clean")
        }
    }

    private fun requireWritableHostPath(clean: String) {
        requireNotSecret(clean)
        if (isProtectedRuntimeTarget(clean)) {
            fail("PROTECTED_RUNTIME_DATA", "应用关键运行时数据目录仅开放只读自诊断，禁止通过文件工具修改或删除：$clean")
        }
    }

    private fun requireReadableFile(clean: String): File {
        val file = File(clean)
        val attrs = readAttributesOrNull(file) ?: fail("NOT_FOUND", "文件不存在：$clean")
        if (attrs.isDirectory) fail("IS_DIRECTORY", "目标是目录：$clean")
        return file
    }

    private fun readAttributesOrNull(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        null
    } catch (_: AccessDeniedException) {
        fail("ACCESS_DENIED", "系统权限不允许访问：${file.path}")
    } catch (_: SecurityException) {
        fail("ACCESS_DENIED", "系统权限不允许访问：${file.path}")
    }

    private fun normalizeAbs(raw: String): String {
        val lexical = ShellCommandSafetyClassifier.normalizeLexicalPath(raw)
            ?: fail("INVALID_PATH", "本机真实路径必须为绝对路径：$raw")
        return BuiltinRealPathPaths.canonicalizeAppDataAlias(lexical, appDataPath, appDataDir.absolutePath)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)

    private companion object {
        const val SMALL_HASH_BYTES = 1024L * 1024L
    }
}
