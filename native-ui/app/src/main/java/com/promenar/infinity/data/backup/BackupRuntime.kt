package com.promenar.infinity.data.backup

import android.content.Context
import androidx.core.content.ContextCompat
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.security.SecretStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface BackupStartupState {
    data object Recovering : BackupStartupState
    data object Ready : BackupStartupState
    data object Blocked : BackupStartupState
    data class DualDatabaseRecoveryRequired(val summary: String, val offerToken: String) : BackupStartupState
    data class DualDatabaseRecoveryBlocked(val detail: String) : BackupStartupState
}

/** 启动期安全恢复闸门；只有 recovery 成功后才允许业务 writer 启动。 */
class BackupRuntime internal constructor(
    private val dataSource: BackupDataSource,
    private val pendingStore: PendingRestoreStore? = null,
    private val codec: BackupPackageCodec? = null,
    private val cacheDirectory: java.nio.file.Path? = null,
) {
    private val _state = MutableStateFlow<BackupStartupState>(BackupStartupState.Recovering)
    val state: StateFlow<BackupStartupState> = _state.asStateFlow()
    private val _failureSummary = MutableStateFlow<String?>(null)
    val failureSummary: StateFlow<String?> = _failureSummary.asStateFlow()
    private val _restoreResult = MutableStateFlow<BackupRestoreResult?>(null)
    val restoreResult: StateFlow<BackupRestoreResult?> = _restoreResult.asStateFlow()
    private val _restoreNotApplied = MutableStateFlow(false)
    val restoreNotApplied: StateFlow<Boolean> = _restoreNotApplied.asStateFlow()

    private val attemptMutex = Mutex()
    private var activeAttempt: CompletableDeferred<BackupStartupState>? = null

    val isWriterGateOpen: Boolean
        get() = state.value == BackupStartupState.Ready

    suspend fun recoverBeforeWriters(): BackupStartupState {
        var ownsAttempt = false
        val attempt = attemptMutex.withLock {
            if (_state.value == BackupStartupState.Ready) return BackupStartupState.Ready
            activeAttempt ?: CompletableDeferred<BackupStartupState>().also {
                activeAttempt = it
                _state.value = BackupStartupState.Recovering
                ownsAttempt = true
            }
        }
        if (!ownsAttempt) return attempt.await()

        return withContext(NonCancellable) {
            val result = try {
                withContext(Dispatchers.IO) { recoverAll() }
                _failureSummary.value = null
                BackupStartupState.Ready
            } catch (error: Exception) {
                _failureSummary.value = com.promenar.infinity.startup.diagnostics.DiagnosticRedactor.summary(error)
                com.promenar.infinity.utils.InfinityLogger.logError("BackupRuntimeRecovery", error)
                BackupStartupState.Blocked
            }
            attemptMutex.withLock {
                _state.value = result
                activeAttempt = null
                attempt.complete(result)
            }
            result
        }
    }

    fun requireWriterGate() {
        if (!isWriterGateOpen) throw BackupStartupException()
    }

    private suspend fun recoverAll() {
        cacheDirectory?.let(BackupStreams::cleanCache)
        // journal 始终优先；它可能是 pending 对应 restore 在上次崩溃后留下的事务。
        dataSource.recoverInterruptedRestore()
        val store = pendingStore ?: return
        val packageCodec = codec ?: throw BackupValidationException("恢复编解码器不可用")
        val pending = store.read() ?: return
        pending.use { payload ->
            val txId = payload.metadata.txId
            if (payload.metadata.phase != PendingRestorePhase.STAGED) {
                store.clear(txId)
                return
            }
            if (dataSource.hasCompletedRestore(txId)) {
                val summary = dataSource.completedRestoreSummary(txId)
                if (summary != null) {
                    _restoreResult.value = BackupRestoreResult(summary.skills, summary)
                    store.clear(txId)
                    return
                }
                decodePayload(packageCodec, payload).use {
                    _restoreResult.value = BackupRestoreResult(it.manifest.skills?.summary, it.restoreSummary)
                }
                store.clear(txId)
                return
            }
            val result = try {
                val validated = decodePayload(packageCodec, payload)
                dataSource.restoreWithResult(validated, txId)
            } catch (error: Exception) {
                // 同一 txId 重放只会被偏好账本拒绝；只有数据源证明已完整回滚时才放弃本次恢复并解除锁定。
                if (!dataSource.isRestoreSettledWithoutApplying(txId)) throw error
                com.promenar.infinity.utils.InfinityLogger.logError("BackupRuntimeRestoreNotApplied", error)
                store.clear(txId)
                _restoreNotApplied.value = true
                return
            }
            if (!dataSource.hasCompletedRestore(txId)) {
                throw BackupValidationException("恢复完成回执缺失，待恢复记录已保留")
            }
            _restoreResult.value = result
            store.clear(txId)
        }
    }

    private fun decodePayload(codec: BackupPackageCodec, payload: PendingRestorePayload): ValidatedBackup =
        if (payload.packageFile == null) codec.decode(payload.packageBytes, payload.password)
        else payload.openPackage().use { codec.decodeFrom(it, payload.password, payload.packageFile.parent) }

    companion object {
        fun createAndroid(
            context: Context,
            database: InfinityDatabase,
            liveSecretStore: SecretStore,
            userSkillsRoot: java.nio.file.Path = context.applicationContext.filesDir.resolve("agent_skills").toPath(),
        ): BackupRuntime {
            val appContext = context.applicationContext
            val workspaceBase = appContext.filesDir.resolve("WorkSpace").also { it.mkdirs() }.toPath()
            val sessionWorkspaceBase = appContext.filesDir.resolve("workspaces").also { it.mkdirs() }.toPath()
            val restoreBase = requireNotNull(ContextCompat.getNoBackupFilesDir(appContext)) {
                "noBackupFilesDir 不可用"
            }.resolve("backup-restore-runtime-v1")
                .also { it.mkdirs() }.toPath()
            val preferenceStore = AndroidTransactionalBackupPreferenceStore(appContext)
            val secretStore = AndroidTransactionalBackupSecretStore(appContext, liveSecretStore)
            val dataSource = RoomBackupDataSource(
                    database = database,
                    preferences = preferenceStore,
                    secrets = secretStore,
                    trustedSourceBases = linkedSetOf(
                        appContext.filesDir.toPath(),
                        workspaceBase,
                        sessionWorkspaceBase,
                    ),
                    trustedRestoreBase = restoreBase,
                    appVersion = BuildConfig.VERSION_NAME,
                    journalAuthenticator = AndroidRestoreJournalAuthenticator(),
                    userSkillsRoot = userSkillsRoot,
                    avatarRoot = appContext.filesDir.resolve("avatars").toPath(),
                )
            return BackupRuntime(
                dataSource = dataSource,
                pendingStore = AndroidPendingRestoreStore(appContext),
                codec = DefaultBackupPackageCodec(),
                cacheDirectory = appContext.cacheDir.resolve("backup-stream-v3").toPath(),
            )
        }
    }
}

class BackupStartupException internal constructor() :
    IllegalStateException("安全恢复未完成，应用写入已禁用")
