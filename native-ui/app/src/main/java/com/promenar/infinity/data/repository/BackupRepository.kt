package com.promenar.infinity.data.repository

import android.content.Context
import androidx.core.content.ContextCompat
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.AndroidPendingRestoreStore
import com.promenar.infinity.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.infinity.data.backup.AndroidTransactionalBackupPreferenceStore
import com.promenar.infinity.data.backup.AndroidTransactionalBackupSecretStore
import com.promenar.infinity.data.backup.BackupContent
import com.promenar.infinity.data.backup.BackupDataSource
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.BackupExportResult
import com.promenar.infinity.data.backup.BackupFailureReason
import com.promenar.infinity.data.backup.SkillBackupSummary
import com.promenar.infinity.data.backup.BackupOptions
import com.promenar.infinity.data.backup.BackupPackageCodec
import com.promenar.infinity.data.backup.BackupPackageLimits
import com.promenar.infinity.data.backup.BackupValidationException
import com.promenar.infinity.data.backup.DefaultBackupPackageCodec
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.backup.PendingRestoreStore
import com.promenar.infinity.data.backup.RoomBackupDataSource
import com.promenar.infinity.data.backup.wipe
import com.promenar.infinity.data.backup.WipeableByteArrayOutputStream
import com.promenar.infinity.data.manager.ProviderConfigurationRevision
import com.promenar.infinity.data.remote.webdav.KtorWebDavBackupClient
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.UploadAndPruneResult
import com.promenar.infinity.data.remote.webdav.WebDavBackupClient
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.data.remote.webdav.WebDavPruneWarning
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupUploadReceipt(
    val fileName: String,
    val pruneWarning: WebDavPruneWarning?,
    val skills: SkillBackupSummary? = null,
)

/**
 * 备份用例边界。只接受 typed 参数，并委托 Task 5/6/7 的安全实现；不感知 UI 状态或 DAO。
 * 返回的包字节由调用方拥有，调用方使用完毕后负责擦除。
 */
