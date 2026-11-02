package com.promenar.infinity.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.BackupFailureReason
import com.promenar.infinity.data.backup.BackupValidationException
import com.promenar.infinity.data.backup.backupFailureReason
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.backup.RestoreRelayActivity
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.data.repository.BackupRepository
import com.promenar.infinity.data.repository.BackupUploadReceipt
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.WebDavAuthRecord
import com.promenar.infinity.data.security.WebDavAuthRecordCodec
import com.promenar.infinity.utils.InfinityLogger
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BackupErrorCode {
    PASSWORD_REQUIRED,
    PASSWORD_MISMATCH,
    CONFIGURATION_MISSING,
    CONNECTION_FAILED,
    REMOTE_LIST_FAILED,
    STALE_SELECTION,
    EXPORT_FAILED,
    UPLOAD_FAILED,
    RESTORE_FAILED,
    RESTART_FAILED,
    RESTORE_CLEANUP_FAILED,
    DOCUMENT_CREATE_FAILED,
    DOCUMENT_OPEN_FAILED,
}

enum class BackupSuccessCode { CONFIG_SAVED, PASSWORD_CLEARED, CONFIG_RESET, CONNECTION_TESTED, REMOTE_LISTED, EXPORTED, UPLOADED }

sealed interface BackupOperation {
    data object Initializing : BackupOperation
    data object SavingConfig : BackupOperation
    data object Idle : BackupOperation
    data object Testing : BackupOperation
    data object ListingRemote : BackupOperation
    data object Exporting : BackupOperation
    data object Uploading : BackupOperation
    data object StagingRestore : BackupOperation
    data object CancellingRestore : BackupOperation
    data object Restarting : BackupOperation
    data class Blocked(val code: BackupErrorCode) : BackupOperation
    data class Success(
        val code: BackupSuccessCode,
        val itemCount: Int = 0,
        val cleanupWarning: Boolean = false,
    ) : BackupOperation
    data class Error(val code: BackupErrorCode, val reason: BackupFailureReason? = null) : BackupOperation
}

enum class WebDavReadiness {
    Disabled,
    Incomplete,
    Ready,
}

val BackupOperation.isCancellable: Boolean
    get() = this is BackupOperation.Testing || this is BackupOperation.ListingRemote ||
        this is BackupOperation.Exporting || this is BackupOperation.Uploading ||
        this is BackupOperation.StagingRestore

data class BackupUiState(
    val includeKeys: Boolean = false,
    val encryptBackup: Boolean = false,
    val webdavEnabled: Boolean = false,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val hasWebDavPassword: Boolean = false,
    val lastBackupTime: Long = 0,
    val remoteBackups: List<RemoteBackup> = emptyList(),
    val selectedRemote: RemoteBackup? = null,
    val remoteConfigRevision: Long? = null,
    val operation: BackupOperation = BackupOperation.Idle,
) {
    val keysChecked: Boolean get() = includeKeys
    val isExporting: Boolean get() = operation is BackupOperation.Exporting || operation is BackupOperation.Uploading
    val isImporting: Boolean get() = operation is BackupOperation.StagingRestore || operation is BackupOperation.Restarting
    val webDavReadiness: WebDavReadiness
        get() = when {
            !webdavEnabled -> WebDavReadiness.Disabled
            webdavUrl.isBlank() || webdavUser.isBlank() || !hasWebDavPassword -> WebDavReadiness.Incomplete
            else -> WebDavReadiness.Ready
        }
    val canExecute: Boolean get() = operation !is BackupOperation.Testing &&
        operation !is BackupOperation.Initializing &&
        operation !is BackupOperation.SavingConfig &&
        operation !is BackupOperation.ListingRemote &&
        operation !is BackupOperation.Exporting &&
        operation !is BackupOperation.Uploading &&
        operation !is BackupOperation.StagingRestore &&
        operation !is BackupOperation.CancellingRestore &&
        operation !is BackupOperation.Restarting
}

interface BackupSettingsStore {
    var webDavEnabled: Boolean
    var webDavUrl: String
    var webDavUser: String
    var lastBackupTime: Long
    var webDavPasswordPlaintext: String?
}

interface BackupOperations {
    suspend fun export(options: BackupExportOptions): ByteArray
    suspend fun upload(config: WebDavConfig, options: BackupExportOptions): BackupUploadReceipt
    suspend fun testRemote(config: WebDavConfig): Result<Unit>
    suspend fun listRemote(config: WebDavConfig): List<RemoteBackup>
    fun newRestoreOperationId(): String = UUID.randomUUID().toString()
    suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?): PendingRestoreMetadata
    suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ): PendingRestoreMetadata
    suspend fun discardPendingRestore(operationId: String)
    suspend fun authorizePendingRestore(operationId: String)
}

fun interface BackupRestartRequester {
    fun requestRestart()
}

internal interface BackupViewModelHooks {
    fun afterOperationRegistered(phase: BackupOperation)
    suspend fun beforeStateCommit(phase: BackupOperation)
    fun afterConfigFailureReconciled(revision: Long) = Unit

    data object None : BackupViewModelHooks {
        override fun afterOperationRegistered(phase: BackupOperation) = Unit
        override suspend fun beforeStateCommit(phase: BackupOperation) = Unit
    }
}

private data class BoundWebDavConfig(val revision: Long, val config: WebDavConfig)
private data class ConfigMutationReservation(val revision: Long, val cancellation: OperationCancellation)
private data class RestoreControl(
    val operationId: String,
    var restartAuthorized: Boolean = false,
    var durableAuthorized: Boolean = false,
    var recoverableResourceCloseBlock: Boolean = false,
)

private class OnceCleanup(private val action: () -> Unit) {
    private val completed = AtomicBoolean(false)
    fun run() {
        if (completed.compareAndSet(false, true)) action()
    }
}

private sealed interface OperationCancellation {
    fun run()

    data object None : OperationCancellation {
        override fun run() = Unit
    }

    class Active(
        private val job: Job?,
        private val cleanup: OnceCleanup?,
    ) : OperationCancellation {
        override fun run() {
            job?.cancel()
            cleanup?.run()
        }
    }
}

