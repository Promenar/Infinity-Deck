package com.promenar.infinity.data.ssh

import com.promenar.infinity.domain.jobs.SftpTransferFailure
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MAX_SFTP_TRANSFER_BYTES = 4L * 1024 * 1024 * 1024
internal const val SFTP_TRANSFER_BUFFER_BYTES = 64 * 1024

data class SftpLocalStat(val size: Long, val revision: String)
data class SftpRemoteStat(val size: Long, val modifiedSeconds: Int, val regular: Boolean)
data class SftpLocalExpectation(val stat: SftpLocalStat?)
data class SftpRemoteExpectation(val stat: SftpRemoteStat?)

/** 本地端口负责路径隔离、临时文件、校验后提交及工作区索引。回调只在 IO 调度器执行；onCommitted 在发布后立即登记不可逆结果。 */
interface SftpLocalFile {
    suspend fun stat(): SftpLocalStat?
    suspend fun <T> read(reader: (InputStream) -> T): T
    suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit = {}, beforeCommit: () -> Unit = {}, onCommitted: (String) -> Unit = {}, writer: (OutputStream) -> String): String
}

data class SftpTransferResult(
    val bytes: Long, val sha256: String, val elapsedSeconds: Double,
    val timingsMs: Map<String, Double> = emptyMap(), val connectionSource: String = "unknown",
) {
    fun toJson() = buildJsonObject {
        put("bytes", bytes); put("sha256", sha256); put("elapsed_seconds", elapsedSeconds)
        put("timings_ms", buildJsonObject { timingsMs.forEach { (stage, ms) -> put(stage, ms) } })
        put("connection_source", connectionSource)
    }
}

/** 通过协程上下文共享给后台任务与传输工作线程；只记录单调耗时和固定阶段名。 */
internal class SftpTransferTrace(private val queuedAt: Long = System.nanoTime()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SftpTransferTrace>
    private val nanos = linkedMapOf<String, Long>()
    private var stage: String? = null
    private var stageStarted = queuedAt
    @Volatile var connectionSource = "unknown"

    @Synchronized fun next(name: String?) {
        val time = System.nanoTime()
        stage?.let { nanos[it] = (nanos[it] ?: 0L) + (time - stageStarted).coerceAtLeast(0) }
        stage = name
        stageStarted = time
    }
    @Synchronized fun workerStarted() {
        nanos["queue_prepare"] = (System.nanoTime() - queuedAt).coerceAtLeast(0)
    }
    @Synchronized fun snapshot(): Map<String, Double> = nanos.mapValues { it.value / 1_000_000.0 }
    fun fields() = buildJsonObject {
        put("timings_ms", buildJsonObject { snapshot().forEach { (stage, ms) -> put(stage, ms) } })
        put("connection_source", connectionSource)
    }
    fun summary() = "SFTP 分段耗时（ms，连接=$connectionSource）：" + snapshot().entries.joinToString(" · ") {
        "${it.key}=%.1f".format(java.util.Locale.ROOT, it.value)
    }
}

internal fun sftpProgressText(total: Long?, done: Long = 0, speed: Long? = null): String {
    fun amount(bytes: Long): String {
        val units = listOf("B", "KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return if (unit == 0) "$bytes B" else "%.1f %s".format(java.util.Locale.ROOT, value, units[unit])
    }
    return "\r已传 ${amount(done)} / ${total?.let(::amount) ?: "未知"}" +
        (speed?.takeIf { it > 0 }?.let { " · ${amount(it)}/s" } ?: "")
}

internal fun requireSftpSize(size: Long) {
    if (size < 0 || size > MAX_SFTP_TRANSFER_BYTES) throw SshFailure("TRANSFER_TOO_LARGE")
}

/** 每个分块均检查取消与总量；源文件变长、缩短都不能发布。 */
internal fun copySftpBytes(input: InputStream, output: OutputStream, size: Long, check: () -> Unit, progress: (Long) -> Unit): String {
    requireSftpSize(size)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(SFTP_TRANSFER_BUFFER_BYTES)
    var count = 0L
    try {
        while (true) {
            check()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            count += read
            if (count > size || count > MAX_SFTP_TRANSFER_BYTES) throw SshFailure("SOURCE_CHANGED")
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            progress(count)
        }
        if (count != size) throw SshFailure("SOURCE_CHANGED")
        check()
        return digest.digest().toHex()
    } finally { buffer.fill(0) }
}

internal fun sftpHash(input: InputStream, size: Long, check: () -> Unit = {}): String =
    copySftpBytes(input, object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }, size, check, {})

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun sftpFailure(code: String): SftpTransferFailure = when (code.removePrefix("SSH_")) {
    "CHECKSUM_MISMATCH" -> SftpTransferFailure.CHECKSUM_MISMATCH
    "TARGET_CONFLICT", "SOURCE_CHANGED" -> SftpTransferFailure.TARGET_CONFLICT
    "NO_SPACE" -> SftpTransferFailure.NO_SPACE
    "TIMEOUT", "SERVICE_TIME_LIMIT" -> SftpTransferFailure.TIME_LIMIT
    "STOPPED" -> SftpTransferFailure.STOPPED
    "CONNECTION_FAILED", "CONNECTION_REFUSED", "HOST_UNREACHABLE", "DNS_FAILED", "IO_FAILED", "OPERATION_FAILED" -> SftpTransferFailure.NETWORK
    else -> SftpTransferFailure.OTHER
}

/** 生成式下载不能绕过既有技能目录的逐次审批入口。 */
fun isSftpProtectedLocalPath(path: String): Boolean {
    val parts = path.replace('\\', '/').split('/').filter(String::isNotEmpty).map { it.lowercase(java.util.Locale.ROOT) }
    return parts.firstOrNull() == "skills" || ".agents" in parts ||
        com.promenar.infinity.data.skills.BuiltinSkillCatalog.isVirtualPath(path)
}

/** 明确的本地错误与远端空间状态不能被通用网络错误覆盖。 */
internal fun classifySftpFailure(failure: Exception): Exception = when {
    failure is com.promenar.infinity.domain.linux.LinuxFileException ||
        failure is com.promenar.infinity.domain.storage.ExternalFolderException -> failure
    failure is com.jcraft.jsch.SftpException && failure.id == 14 -> SshFailure("NO_SPACE")
    else -> classifySshFailure(failure)
}

/** 提交后只吸收中断；索引等真实错误必须让调用方看见。 */
internal fun committedSftpResult(result: SftpTransferResult?, failure: Throwable): SftpTransferResult? = result?.takeIf {
    failure is kotlinx.coroutines.CancellationException ||
        failure is SshFailure && failure.code in setOf("STOPPED", "TIMEOUT", "SERVICE_TIME_LIMIT")
}