class BackupRepository internal constructor(
    private val dataSource: BackupDataSource,
    private val codec: BackupPackageCodec,
    private val webDav: WebDavBackupClient,
    private val pendingStore: PendingRestoreStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        dataSource = createDataSource(context),
        codec = DefaultBackupPackageCodec(),
        webDav = KtorWebDavBackupClient(),
        pendingStore = AndroidPendingRestoreStore(context),
    )

    suspend fun export(options: BackupExportOptions = BackupExportOptions()): ByteArray = exportWithSummary(options).packageBytes

    suspend fun exportWithSummary(options: BackupExportOptions = BackupExportOptions()): BackupExportResult = withContext(Dispatchers.IO) {
        val password = validatedPassword(options)
        val content = CANONICAL_CONTENT + if (options.includeSecrets) setOf(BackupContent.SECRETS) else emptySet()
        var snapshot: com.promenar.infinity.data.backup.BackupSnapshot? = null
        try {
            val captured = dataSource.snapshot(content)
            snapshot = captured
            val bytes = codec.encode(
                captured,
                BackupOptions(
                    content = content,
                    includeSecrets = options.includeSecrets,
                    encryptPackage = options.encryptPackage || options.includeSecrets,
                    password = password,
                ),
            )
                .also(::requireBoundedPackage)
            BackupExportResult(bytes, captured.skills?.metadata?.summary)
        } finally {
            snapshot?.wipe()
            password?.fill('\u0000')
        }
    }

    suspend fun upload(config: WebDavConfig, options: BackupExportOptions = BackupExportOptions()): BackupUploadReceipt {
        val exported = exportWithSummary(options)
        val bytes = exported.packageBytes
        try {
            val fileName = backupFileName(clock())
            val result = webDav.uploadAndPrune(config, fileName, bytes, keep = REMOTE_KEEP)
            return when (result) {
                is UploadAndPruneResult.Committed -> BackupUploadReceipt(result.fileName, result.pruneWarning, exported.skills)
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun testRemote(config: WebDavConfig): Result<Unit> = webDav.test(config)

    suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> = webDav.list(config)

    suspend fun downloadRemote(config: WebDavConfig, selected: RemoteBackup): ByteArray {
        val current = webDav.list(config).singleOrNull { it == selected }
            ?: throw BackupValidationException("所选远程备份已变化，请刷新列表后重试")
        return webDav.download(config, current).also(::requireBoundedPackage)
    }

    suspend fun stageLocalRestore(input: InputStream, password: CharArray? = null): PendingRestoreMetadata =
        stageLocalRestore(UUID.randomUUID().toString(), input, password)

    suspend fun stageLocalRestore(
        operationId: String,
        input: InputStream,
        password: CharArray? = null,
    ): PendingRestoreMetadata =
        withContext(Dispatchers.IO) {
            pendingStore.begin(operationId)
            val bytes = readBounded(input)
            try {
                stageValidated(operationId, bytes, password)
            } finally {
                bytes.fill(0)
            }
        }

    suspend fun stageRemoteRestore(
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray? = null,
    ): PendingRestoreMetadata = stageRemoteRestore(UUID.randomUUID().toString(), config, selected, password)

    suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray? = null,
    ): PendingRestoreMetadata = withContext(Dispatchers.IO) {
        pendingStore.begin(operationId)
        val bytes = downloadRemote(config, selected)
        try {
            stageValidated(operationId, bytes, password)
        } finally {
            bytes.fill(0)
        }
    }

    fun stageValidated(packageBytes: ByteArray, password: CharArray? = null): PendingRestoreMetadata {
        return stageValidated(UUID.randomUUID().toString(), packageBytes, password)
    }

    fun stageValidated(operationId: String, packageBytes: ByteArray, password: CharArray? = null): PendingRestoreMetadata {
        requireBoundedPackage(packageBytes)
        pendingStore.begin(operationId)
        val ownedPassword = password?.copyOf()
        try {
            codec.decode(packageBytes, ownedPassword).use { /* 验证先行，运行中绝不写 Operational 数据 */ }
            return pendingStore.stage(operationId, packageBytes, ownedPassword)
        } finally {
            ownedPassword?.fill('\u0000')
        }
    }

    suspend fun discardPendingRestore(operationId: String) = withContext(Dispatchers.IO) {
        pendingStore.cancel(operationId)
    }

    suspend fun authorizePendingRestore(operationId: String) = withContext(Dispatchers.IO) {
        pendingStore.authorize(operationId)
    }

    private fun validatedPassword(options: BackupExportOptions): CharArray? {
        val encryptionRequired = options.encryptPackage || options.includeSecrets
        if (!encryptionRequired) return null
        val password = options.password ?: throw BackupValidationException("加密备份必须设置备份密码")
        val confirmation = options.passwordConfirmation ?: throw BackupValidationException("加密备份必须确认备份密码")
        if (password.isEmpty() || confirmation.isEmpty()) throw BackupValidationException("备份密码不能为空")
        var difference = password.size xor confirmation.size
        val length = maxOf(password.size, confirmation.size)
        for (index in 0 until length) {
            difference = difference or ((password.getOrNull(index)?.code ?: 0) xor
                (confirmation.getOrNull(index)?.code ?: 0))
        }
        if (difference != 0) throw BackupValidationException("两次输入的备份密码不一致")
        return password.copyOf()
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = WipeableByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size().toLong() + count > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
                    throw BackupValidationException("本地备份包超过 16 MiB 安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                }
                output.write(buffer, 0, count)
            }
            val result = output.toByteArray()
            try {
                requireBoundedPackage(result)
                return result
            } catch (error: Throwable) {
                result.fill(0)
                throw error
            }
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private fun requireBoundedPackage(bytes: ByteArray) {
        if (bytes.isEmpty()) throw BackupValidationException("备份包大小无效")
        if (bytes.size.toLong() > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("备份包大小无效", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
    }

    private fun backupFileName(timestamp: Long): String =
        "infinity_backup_${timestamp.toString().padStart(13, '0')}.iwb"

    companion object {
        val CANONICAL_CONTENT = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
        const val REMOTE_KEEP = 5

        private fun createDataSource(context: Context): BackupDataSource {
            val app = context.applicationContext as InfinityApplication
            val restoreBase = requireNotNull(ContextCompat.getNoBackupFilesDir(app))
                .resolve("backup-restore-runtime-v1").also { it.mkdirs() }.toPath()
            return RoomBackupDataSource(
                database = app.database,
                preferences = AndroidTransactionalBackupPreferenceStore(app),
                secrets = AndroidTransactionalBackupSecretStore(app, app.secretStore),
                trustedSourceBases = linkedSetOf(
                    app.filesDir.toPath(),
                    app.filesDir.resolve("WorkSpace").also { it.mkdirs() }.toPath(),
                    app.filesDir.resolve("workspaces").also { it.mkdirs() }.toPath(),
                ),
                trustedRestoreBase = restoreBase,
                appVersion = BuildConfig.VERSION_NAME,
                configurationRevision = ProviderConfigurationRevision::current,
                journalAuthenticator = AndroidRestoreJournalAuthenticator(),
                userSkillsRoot = app.filesDir.resolve("agent_skills").toPath(),
                avatarRoot = app.filesDir.resolve("avatars").toPath(),
            )
        }
    }
}