class BackupViewModel internal constructor(
    private val operations: BackupOperations,
    private val settings: BackupSettingsStore,
    private val secrets: SecretStore,
    private val restartRequester: BackupRestartRequester,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hooks: BackupViewModelHooks = BackupViewModelHooks.None,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val synchronousIoForTests: Boolean = false,
    private val failureRecorder: (BackupOperation, Throwable) -> Unit = ::recordBackupFailureDiagnostic,
) : ViewModel() {
    private val generation = AtomicLong(0)
    private val configRevision = AtomicLong(0)
    private val operationLock = Any()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentJob: Job? = null
    private var currentOperationToken: Long? = null
    private var currentCleanup: OnceCleanup? = null
    private var activeWebDavRevision: Long? = null
    private var configMutationRevision: Long? = null
    private var configJob: Job? = null
    private var restoreControl: RestoreControl? = null
    private var restoreBlocked = false
    private var webDavBlocked = false
    private var cleared = false
    private var initialized = false

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    constructor(application: Application) : this(
        operations = LazyBackupOperations(Dispatchers.IO) {
            RepositoryBackupOperations(BackupRepository(application))
        },
        settings = SharedPreferencesBackupSettingsStore(
            application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        secrets = (application as InfinityApplication).secretStore,
        restartRequester = BackupRestartRequester { RestoreRelayActivity.requestRestart(application) },
    )

    init {
        if (synchronousIoForTests) {
            initializeCanonicalWebDavAuth()
            initialized = true
        } else {
            _uiState.update { it.copy(operation = BackupOperation.Initializing) }
            operationScope.launch {
                try {
                    withContext(ioDispatcher) { initializeCanonicalWebDavAuth() }
                } catch (error: Error) {
                    publishFatalConfigFailure(error)
                    throw error
                } finally {
                    synchronized(operationLock) {
                        initialized = true
                        if (_uiState.value.operation is BackupOperation.Initializing) {
                            _uiState.update { it.copy(operation = BackupOperation.Idle) }
                        }
                    }
                }
            }
        }
    }

    fun setWebdavEnabled(enabled: Boolean) {
        val reservation = synchronized(operationLock) {
            if (_uiState.value.webdavEnabled == enabled) return
            reserveWebDavMutationLocked() ?: return
        }
        try {
            reservation.cancellation.run()
            settings.webDavEnabled = enabled
            finishWebDavMutation(reservation.revision) { it.copy(webdavEnabled = enabled) }
        } catch (error: Throwable) {
            handleWebDavMutationFailure(reservation.revision, error)
            throw error
        }
    }

    fun setIncludeKeys(include: Boolean) {
        synchronized(operationLock) {
            if (!initialized || restoreControl?.restartAuthorized == true || restoreBlocked || cleared) return
        }
        _uiState.update { it.copy(includeKeys = include, encryptBackup = it.encryptBackup || include) }
    }

    fun setEncryptBackup(encrypt: Boolean) {
        synchronized(operationLock) {
            if (!initialized || restoreControl?.restartAuthorized == true || restoreBlocked || cleared) return
        }
        _uiState.update { state ->
            if (!encrypt && state.includeKeys) state else state.copy(encryptBackup = encrypt)
        }
    }

    /** password 非空时由本方法取得所有权，并在返回前清零；null 表示保留既有密码。 */
    fun saveWebDavConfig(url: String, user: String, password: CharArray?): Boolean {
        val ownedPassword = password?.copyOf()
        password?.fill('\u0000')
        val reservation = synchronized(operationLock) {
            val changed = url != _uiState.value.webdavUrl || user != _uiState.value.webdavUser || ownedPassword != null
            if (!changed) return true
            reserveWebDavMutationLocked()
        }
        if (reservation == null) {
            ownedPassword?.fill('\u0000')
            return false
        }
        if (!synchronousIoForTests) {
            _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
            launchConfigMutation(reservation.revision) {
                try {
                    withContext(ioDispatcher) { saveWebDavConfigNow(url, user, ownedPassword, reservation) }
                } catch (cancelled: CancellationException) {
                    publishConfigFailureIfNeeded(reservation.revision)
                    throw cancelled
                } catch (error: Exception) {
                    publishConfigFailureIfNeeded(reservation.revision)
                } catch (error: Error) {
                    hooks.afterConfigFailureReconciled(reservation.revision)
                    publishFatalConfigFailure(error, reservation.revision)
                    throw error
                } finally {
                    ownedPassword?.fill('\u0000')
                }
            }
            return true
        }
        return try {
            saveWebDavConfigNow(url, user, ownedPassword, reservation)
        } finally {
            ownedPassword?.fill('\u0000')
        }
    }

    private fun saveWebDavConfigNow(
        url: String,
        user: String,
        password: CharArray?,
        reservation: ConfigMutationReservation,
        releaseReservation: Boolean = true,
        manageFailure: Boolean = true,
    ): Boolean {
        try {
            reservation.cancellation.run()
            val existingPassword = if (password == null) readCanonicalAuth().useAndCopyPassword() else null
            val effectivePassword = password ?: existingPassword
            var encoded = ByteArray(0)
            try {
                encoded = WebDavAuthRecordCodec.encode(url, user, effectivePassword)
                secrets.put(SecretCatalog.webDavAuthRecord, encoded)
            } finally {
                encoded.fill(0)
                existingPassword?.fill('\u0000')
            }
            val hasPassword = effectivePassword?.isNotEmpty() == true
            if (releaseReservation) {
                finishWebDavMutation(reservation.revision) {
                    it.copy(
                        webdavUrl = url,
                        webdavUser = user,
                        hasWebDavPassword = hasPassword,
                        operation = BackupOperation.Success(BackupSuccessCode.CONFIG_SAVED),
                    )
                }
            } else synchronized(operationLock) {
                check(configMutationRevision == reservation.revision)
                _uiState.update { it.copy(
                    webdavUrl = url,
                    webdavUser = user,
                    hasWebDavPassword = hasPassword,
                    operation = BackupOperation.Testing,
                ) }
            }
            cleanupWebDavCachesBestEffort(url, user)
            return true
        } catch (error: Throwable) {
            if (manageFailure) handleWebDavMutationFailure(reservation.revision, error)
            throw error
        }
    }

    /** 保存与连接测试是同一个异步操作，不会用到旧 revision。 */
    fun saveAndTestWebDavConfig(url: String, user: String, password: CharArray?): Boolean {
        val ownedPassword = password?.copyOf()
        password?.fill('\u0000')
        val reservation = synchronized(operationLock) { reserveWebDavMutationLocked() }
        if (reservation == null) {
            ownedPassword?.fill('\u0000')
            return false
        }
        _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
        launchConfigMutation(reservation.revision) {
            try {
                withContext(ioDispatcher) {
                    saveWebDavConfigNow(
                        url,
                        user,
                        ownedPassword,
                        reservation,
                        releaseReservation = false,
                        manageFailure = false,
                    )
                    val config = readCanonicalWebDavConfig()
                    operations.testRemote(config).getOrThrow()
                }
                completeConfigMutation(
                    reservation.revision,
                    BackupOperation.Success(BackupSuccessCode.CONNECTION_TESTED),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable + ioDispatcher) {
                    resolveSaveAndTestFailure(reservation.revision, fatal = false)
                }
            } catch (error: Error) {
                try {
                    withContext(NonCancellable + ioDispatcher) {
                        resolveSaveAndTestFailure(reservation.revision, fatal = true)
                    }
                } catch (resolutionError: Error) {
                    if (resolutionError !== error) error.addSuppressed(resolutionError)
                }
                throw error
            } finally {
                ownedPassword?.fill('\u0000')
            }
        }
        return true
    }

    fun deleteWebDavPassword(): Boolean {
        val reservation = synchronized(operationLock) { reserveWebDavMutationLocked() } ?: return false
        if (!synchronousIoForTests) {
            _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
            launchConfigMutation(reservation.revision) {
                try {
                    withContext(ioDispatcher) { deleteWebDavPasswordNow(reservation) }
                } catch (cancelled: CancellationException) {
                    publishConfigFailureIfNeeded(reservation.revision)
                    throw cancelled
                } catch (error: Exception) {
                    publishConfigFailureIfNeeded(reservation.revision)
                } catch (error: Error) {
                    hooks.afterConfigFailureReconciled(reservation.revision)
                    publishFatalConfigFailure(error, reservation.revision)
                    throw error
                }
            }
            return true
        }
        return deleteWebDavPasswordNow(reservation)
    }

    private fun deleteWebDavPasswordNow(reservation: ConfigMutationReservation): Boolean {
        try {
            reservation.cancellation.run()
            val current = readCanonicalAuth()
            current.use {
                val encoded = WebDavAuthRecordCodec.encode(it.endpoint, it.username, null)
                try {
                    secrets.put(SecretCatalog.webDavAuthRecord, encoded)
                } finally {
                    encoded.fill(0)
                }
                // canonical 先发布，prefs 只是可修复的公开缓存。
            }
            finishWebDavMutation(reservation.revision) {
                it.copy(
                    hasWebDavPassword = false,
                    operation = BackupOperation.Success(BackupSuccessCode.PASSWORD_CLEARED),
                )
            }
            cleanupWebDavCachesBestEffort(current.endpoint, current.username)
            return true
        } catch (error: Throwable) {
            handleWebDavMutationFailure(reservation.revision, error)
            throw error
        }
    }

    /** 仅用于 canonical 损坏后的显式安全恢复；不会回退读取 legacy 密码。 */
    fun resetWebDavAuth(): Boolean {
        val revision = synchronized(operationLock) {
            if (!initialized || configMutationRevision != null || currentJob != null || restoreControl != null || cleared) return false
            configRevision.incrementAndGet().also { configMutationRevision = it }
        }
        if (!synchronousIoForTests) {
            _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
            launchConfigMutation(revision) {
                try {
                    withContext(ioDispatcher) { resetWebDavAuthNow(revision) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Error) {
                    hooks.afterConfigFailureReconciled(revision)
                    publishFatalConfigFailure(error, revision)
                    throw error
                }
            }
            return true
        }
        return resetWebDavAuthNow(revision)
    }

    private fun resetWebDavAuthNow(revision: Long): Boolean {
        return try {
            val encoded = WebDavAuthRecordCodec.encode("", "", null)
            try {
                secrets.put(SecretCatalog.webDavAuthRecord, encoded)
            } finally {
                encoded.fill(0)
            }
            synchronized(operationLock) {
                check(configMutationRevision == revision)
                webDavBlocked = false
                configMutationRevision = null
                _uiState.update { it.copy(
                    webdavUrl = "",
                    webdavUser = "",
                    hasWebDavPassword = false,
                    remoteBackups = emptyList(),
                    selectedRemote = null,
                    remoteConfigRevision = null,
                    operation = BackupOperation.Success(BackupSuccessCode.CONFIG_RESET),
                ) }
            }
            cleanupWebDavCachesBestEffort("", "")
            true
        } catch (error: Throwable) {
            val recovered = try {
                readCanonicalAuth().use { it.endpoint.isEmpty() && it.username.isEmpty() && it.passwordBytes.isEmpty() }
            } catch (_: Exception) {
                false
            }
            synchronized(operationLock) {
                if (configMutationRevision == revision) configMutationRevision = null
                webDavBlocked = !recovered
                _uiState.update { state -> state.copy(
                    operation = if (recovered) BackupOperation.Success(BackupSuccessCode.CONFIG_RESET)
                    else BackupOperation.Blocked(BackupErrorCode.CONNECTION_FAILED),
                    webdavUrl = if (recovered) "" else state.webdavUrl,
                    webdavUser = if (recovered) "" else state.webdavUser,
                    hasWebDavPassword = if (recovered) false else state.hasWebDavPassword,
                ) }
            }
            if (recovered) cleanupWebDavCachesBestEffort("", "")
            if (error is Error) throw error
            recovered
        }
    }

    fun testConnection(): Boolean {
        val revision = captureWebDavRevision() ?: return false
        return startOperation(BackupOperation.Testing, webDavRevision = revision) { token ->
            val bound = readBoundWebDavConfig(revision)
            operations.testRemote(bound.config).getOrThrow()
            hooks.beforeStateCommit(BackupOperation.Testing)
            complete(token, BackupOperation.Success(BackupSuccessCode.CONNECTION_TESTED), bound.revision)
        }
    }

    fun listRemote(): Boolean {
        val revision = captureWebDavRevision() ?: return false
        return startOperation(BackupOperation.ListingRemote, webDavRevision = revision) { token ->
            val bound = readBoundWebDavConfig(revision)
            val backups = operations.listRemote(bound.config)
            hooks.beforeStateCommit(BackupOperation.ListingRemote)
            synchronized(operationLock) {
                if (isCurrentLocked(token, bound.revision)) {
                    _uiState.update {
                        it.copy(
                            remoteBackups = backups,
                            selectedRemote = null,
                            remoteConfigRevision = bound.revision,
                            operation = BackupOperation.Success(BackupSuccessCode.REMOTE_LISTED, itemCount = backups.size),
                        )
                    }
                }
            }
        }
    }

    fun selectRemote(remote: RemoteBackup): Boolean {
        synchronized(operationLock) {
            if (currentJob != null || configMutationRevision != null || restoreControl != null) return false
            val state = _uiState.value
            if (state.remoteConfigRevision != configRevision.get()) return false
            val accepted = state.remoteBackups.singleOrNull { it == remote } ?: return false
            _uiState.update { it.copy(selectedRemote = accepted) }
            return true
        }
    }

    fun reportDocumentError(code: BackupErrorCode): Boolean {
        require(code == BackupErrorCode.DOCUMENT_CREATE_FAILED || code == BackupErrorCode.DOCUMENT_OPEN_FAILED)
        synchronized(operationLock) {
            if (!initialized || isBusyLocked()) return false
            _uiState.update { it.copy(operation = BackupOperation.Error(code)) }
            return true
        }
    }

    /** 仅供当前 WebDAV 配置 sheet 临时显示；调用方取得数组所有权并负责及时清零。 */
    suspend fun revealWebDavPassword(): CharArray? = withContext(ioDispatcher) {
        synchronized(operationLock) {
            if (!initialized || webDavBlocked || !_uiState.value.hasWebDavPassword || cleared) return@withContext null
        }
        readCanonicalAuth().useAndCopyPassword()
    }

    /** password 由本方法取得所有权并清零。 */
    fun export(output: OutputStream, password: CharArray?, confirmation: CharArray?): Boolean =
        startPasswordOperation(
            BackupOperation.Exporting,
            password,
            confirmation,
            externalCleanup = { output.close() },
        ) { token, options ->
            val bytes = operations.export(options)
            try {
                // SAF 文档可能由云盘等提供方承载，写入不得占用主线程。
                withContext(ioDispatcher) {
                    output.write(bytes)
                    output.flush()
                }
            } finally {
                bytes.fill(0)
            }
            hooks.beforeStateCommit(BackupOperation.Exporting)
            recordBackupSuccess(token, BackupSuccessCode.EXPORTED)
        }

    /** password/confirmation 由本方法取得所有权并清零。 */
    fun upload(password: CharArray?, confirmation: CharArray?): Boolean {
        val revision = captureWebDavRevision()
        if (revision == null) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            return false
        }
        return startPasswordOperation(
            BackupOperation.Uploading,
            password,
            confirmation,
            webDavRevision = revision,
        ) { token, options ->
            val bound = readBoundWebDavConfig(revision)
            val receipt = operations.upload(bound.config, options)
            hooks.beforeStateCommit(BackupOperation.Uploading)
            recordBackupSuccess(
                token,
                BackupSuccessCode.UPLOADED,
                cleanupWarning = receipt.pruneWarning != null,
                webDavRevision = bound.revision,
            )
        }
    }

    /** password 由本方法取得所有权并清零。 */
    fun restoreLocal(input: InputStream, password: CharArray?): Boolean =
        startRestore(password, completion = { input.close() }) { operationId, owned ->
            operations.stageLocalRestore(operationId, input, owned)
        }

    /** 必须先 list 并按完整 RemoteBackup 对象选中；password 由本方法取得所有权并清零。 */
    fun restoreSelectedRemote(password: CharArray?): Boolean {
        var rejectedAsBusy = false
        val selectedRevision = synchronized(operationLock) {
            if (currentJob != null || configMutationRevision != null || restoreControl != null) {
                rejectedAsBusy = true
                null
            } else {
                val state = _uiState.value
                val revision = state.remoteConfigRevision
                val selected = state.selectedRemote
                if (revision == null || revision != configRevision.get() || selected !in state.remoteBackups) null
                else selected!! to revision
            }
        }
        if (selectedRevision == null) {
            password?.fill('\u0000')
            if (rejectedAsBusy) return false
            _uiState.update {
                it.copy(operation = BackupOperation.Error(BackupErrorCode.STALE_SELECTION))
            }
            return false
        }
        val revision = captureWebDavRevision()
        if (revision == null || revision != selectedRevision.second) {
            password?.fill('\u0000')
            return false
        }
        val selected = selectedRevision.first
        return startRestore(password, webDavRevision = revision) { operationId, owned ->
            val bound = readBoundWebDavConfig(revision)
            operations.stageRemoteRestore(operationId, bound.config, selected, owned)
        }
    }

    fun cancelOperation() {
        synchronized(operationLock) {
            if (!_uiState.value.operation.isCancellable) return
        }
        var restoreOperationId: String? = null
        val cancellation = synchronized(operationLock) {
            val restore = restoreControl
            if (restore != null) {
                if (restore.restartAuthorized) return@synchronized OperationCancellation.None
                restoreOperationId = restore.operationId
                generation.incrementAndGet()
                _uiState.update { state -> state.copy(operation = BackupOperation.CancellingRestore) }
                OperationCancellation.Active(currentJob, currentCleanup)
            } else {
                generation.incrementAndGet()
                takeCurrentOperationLocked().also {
                    _uiState.update { state -> state.copy(operation = BackupOperation.Idle) }
                }
            }
        }
        try {
            cancellation.run()
        } catch (error: Throwable) {
            restoreOperationId?.let { markRestoreResourceCloseBlocked(it, error !is Error) }
            if (restoreOperationId == null || error is Error) throw error
        }
    }

    override fun onCleared() {
        val configToCancel = synchronized(operationLock) {
            cleared = true
            configJob
        }
        try {
            cancelOperation()
        } finally {
            configToCancel?.cancel()
            synchronized(operationLock) {
                if (currentJob == null && configJob == null) operationScope.cancel()
            }
            super.onCleared()
        }
    }

    private fun startRestore(
        password: CharArray?,
        completion: () -> Unit = {},
        webDavRevision: Long? = null,
        stage: suspend (String, CharArray?) -> PendingRestoreMetadata,
    ): Boolean {
        val operationId = operations.newRestoreOperationId()
        val owned = password?.copyOf()
        password?.fill('\u0000')
        val started = startOperation(
            phase = BackupOperation.StagingRestore,
            onCompletion = {
                owned?.fill('\u0000')
                completion()
            },
            webDavRevision = webDavRevision,
            restoreOperationId = operationId,
        ) { token ->
            try {
                stage(operationId, owned)
                hooks.beforeStateCommit(BackupOperation.StagingRestore)
                if (authorizeRestart(token, webDavRevision, operationId)) {
                    try {
                        operations.authorizePendingRestore(operationId)
                        synchronized(operationLock) {
                            if (restoreControl?.operationId == operationId) restoreControl?.durableAuthorized = true
                        }
                        requestAuthorizedRestart(operationId)
                    } catch (error: Exception) {
                        markRestartAuthorizationBlocked(operationId)
                    } catch (error: Error) {
                        markRestartAuthorizationBlocked(operationId)
                        throw error
                    }
                }
            } finally {
                if (!isRestartAuthorized(operationId)) {
                    withContext(NonCancellable) {
                        try {
                            operations.discardPendingRestore(operationId)
                            releaseRecoverableResourceCloseBlock(operationId)
                        } catch (error: Throwable) {
                            markRestoreCleanupBlocked(operationId)
                            throw error
                        }
                    }
                }
            }
        }
        if (!started) {
            owned?.fill('\u0000')
            completion()
        }
        return started
    }

    private fun startPasswordOperation(
        phase: BackupOperation,
        password: CharArray?,
        confirmation: CharArray?,
        externalCleanup: () -> Unit = {},
        webDavRevision: Long? = null,
        block: suspend (Long, BackupExportOptions) -> Unit,
    ): Boolean {
        if (isBusy()) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            externalCleanup()
            return false
        }
        val include = _uiState.value.includeKeys
        val encrypt = _uiState.value.encryptBackup || include
        val ownedPassword = password?.copyOf()
        val ownedConfirmation = confirmation?.copyOf()
        password?.fill('\u0000')
        confirmation?.fill('\u0000')
        if (encrypt && (ownedPassword == null || ownedPassword.isEmpty() ||
                ownedConfirmation == null || ownedConfirmation.isEmpty())) {
            ownedPassword?.fill('\u0000')
            ownedConfirmation?.fill('\u0000')
            publishPasswordValidationFailure(BackupErrorCode.PASSWORD_REQUIRED)
            externalCleanup()
            return false
        }
        if (encrypt && !constantTimeEquals(ownedPassword!!, ownedConfirmation!!)) {
            ownedPassword.fill('\u0000')
            ownedConfirmation.fill('\u0000')
            publishPasswordValidationFailure(BackupErrorCode.PASSWORD_MISMATCH)
            externalCleanup()
            return false
        }
        val started = startOperation(
            phase = phase,
            onCompletion = {
                ownedPassword?.fill('\u0000')
                ownedConfirmation?.fill('\u0000')
                externalCleanup()
            },
            webDavRevision = webDavRevision,
        ) { token ->
            block(
                token,
                BackupExportOptions(
                    includeSecrets = include,
                    password = if (encrypt) ownedPassword else null,
                    passwordConfirmation = if (encrypt) ownedConfirmation else null,
                    encryptPackage = encrypt,
                ),
            )
        }
        if (!started) {
            ownedPassword?.fill('\u0000')
            ownedConfirmation?.fill('\u0000')
            externalCleanup()
        }
        return started
    }

    private fun startOperation(
        phase: BackupOperation,
        onCompletion: () -> Unit = {},
        webDavRevision: Long? = null,
        restoreOperationId: String? = null,
        block: suspend (Long) -> Unit,
    ): Boolean {
        val token: Long
        val cleanup = OnceCleanup(onCompletion)
        val launched = synchronized(operationLock) {
            if (!initialized || currentJob != null || configMutationRevision != null || restoreBlocked || cleared || restoreControl?.restartAuthorized == true) return false
            if (webDavRevision != null && webDavRevision != configRevision.get()) return false
            token = generation.incrementAndGet()
            _uiState.update { it.copy(operation = phase) }
            if (restoreOperationId != null) restoreControl = RestoreControl(restoreOperationId)
            operationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    try {
                        block(token)
                    } finally {
                        cleanup.run()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportFailure(token, phase, error)
                } catch (error: Error) {
                    // 导出与上传在内存中物化整包，内存耗尽等 Error 只让本次操作失败，不让应用崩溃。
                    if (phase != BackupOperation.Exporting && phase != BackupOperation.Uploading) throw error
                    reportFailure(token, phase, error)
                } finally {
                    synchronized(operationLock) {
                        if (currentOperationToken == token) {
                            currentJob = null
                            currentOperationToken = null
                            currentCleanup = null
                            activeWebDavRevision = null
                            val restore = restoreControl
                            if (restore != null && !restore.restartAuthorized && !restoreBlocked) {
                                restoreControl = null
                                if (_uiState.value.operation is BackupOperation.CancellingRestore) {
                                    _uiState.update { it.copy(operation = BackupOperation.Idle) }
                                }
                            }
                            if (cleared && configJob == null) operationScope.cancel()
                        }
                    }
                }
            }.also {
                currentJob = it
                currentOperationToken = token
                currentCleanup = cleanup
                activeWebDavRevision = webDavRevision
            }
        }
        hooks.afterOperationRegistered(phase)
        launched.start()
        return true
    }

    private fun recordBackupSuccess(
        token: Long,
        code: BackupSuccessCode,
        cleanupWarning: Boolean = false,
        webDavRevision: Long? = null,
    ) {
        val now = clock()
        val committed = synchronized(operationLock) {
            if (!isCurrentLocked(token, webDavRevision)) false else {
                _uiState.update {
                    it.copy(lastBackupTime = now, operation = BackupOperation.Success(code, cleanupWarning = cleanupWarning))
                }
                true
            }
        }
        if (committed) settings.lastBackupTime = now
    }

    private fun complete(token: Long, operation: BackupOperation, webDavRevision: Long? = null) {
        synchronized(operationLock) {
            if (isCurrentLocked(token, webDavRevision)) _uiState.update { it.copy(operation = operation) }
        }
    }

    private fun reportFailure(token: Long, phase: BackupOperation, error: Throwable) {
        try {
            failureRecorder(phase, error)
        } catch (_: Exception) {
            // 诊断写入失败不影响错误态发布。
        }
        val reason = if (phase == BackupOperation.Exporting || phase == BackupOperation.Uploading) {
            error.backupFailureReason()
        } else null
        fail(token, errorCodeFor(phase), reason)
    }

    private fun fail(token: Long, code: BackupErrorCode, reason: BackupFailureReason? = null) {
        synchronized(operationLock) {
            if (!restoreBlocked && !webDavBlocked && isCurrentLocked(token, null)) {
                _uiState.update { it.copy(operation = BackupOperation.Error(code, reason)) }
            }
        }
    }

    private fun isCurrentLocked(token: Long, webDavRevision: Long?): Boolean =
        generation.get() == token && (webDavRevision == null || configRevision.get() == webDavRevision)

    private fun authorizeRestart(token: Long, webDavRevision: Long?, operationId: String): Boolean =
        synchronized(operationLock) {
            val control = restoreControl
            if (!isCurrentLocked(token, webDavRevision) || control?.operationId != operationId || cleared) return false
            control.restartAuthorized = true
            _uiState.update { it.copy(operation = BackupOperation.Restarting) }
            true
        }

    private fun requestAuthorizedRestart(operationId: String) {
        try {
            restartRequester.requestRestart()
        } catch (error: Throwable) {
            synchronized(operationLock) {
                if (restoreControl?.operationId == operationId && restoreControl?.restartAuthorized == true) {
                    restoreBlocked = true
                    _uiState.update {
                        it.copy(operation = BackupOperation.Blocked(BackupErrorCode.RESTART_FAILED))
                    }
                }
            }
            if (error is Error) throw error
        }
    }

    fun retryRestart(): Boolean {
        val control = synchronized(operationLock) {
            val control = restoreControl ?: return false
            if (!control.restartAuthorized || _uiState.value.operation !is BackupOperation.Blocked) return false
            restoreBlocked = false
            _uiState.update { it.copy(operation = BackupOperation.Restarting) }
            control.copy()
        }
        operationScope.launch {
            try {
                if (!control.durableAuthorized) {
                    operations.authorizePendingRestore(control.operationId)
                    synchronized(operationLock) {
                        if (restoreControl?.operationId == control.operationId) restoreControl?.durableAuthorized = true
                    }
                }
                requestAuthorizedRestart(control.operationId)
            } catch (error: Exception) {
                markRestartAuthorizationBlocked(control.operationId)
            } catch (error: Error) {
                markRestartAuthorizationBlocked(control.operationId)
                throw error
            }
        }
        return true
    }

    fun cancelPendingRestart(): Boolean {
        val operationId = synchronized(operationLock) {
            val control = restoreControl ?: return false
            if (!control.restartAuthorized) return false
            control.restartAuthorized = false
            restoreBlocked = true
            _uiState.update { it.copy(operation = BackupOperation.CancellingRestore) }
            control.operationId
        }
        operationScope.launch {
            try {
                withContext(NonCancellable) { operations.discardPendingRestore(operationId) }
                synchronized(operationLock) {
                    if (restoreControl?.operationId == operationId) {
                        restoreControl = null
                        restoreBlocked = false
                        _uiState.update { it.copy(operation = BackupOperation.Idle) }
                    }
                }
            } catch (error: Exception) {
                markRestoreCleanupBlocked(operationId)
            } catch (error: Error) {
                markRestoreCleanupBlocked(operationId)
                throw error
            }
        }
        return true
    }

    private fun isRestartAuthorized(operationId: String): Boolean = synchronized(operationLock) {
        restoreControl?.operationId == operationId && restoreControl?.restartAuthorized == true
    }

    private fun markRestoreCleanupBlocked(operationId: String) {
        synchronized(operationLock) {
            if (restoreControl?.operationId == operationId) {
                restoreBlocked = true
                _uiState.update {
                    it.copy(operation = BackupOperation.Blocked(BackupErrorCode.RESTORE_CLEANUP_FAILED))
                }
            }
        }
    }

    private fun markRestoreResourceCloseBlocked(operationId: String, recoverable: Boolean) {
        synchronized(operationLock) {
            val control = restoreControl
            if (control?.operationId == operationId) {
                control.recoverableResourceCloseBlock = recoverable
                restoreBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(BackupErrorCode.RESTORE_CLEANUP_FAILED)) }
            }
        }
    }

    private fun releaseRecoverableResourceCloseBlock(operationId: String) {
        synchronized(operationLock) {
            val control = restoreControl
            if (control?.operationId == operationId && control.recoverableResourceCloseBlock && !control.restartAuthorized) {
                control.recoverableResourceCloseBlock = false
                restoreBlocked = false
                restoreControl = null
                _uiState.update { it.copy(operation = BackupOperation.Idle) }
            }
        }
    }

    private fun markRestartAuthorizationBlocked(operationId: String) {
        synchronized(operationLock) {
            if (restoreControl?.operationId == operationId && restoreControl?.restartAuthorized == true) {
                restoreBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(BackupErrorCode.RESTART_FAILED)) }
            }
        }
    }
    private fun isBusy(): Boolean = synchronized(operationLock) {
        isBusyLocked()
    }

    private fun isBusyLocked(): Boolean =
        currentJob != null || configMutationRevision != null || restoreControl != null || restoreBlocked || cleared

    private fun publishPasswordValidationFailure(code: BackupErrorCode) {
        synchronized(operationLock) {
            if (!initialized || isBusyLocked() || restoreControl?.restartAuthorized == true) return
            _uiState.update { it.copy(operation = BackupOperation.Error(code)) }
        }
    }

    private fun captureWebDavRevision(): Long? = synchronized(operationLock) {
        if (!initialized || configMutationRevision != null || webDavBlocked || restoreControl != null ||
            _uiState.value.webDavReadiness != WebDavReadiness.Ready
        ) null
        else configRevision.get()
    }

    private suspend fun readBoundWebDavConfig(revision: Long): BoundWebDavConfig {
        val config = try {
            withContext(ioDispatcher) { readCanonicalWebDavConfig() }
        } catch (error: Exception) {
            synchronized(operationLock) {
                webDavBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(BackupErrorCode.CONNECTION_FAILED)) }
            }
            throw error
        }
        synchronized(operationLock) {
            if (configMutationRevision != null || configRevision.get() != revision) {
                throw CancellationException("WebDAV 配置 revision 已变更")
            }
        }
        return BoundWebDavConfig(revision, config)
    }

    private fun reserveWebDavMutationLocked(): ConfigMutationReservation? {
        if (!initialized || configMutationRevision != null || webDavBlocked || restoreControl != null || cleared) return null
        if (currentJob != null && activeWebDavRevision == null) return null
        val revision = configRevision.incrementAndGet()
        configMutationRevision = revision
        val cancellation = if (activeWebDavRevision != null) {
            generation.incrementAndGet()
            takeCurrentOperationLocked()
        } else {
            OperationCancellation.None
        }
        _uiState.update {
            it.copy(
                remoteBackups = emptyList(),
                selectedRemote = null,
                remoteConfigRevision = null,
                operation = if (currentJob != null) it.operation else BackupOperation.Idle,
            )
        }
        return ConfigMutationReservation(revision, cancellation)
    }

    private fun finishWebDavMutation(revision: Long, update: (BackupUiState) -> BackupUiState) {
        synchronized(operationLock) {
            check(configMutationRevision == revision) { "WebDAV 配置保存已失效" }
            _uiState.update(update)
            configMutationRevision = null
        }
    }

    private fun completeConfigMutation(revision: Long, operation: BackupOperation) {
        synchronized(operationLock) {
            if (configMutationRevision != revision) return
            configMutationRevision = null
            _uiState.update { it.copy(operation = operation) }
        }
    }

    private fun launchConfigMutation(revision: Long, block: suspend () -> Unit) {
        lateinit var job: Job
        job = operationScope.launch(start = CoroutineStart.LAZY) {
            var operationFailure: Throwable? = null
            try {
                block()
            } catch (error: Throwable) {
                operationFailure = error
                throw error
            } finally {
                try {
                    withContext(NonCancellable + ioDispatcher) {
                        reconcileIncompleteConfigMutation(revision)
                    }
                } catch (reconcileFailure: Throwable) {
                    if (reconcileFailure is Error) {
                        operationFailure?.let { reconcileFailure.addSuppressed(it) }
                        throw reconcileFailure
                    }
                    operationFailure?.addSuppressed(reconcileFailure) ?: throw reconcileFailure
                }
            }
        }
        val cancelImmediately = synchronized(operationLock) {
            check(configMutationRevision == revision) { "WebDAV 配置操作已失效" }
            // 上一个 Job 可能只剩 finally/缓存清理；reservation 已释放后允许新操作接管槽位。
            configJob = job
            cleared
        }
        job.invokeOnCompletion {
            synchronized(operationLock) {
                if (configJob === job) configJob = null
                if (cleared && currentJob == null) operationScope.cancel()
            }
        }
        job.start()
        if (cancelImmediately) job.cancel()
    }

    private fun reconcileIncompleteConfigMutation(revision: Long) {
        if (synchronized(operationLock) { configMutationRevision != revision }) return
        var endpoint: String? = null
        var username: String? = null
        var hasPassword = false
        var canonicalFailure: Throwable? = null
        val canonicalAvailable = try {
            readCanonicalAuth().use {
                endpoint = it.endpoint
                username = it.username
                hasPassword = it.passwordBytes.isNotEmpty()
            }
            true
        } catch (error: Throwable) {
            canonicalFailure = error
            false
        }
        synchronized(operationLock) {
            if (configMutationRevision != revision) return
            configMutationRevision = null
            webDavBlocked = !canonicalAvailable
            _uiState.update { state -> state.copy(
                webdavUrl = endpoint ?: state.webdavUrl,
                webdavUser = username ?: state.webdavUser,
                hasWebDavPassword = if (canonicalAvailable) hasPassword else state.hasWebDavPassword,
                operation = if (canonicalAvailable) BackupOperation.Error(BackupErrorCode.CONFIGURATION_MISSING)
                else BackupOperation.Blocked(BackupErrorCode.CONNECTION_FAILED),
            ) }
        }
        if (canonicalAvailable) cleanupWebDavCachesBestEffort(endpoint.orEmpty(), username.orEmpty())
        (canonicalFailure as? Error)?.let { throw it }
    }

    private fun resolveSaveAndTestFailure(revision: Long, fatal: Boolean) {
        var readFailure: Throwable? = null
        val auth = try { readCanonicalAuth() } catch (error: Throwable) {
            readFailure = error
            null
        }
        var endpoint: String? = null
        var username: String? = null
        var hasPassword = false
        auth?.use {
            endpoint = it.endpoint
            username = it.username
            hasPassword = it.passwordBytes.isNotEmpty()
        }
        synchronized(operationLock) {
            if (configMutationRevision != revision) return
            configMutationRevision = null
            webDavBlocked = fatal || auth == null || readFailure is Error
            _uiState.update { state -> state.copy(
                webdavUrl = endpoint ?: state.webdavUrl,
                webdavUser = username ?: state.webdavUser,
                hasWebDavPassword = if (auth == null) state.hasWebDavPassword else hasPassword,
                operation = if (webDavBlocked) BackupOperation.Blocked(BackupErrorCode.CONNECTION_FAILED)
                else BackupOperation.Error(BackupErrorCode.CONNECTION_FAILED),
            ) }
        }
        if (auth != null) cleanupWebDavCachesBestEffort(endpoint.orEmpty(), username.orEmpty())
        (readFailure as? Error)?.let { throw it }
    }

    private fun takeCurrentOperationLocked(): OperationCancellation {
        val cancellation = OperationCancellation.Active(currentJob, currentCleanup)
        currentJob = null
        currentOperationToken = null
        currentCleanup = null
        activeWebDavRevision = null
        return cancellation
    }

    private fun initializeCanonicalWebDavAuth() {
        _uiState.update { it.copy(
            webdavEnabled = settings.webDavEnabled,
            lastBackupTime = settings.lastBackupTime,
        ) }
        try {
            val existing = secrets.get(SecretCatalog.webDavAuthRecord)
            val auth = if (existing != null) {
                try { WebDavAuthRecordCodec.decode(existing) } finally { existing.fill(0) }
            } else {
                val legacyBytes = secrets.get(SecretCatalog.webDavPassword)
                val plaintext = settings.webDavPasswordPlaintext
                val password = try {
                    when {
                        legacyBytes != null -> decodeUtf8ToChars(legacyBytes)
                        plaintext != null -> plaintext.toCharArray()
                        else -> null
                    }
                } finally {
                    legacyBytes?.fill(0)
                }
                try {
                    val encoded = WebDavAuthRecordCodec.encode(settings.webDavUrl, settings.webDavUser, password)
                    try { secrets.put(SecretCatalog.webDavAuthRecord, encoded) } finally { encoded.fill(0) }
                    readCanonicalAuth()
                } finally {
                    password?.fill('\u0000')
                }
            }
            auth.use {
                _uiState.update { state -> state.copy(
                    webdavUrl = it.endpoint,
                    webdavUser = it.username,
                    hasWebDavPassword = it.passwordBytes.isNotEmpty(),
                ) }
                cleanupWebDavCachesBestEffort(it.endpoint, it.username)
            }
        } catch (_: Exception) {
            synchronized(operationLock) {
                webDavBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(BackupErrorCode.CONNECTION_FAILED)) }
            }
        }
    }

    private fun readCanonicalAuth(): WebDavAuthRecord {
        val encoded = secrets.get(SecretCatalog.webDavAuthRecord)
            ?: throw IllegalStateException("WebDAV 安全认证记录缺失")
        return try { WebDavAuthRecordCodec.decode(encoded) } finally { encoded.fill(0) }
    }

    private fun readCanonicalWebDavConfig(): WebDavConfig {
        val auth = readCanonicalAuth()
        auth.use {
            val password = decodeUtf8ToChars(it.passwordBytes)
            return try {
                WebDavConfig(it.endpoint, it.username, password.concatToString())
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun WebDavAuthRecord.useAndCopyPassword(): CharArray = use {
        decodeUtf8ToChars(it.passwordBytes)
    }

    private fun decodeUtf8ToChars(bytes: ByteArray): CharArray {
        val chars = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(chars.remaining()).also(chars::get)
        } finally {
            for (index in 0 until chars.limit()) chars.put(index, '\u0000')
        }
    }

    private fun repairPublicWebDavCache(url: String, user: String) {
        settings.webDavUrl = url
        settings.webDavUser = user
    }

    private fun removeLegacyWebDavSecrets() {
        secrets.remove(SecretCatalog.webDavPassword)
        settings.webDavPasswordPlaintext = null
    }

    private fun cleanupWebDavCachesBestEffort(url: String, user: String) {
        try {
            repairPublicWebDavCache(url, user)
            removeLegacyWebDavSecrets()
        } catch (_: Exception) {
            // canonical 记录已经是唯一事实源；下次重建会再次修复缓存。
        }
    }

    private fun publishCanonicalAfterAmbiguousWrite(revision: Long): Boolean {
        val auth = try { readCanonicalAuth() } catch (_: Exception) { return false }
        auth.use {
            synchronized(operationLock) {
                if (configMutationRevision != revision) return true
                _uiState.update { state -> state.copy(
                    webdavUrl = it.endpoint,
                    webdavUser = it.username,
                    hasWebDavPassword = it.passwordBytes.isNotEmpty(),
                ) }
                configMutationRevision = null
            }
            cleanupWebDavCachesBestEffort(it.endpoint, it.username)
        }
        return true
    }

    private fun blockOrReleaseWebDavMutation(revision: Long, error: Throwable) {
        synchronized(operationLock) {
            if (configMutationRevision == revision) {
                configMutationRevision = null
                if (error is Error) {
                    webDavBlocked = true
                    _uiState.update { it.copy(operation = BackupOperation.Blocked(BackupErrorCode.CONNECTION_FAILED)) }
                }
            }
        }
    }

    private fun handleWebDavMutationFailure(revision: Long, original: Throwable) {
        var reconciled = false
        try {
            reconciled = publishCanonicalAfterAmbiguousWrite(revision)
        } catch (reconcileFailure: Throwable) {
            if (reconcileFailure !== original) original.addSuppressed(reconcileFailure)
        } finally {
            if (!reconciled) blockOrReleaseWebDavMutation(revision, original)
        }
    }

    private fun publishConfigFailureIfNeeded(revision: Long) {
        synchronized(operationLock) {
            if (configRevision.get() != revision) return
            if (configMutationRevision != null && configMutationRevision != revision) return
            if (_uiState.value.operation is BackupOperation.SavingConfig) {
                _uiState.update { it.copy(operation = BackupOperation.Error(BackupErrorCode.CONFIGURATION_MISSING)) }
            }
        }
    }

    private fun publishFatalConfigFailure(error: Error, revision: Long? = null) {
        synchronized(operationLock) {
            if (revision != null && configRevision.get() != revision) return
            if (revision != null && configMutationRevision != null && configMutationRevision != revision) return
            configMutationRevision = null
            webDavBlocked = true
            _uiState.update { it.copy(operation = BackupOperation.Blocked(BackupErrorCode.CONFIGURATION_MISSING)) }
        }
    }

    private fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
        var difference = left.size xor right.size
        val size = maxOf(left.size, right.size)
        repeat(size) { index ->
            difference = difference or ((left.getOrNull(index)?.code ?: 0) xor (right.getOrNull(index)?.code ?: 0))
        }
        return difference == 0
    }

    private fun errorCodeFor(phase: BackupOperation) = when (phase) {
        BackupOperation.Testing -> BackupErrorCode.CONNECTION_FAILED
        BackupOperation.ListingRemote -> BackupErrorCode.REMOTE_LIST_FAILED
        BackupOperation.Exporting -> BackupErrorCode.EXPORT_FAILED
        BackupOperation.Uploading -> BackupErrorCode.UPLOAD_FAILED
        BackupOperation.StagingRestore -> BackupErrorCode.RESTORE_FAILED
        else -> BackupErrorCode.RESTORE_FAILED
    }

    companion object {
        private const val PREFS_NAME = "infinity_backup_settings"

        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(application) as T
        }
    }
}

