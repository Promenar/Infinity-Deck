package com.promenar.infinity.data.repository

import android.content.Context
import androidx.core.content.ContextCompat
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.AndroidPendingRestoreStore
import com.promenar.infinity.data.backup.appBackupSourceBases
import com.promenar.infinity.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.infinity.data.backup.AndroidTransactionalBackupPreferenceStore
import com.promenar.infinity.data.backup.AndroidTransactionalBackupSecretStore
import com.promenar.infinity.data.backup.BackupLayer
import com.promenar.infinity.data.backup.BackupLayerStats
import com.promenar.infinity.data.backup.BackupResultSummary
import com.promenar.infinity.data.backup.BackupStreams
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
    val summary: BackupResultSummary = BackupResultSummary(skills = skills),
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
    private val cacheDirectory: java.nio.file.Path? = null,
) {
    constructor(context: Context) : this(
        dataSource = createDataSource(context),
        codec = DefaultBackupPackageCodec(),
        webDav = KtorWebDavBackupClient(),
        pendingStore = AndroidPendingRestoreStore(context),
        cacheDirectory = context.applicationContext.cacheDir.resolve("backup-stream-v3").toPath(),
    )

    suspend fun estimate(): Map<BackupLayer, BackupLayerStats> = withContext(Dispatchers.IO) { dataSource.estimate() }

    suspend fun exportTo(output: java.io.OutputStream, options: BackupExportOptions = BackupExportOptions()): BackupResultSummary =
        withSpool { root ->
            val (file, summary) = createPackage(root, options)
            java.nio.file.Files.newInputStream(file).use { input ->
                BackupStreams.copy(input, output, BackupStreams.MAX_WIRE_BYTES)
            }
            output.flush()
            summary
        }

    private suspend fun createPackage(root: java.nio.file.Path, options: BackupExportOptions): Pair<java.nio.file.Path, BackupResultSummary> {
        val password = validatedPassword(options)
        val layers = options.layers + BackupLayer.CORE + if (options.includeSecrets) setOf(BackupLayer.SECRETS) else emptySet()
        var snapshot: com.promenar.infinity.data.backup.BackupSnapshot? = null
        try {
            if (BackupLayer.SECRETS in layers && !options.includeSecrets) throw BackupValidationException("密钥层需要加密密码确认")
            snapshot = dataSource.snapshotTo(layers, root)
            val target = root.resolve("export.iwb")
            val summary = java.nio.file.Files.newOutputStream(target).use { output -> codec.encodeTo(snapshot,
                BackupOptions(CANONICAL_CONTENT + if (options.includeSecrets) setOf(BackupContent.SECRETS) else emptySet(),
                    options.includeSecrets, password, options.encryptPackage || options.includeSecrets), output) }
            if (java.nio.file.Files.size(target) > BackupStreams.MAX_WIRE_BYTES) throw BackupValidationException("备份包超过传输上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            return target to summary
        } finally { snapshot?.wipe(); password?.fill('\u0000') }
    }

    private suspend fun <T> withSpool(block: suspend (java.nio.file.Path) -> T): T = withContext(Dispatchers.IO) {
        spoolMutex.lock()
        var owned: java.nio.file.Path? = null
        try {
            val candidate = (cacheDirectory ?: java.nio.file.Files.createTempDirectory("infinity-backup-")).toAbsolutePath().normalize()
            com.promenar.infinity.data.backup.FileRestoreJournal.rejectSymlinkAncestors(candidate)
            java.nio.file.Files.createDirectories(candidate)
            val root = com.promenar.infinity.data.backup.FileRestoreJournal.requireTrustedDirectory(candidate)
            owned = root
            BackupStreams.cleanCache(root)
            block(root)
        } catch (error: java.io.IOException) {
            if (owned?.toFile()?.usableSpace?.let { it < 1024 * 1024 } == true) throw BackupValidationException(
                "可用存储空间不足", error, BackupFailureReason.CAPACITY_EXCEEDED, com.promenar.infinity.data.backup.BackupFailureDetail.INSUFFICIENT_STORAGE)
            throw error
        } finally {
            try {
                owned?.let { root ->
                    BackupStreams.cleanCache(root)
                    if (cacheDirectory == null) java.nio.file.Files.deleteIfExists(root)
                }
            } finally { spoolMutex.unlock() }
        }
    }

    suspend fun export(options: BackupExportOptions = BackupExportOptions()): ByteArray = exportWithSummary(options).packageBytes

    suspend fun exportWithSummary(output: java.io.OutputStream, options: BackupExportOptions = BackupExportOptions()): BackupResultSummary = exportTo(output, options)

    /** 小包调用点的兼容桥接；应用导出使用 OutputStream 接口。 */
    suspend fun exportWithSummary(options: BackupExportOptions = BackupExportOptions()): BackupExportResult = withSpool { root ->
        val (path, summary) = createPackage(root, options)
        val bytes = java.nio.file.Files.newInputStream(path).use(::readBounded)
        BackupExportResult(bytes, summary.skills, summary)
    }

    suspend fun upload(config: WebDavConfig, options: BackupExportOptions = BackupExportOptions()): BackupUploadReceipt = withSpool { root ->
        val (file, summary) = createPackage(root, options)
        val result = webDav.uploadAndPrune(config, backupFileName(clock()), file, keep = REMOTE_KEEP)
        when (result) {
            is UploadAndPruneResult.Committed -> BackupUploadReceipt(result.fileName, result.pruneWarning, summary.skills, summary)
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
        withSpool { root ->
            pendingStore.begin(operationId)
            val path = root.resolve("import.iwb")
            java.nio.file.Files.newOutputStream(path).use { output -> BackupStreams.copy(input, output, BackupStreams.MAX_WIRE_BYTES) }
            stageValidatedFile(operationId, path, password, root)
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
    ): PendingRestoreMetadata = withSpool { root ->
        pendingStore.begin(operationId)
        val current = webDav.list(config).singleOrNull { it == selected }
            ?: throw BackupValidationException("所选备份已变化，请刷新列表")
        val path = root.resolve("download.iwb")
        webDav.downloadTo(config, current, path)
        stageValidatedFile(operationId, path, password, root)
    }

    private suspend fun stageValidatedFile(operationId: String, path: java.nio.file.Path, password: CharArray?, root: java.nio.file.Path): PendingRestoreMetadata {
        val owned = password?.copyOf()
        try {
            val summary = java.nio.file.Files.newInputStream(path).use { codec.decodeFrom(it, owned, root).use { validated ->
                dataSource.validateStaged(validated)
                BackupResultSummary(validated.manifest.layers.orEmpty(), validated.manifest.skipped, skills = validated.manifest.skills?.summary)
            } }
            return pendingStore.stageFile(operationId, path, owned).copy(summary = summary)
        } finally { owned?.fill('\u0000') }
    }

    fun stageValidated(packageBytes: ByteArray, password: CharArray? = null): PendingRestoreMetadata {
        return stageValidated(UUID.randomUUID().toString(), packageBytes, password)
    }

    fun stageValidated(operationId: String, packageBytes: ByteArray, password: CharArray? = null): PendingRestoreMetadata {
        requireBoundedPackage(packageBytes)
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            stageLocalRestore(operationId, packageBytes.inputStream(), password)
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
        private val spoolMutex = kotlinx.coroutines.sync.Mutex()

        private fun createDataSource(context: Context): BackupDataSource {
            val app = context.applicationContext as InfinityApplication
            val restoreBase = requireNotNull(ContextCompat.getNoBackupFilesDir(app))
                .resolve("backup-restore-runtime-v1").also { it.mkdirs() }.toPath()
            return RoomBackupDataSource(
                database = app.database,
                preferences = AndroidTransactionalBackupPreferenceStore(app),
                secrets = AndroidTransactionalBackupSecretStore(app, app.secretStore),
                trustedSourceBases = appBackupSourceBases(app.filesDir),
                trustedRestoreBase = restoreBase,
                appVersion = BuildConfig.VERSION_NAME,
                configurationRevision = ProviderConfigurationRevision::current,
                journalAuthenticator = AndroidRestoreJournalAuthenticator(),
                userSkillsRoot = app.filesDir.resolve("agent_skills").toPath(),
                avatarRoot = app.filesDir.resolve("avatars").toPath(),
                embeddingSpaceKey = { app.embeddingClient.vectorSpaceKey() },
            )
        }
    }
}
