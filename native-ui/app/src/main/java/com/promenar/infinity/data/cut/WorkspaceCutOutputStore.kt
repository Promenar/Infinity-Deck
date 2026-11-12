package com.promenar.infinity.data.cut

import com.promenar.infinity.data.production.CutCompositionPlan
import com.promenar.infinity.data.production.CutExportRecord
import com.promenar.infinity.data.production.ProductionManifestCodec
import com.promenar.infinity.data.storage.copyExternalStream
import com.promenar.infinity.data.storage.externalStreamHash
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface CutOutputStore {
    suspend fun publish(
        sessionId: String,
        plan: CutCompositionPlan,
        video: File,
        register: suspend (CutExportRecord) -> Unit,
    ): CutExportRecord
}

/** 绑定须在整个事务期间指向同一工作区；私有工作区通过 [afterWrite] 确认新文件。 */
data class CutOutputTarget(
    val backend: ExternalFolderBackend,
    val afterWrite: suspend () -> Unit = {},
)

/** 成片先冻结到本地，工作区替换失败时用本地备份补偿，所有删除均绑定身份和摘要。 */
class WorkspaceCutOutputStore(
    private val cacheDir: File,
    private val resolveTarget: suspend (sessionId: String, path: String) -> CutOutputTarget,
    private val now: () -> Long = System::currentTimeMillis,
) : CutOutputStore {
    private data class LocalFile(val file: File, val size: Long, val sha256: String)
    private data class Original(val entry: ExternalFolderEntry, val local: LocalFile)
    private class Output(val path: String, val target: CutOutputTarget, val local: LocalFile) {
        val virtualPath = "/$path"
        var original: Original? = null
        var deleteAttempted = false
        var writeAttempted = false
        var created: ExternalFolderEntry? = null
    }

    private val publicationMutex = Mutex()

    override suspend fun publish(
        sessionId: String,
        plan: CutCompositionPlan,
        video: File,
        register: suspend (CutExportRecord) -> Unit,
    ): CutExportRecord = withContext(Dispatchers.IO) {
        publicationMutex.withLock {
            validate(plan)
            require(video.isFile && video.length() > 0) { "成片临时文件不存在或为空。" }
            check(cacheDir.isDirectory || cacheDir.mkdirs()) { "无法建立成片恢复目录。" }
            val recoveryDirectory = Files.createTempDirectory(cacheDir.toPath(), "cut-output-").toFile()
            val outputs = mutableListOf<Output>()
            var preserveRecovery = false
            try {
                val stagedVideo = freeze(video, File(recoveryDirectory, "video.mp4"))
                val subtitle = File(recoveryDirectory, "subtitles.srt").apply { writeText(plan.srt, Charsets.UTF_8) }
                val stagedSubtitle = describe(subtitle)
                outputs += Output(plan.videoPath, resolveTarget(sessionId, plan.videoPath), stagedVideo)
                outputs += Output(plan.subtitlePath, resolveTarget(sessionId, plan.subtitlePath), stagedSubtitle)
                // 在第一次工作区删除之前备齐两份旧文件和可独立解释的恢复清单。
                outputs.forEachIndexed { index, output ->
                    output.original = backup(output, File(recoveryDirectory, "original-$index.bin"))
                }
                recoveryManifest(recoveryDirectory, sessionId, outputs)
                outputs.forEach { ensureParents(it) }
                for (output in outputs) {
                    currentCoroutineContext().ensureActive()
                    output.original?.let { original ->
                        output.deleteAttempted = true
                        output.target.backend.deleteForTransfer(output.virtualPath, original.entry, original.local.sha256)
                        if (output.target.backend.stat(output.virtualPath) != null) conflict("旧产物删除未完成")
                    }
                    output.writeAttempted = true
                    // 非取消区只保护写入结果与 receipt 的交接；生产者逐块检查原调用方的取消状态。
                    val caller = currentCoroutineContext()
                    withContext(NonCancellable) {
                        output.created = writeLocal(output, output.local, caller)
                    }
                    caller.ensureActive()
                    verify(output, requireNotNull(output.created), output.local)
                    output.target.afterWrite()
                }
                outputs.forEach { verify(it, requireNotNull(it.created), it.local) }
                currentCoroutineContext().ensureActive()
                val record = CutExportRecord(plan.videoPath, plan.subtitlePath, plan.durationSeconds, stagedVideo.size, now())
                require(record.exportedAt >= 0) { "成片导出时间不能为负数。" }
                register(record)
                record
            } catch (failure: Exception) {
                val rollbackFailures = withContext(NonCancellable) {
                    outputs.asReversed().mapNotNull { output ->
                        try { rollback(output); null } catch (cleanup: Exception) { cleanup }
                    }
                }
                if (rollbackFailures.isNotEmpty()) {
                    preserveRecovery = true
                    throw ExternalFolderException(
                        "RECOVERY_REQUIRED",
                        "成片事务未能安全恢复，请核查工作区产物；本地恢复文件：${recoveryDirectory.absolutePath}",
                    ).apply {
                        addSuppressed(failure)
                        rollbackFailures.forEach(::addSuppressed)
                    }
                }
                throw failure
            } finally {
                if (!preserveRecovery) withContext(NonCancellable) { recoveryDirectory.deleteRecursively() }
            }
        }
    }

    private fun validate(plan: CutCompositionPlan) {
        ProductionManifestCodec.validatePath(plan.videoPath)
        ProductionManifestCodec.validatePath(plan.subtitlePath)
        val directory = plan.videoPath.substringBeforeLast('/', "")
        require(directory.endsWith("/cuts") && plan.subtitlePath.substringBeforeLast('/', "") == directory &&
            plan.videoPath.endsWith(".mp4") && plan.subtitlePath.endsWith(".srt")) { "成片与字幕必须位于项目 cuts 目录。" }
        require(plan.durationSeconds.isFinite() && plan.durationSeconds > 0) { "成片时长必须为有限正数。" }
    }

    private suspend fun freeze(source: File, target: File): LocalFile {
        val size = source.length()
        val hash = source.inputStream().use { input ->
            target.outputStream().use { output -> copyExternalStream(input, output).also { output.fd.sync() } }
        }
        if (target.length() != size || source.length() != size) conflict("成片临时文件在读取期间改变")
        return LocalFile(target, size, hash)
    }

    private suspend fun describe(file: File) = LocalFile(file, file.length(), file.inputStream().use { externalStreamHash(it) })

    private suspend fun backup(output: Output, file: File): Original? {
        val backend = output.target.backend
        val before = backend.stat(output.virtualPath) ?: return null
        require(!before.isDirectory) { "成片目标被同名目录占用。" }
        require(before.size >= 0) { "旧产物大小无效。" }
        if (before.identity == null) throw ExternalFolderException("UNSUPPORTED", "存储后端无法提供产物身份，不能安全替换。")
        val hash = backend.readStream(output.virtualPath, before.size) { input ->
            file.outputStream().use { local -> copyExternalStream(input, local).also { local.fd.sync() } }
        }
        val after = backend.stat(output.virtualPath) ?: conflict("备份期间旧产物已不存在")
        if (before.identity != after.identity || after.isDirectory || before.size != after.size || file.length() != before.size ||
            after.sha256 != null && after.sha256 != hash) conflict("备份期间旧产物发生变化")
        return Original(after, LocalFile(file, file.length(), hash))
    }

    private suspend fun ensureParents(output: Output) {
        val missing = mutableListOf<String>()
        var path = output.virtualPath.substringBeforeLast('/')
        while (path.isNotEmpty() && path != "/") {
            val entry = output.target.backend.stat(path)
            if (entry != null) {
                require(entry.isDirectory) { "成片父路径被同名文件占用。" }
                break
            }
            missing += path
            path = path.substringBeforeLast('/', "")
        }
        // 从最近的实体目录向下建立，挂载聚合节点 /mnt 不属于可直接访问的存储根。
        missing.asReversed().forEach { output.target.backend.createDirectory(it) }
    }

    private suspend fun writeLocal(output: Output, local: LocalFile, caller: CoroutineContext): ExternalFolderEntry =
        output.target.backend.writeStream(output.virtualPath, local.size, createOnly = true) { destination ->
            copyLocal(local, destination, caller)
        }

    private suspend fun copyLocal(local: LocalFile, output: OutputStream, caller: CoroutineContext) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        local.file.inputStream().use { input ->
            while (true) {
                caller.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
            }
        }
        caller.ensureActive()
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (size != local.size || hash != local.sha256) conflict("本地成片或恢复副本发生变化")
    }

    private suspend fun verify(output: Output, expected: ExternalFolderEntry, local: LocalFile) {
        if (expected.identity == null) throw ExternalFolderException("UNSUPPORTED", "存储后端无法确认产物身份。")
        val backend = output.target.backend
        val before = backend.stat(output.virtualPath) ?: conflict("产物已不存在")
        if (before.identity != expected.identity || before.isDirectory || before.size != local.size ||
            backend.readStream(output.virtualPath, local.size, ::externalStreamHash) != local.sha256) conflict("产物身份或内容发生变化")
        val after = backend.stat(output.virtualPath) ?: conflict("校验期间产物已不存在")
        if (after.identity != before.identity || after.isDirectory || after.size != before.size || after.lastModified != before.lastModified) {
            conflict("校验期间产物发生变化")
        }
    }

    private suspend fun rollback(output: Output) {
        if (!output.deleteAttempted && !output.writeAttempted) return
        val backend = output.target.backend
        val created = output.created
        if (created != null && backend.stat(output.virtualPath) != null) {
            verify(output, created, output.local)
            backend.deleteForTransfer(output.virtualPath, created, output.local.sha256)
        } else if (created == null && output.writeAttempted && backend.stat(output.virtualPath) != null) {
            // writeStream 自行清理未交付 receipt 的失败写入；剩余条目无法证明归属。
            conflict("失败写入留下身份未知的产物，不能按路径删除")
        }
        val original = output.original ?: return
        if (!output.deleteAttempted) return
        if (backend.stat(output.virtualPath) == null) {
            val restored = writeLocal(output, original.local, currentCoroutineContext())
            verify(output, restored, original.local)
            output.target.afterWrite()
        } else {
            // 外部并发编辑保留原位，恢复副本交由调用方诊断。
            verify(output, original.entry, original.local)
        }
    }

    private fun recoveryManifest(directory: File, sessionId: String, outputs: List<Output>) {
        val properties = Properties().apply {
            setProperty("sessionId", sessionId)
            outputs.forEachIndexed { index, output ->
                setProperty("$index.path", output.path)
                setProperty("$index.new.file", output.local.file.name)
                setProperty("$index.new.sha256", output.local.sha256)
                output.original?.let { original ->
                    setProperty("$index.original.file", original.local.file.name)
                    setProperty("$index.original.sha256", original.local.sha256)
                    setProperty("$index.original.identity", requireNotNull(original.entry.identity))
                }
            }
        }
        File(directory, "recovery.properties").outputStream().use { output ->
            properties.store(output, "成片产物事务恢复信息")
            output.fd.sync()
        }
    }

    private fun conflict(message: String): Nothing = throw ExternalFolderException("CONFLICT", message)
}
