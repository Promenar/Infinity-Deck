package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.isSafeRelativeSubPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 管理 ComfyUI 提交器脚本源码与配套辅助文件（同目录工具模块、静态工作流 JSON 等）。 */
interface ComfySubmitterFileStore {
    suspend fun saveFiles(
        hostId: String,
        scriptId: String,
        fileName: String,
        sourceText: String,
        supportFiles: Map<String, ByteArray> = emptyMap(),
    ): String

    suspend fun readEntryText(hostId: String, scriptId: String, fileName: String): String?
    suspend fun readSupportFile(hostId: String, scriptId: String, relativePath: String): ByteArray?
    suspend fun stageIntoDirectory(hostId: String, scriptId: String, targetDir: File): Boolean
    suspend fun deleteScript(hostId: String, scriptId: String)
    suspend fun deleteHost(hostId: String)
}

class FileComfySubmitterFileStore(private val rootDir: File) : ComfySubmitterFileStore {
    private val mutex = Mutex()

    override suspend fun saveFiles(
        hostId: String,
        scriptId: String,
        fileName: String,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
    ): String = withContext(Dispatchers.IO) {
        validateIds(hostId, scriptId)
        require(isSafeRelativeSubPath(fileName) && fileName.endsWith(".py")) { "主脚本路径无效：$fileName" }
        val mainBytes = sourceText.toByteArray(Charsets.UTF_8)
        require(mainBytes.size <= MAX_SCRIPT_BYTES) { "主提交脚本超过大小上限（512 KiB）" }
        val totalSupportBytes = supportFiles.values.sumOf { it.size.toLong() }
        require(supportFiles.size <= 64 && totalSupportBytes <= MAX_SUPPORT_BUNDLE_BYTES) {
            "配套文件过多或超过总大小上限（16 MiB）"
        }
        supportFiles.keys.forEach { rel ->
            require(isSafeRelativeSubPath(rel) && rel != fileName) { "配套文件相对路径无效或与主脚本重名：$rel" }
        }
        val sha = sha256Hex(mainBytes)
        mutex.withLock {
            val dir = scriptDir(hostId, scriptId)
            if (dir.exists()) dir.deleteRecursively()
            check(dir.mkdirs() || dir.isDirectory) { "无法创建提交器目录" }
            val entryFile = safeChild(dir, fileName)
            entryFile.parentFile?.mkdirs()
            entryFile.writeBytes(mainBytes)
            supportFiles.forEach { (rel, bytes) ->
                val target = safeChild(dir, rel)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
        sha
    }

    override suspend fun readEntryText(hostId: String, scriptId: String, fileName: String): String? =
        withContext(Dispatchers.IO) {
            if (!isValidId(hostId) || !isValidId(scriptId) || !isSafeRelativeSubPath(fileName)) return@withContext null
            mutex.withLock {
                val file = safeChild(scriptDir(hostId, scriptId), fileName)
                if (file.isFile) file.readText(Charsets.UTF_8) else null
            }
        }

    override suspend fun readSupportFile(hostId: String, scriptId: String, relativePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!isValidId(hostId) || !isValidId(scriptId) || !isSafeRelativeSubPath(relativePath)) return@withContext null
            mutex.withLock {
                val file = safeChild(scriptDir(hostId, scriptId), relativePath)
                if (file.isFile) file.readBytes() else null
            }
        }

    override suspend fun stageIntoDirectory(hostId: String, scriptId: String, targetDir: File): Boolean =
        withContext(Dispatchers.IO) {
            validateIds(hostId, scriptId)
            mutex.withLock {
                val src = scriptDir(hostId, scriptId)
                if (!src.isDirectory) return@withLock false
                val targetCanonical = targetDir.canonicalFile
                src.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val rel = file.relativeTo(src).invariantSeparatorsPath
                        val dest = safeChild(targetCanonical, rel)
                        dest.parentFile?.mkdirs()
                        file.copyTo(dest, overwrite = true)
                    }
                }
                true
            }
        }

    override suspend fun deleteScript(hostId: String, scriptId: String) {
        withContext(Dispatchers.IO) {
            if (!isValidId(hostId) || !isValidId(scriptId)) return@withContext
            mutex.withLock { scriptDir(hostId, scriptId).deleteRecursively() }
        }
    }

    override suspend fun deleteHost(hostId: String) {
        withContext(Dispatchers.IO) {
            if (!isValidId(hostId)) return@withContext
            mutex.withLock { File(rootDir, hostId).deleteRecursively() }
        }
    }

    private fun scriptDir(hostId: String, scriptId: String): File =
        File(File(rootDir, hostId), scriptId).canonicalFile

    private fun safeChild(baseDir: File, relativePath: String): File {
        val baseCanonical = baseDir.canonicalFile
        val resolved = File(baseCanonical, relativePath).canonicalFile
        val basePrefix = baseCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        require(resolved.path.startsWith(basePrefix)) { "提交器路径超出隔离目录范围：$relativePath" }
        return resolved
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 512 * 1024
        const val MAX_SUPPORT_BUNDLE_BYTES = 16L * 1024 * 1024
        fun isValidId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
        fun validateIds(hostId: String, scriptId: String) {
            require(isValidId(hostId) && isValidId(scriptId)) { "hostId 或 scriptId 格式无效" }
        }
    }
}

class InMemoryComfySubmitterFileStore : ComfySubmitterFileStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<Pair<String, String>, Pair<String, Map<String, ByteArray>>>()

    override suspend fun saveFiles(
        hostId: String,
        scriptId: String,
        fileName: String,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
    ): String {
        require(hostId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && scriptId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "hostId 或 scriptId 格式无效"
        }
        require(isSafeRelativeSubPath(fileName) && fileName.endsWith(".py")) { "主脚本路径无效" }
        supportFiles.keys.forEach { rel ->
            require(isSafeRelativeSubPath(rel) && rel != fileName) { "配套文件路径无效：$rel" }
        }
        val mainBytes = sourceText.toByteArray(Charsets.UTF_8)
        val sha = sha256Hex(mainBytes)
        mutex.withLock {
            val map = linkedMapOf(fileName to mainBytes)
            supportFiles.forEach { (k, v) -> map[k] = v.copyOf() }
            entries[hostId to scriptId] = fileName to map
        }
        return sha
    }

    override suspend fun readEntryText(hostId: String, scriptId: String, fileName: String): String? =
        mutex.withLock { entries[hostId to scriptId]?.second?.get(fileName)?.toString(Charsets.UTF_8) }

    override suspend fun readSupportFile(hostId: String, scriptId: String, relativePath: String): ByteArray? =
        mutex.withLock { entries[hostId to scriptId]?.second?.get(relativePath)?.copyOf() }

    override suspend fun stageIntoDirectory(hostId: String, scriptId: String, targetDir: File): Boolean =
        mutex.withLock {
            val bundle = entries[hostId to scriptId]?.second ?: return@withLock false
            val baseCanonical = targetDir.canonicalFile
            bundle.forEach { (rel, bytes) ->
                val dest = File(baseCanonical, rel).canonicalFile
                val basePrefix = baseCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
                require(dest.path.startsWith(basePrefix))
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
            }
            true
        }

    override suspend fun deleteScript(hostId: String, scriptId: String) {
        mutex.withLock { entries.remove(hostId to scriptId) }
    }

    override suspend fun deleteHost(hostId: String) {
        mutex.withLock { entries.keys.removeAll { it.first == hostId } }
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