/** 诊断只记录阶段、异常类型、分类与备份层固定文案；口令、密钥与文件内容不会出现在这些字段中。 */
internal fun backupFailureDiagnosticFields(phase: BackupOperation, error: Throwable): Map<String, Any?> = mapOf(
    "phase" to phase::class.simpleName,
    "error" to error.javaClass.simpleName,
    "reason" to error.backupFailureReason()?.name,
    "detail" to generateSequence(error) { it.cause }.take(8)
        .firstNotNullOfOrNull { it as? BackupValidationException }?.message,
    "causes" to generateSequence(error.cause) { it.cause }.take(4).joinToString(">") { it.javaClass.simpleName },
)

private fun recordBackupFailureDiagnostic(phase: BackupOperation, error: Throwable) {
    InfinityLogger.diagnostic("backup_operation_failed", backupFailureDiagnosticFields(phase, error))
}

internal fun BackupUiState.withKeysIncluded(include: Boolean): BackupUiState = copy(includeKeys = include)

internal class LazyBackupOperations(
    private val factoryDispatcher: CoroutineDispatcher,
    private val factory: () -> BackupOperations,
) : BackupOperations {
    private val mutex = Mutex()
    @Volatile private var cached: BackupOperations? = null

    private suspend fun delegate(): BackupOperations = cached ?: mutex.withLock {
        cached ?: withContext(factoryDispatcher) { factory() }.also { cached = it }
    }

    override suspend fun export(options: BackupExportOptions) = withContext(factoryDispatcher) {
        delegate().export(options)
    }
    override suspend fun upload(config: WebDavConfig, options: BackupExportOptions) = withContext(factoryDispatcher) {
        delegate().upload(config, options)
    }
    override suspend fun testRemote(config: WebDavConfig) = withContext(factoryDispatcher) {
        delegate().testRemote(config)
    }
    override suspend fun listRemote(config: WebDavConfig) = withContext(factoryDispatcher) {
        delegate().listRemote(config)
    }
    override fun newRestoreOperationId(): String = cached?.newRestoreOperationId() ?: UUID.randomUUID().toString()
    override suspend fun stageLocalRestore(
        operationId: String,
        input: InputStream,
        password: CharArray?,
    ) = withContext(factoryDispatcher) { delegate().stageLocalRestore(operationId, input, password) }
    override suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ) = withContext(factoryDispatcher) { delegate().stageRemoteRestore(operationId, config, selected, password) }
    override suspend fun discardPendingRestore(operationId: String) = withContext(factoryDispatcher) {
        delegate().discardPendingRestore(operationId)
    }
    override suspend fun authorizePendingRestore(operationId: String) = withContext(factoryDispatcher) {
        delegate().authorizePendingRestore(operationId)
    }
}

