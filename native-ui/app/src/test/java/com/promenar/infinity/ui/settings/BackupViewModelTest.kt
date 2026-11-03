package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.backup.BackupLayer
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.BackupFailureReason
import com.promenar.infinity.data.backup.BackupValidationException
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.backup.PendingRestorePhase
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.data.remote.webdav.WebDavPruneWarning
import com.promenar.infinity.data.repository.BackupUploadReceipt
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.WebDavAuthRecordCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `层选择强制核心且默认不包含工作区文件和密钥`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations)
        assertThat(vm.uiState.value.selectedLayers).containsExactlyElementsIn(com.promenar.infinity.data.backup.BackupLayer.DEFAULT)
        vm.setSelectedLayers(setOf(com.promenar.infinity.data.backup.BackupLayer.SESSIONS))
        vm.export(ByteArrayOutputStream(), null, null)
        advanceUntilIdle()
        assertThat(operations.lastOptions!!.layers).containsExactly(com.promenar.infinity.data.backup.BackupLayer.CORE, com.promenar.infinity.data.backup.BackupLayer.SESSIONS)
        assertThat(vm.uiState.value.exportSummary).isNotNull()
        vm.dismissExportSummary()
        assertThat(vm.uiState.value.exportSummary).isNull()
    }

    @Test
    fun `逐层开关不能取消核心层且工作区文件可单独加入`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertThat(vm.setLayerSelected(BackupLayer.CORE, false)).isFalse()
        assertThat(vm.setLayerSelected(BackupLayer.WORKSPACE_FILES, true)).isTrue()
        assertThat(vm.setLayerSelected(BackupLayer.KNOWLEDGE, false)).isTrue()
        assertThat(vm.uiState.value.selectedLayers).containsExactly(BackupLayer.CORE, BackupLayer.SESSIONS, BackupLayer.WORKSPACE_FILES)
    }

    @Test
    fun `每层估算刷新为独立状态且密钥勾选同步层选择`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setIncludeKeys(true)
        assertThat(vm.uiState.value.selectedLayers).contains(com.promenar.infinity.data.backup.BackupLayer.SECRETS)
        vm.setIncludeKeys(false)
        assertThat(vm.uiState.value.selectedLayers).doesNotContain(com.promenar.infinity.data.backup.BackupLayer.SECRETS)
        assertThat(vm.refreshEstimates()).isTrue()
        advanceUntilIdle()
        assertThat(vm.uiState.value.estimating).isFalse()
        assertThat(vm.uiState.value.estimateFailed).isFalse()
    }

    @Test
    fun `backup actions stack at accessibility font scales`() {
        assertThat(shouldStackBackupActions(1.0f)).isFalse()
        assertThat(shouldStackBackupActions(1.49f)).isFalse()
        assertThat(shouldStackBackupActions(1.5f)).isTrue()
        assertThat(shouldStackBackupActions(2.0f)).isTrue()
    }

    @Test
    fun `state never contains secrets and include keys defaults off`() {
        val state = BackupUiState()
        val names = BackupUiState::class.java.declaredFields.map { it.name }

        assertThat(state.includeKeys).isFalse()
        assertThat(state.encryptBackup).isFalse()
        assertThat(state.hasWebDavPassword).isFalse()
        assertThat(names).containsNoneOf("webdavPass", "password", "passwordConfirmation", "apiKey", "progress")
    }

    @Test
    fun `enabled WebDAV without endpoint user or password is incomplete and cannot upload`() {
        val vm = newViewModel(
            settings = FakeSettings(webDavEnabled = true),
            secrets = FakeSecrets(),
        )

        assertThat(vm.uiState.value.webDavReadiness).isEqualTo(WebDavReadiness.Incomplete)
        assertThat(vm.upload("pw".toCharArray(), "pw".toCharArray())).isFalse()
    }

    @Test
    fun `disabled WebDAV never starts a remote operation even when canonical auth is complete`() {
        val operations = FakeOperations()
        val settings = FakeSettings(
            webDavEnabled = false,
            webDavUrl = "https://dav.invalid/",
            webDavUser = "user",
        )
        val secrets = FakeSecrets().apply {
            put(SecretCatalog.webDavPassword, "secret".encodeToByteArray())
        }
        val vm = newViewModel(operations = operations, settings = settings, secrets = secrets)

        assertThat(vm.uiState.value.webDavReadiness).isEqualTo(WebDavReadiness.Disabled)
        assertThat(vm.testConnection()).isFalse()
        assertThat(operations.lastConfig).isNull()
    }

    @Test
    fun `core-only encrypted export forwards password without including keys`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations)
        vm.setEncryptBackup(true)
        val password = "same".toCharArray()
        val confirmation = "same".toCharArray()

        vm.export(ByteArrayOutputStream(), password, confirmation)
        advanceUntilIdle()

        assertThat(operations.lastOptions?.includeSecrets).isFalse()
        assertThat(operations.lastOptions?.encryptPackage).isTrue()
        assertThat(operations.lastOptions?.password).isEqualTo(CharArray(4))
    }

    @Test
    fun `only genuinely cancellable operation phases expose cancel`() {
        assertThat(BackupOperation.Testing.isCancellable).isTrue()
        assertThat(BackupOperation.ListingRemote.isCancellable).isTrue()
        assertThat(BackupOperation.Exporting.isCancellable).isTrue()
        assertThat(BackupOperation.Uploading.isCancellable).isTrue()
        assertThat(BackupOperation.StagingRestore.isCancellable).isTrue()
        assertThat(BackupOperation.Initializing.isCancellable).isFalse()
        assertThat(BackupOperation.SavingConfig.isCancellable).isFalse()
        assertThat(BackupOperation.CancellingRestore.isCancellable).isFalse()
        assertThat(BackupOperation.Restarting.isCancellable).isFalse()
    }

    @Test
    fun `document errors accept only stable document codes while idle`() {
        val vm = newViewModel()

        assertThat(vm.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)).isTrue()
        assertThat(vm.uiState.value.operation).isEqualTo(
            BackupOperation.Error(BackupErrorCode.DOCUMENT_OPEN_FAILED),
        )
        assertThrows<IllegalArgumentException> {
            vm.reportDocumentError(BackupErrorCode.RESTORE_FAILED)
        }
    }

    @Test
    fun `startup migrates nonempty plaintext once without overwriting an existing secret`() {
        val settings = FakeSettings(webDavPasswordPlaintext = "legacy")
        val secrets = FakeSecrets()
        newViewModel(settings = settings, secrets = secrets)

        assertThat(secrets.authPassword()).isEqualTo("legacy")
        assertThat(settings.webDavPasswordPlaintext).isNull()

        settings.webDavPasswordPlaintext = "stale"
        secrets.put(SecretCatalog.webDavPassword, "current".encodeToByteArray())
        val vm = newViewModel(settings = settings, secrets = secrets)
        // canonical 已存在时不再使用后写入的 legacy raw secret。
        assertThat(secrets.authPassword()).isEqualTo("legacy")
        assertThat(settings.webDavPasswordPlaintext).isNull()
        assertThat(vm.uiState.value.hasWebDavPassword).isTrue()
    }

    @Test
    fun `legacy raw secret wins over plaintext and both legacy locations are removed after canonical commit`() {
        val settings = FakeSettings(
            webDavUrl = "https://legacy.invalid/",
            webDavUser = "legacy-user",
            webDavPasswordPlaintext = "plaintext-stale",
        )
        val secrets = FakeSecrets().apply {
            put(SecretCatalog.webDavPassword, "raw-current".encodeToByteArray())
        }

        val vm = newViewModel(settings = settings, secrets = secrets)

        assertThat(secrets.authPassword()).isEqualTo("raw-current")
        assertThat(secrets.contains(SecretCatalog.webDavPassword)).isFalse()
        assertThat(settings.webDavPasswordPlaintext).isNull()
        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://legacy.invalid/")
        assertThat(vm.uiState.value.webdavUser).isEqualTo("legacy-user")
    }

    @Test
    fun `saving replacing and deleting WebDAV password uses SecretStore and clears plaintext`() {
        val settings = FakeSettings(webDavPasswordPlaintext = "legacy")
        val secrets = FakeSecrets()
        val vm = newViewModel(settings = settings, secrets = secrets)

        val first = "first".toCharArray()
        vm.saveWebDavConfig("https://dav.invalid/", "user", first)
        assertThat(first).isEqualTo(CharArray(5))
        assertThat(secrets.authPassword()).isEqualTo("first")
        assertThat(settings.webDavPasswordPlaintext).isNull()

        val second = "second".toCharArray()
        vm.saveWebDavConfig("https://dav.invalid/", "user", second)
        assertThat(second).isEqualTo(CharArray(6))
        assertThat(secrets.authPassword()).isEqualTo("second")

        vm.deleteWebDavPassword()
        assertThat(secrets.contains(SecretCatalog.webDavPassword)).isFalse()
        assertThat(vm.uiState.value.hasWebDavPassword).isFalse()
    }

    @Test
    fun `failed canonical save wipes input preserves old auth and removes unsafe plaintext fallback`() {
        val settings = FakeSettings(
            webDavUrl = "https://old.invalid/",
            webDavUser = "old-user",
            webDavPasswordPlaintext = "legacy",
        )
        val secrets = FakeSecrets()
        val vm = newViewModel(settings = settings, secrets = secrets)
        settings.webDavPasswordPlaintext = "fallback"
        secrets.throwOnPut = true
        val password = "replacement".toCharArray()

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            vm.saveWebDavConfig("https://new.invalid/", "new-user", password)
        }

        assertThat(password).isEqualTo(CharArray("replacement".length))
        assertThat(settings.webDavUrl).isEqualTo("https://old.invalid/")
        assertThat(settings.webDavUser).isEqualTo("old-user")
        assertThat(settings.webDavPasswordPlaintext).isNull()
        assertThat(secrets.authPassword()).isEqualTo("legacy")
    }

    @Test
    fun `mismatched key password never calls repository and consumes both inputs`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations)
        vm.setIncludeKeys(true)
        val password = "one".toCharArray()
        val confirmation = "two".toCharArray()

        vm.export(ByteArrayOutputStream(), password, confirmation)
        advanceUntilIdle()

        assertThat(operations.exportCalls).isEqualTo(0)
        assertThat(password).isEqualTo(CharArray(3))
        assertThat(confirmation).isEqualTo(CharArray(3))
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)
    }

    @Test
    fun `matching key password reaches repository then all owned arrays are cleared`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations)
        vm.setIncludeKeys(true)
        val password = "same".toCharArray()
        val confirmation = "same".toCharArray()

        vm.export(ByteArrayOutputStream(), password, confirmation)
        advanceUntilIdle()

        assertThat(operations.exportCalls).isEqualTo(1)
        assertThat(operations.observedPassword).isEqualTo(CharArray(4))
        assertThat(operations.observedConfirmation).isEqualTo(CharArray(4))
        assertThat(password).isEqualTo(CharArray(4))
        assertThat(confirmation).isEqualTo(CharArray(4))
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
    }

    @Test
    fun `test and list use stored password while state exposes only real remote metadata`() = runTest(dispatcher) {
        val remote = RemoteBackup("one.infinity", 42, 1234, "etag")
        val operations = FakeOperations(remote = listOf(remote))
        val secrets = FakeSecrets().apply { put(SecretCatalog.webDavPassword, "secret".encodeToByteArray()) }
        val vm = newViewModel(operations = operations, secrets = secrets)
        vm.saveWebDavConfig("https://dav.invalid/", "u", null)

        vm.testConnection()
        advanceUntilIdle()
        assertThat(operations.lastConfig?.password).isEqualTo("secret")
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)

        vm.listRemote()
        advanceUntilIdle()
        assertThat(vm.uiState.value.remoteBackups).containsExactly(remote)
        assertThat(vm.selectRemote(remote)).isTrue()
        assertThat(vm.selectRemote(remote.copy(strongEtag = "changed"))).isFalse()
    }

    @Test
    fun `WebDAV reveal returns owned clearable array without adding plaintext to state`() = runTest(dispatcher) {
        val secrets = FakeSecrets()
        val vm = newViewModel(secrets = secrets)
        vm.saveWebDavConfig("https://dav.invalid/", "user", "temporary-password".toCharArray())

        val revealed = vm.revealWebDavPassword()

        assertThat(revealed?.concatToString()).isEqualTo("temporary-password")
        assertThat(vm.uiState.value.toString()).doesNotContain("temporary-password")
        revealed?.fill('\u0000')
        assertThat(revealed).isEqualTo(CharArray("temporary-password".length))
    }

    @Test
    fun `changing WebDAV endpoint or password invalidates listed selection`() = runTest(dispatcher) {
        val remote = RemoteBackup("a.infinity", 42, 1234, "etag-a")
        val operations = FakeOperations(remote = listOf(remote))
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)
        vm.saveWebDavConfig("https://a.invalid/", "a", "a-pass".toCharArray())
        vm.listRemote(); advanceUntilIdle(); assertThat(vm.selectRemote(remote)).isTrue()

        vm.saveWebDavConfig("https://b.invalid/", "b", "b-pass".toCharArray())

        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.selectedRemote).isNull()
        assertThat(vm.restoreSelectedRemote(null)).isFalse()
        assertThat(operations.stageRemoteCalls).isEqualTo(0)
        assertThat(restart.calls).isEqualTo(0)

        operations.remote = listOf(remote)
        vm.listRemote(); advanceUntilIdle(); assertThat(vm.selectRemote(remote)).isTrue()
        vm.deleteWebDavPassword()
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.selectedRemote).isNull()
    }

    @Test
    fun `listing result from old config cannot repopulate state after config change`() = runTest(dispatcher) {
        val stale = RemoteBackup("stale.infinity", 42, 1234, "etag-a")
        val listGate = CompletableDeferred<List<RemoteBackup>>()
        val operations = FakeOperations(listGate = listGate)
        val vm = newViewModel(operations = operations)
        vm.saveWebDavConfig("https://a.invalid/", "a", null)
        vm.listRemote()
        runCurrent()

        vm.saveWebDavConfig("https://b.invalid/", "b", null)
        listGate.complete(listOf(stale))
        advanceUntilIdle()

        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://b.invalid/")
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.selectedRemote).isNull()
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
    }

    @Test
    fun `config change at commit hook prevents old list state write`() = runTest(dispatcher) {
        val stale = RemoteBackup("stale.infinity", 42, 1234, "etag-a")
        val hooks = CommitGateHooks(BackupOperation.ListingRemote)
        val vm = newViewModel(operations = FakeOperations(remote = listOf(stale)), hooks = hooks)
        vm.saveWebDavConfig("https://a.invalid/", "a", null)
        vm.listRemote()
        runCurrent()
        hooks.entered.await()

        vm.saveWebDavConfig("https://b.invalid/", "b", null)
        hooks.release.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://b.invalid/")
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
    }

    @Test
    fun `operations reject during lock-free config save and never mix old endpoint with new password`() = runTest(dispatcher) {
        val secrets = FakeSecrets()
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations, secrets = secrets)
        vm.saveWebDavConfig("https://a.invalid/", "a", "a-pass".toCharArray())
        secrets.blockPut = true
        val saveResult = AtomicReference<Boolean>()
        val saveThread = Thread {
            saveResult.set(vm.saveWebDavConfig("https://b.invalid/", "b", "b-pass".toCharArray()))
        }.apply { start() }
        assertThat(secrets.putEntered.await(5, TimeUnit.SECONDS)).isTrue()

        val operationResult = AtomicReference<Boolean>()
        val operationThread = Thread { operationResult.set(vm.testConnection()) }.apply { start() }
        operationThread.join(1_000)
        val returnedWithoutBlocking = !operationThread.isAlive
        secrets.putRelease.countDown()
        saveThread.join(5_000)
        operationThread.join(5_000)

        assertThat(returnedWithoutBlocking).isTrue()
        assertThat(operationResult.get()).isFalse()
        assertThat(saveResult.get()).isTrue()
        assertThat(vm.testConnection()).isTrue()
        advanceUntilIdle()
        assertThat(operations.lastConfig?.baseUrl).isEqualTo("https://b.invalid/")
        assertThat(operations.lastConfig?.username).isEqualTo("b")
        assertThat(operations.lastConfig?.password).isEqualTo("b-pass")
    }

    @Test
    fun `remote restore stages exact selected object and stale selection cannot restart`() = runTest(dispatcher) {
        val remote = RemoteBackup("one.infinity", 42, 1234, "etag")
        val operations = FakeOperations(remote = listOf(remote))
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)
        vm.listRemote(); advanceUntilIdle(); assertThat(vm.selectRemote(remote)).isTrue()

        operations.remote = listOf(remote.copy(strongEtag = "new"))
        val password = "optional".toCharArray()
        vm.restoreSelectedRemote(password)
        advanceUntilIdle()

        assertThat(password).isEqualTo(CharArray(8))
        assertThat(operations.stageRemoteCalls).isEqualTo(1)
        assertThat(restart.calls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)
    }

    @Test
    fun `local stage failure does not restart while success transitions to Restarting`() = runTest(dispatcher) {
        val operations = FakeOperations(stageFailure = true)
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)

        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        assertThat(restart.calls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)

        operations.stageFailure = false
        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        assertThat(restart.calls).isEqualTo(1)
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Restarting)
    }

    @Test
    fun `upload preserves committed cleanup warning`() = runTest(dispatcher) {
        val operations = FakeOperations(warning = WebDavPruneWarning("cleanup", listOf("old")))
        val vm = newViewModel(operations = operations)

        vm.upload(null, null)
        advanceUntilIdle()

        val success = vm.uiState.value.operation as BackupOperation.Success
        assertThat(success.cleanupWarning).isTrue()
    }

    @Test
    fun `only one operation runs and cancellation prevents stale result overwrite`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)

        assertThat(vm.testConnection()).isTrue()
        runCurrent()
        assertThat(vm.listRemote()).isFalse()
        vm.cancelOperation()
        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
    }

    @Test
    fun `rejected password operation cannot overwrite the active phase`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)
        vm.setIncludeKeys(true)
        vm.testConnection()
        runCurrent()

        val password = "one".toCharArray()
        val confirmation = "two".toCharArray()
        assertThat(vm.export(ByteArrayOutputStream(), password, confirmation)).isFalse()

        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        assertThat(password).isEqualTo(CharArray(3))
        assertThat(confirmation).isEqualTo(CharArray(3))
        vm.cancelOperation()
    }

    @Test
    fun `busy rejection closes owned local restore stream`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)
        vm.testConnection()
        runCurrent()
        val input = CloseTrackingInputStream(byteArrayOf(1))

        assertThat(vm.restoreLocal(input, null)).isFalse()

        assertThat(input.closed).isTrue()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        vm.cancelOperation()
    }

    @Test
    fun `busy remote restore rejection preserves active phase and local close Error propagates`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val vm = newViewModel(operations = FakeOperations(testGate = gate))
        vm.testConnection(); runCurrent()

        assertThat(vm.restoreSelectedRemote(null)).isFalse()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)

        val input = CloseTrackingInputStream(byteArrayOf(1), closeError = AssertionError("input-close"))
        val thrown = org.junit.jupiter.api.assertThrows<AssertionError> {
            vm.restoreLocal(input, null)
        }
        assertThat(thrown).hasMessageThat().isEqualTo("input-close")
        vm.cancelOperation()
    }

    @Test
    fun `export owns and closes output on busy and password validation rejection`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val vm = newViewModel(operations = FakeOperations(testGate = gate))
        vm.testConnection(); runCurrent()
        val busyOutput = TrackingOutputStream()
        assertThat(vm.export(busyOutput, null, null)).isFalse()
        assertThat(busyOutput.closeCount).isEqualTo(1)
        vm.cancelOperation()

        vm.setIncludeKeys(true)
        val missingOutput = TrackingOutputStream()
        assertThat(vm.export(missingOutput, null, null)).isFalse()
        assertThat(missingOutput.closeCount).isEqualTo(1)

        val mismatchOutput = TrackingOutputStream()
        assertThat(vm.export(mismatchOutput, "one".toCharArray(), "two".toCharArray())).isFalse()
        assertThat(mismatchOutput.closeCount).isEqualTo(1)
    }

    @Test
    fun `export closes output exactly once on success failure and prelaunch cancellation`() = runTest(dispatcher) {
        val successOutput = TrackingOutputStream()
        val successVm = newViewModel()
        successVm.export(successOutput, null, null); advanceUntilIdle()
        assertThat(successOutput.closeCount).isEqualTo(1)

        val failedOutput = TrackingOutputStream()
        val failedVm = newViewModel(operations = FakeOperations(exportFailure = true))
        failedVm.export(failedOutput, null, null); advanceUntilIdle()
        assertThat(failedOutput.closeCount).isEqualTo(1)
        assertThat(failedVm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)

        val exportGate = CompletableDeferred<Unit>()
        val cancelledOutput = TrackingOutputStream()
        val cancelledVm = newViewModel(operations = FakeOperations(exportGate = exportGate))
        cancelledVm.export(cancelledOutput, null, null)
        cancelledVm.cancelOperation()
        advanceUntilIdle()
        assertThat(cancelledOutput.closeCount).isEqualTo(1)

        val clearGate = CompletableDeferred<Unit>()
        val clearedOutput = TrackingOutputStream()
        val clearedVm = newViewModel(operations = FakeOperations(exportGate = clearGate))
        val store = ViewModelStore().apply { put("export", clearedVm) }
        clearedVm.export(clearedOutput, null, null)
        store.clear()
        advanceUntilIdle()
        assertThat(clearedOutput.closeCount).isEqualTo(1)
    }

    @Test
    fun `导出因容量超限失败时显示容量原因并记录诊断`() = runTest(dispatcher) {
        val recorded = mutableListOf<Pair<BackupOperation, Throwable>>()
        val failure = BackupValidationException("snapshot 超过 Android 内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        val vm = newViewModel(operations = FakeOperations(exportError = failure), failureRecorder = { phase, error -> recorded += phase to error })

        vm.export(TrackingOutputStream(), null, null); advanceUntilIdle()

        assertThat(vm.uiState.value.operation)
            .isEqualTo(BackupOperation.Error(BackupErrorCode.EXPORT_FAILED, BackupFailureReason.CAPACITY_EXCEEDED))
        assertThat(recorded).containsExactly(BackupOperation.Exporting to failure)
    }

    @Test
    fun `会话文件不一致与技能内容原因经原因链传到上传错误态`() = runTest(dispatcher) {
        val wrapped = IllegalStateException("wrapper", BackupValidationException("备份源文件不存在", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH))
        val vm = newViewModel(operations = FakeOperations(uploadError = wrapped))
        vm.upload(null, null); advanceUntilIdle()
        assertThat(vm.uiState.value.operation)
            .isEqualTo(BackupOperation.Error(BackupErrorCode.UPLOAD_FAILED, BackupFailureReason.WORKSPACE_FILE_MISMATCH))

        val skill = newViewModel(operations = FakeOperations(exportError = BackupValidationException("技能目录包含非普通文件", reason = BackupFailureReason.SKILL_CONTENT_UNSUPPORTED)))
        skill.export(TrackingOutputStream(), null, null); advanceUntilIdle()
        assertThat(skill.uiState.value.operation)
            .isEqualTo(BackupOperation.Error(BackupErrorCode.EXPORT_FAILED, BackupFailureReason.SKILL_CONTENT_UNSUPPORTED))
    }

    @Test
    fun `未归类的导出失败仍显示通用错误且记录诊断`() = runTest(dispatcher) {
        val recorded = mutableListOf<BackupOperation>()
        val vm = newViewModel(operations = FakeOperations(exportFailure = true), failureRecorder = { phase, _ -> recorded += phase })
        vm.export(TrackingOutputStream(), null, null); advanceUntilIdle()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Error(BackupErrorCode.EXPORT_FAILED))
        assertThat(recorded).containsExactly(BackupOperation.Exporting)
    }

    @Test
    fun `导出与上传遇到 Error 时进入错误态而不让应用崩溃`() = runTest(dispatcher) {
        val output = TrackingOutputStream()
        val exportVm = newViewModel(operations = FakeOperations(exportError = OutOfMemoryError("synthetic")))
        exportVm.export(output, null, null); advanceUntilIdle()
        assertThat(exportVm.uiState.value.operation)
            .isEqualTo(BackupOperation.Error(BackupErrorCode.EXPORT_FAILED, BackupFailureReason.CAPACITY_EXCEEDED))
        assertThat(output.closeCount).isEqualTo(1)
        assertThat(exportVm.uiState.value.canExecute).isTrue()

        val uploadVm = newViewModel(operations = FakeOperations(uploadError = StackOverflowError("synthetic")))
        uploadVm.upload(null, null); advanceUntilIdle()
        assertThat(uploadVm.uiState.value.operation).isEqualTo(BackupOperation.Error(BackupErrorCode.UPLOAD_FAILED))
        assertThat(uploadVm.testConnection()).isTrue()
        uploadVm.cancelOperation()
    }

    @Test
    fun `导出写入文档输出流在 IO 调度器上执行`() = runTest(dispatcher) {
        val io = MarkingDispatcher(dispatcher)
        val output = ThreadMarkingOutputStream(io)
        val vm = newViewModel(ioDispatcher = io)
        vm.export(output, null, null); advanceUntilIdle()
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
        assertThat(output.writesOutsideIo).isEqualTo(0)
        assertThat(output.writesOnIo).isGreaterThan(0)
    }

    @Test
    fun `失败诊断只含阶段类型分类与备份层固定文案`() {
        val error = IllegalStateException("synthetic-password", BackupValidationException("备份源文件不存在", java.io.IOException("/data/user/0/secret"), BackupFailureReason.WORKSPACE_FILE_MISMATCH))
        val fields = backupFailureDiagnosticFields(BackupOperation.Exporting, error)
        assertThat(fields).containsExactly(
            "phase", "Exporting",
            "error", "IllegalStateException",
            "reason", "WORKSPACE_FILE_MISMATCH",
            "detail", "备份源文件不存在",
            "causes", "BackupValidationException>IOException",
        )
        assertThat(fields.values.joinToString()).doesNotContain("synthetic-password")
        assertThat(fields.values.joinToString()).doesNotContain("/data/user/0")
    }

    private class MarkingDispatcher(private val delegate: kotlinx.coroutines.CoroutineDispatcher) : kotlinx.coroutines.CoroutineDispatcher() {
        val active = ThreadLocal.withInitial { false }
        override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = true
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            delegate.dispatch(context, Runnable {
                active.set(true)
                try { block.run() } finally { active.set(false) }
            })
        }
    }

    private class ThreadMarkingOutputStream(private val io: MarkingDispatcher) : OutputStream() {
        var writesOnIo = 0
        var writesOutsideIo = 0
        private fun mark() { if (io.active.get()) writesOnIo++ else writesOutsideIo++ }
        override fun write(value: Int) = mark()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = mark()
    }

    @Test
    fun `export close Error is not swallowed on synchronous rejection`() {
        val vm = newViewModel()
        vm.setIncludeKeys(true)
        val output = TrackingOutputStream(closeError = AssertionError("close-error"))

        val thrown = org.junit.jupiter.api.assertThrows<AssertionError> {
            vm.export(output, null, null)
        }

        assertThat(thrown).hasMessageThat().isEqualTo("close-error")
        assertThat(output.closeCount).isEqualTo(1)
    }

    @Test
    fun `LAZY registration placeholder rejects a concurrent second start`() {
        val hooks = BlockingRegistrationHooks()
        val vm = newViewModel(hooks = hooks)
        val firstResult = AtomicReference<Boolean>()
        val first = Thread { firstResult.set(vm.testConnection()) }.apply { start() }
        assertThat(hooks.entered.await(5, TimeUnit.SECONDS)).isTrue()

        val secondResult = vm.listRemote()

        assertThat(secondResult).isFalse()
        hooks.release.countDown()
        first.join(5_000)
        assertThat(firstResult.get()).isTrue()
        vm.cancelOperation()
    }

    @Test
    fun `cancel cleanup cannot overwrite a newly registered phase`() = runTest(dispatcher) {
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val output = TrackingOutputStream(closeEntered = closeEntered, closeRelease = closeRelease)
        val exportGate = CompletableDeferred<Unit>()
        val vm = newViewModel(operations = FakeOperations(exportGate = exportGate))
        vm.export(output, null, null)
        val cancelThread = Thread { vm.cancelOperation() }.apply { start() }
        assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(vm.testConnection()).isTrue()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        closeRelease.countDown()
        cancelThread.join(5_000)

        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        vm.cancelOperation()
    }

    @Test
    fun `cancel after remote stage starts can never request restart`() = runTest(dispatcher) {
        val remote = RemoteBackup("one.infinity", 42, 1234, "etag")
        val stageStarted = CompletableDeferred<Unit>()
        val stageRelease = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            remote = listOf(remote),
            remoteStageStarted = stageStarted,
            remoteStageGate = stageRelease,
        )
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)
        vm.listRemote(); advanceUntilIdle(); vm.selectRemote(remote)
        vm.restoreSelectedRemote(null)
        runCurrent(); stageStarted.await()

        vm.cancelOperation()
        stageRelease.complete(Unit)
        advanceUntilIdle()

        assertThat(restart.calls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
    }

    @Test
    fun `clearing ViewModel cancels in flight work`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)
        val store = ViewModelStore().apply { put("backup", vm) }

        vm.testConnection(); runCurrent(); store.clear(); advanceUntilIdle()

        assertThat(operations.testCancelled).isTrue()
    }

    @Test
    fun `cancelled noncooperative stage keeps gate closed until durable pending cleanup finishes`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val discardRelease = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            localStageStarted = started,
            localStageGate = release,
            discardGate = discardRelease,
        )
        val vm = newViewModel(operations = operations)
        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        runCurrent(); started.await()

        vm.cancelOperation()
        assertThat(vm.testConnection()).isFalse()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.CancellingRestore)
        release.complete(Unit)
        runCurrent()
        assertThat(operations.discardCalls).isEqualTo(1)
        assertThat(vm.listRemote()).isFalse()
        val rejectedPassword = "new-secret".toCharArray()
        assertThat(vm.saveWebDavConfig("https://blocked.invalid/", "u", rejectedPassword)).isFalse()
        assertThat(rejectedPassword).isEqualTo(CharArray("new-secret".length))

        discardRelease.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
        assertThat(vm.testConnection()).isTrue()
    }

    @Test
    fun `onCleared keeps independent cleanup scope alive for noncooperative staged restore`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = FakeOperations(localStageStarted = started, localStageGate = release)
        val vm = newViewModel(operations = operations)
        val store = ViewModelStore().apply { put("restore", vm) }
        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        runCurrent(); started.await()

        store.clear()
        assertThat(vm.testConnection()).isFalse()
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(operations.discardCalls).isEqualTo(1)
        assertThat(operations.pending).isFalse()
    }

    @Test
    fun `restore cancellation immediately closes a genuinely blocking input and then cleans pending`() = runTest(dispatcher) {
        val input = CloseUnblocksBlockingInputStream()
        val operations = FakeOperations(readInputUntilClosed = true)
        val vm = newViewModel(operations = operations)
        val password = "restore-secret".toCharArray()
        vm.restoreLocal(input, password)
        runCurrent()
        assertThat(input.readEntered.await(5, TimeUnit.SECONDS)).isTrue()

        vm.cancelOperation()

        assertThat(input.readExited.await(5, TimeUnit.SECONDS)).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (operations.discardCalls == 0 && System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertThat(input.closeCount).isEqualTo(1)
        assertThat(operations.restorePasswordReference).isEqualTo(CharArray("restore-secret".length))
        assertThat(operations.discardCalls).isEqualTo(1)
        assertThat(operations.pending).isFalse()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
    }

    @Test
    fun `blocking input close Exception is released after durable discard while Error stays blocked`() = runTest(dispatcher) {
        val exceptionInput = CloseUnblocksBlockingInputStream(closeFailure = IOException("close-exception"))
        val exceptionOps = FakeOperations(readInputUntilClosed = true)
        val exceptionVm = newViewModel(operations = exceptionOps)
        exceptionVm.restoreLocal(exceptionInput, null)
        runCurrent(); assertThat(exceptionInput.readEntered.await(5, TimeUnit.SECONDS)).isTrue()
        exceptionVm.cancelOperation()
        assertThat(exceptionInput.readExited.await(5, TimeUnit.SECONDS)).isTrue()
        val exceptionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (exceptionOps.discardCalls == 0 && System.nanoTime() < exceptionDeadline) {
            runCurrent()
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertThat(exceptionOps.discardCalls).isEqualTo(1)
        assertThat(exceptionOps.pending).isFalse()
        assertThat(exceptionVm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
        assertThat(exceptionVm.testConnection()).isTrue()
        exceptionVm.cancelOperation()

        val errorInput = CloseUnblocksBlockingInputStream(closeFailure = AssertionError("close-error"))
        val errorOps = FakeOperations(readInputUntilClosed = true)
        val errorVm = newViewModel(operations = errorOps)
        errorVm.restoreLocal(errorInput, null)
        runCurrent(); assertThat(errorInput.readEntered.await(5, TimeUnit.SECONDS)).isTrue()
        val thrown = assertThrows<AssertionError> { errorVm.cancelOperation() }
        assertThat(thrown).hasMessageThat().isEqualTo("close-error")
        assertThat(errorVm.uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
        assertThat(errorInput.readExited.await(5, TimeUnit.SECONDS)).isTrue()
        advanceUntilIdle()
    }

    @Test
    fun `stage write followed by failure is cleaned and cleanup failure blocks every new operation`() = runTest(dispatcher) {
        val cleaned = FakeOperations(stageWriteThenThrow = true)
        val cleanedVm = newViewModel(operations = cleaned)
        cleanedVm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        assertThat(cleaned.discardCalls).isEqualTo(1)
        assertThat(cleaned.pending).isFalse()
        assertThat(cleanedVm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)

        val failed = FakeOperations(stageWriteThenThrow = true, discardFailure = IllegalStateException("cleanup"))
        val failedVm = newViewModel(operations = failed)
        failedVm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        assertThat(failedVm.uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
        assertThat(failedVm.testConnection()).isFalse()
        assertThat(failedVm.restoreLocal(ByteArrayInputStream(byteArrayOf(2)), null)).isFalse()
    }

    @Test
    fun `restart authorization is linearized and dispatch exception retains pending for retry`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val restart = FakeRestart(failFirst = IllegalStateException("dispatch"))
        val vm = newViewModel(operations = operations, restart = restart)
        restart.concurrentProbe = { vm.testConnection() }

        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()

        assertThat(restart.probeReturnedWithoutBlocking).isTrue()
        assertThat(restart.probeResult).isFalse()
        assertThat(operations.pending).isTrue()
        assertThat(operations.discardCalls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
        vm.setIncludeKeys(true)
        assertThat(vm.uiState.value.includeKeys).isFalse()
        assertThat(vm.saveWebDavConfig("https://new.invalid/", "u", "p".toCharArray())).isFalse()
        vm.cancelOperation()
        assertThat(operations.discardCalls).isEqualTo(0)

        assertThat(vm.retryRestart()).isTrue()
        advanceUntilIdle()
        assertThat(restart.calls).isEqualTo(2)
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Restarting)
    }

    @Test
    fun `only explicit pending restart cancellation removes authorized pending`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(
            operations = operations,
            restart = FakeRestart(failFirst = IllegalStateException("dispatch")),
        )
        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        vm.cancelOperation()
        assertThat(operations.pending).isTrue()

        assertThat(vm.cancelPendingRestart()).isTrue()
        advanceUntilIdle()

        assertThat(operations.pending).isFalse()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
        assertThat(vm.testConnection()).isTrue()
    }

    @Test
    fun `pending restart cancellation Error blocks restore before it remains visible`() {
        val operations = FakeOperations(discardFailure = AssertionError("fatal-pending-discard"))
        val vmRef = AtomicReference<BackupViewModel>()

        val thrown = assertThrows<AssertionError> {
            runTest(dispatcher) {
                val vm = newViewModel(
                    operations = operations,
                    restart = FakeRestart(failFirst = IllegalStateException("dispatch")),
                )
                vmRef.set(vm)
                vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
                advanceUntilIdle()
                assertThat(vm.cancelPendingRestart()).isTrue()
                advanceUntilIdle()
            }
        }

        assertThat(thrown).hasMessageThat().isEqualTo("fatal-pending-discard")
        assertThat(operations.pending).isTrue()
        assertThat(vmRef.get().uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
        assertThat(vmRef.get().testConnection()).isFalse()
    }

    @Test
    fun `durable authorization failure never dispatches restart or auto-clears pending`() = runTest(dispatcher) {
        val operations = FakeOperations(authorizeFailure = IllegalStateException("authorize"))
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)

        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()

        assertThat(restart.calls).isEqualTo(0)
        assertThat(operations.pending).isTrue()
        assertThat(operations.discardCalls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
    }

    @Test
    fun `durable authorization Error blocks restore before it remains visible`() {
        val operations = FakeOperations(authorizeFailure = AssertionError("fatal-authorize"))
        val restart = FakeRestart()
        val vmRef = AtomicReference<BackupViewModel>()

        val thrown = assertThrows<AssertionError> {
            runTest(dispatcher) {
                val vm = newViewModel(operations = operations, restart = restart)
                vmRef.set(vm)
                vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
                advanceUntilIdle()
            }
        }

        assertThat(thrown).hasMessageThat().isEqualTo("fatal-authorize")
        assertThat(restart.calls).isEqualTo(0)
        assertThat(operations.pending).isTrue()
        assertThat(operations.discardCalls).isEqualTo(0)
        assertThat(vmRef.get().uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
        assertThat(vmRef.get().testConnection()).isFalse()
    }

    @Test
    fun `canonical auth commit is atomic across before-write and after-write failures`() = runTest(dispatcher) {
        val secrets = FakeSecrets()
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations, secrets = secrets)
        vm.saveWebDavConfig("https://old.invalid/", "old", "old-pass".toCharArray())

        secrets.throwBeforePut = IllegalStateException("before")
        assertThrows<IllegalStateException> {
            vm.saveWebDavConfig("https://new.invalid/", "new", "new-pass".toCharArray())
        }
        secrets.throwBeforePut = null
        vm.testConnection(); advanceUntilIdle()
        assertThat(operations.lastConfig).isEqualTo(WebDavConfig("https://old.invalid/", "old", "old-pass"))

        secrets.throwAfterPut = IllegalStateException("after")
        assertThrows<IllegalStateException> {
            vm.saveWebDavConfig("https://new.invalid/", "new", "new-pass".toCharArray())
        }
        secrets.throwAfterPut = null
        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://new.invalid/")
        vm.testConnection(); advanceUntilIdle()
        assertThat(operations.lastConfig).isEqualTo(WebDavConfig("https://new.invalid/", "new", "new-pass"))
    }

    @Test
    fun `prefs cache failure cannot split canonical auth and reconstruction repairs cache`() = runTest(dispatcher) {
        val settings = FakeSettings(webDavEnabled = true)
        val secrets = FakeSecrets()
        val operations = FakeOperations()
        val vm = newViewModel(operations, settings, secrets)
        settings.cacheFailure = IllegalStateException("prefs")

        assertThat(vm.saveWebDavConfig("https://canonical.invalid/", "canonical", "secret".toCharArray())).isTrue()
        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://canonical.invalid/")
        vm.testConnection(); advanceUntilIdle()
        assertThat(operations.lastConfig).isEqualTo(WebDavConfig("https://canonical.invalid/", "canonical", "secret"))

        settings.cacheFailure = null
        val recreated = newViewModel(operations, settings, secrets)
        assertThat(recreated.uiState.value.webdavUrl).isEqualTo("https://canonical.invalid/")
        assertThat(settings.webDavUrl).isEqualTo("https://canonical.invalid/")
    }

    @Test
    fun `corrupt canonical auth fails WebDAV closed without disabling local restore`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { putRaw(SecretCatalog.webDavAuthRecord, byteArrayOf(1, 2, 3)) }
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations, secrets = secrets)

        assertThat(vm.testConnection()).isFalse()
        assertThat(vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)).isTrue()
        advanceUntilIdle()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Restarting)
    }

    @Test
    fun `corrupt canonical rejects normal writes until explicit reset then allows clean replacement and recreation`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { putRaw(SecretCatalog.webDavAuthRecord, byteArrayOf(1, 2, 3)) }
        val settings = FakeSettings(webDavEnabled = true, webDavPasswordPlaintext = "must-not-return")
        val operations = FakeOperations()
        val vm = newViewModel(operations, settings, secrets)
        val rejected = "rejected".toCharArray()

        assertThat(vm.saveWebDavConfig("https://rejected.invalid/", "r", rejected)).isFalse()
        assertThat(rejected).isEqualTo(CharArray("rejected".length))
        assertThat(vm.testConnection()).isFalse()
        assertThat(vm.resetWebDavAuth()).isTrue()
        assertThat(secrets.contains(SecretCatalog.webDavPassword)).isFalse()
        assertThat(settings.webDavPasswordPlaintext).isNull()

        assertThat(vm.saveWebDavConfig("https://recovered.invalid/", "user", "new-secret".toCharArray())).isTrue()
        assertThat(vm.testConnection()).isTrue()
        advanceUntilIdle()
        assertThat(operations.lastConfig).isEqualTo(
            WebDavConfig("https://recovered.invalid/", "user", "new-secret"),
        )

        val recreated = newViewModel(operations, settings, secrets)
        assertThat(recreated.uiState.value.webdavUrl).isEqualTo("https://recovered.invalid/")
        assertThat(recreated.uiState.value.hasWebDavPassword).isTrue()
    }

    @Test
    fun `config cache Error propagates but reservation is released after canonical publish`() = runTest(dispatcher) {
        val settings = FakeSettings(webDavEnabled = true)
        val secrets = FakeSecrets()
        val operations = FakeOperations()
        val vm = newViewModel(operations, settings, secrets)
        settings.cacheFailure = AssertionError("prefs-error")

        assertThrows<AssertionError> {
            vm.saveWebDavConfig("https://committed.invalid/", "u", "p".toCharArray())
        }
        settings.cacheFailure = null
        assertThat(vm.testConnection()).isTrue()
        advanceUntilIdle()
        assertThat(operations.lastConfig?.baseUrl).isEqualTo("https://committed.invalid/")
    }

    @Test
    fun `production async init save delete and reset keep all SecretStore IO off Main`() = runTest(dispatcher) {
        val mainThread = Thread.currentThread()
        val secrets = FakeSecrets()
        val vm = newViewModel(
            secrets = secrets,
            synchronousIo = false,
            ioDispatcher = Dispatchers.IO,
        )
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Initializing)
        runCurrent()
        drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.Initializing }
        assertThat(secrets.accessThreads).isNotEmpty()
        assertThat(secrets.accessThreads.none { it === mainThread }).isTrue()

        secrets.accessThreads.clear()
        assertThat(vm.saveWebDavConfig("https://async.invalid/", "u", "p".toCharArray())).isTrue()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.SavingConfig)
        drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.SavingConfig }
        assertThat(secrets.accessThreads).isNotEmpty()
        assertThat(secrets.accessThreads.none { it === mainThread }).isTrue()

        secrets.accessThreads.clear()
        assertThat(vm.deleteWebDavPassword()).isTrue()
        drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.SavingConfig }
        assertThat(secrets.accessThreads).isNotEmpty()
        assertThat(secrets.accessThreads.none { it === mainThread }).isTrue()

        secrets.accessThreads.clear()
        assertThat(vm.resetWebDavAuth()).isTrue()
        drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.SavingConfig }
        assertThat(secrets.accessThreads).isNotEmpty()
        assertThat(secrets.accessThreads.none { it === mainThread }).isTrue()

        assertThat(vm.saveWebDavConfig("https://async.invalid/", "u", "after-reset".toCharArray())).isTrue()
        drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.SavingConfig }
        secrets.accessThreads.clear()
        assertThat(vm.testConnection()).isTrue()
        drainRealIoUntil { vm.uiState.value.operation is BackupOperation.Success }
        assertThat(secrets.accessThreads).isNotEmpty()
        assertThat(secrets.accessThreads.none { it === mainThread }).isTrue()
    }

    @Test
    fun `lazy repository operations constructs its delegate once and off Main`() = runTest(dispatcher) {
        val mainThread = Thread.currentThread()
        val factoryThreads = ConcurrentLinkedQueue<Thread>()
        val delegate = FakeOperations()
        val lazy = LazyBackupOperations(Dispatchers.IO) {
            factoryThreads += Thread.currentThread()
            delegate
        }

        assertThat(lazy.testRemote(WebDavConfig("https://factory.invalid/", "u", "p")).isSuccess).isTrue()
        lazy.listRemote(WebDavConfig("https://factory.invalid/", "u", "p"))

        assertThat(factoryThreads).hasSize(1)
        assertThat(factoryThreads.single()).isNotSameInstanceAs(mainThread)
        assertThat(delegate.operationThreads).isNotEmpty()
        assertThat(delegate.operationThreads.none { it === mainThread }).isTrue()
    }

    @Test
    fun `save and test holds one config reservation through network barrier and commits only its revision`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(
            operations = operations,
            synchronousIo = false,
            ioDispatcher = Dispatchers.IO,
        )
        runCurrent()
        drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.Initializing }

        assertThat(vm.saveAndTestWebDavConfig("https://atomic.invalid/", "new", "secret".toCharArray())).isTrue()
        drainRealIoUntil { vm.uiState.value.operation is BackupOperation.Testing }
        val rejected = "other".toCharArray()
        assertThat(vm.saveWebDavConfig("https://other.invalid/", "other", rejected)).isFalse()
        assertThat(rejected).isEqualTo(CharArray(5))
        assertThat(vm.testConnection()).isFalse()
        val localOutput = TrackingOutputStream()
        val localInput = CloseTrackingInputStream(byteArrayOf(1))
        assertThat(vm.export(localOutput, null, null)).isFalse()
        assertThat(vm.restoreLocal(localInput, null)).isFalse()
        assertThat(localOutput.closeCount).isEqualTo(1)
        assertThat(localInput.closed).isTrue()
        assertThat(operations.exportCalls).isEqualTo(0)
        assertThat(operations.pending).isFalse()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)

        gate.complete(Result.success(Unit))
        drainRealIoUntil { vm.uiState.value.operation is BackupOperation.Success }
        assertThat(operations.lastConfig).isEqualTo(WebDavConfig("https://atomic.invalid/", "new", "secret"))
        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://atomic.invalid/")
    }

    @Test
    fun `include keys export is rejected before password validation while config test owns admission`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(
            operations = operations,
            synchronousIo = false,
            ioDispatcher = Dispatchers.IO,
        )
        runCurrent(); drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.Initializing }
        vm.setIncludeKeys(true)
        assertThat(vm.saveAndTestWebDavConfig("https://busy.invalid/", "u", "pw".toCharArray())).isTrue()
        drainRealIoUntil { vm.uiState.value.operation is BackupOperation.Testing }
        val output = TrackingOutputStream()

        assertThat(vm.export(output, null, null)).isFalse()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(operations.exportCalls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        gate.complete(Result.success(Unit))
        drainRealIoUntil { vm.uiState.value.operation is BackupOperation.Success }
    }

    @Test
    fun `stale fatal config publisher cannot clear or overwrite successor revision`() {
        val vmRef = AtomicReference<BackupViewModel>()
        val hooks = FatalConfigRaceHooks()
        val secrets = FakeSecrets()

        val thrown = assertThrows<AssertionError> {
            runTest(dispatcher) {
                val vm = newViewModel(
                    secrets = secrets,
                    hooks = hooks,
                    synchronousIo = false,
                    ioDispatcher = dispatcher,
                )
                vmRef.set(vm)
                runCurrent()
                secrets.throwAfterPut = AssertionError("fatal-a")
                hooks.afterReconciled = {
                    secrets.throwAfterPut = null
                    assertThat(vm.saveWebDavConfig("https://b.invalid/", "b", "b-pass".toCharArray())).isTrue()
                }

                assertThat(vm.saveWebDavConfig("https://a.invalid/", "a", "a-pass".toCharArray())).isTrue()
                advanceUntilIdle()
                assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://b.invalid/")
                assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
            }
        }

        assertThat(thrown).hasMessageThat().isEqualTo("fatal-a")
        assertThat(vmRef.get().uiState.value.webdavUrl).isEqualTo("https://b.invalid/")
        assertThat(vmRef.get().uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
    }

    @Test
    fun `save and test failure still publishes the atomically saved canonical revision`() = runTest(dispatcher) {
        val gate = CompletableDeferred(Result.failure<Unit>(IllegalStateException("offline")))
        val operations = FakeOperations(testGate = gate)
        val settings = FakeSettings()
        val secrets = FakeSecrets()
        val vm = newViewModel(
            operations = operations,
            settings = settings,
            secrets = secrets,
            synchronousIo = false,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertThat(vm.saveAndTestWebDavConfig("https://saved.invalid/", "saved", "pw".toCharArray())).isTrue()
        advanceUntilIdle()

        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://saved.invalid/")
        assertThat(operations.lastConfig).isEqualTo(WebDavConfig("https://saved.invalid/", "saved", "pw"))
        val recreated = newViewModel(operations, settings, secrets)
        assertThat(recreated.uiState.value.webdavUrl).isEqualTo("https://saved.invalid/")
        assertThat(recreated.uiState.value.hasWebDavPassword).isTrue()
    }

    @Test
    fun `onCleared cancels save and test then reconciles canonical state without stranding mutation`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val settings = FakeSettings()
        val secrets = FakeSecrets()
        val vm = newViewModel(
            operations = operations,
            settings = settings,
            secrets = secrets,
            synchronousIo = false,
            ioDispatcher = Dispatchers.IO,
        )
        val store = ViewModelStore().apply { put("config", vm) }
        runCurrent(); drainRealIoUntil { vm.uiState.value.operation !is BackupOperation.Initializing }

        val password = "owned-secret".toCharArray()
        assertThat(vm.saveAndTestWebDavConfig("https://clear.invalid/", "clear", password)).isTrue()
        assertThat(password).isEqualTo(CharArray("owned-secret".length))
        drainRealIoUntil { vm.uiState.value.operation is BackupOperation.Testing }

        store.clear()
        drainRealIoUntil {
            vm.uiState.value.operation is BackupOperation.Error ||
                vm.uiState.value.operation is BackupOperation.Blocked
        }

        assertThat(operations.testCancelled).isTrue()
        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://clear.invalid/")
        assertThat(vm.uiState.value.webdavUser).isEqualTo("clear")
        assertThat(vm.uiState.value.operation).isNotEqualTo(BackupOperation.Testing)
        val recreated = newViewModel(operations, settings, secrets)
        assertThat(recreated.uiState.value.webdavUrl).isEqualTo("https://clear.invalid/")
        assertThat(recreated.uiState.value.hasWebDavPassword).isTrue()
    }

    @Test
    fun `onCleared reconciliation Error publishes blocked then remains visible`() {
        val vmRef = AtomicReference<BackupViewModel>()
        val thrown = assertThrows<AssertionError> {
            runTest(dispatcher) {
                val gate = CompletableDeferred<Result<Unit>>()
                val secrets = FakeSecrets()
                val vm = newViewModel(
                    operations = FakeOperations(testGate = gate),
                    secrets = secrets,
                    synchronousIo = false,
                    ioDispatcher = dispatcher,
                )
                vmRef.set(vm)
                val store = ViewModelStore().apply { put("fatal-config", vm) }
                runCurrent()
                assertThat(vm.saveAndTestWebDavConfig("https://fatal.invalid/", "u", "pw".toCharArray())).isTrue()
                runCurrent()
                assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
                secrets.getFailure = AssertionError("fatal-canonical-read")
                store.clear()
                advanceUntilIdle()
            }
        }
        assertThat(thrown).hasMessageThat().isEqualTo("fatal-canonical-read")
        assertThat(vmRef.get().uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
        assertThat(vmRef.get().testConnection()).isFalse()
    }

    @Test
    fun `restart Error is never converted to a recoverable Exception state`() {
        val operations = FakeOperations()
        val vmRef = AtomicReference<BackupViewModel>()
        val thrown = assertThrows<AssertionError> {
            runTest(dispatcher) {
                val vm = newViewModel(operations = operations, restart = FakeRestart(AssertionError("fatal-restart")))
                vmRef.set(vm)
                vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
                advanceUntilIdle()
            }
        }

        assertThat(thrown).hasMessageThat().isEqualTo("fatal-restart")
        assertThat(operations.discardCalls).isEqualTo(0)
        assertThat(operations.pending).isTrue()
        assertThat(vmRef.get().uiState.value.operation).isInstanceOf(BackupOperation.Blocked::class.java)
    }

    @Test
    fun `pending cleanup Error remains visible and leaves restore fail closed`() {
        val operations = FakeOperations(
            stageWriteThenThrow = true,
            discardFailure = AssertionError("fatal-cleanup"),
        )
        val thrown = assertThrows<AssertionError> {
            runTest(dispatcher) {
                val vm = newViewModel(operations = operations)
                vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
                advanceUntilIdle()
            }
        }

        assertThat(thrown).hasMessageThat().isEqualTo("fatal-cleanup")
        assertThat(operations.pending).isTrue()
        assertThat(operations.discardCalls).isEqualTo(1)
    }

    private fun newViewModel(
        operations: FakeOperations = FakeOperations(),
        settings: FakeSettings? = null,
        secrets: FakeSecrets? = null,
        restart: FakeRestart = FakeRestart(),
        hooks: BackupViewModelHooks = BackupViewModelHooks.None,
        synchronousIo: Boolean = true,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = dispatcher,
        failureRecorder: (BackupOperation, Throwable) -> Unit = { _, _ -> },
    ): BackupViewModel {
        val completeSettings = settings ?: FakeSettings(
            webDavEnabled = true,
            webDavUrl = "https://default.invalid/",
            webDavUser = "default-user",
        )
        val completeSecrets = secrets ?: FakeSecrets().apply {
            put(SecretCatalog.webDavPassword, "default-password".encodeToByteArray())
        }
        return BackupViewModel(
            operations,
            completeSettings,
            completeSecrets,
            restart,
            clock = { 999L },
            hooks = hooks,
            ioDispatcher = ioDispatcher,
            synchronousIoForTests = synchronousIo,
            failureRecorder = failureRecorder,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.drainRealIoUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(10)
        }
        assertThat(condition()).isTrue()
    }

    private class BlockingRegistrationHooks : BackupViewModelHooks {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun afterOperationRegistered(phase: BackupOperation) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        override suspend fun beforeStateCommit(phase: BackupOperation) = Unit
    }

    private class CommitGateHooks(private val target: BackupOperation) : BackupViewModelHooks {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override fun afterOperationRegistered(phase: BackupOperation) = Unit
        override suspend fun beforeStateCommit(phase: BackupOperation) {
            if (phase::class == target::class) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
    }

    private class FatalConfigRaceHooks : BackupViewModelHooks {
        var afterReconciled: () -> Unit = {}
        override fun afterOperationRegistered(phase: BackupOperation) = Unit
        override suspend fun beforeStateCommit(phase: BackupOperation) = Unit
        override fun afterConfigFailureReconciled(revision: Long) = afterReconciled()
    }

    private class FakeSettings(
        webDavEnabled: Boolean = false,
        webDavUrl: String = "",
        webDavUser: String = "",
        override var lastBackupTime: Long = 0,
        webDavPasswordPlaintext: String? = null,
    ) : BackupSettingsStore {
        var cacheFailure: Throwable? = null
        private var enabledValue = webDavEnabled
        private var urlValue = webDavUrl
        private var userValue = webDavUser
        private var plaintextValue = webDavPasswordPlaintext
        override var webDavEnabled: Boolean
            get() = enabledValue
            set(value) { cacheFailure?.let { throw it }; enabledValue = value }
        override var webDavUrl: String
            get() = urlValue
            set(value) { cacheFailure?.let { throw it }; urlValue = value }
        override var webDavUser: String
            get() = userValue
            set(value) { cacheFailure?.let { throw it }; userValue = value }
        override var webDavPasswordPlaintext: String?
            get() = plaintextValue
            set(value) { cacheFailure?.let { throw it }; plaintextValue = value }
    }

    private class FakeSecrets : SecretStore {
        private val values = ConcurrentHashMap<SecretId, ByteArray>()
        val accessThreads = ConcurrentLinkedQueue<Thread>()
        var throwOnPut = false
        var throwBeforePut: Throwable? = null
        var throwAfterPut: Throwable? = null
        var getFailure: Throwable? = null
        var blockPut = false
        val putEntered = CountDownLatch(1)
        val putRelease = CountDownLatch(1)
        override fun put(id: SecretId, value: ByteArray) {
            accessThreads += Thread.currentThread()
            if (throwOnPut) error("secret write failed")
            throwBeforePut?.let { throw it }
            if (blockPut) {
                putEntered.countDown()
                putRelease.await(5, TimeUnit.SECONDS)
            }
            values[id] = value.copyOf()
            throwAfterPut?.let { throw it }
        }
        override fun get(id: SecretId): ByteArray? {
            accessThreads += Thread.currentThread()
            getFailure?.let { throw it }
            return values[id]?.copyOf()
        }
        override fun contains(id: SecretId): Boolean {
            accessThreads += Thread.currentThread()
            return values.containsKey(id)
        }
        override fun remove(id: SecretId) {
            accessThreads += Thread.currentThread()
            values.remove(id)?.fill(0)
        }
        fun text(id: SecretId) = values[id]?.toString(Charsets.UTF_8)
        fun putRaw(id: SecretId, bytes: ByteArray) { values[id] = bytes.copyOf() }
        fun authPassword(): String? {
            val encoded = values[SecretCatalog.webDavAuthRecord]?.copyOf() ?: return null
            return try {
                WebDavAuthRecordCodec.decode(encoded).use { it.passwordBytes.toString(Charsets.UTF_8) }
            } finally {
                encoded.fill(0)
            }
        }
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray,
        private val closeError: Error? = null,
    ) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            closeError?.let { throw it }
            super.close()
        }
    }

    private class CloseUnblocksBlockingInputStream(
        private val closeFailure: Throwable? = null,
    ) : InputStream() {
        private val monitor = Object()
        @Volatile private var closed = false
        val readEntered = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        var closeCount = 0
        override fun read(): Int {
            readEntered.countDown()
            try {
                synchronized(monitor) {
                    while (!closed) monitor.wait()
                }
                throw IOException("stream closed")
            } finally {
                readExited.countDown()
            }
        }
        override fun close() {
            closeCount++
            synchronized(monitor) {
                closed = true
                monitor.notifyAll()
            }
            closeFailure?.let { throw it }
        }
    }

    private class TrackingOutputStream(
        private val closeError: Error? = null,
        private val closeEntered: CountDownLatch? = null,
        private val closeRelease: CountDownLatch? = null,
    ) : OutputStream() {
        var closeCount = 0
        override fun write(value: Int) = Unit
        override fun close() {
            closeCount++
            closeEntered?.countDown()
            closeRelease?.await(5, TimeUnit.SECONDS)
            closeError?.let { throw it }
        }
    }

    private class FakeRestart(private var failFirst: Throwable? = null) : BackupRestartRequester {
        var calls = 0
        var concurrentProbe: (() -> Boolean)? = null
        var probeReturnedWithoutBlocking = false
        var probeResult: Boolean? = null
        override fun requestRestart() {
            calls++
            concurrentProbe?.let { probe ->
                val thread = Thread { probeResult = probe() }.apply { start() }
                thread.join(1_000)
                probeReturnedWithoutBlocking = !thread.isAlive
            }
            failFirst?.let { error -> failFirst = null; throw error }
        }
    }

    private class FakeOperations(
        var remote: List<RemoteBackup> = emptyList(),
        var stageFailure: Boolean = false,
        private val warning: WebDavPruneWarning? = null,
        private val testGate: CompletableDeferred<Result<Unit>>? = null,
        private val listGate: CompletableDeferred<List<RemoteBackup>>? = null,
        private val exportGate: CompletableDeferred<Unit>? = null,
        private val exportFailure: Boolean = false,
        private val exportError: Throwable? = null,
        private val uploadError: Throwable? = null,
        private val remoteStageStarted: CompletableDeferred<Unit>? = null,
        private val remoteStageGate: CompletableDeferred<Unit>? = null,
        private val localStageStarted: CompletableDeferred<Unit>? = null,
        private val localStageGate: CompletableDeferred<Unit>? = null,
        private val stageWriteThenThrow: Boolean = false,
        private val discardFailure: Throwable? = null,
        private val discardGate: CompletableDeferred<Unit>? = null,
        private val authorizeFailure: Throwable? = null,
        private val readInputUntilClosed: Boolean = false,
    ) : BackupOperations {
        var exportCalls = 0
        var stageRemoteCalls = 0
        var lastConfig: WebDavConfig? = null
        var observedPassword: CharArray? = null
        var observedConfirmation: CharArray? = null
        var lastOptions: BackupExportOptions? = null
        var testCancelled = false
        var discardCalls = 0
        var pending = false
        var restorePasswordReference: CharArray? = null
        val operationThreads = ConcurrentLinkedQueue<Thread>()

        override suspend fun export(options: BackupExportOptions): ByteArray {
            exportCalls++
            lastOptions = options
            observedPassword = options.password
            observedConfirmation = options.passwordConfirmation
            exportGate?.await()
            if (exportFailure) error("export failed")
            exportError?.let { throw it }
            return byteArrayOf(1, 2, 3)
        }
        override suspend fun upload(config: WebDavConfig, options: BackupExportOptions): BackupUploadReceipt {
            lastConfig = config
            lastOptions = options
            observedPassword = options.password
            observedConfirmation = options.passwordConfirmation
            uploadError?.let { throw it }
            return BackupUploadReceipt("new.infinity", warning)
        }
        override suspend fun testRemote(config: WebDavConfig): Result<Unit> {
            operationThreads += Thread.currentThread()
            lastConfig = config
            return try { testGate?.await() ?: Result.success(Unit) } catch (error: kotlinx.coroutines.CancellationException) {
                testCancelled = true
                throw error
            }
        }
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> {
            operationThreads += Thread.currentThread()
            lastConfig = config
            return listGate?.let { withContext(NonCancellable) { it.await() } } ?: remote
        }
        override suspend fun stageLocalRestore(
            operationId: String,
            input: java.io.InputStream,
            password: CharArray?,
        ): PendingRestoreMetadata {
            if (stageFailure) error("failure")
            pending = true
            restorePasswordReference = password
            if (readInputUntilClosed) withContext(Dispatchers.IO) { input.read() }
            if (localStageGate != null) withContext(NonCancellable) {
                localStageStarted?.complete(Unit)
                localStageGate.await()
            }
            if (stageWriteThenThrow) error("failure after durable stage")
            return metadata(operationId)
        }
        override suspend fun stageRemoteRestore(
            operationId: String,
            config: WebDavConfig,
            selected: RemoteBackup,
            password: CharArray?,
        ): PendingRestoreMetadata {
            stageRemoteCalls++
            if (selected !in remote) error("stale")
            if (remoteStageGate != null) withContext(NonCancellable) {
                remoteStageStarted?.complete(Unit)
                remoteStageGate.await()
            }
            pending = true
            return metadata(operationId)
        }
        override suspend fun discardPendingRestore(operationId: String) {
            discardCalls++
            discardGate?.await()
            discardFailure?.let { throw it }
            pending = false
        }
        override suspend fun authorizePendingRestore(operationId: String) {
            authorizeFailure?.let { throw it }
        }
        private fun metadata(operationId: String) = PendingRestoreMetadata(
            operationId,
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)),
            PendingRestorePhase.STAGING,
        )
    }
}
