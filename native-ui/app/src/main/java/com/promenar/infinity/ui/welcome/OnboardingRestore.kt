package com.promenar.infinity.ui.welcome

import com.promenar.infinity.data.backup.BackupCrypto
import com.promenar.infinity.data.backup.BackupLayer
import com.promenar.infinity.data.backup.BackupManifest
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.ui.settings.BackupOperations
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 引导里只有第一个配置步骤提供“从备份恢复”：有 Linux 步骤时是 Linux，否则是连接提供商。 */
internal fun onboardingRestoreStep(linuxOffered: Boolean): OnboardingStep =
    if (linuxOffered) OnboardingStep.LINUX else OnboardingStep.PROVIDER

/**
 * 不解密能读到的包头信息。加密包只能确定“已加密”；明文分层包还能读出清单里的时间、来源版本与层。
 * [encrypted] 为 null 表示无法判断（读取失败或远端尚未下载），界面显示可留空的密码框。
 */
internal data class BackupPackagePeek(
    val encrypted: Boolean? = null,
    val createdAt: Long? = null,
    val appVersion: String? = null,
    /** 分层包包含的层；旧版不分层的包与加密包为 null。 */
    val layers: Set<BackupLayer>? = null,
) {
    val needsPassword: Boolean get() = encrypted != false
    val passwordOptional: Boolean get() = encrypted == null

    companion object {
        val Unknown = BackupPackagePeek()
        val Encrypted = BackupPackagePeek(encrypted = true)
    }
}

private val ZipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private const val MANIFEST_ENTRY = "manifest.json"
private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
private val PeekJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * 读取备份包头而不解密，也不暂存：只看前 4 字节判断加密信封，明文 zip 顺序扫描到清单为止。
 * 任何读取或解析失败都返回 [BackupPackagePeek.Unknown]，真正的校验仍由恢复管线完成。调用方负责关闭 [input]。
 */
internal fun peekBackupPackage(input: InputStream, crypto: BackupCrypto = BackupCrypto()): BackupPackagePeek = try {
    val buffered = input.buffered()
    buffered.mark(ZipMagic.size)
    val prefix = ByteArray(ZipMagic.size)
    var read = 0
    while (read < prefix.size) {
        val count = buffered.read(prefix, read, prefix.size - read)
        if (count < 0) break
        read += count
    }
    when {
        read < prefix.size -> BackupPackagePeek.Unknown
        crypto.isEncrypted(prefix) || crypto.isStreamEncrypted(prefix) -> BackupPackagePeek.Encrypted
        !prefix.contentEquals(ZipMagic) -> BackupPackagePeek.Unknown
        else -> {
            buffered.reset()
            readPlainManifest(buffered)?.let { manifest ->
                BackupPackagePeek(
                    encrypted = false,
                    createdAt = manifest.createdAt.takeIf { it > 0 },
                    appVersion = manifest.appVersion.takeIf(String::isNotBlank),
                    layers = manifest.layers?.keys,
                )
            } ?: BackupPackagePeek(encrypted = false)
        }
    }
} catch (_: Exception) {
    BackupPackagePeek.Unknown
}

private fun readPlainManifest(input: InputStream): BackupManifest? {
    val zip = ZipInputStream(input)
    while (true) {
        val entry = zip.nextEntry ?: return null
        if (entry.name != MANIFEST_ENTRY) continue
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            bytes.write(buffer, 0, count)
            if (bytes.size() > MAX_MANIFEST_BYTES) return null
        }
        return PeekJson.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8.name()))
    }
}

/** 恢复面板选中的备份：本机文件或 WebDAV 远端条目。 */
internal data class OnboardingRestorePackage(
    val name: String,
    val sizeBytes: Long?,
    val peek: BackupPackagePeek,
    /** 远端条目的修改时间；本机文件以包头时间为准。 */
    val modifiedAt: Long? = null,
    val remote: RemoteBackup? = null,
)

/**
 * 引导页使用的恢复操作：完全委托设置页的恢复管线，只在暂存成功后记下向导恢复标记，
 * 丢弃暂存后撤销标记。标记写入失败时抛出，管线会丢弃本次暂存并显示恢复失败。
 */
internal class OnboardingRestoreOperations(
    private val delegate: BackupOperations,
    private val store: OnboardingStateStore,
) : BackupOperations by delegate {
    override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?): PendingRestoreMetadata =
        delegate.stageLocalRestore(operationId, input, password).also { recordStaged(it) }

    override suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ): PendingRestoreMetadata = delegate.stageRemoteRestore(operationId, config, selected, password).also { recordStaged(it) }

    override suspend fun discardPendingRestore(operationId: String) {
        delegate.discardPendingRestore(operationId)
        store.clearRestoreStaged()
    }

    private fun recordStaged(staged: PendingRestoreMetadata) {
        check(store.recordRestoreStaged(staged.txId)) { "向导恢复标记写入失败" }
    }
}