private class RepositoryBackupOperations(private val repository: BackupRepository) : BackupOperations {
    override suspend fun export(options: BackupExportOptions) = repository.export(options)
    override suspend fun upload(config: WebDavConfig, options: BackupExportOptions) = repository.upload(config, options)
    override suspend fun testRemote(config: WebDavConfig) = repository.testRemote(config)
    override suspend fun listRemote(config: WebDavConfig) = repository.listRemote(config)
    override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?) =
        repository.stageLocalRestore(operationId, input, password)
    override suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ) = repository.stageRemoteRestore(operationId, config, selected, password)
    override suspend fun discardPendingRestore(operationId: String) = repository.discardPendingRestore(operationId)
    override suspend fun authorizePendingRestore(operationId: String) {
        repository.authorizePendingRestore(operationId)
    }
}

private class SharedPreferencesBackupSettingsStore(private val prefs: SharedPreferences) : BackupSettingsStore {
    override var webDavEnabled: Boolean
        get() = prefs.getBoolean("webdav_enabled", false)
        set(value) { prefs.edit().putBoolean("webdav_enabled", value).apply() }
    override var webDavUrl: String
        get() = prefs.getString("webdav_url", "").orEmpty()
        set(value) { prefs.edit().putString("webdav_url", value).apply() }
    override var webDavUser: String
        get() = prefs.getString("webdav_user", "").orEmpty()
        set(value) { prefs.edit().putString("webdav_user", value).apply() }
    override var lastBackupTime: Long
        get() = prefs.getLong("last_backup_time", 0)
        set(value) { prefs.edit().putLong("last_backup_time", value).apply() }
    override var webDavPasswordPlaintext: String?
        get() = prefs.getString("webdav_pass", null)
        set(value) {
            prefs.edit().also { editor ->
                if (value == null) editor.remove("webdav_pass") else editor.putString("webdav_pass", value)
            }.apply()
        }
}
