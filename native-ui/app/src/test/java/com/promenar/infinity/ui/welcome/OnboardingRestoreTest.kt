package com.promenar.infinity.ui.welcome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.BackupLayer
import com.promenar.infinity.data.backup.BackupLayerStats
import com.promenar.infinity.data.backup.BackupManifest
import com.promenar.infinity.data.backup.BackupResultSummary
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.backup.PendingRestorePhase
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.ui.settings.BackupOperations
import com.promenar.infinity.ui.settings.restoreResultMissingKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class OnboardingRestoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class FakeOperations(private val txId: String = "tx") : BackupOperations {
        val discarded = mutableListOf<String>()
        var failDiscard = false

        override suspend fun export(options: BackupExportOptions): ByteArray = error("unused")
        override suspend fun upload(config: WebDavConfig, options: BackupExportOptions) = error("unused")
        override suspend fun testRemote(config: WebDavConfig): Result<Unit> = error("unused")
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> = error("unused")
        override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?) =
            PendingRestoreMetadata(txId, ByteArray(32), PendingRestorePhase.STAGING)
        override suspend fun stageRemoteRestore(operationId: String, config: WebDavConfig, selected: RemoteBackup, password: CharArray?) =
            PendingRestoreMetadata(txId, ByteArray(32), PendingRestorePhase.STAGING)
        override suspend fun discardPendingRestore(operationId: String) {
            if (failDiscard) throw java.io.IOException("discard failed")
            discarded += operationId
        }
        override suspend fun authorizePendingRestore(operationId: String) = Unit
    }

    @Test
    fun `第一个配置步骤有Linux时是Linux否则是连接提供商`() {
        assertThat(onboardingRestoreStep(linuxOffered = true)).isEqualTo(OnboardingStep.LINUX)
        assertThat(onboardingRestoreStep(linuxOffered = false)).isEqualTo(OnboardingStep.PROVIDER)
    }

    @Test
    fun `暂存成功后记下向导恢复标记且丢弃暂存后撤销`() = runTest {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("zh")).isTrue()
        val delegate = FakeOperations(txId = "tx-local")
        val operations = OnboardingRestoreOperations(delegate, store)

        operations.stageLocalRestore("op", ByteArrayInputStream(ByteArray(0)), null)
        assertThat(store.stagedRestoreTxId).isEqualTo("tx-local")

        operations.discardPendingRestore("op")
        assertThat(delegate.discarded).containsExactly("op")
        assertThat(store.stagedRestoreTxId).isNull()
    }

    @Test
    fun `丢弃暂存失败时保留标记以便暂存仍被提交时完成引导`() = runTest {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("zh")).isTrue()
        val delegate = FakeOperations(txId = "tx-kept").apply { failDiscard = true }
        val operations = OnboardingRestoreOperations(delegate, store)
        operations.stageLocalRestore("op", ByteArrayInputStream(ByteArray(0)), null)

        val failure = runCatching { operations.discardPendingRestore("op") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        assertThat(store.stagedRestoreTxId).isEqualTo("tx-kept")
    }

    @Test
    fun `标记写入被拒绝时暂存失败交由管线丢弃`() = runTest {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("zh")).isTrue()
        val operations = OnboardingRestoreOperations(FakeOperations(txId = " "), store)

        val failure = runCatching { operations.stageLocalRestore("op", ByteArrayInputStream(ByteArray(0)), null) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.stagedRestoreTxId).isNull()
    }

    @Test
    fun `加密信封只读前缀判断为已加密`() {
        val stream = byteArrayOf(78, 88, 66, 83) + ByteArray(64)
        val legacy = "NXBK".toByteArray() + ByteArray(64)

        assertThat(peekBackupPackage(ByteArrayInputStream(stream))).isEqualTo(BackupPackagePeek.Encrypted)
        assertThat(peekBackupPackage(ByteArrayInputStream(legacy))).isEqualTo(BackupPackagePeek.Encrypted)
        assertThat(BackupPackagePeek.Encrypted.needsPassword).isTrue()
        assertThat(BackupPackagePeek.Encrypted.passwordOptional).isFalse()
    }

    @Test
    fun `明文分层包读出清单里的时间版本与层`() {
        val manifest = BackupManifest(
            formatVersion = 3,
            databaseSchemaVersion = 40,
            appVersion = "0.17.0-beta",
            createdAt = 1_793_679_632_815,
            entries = emptyList(),
            encrypted = false,
            containsSecrets = false,
            layers = mapOf(BackupLayer.CORE to BackupLayerStats(1, 10), BackupLayer.SESSIONS to BackupLayerStats(2, 20)),
        )
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("preferences.json")); out.write(ByteArray(2048)); out.closeEntry()
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                out.closeEntry()
            }
        }.toByteArray()

        val peek = peekBackupPackage(ByteArrayInputStream(zip))

        assertThat(peek.encrypted).isFalse()
        assertThat(peek.needsPassword).isFalse()
        assertThat(peek.createdAt).isEqualTo(1_793_679_632_815)
        assertThat(peek.appVersion).isEqualTo("0.17.0-beta")
        assertThat(peek.layers).containsExactly(BackupLayer.CORE, BackupLayer.SESSIONS)
    }

    @Test
    fun `无法识别的内容不猜测加密状态而显示可留空的密码框`() {
        val peek = peekBackupPackage(ByteArrayInputStream("not a backup".toByteArray()))
        assertThat(peek).isEqualTo(BackupPackagePeek.Unknown)
        assertThat(peek.needsPassword).isTrue()
        assertThat(peek.passwordOptional).isTrue()
        assertThat(peekBackupPackage(ByteArrayInputStream(ByteArray(2)))).isEqualTo(BackupPackagePeek.Unknown)
    }

    @Test
    fun `分层备份缺少密钥层时结果弹窗提示重新填写`() {
        val withoutKeys = BackupResultSummary(layers = mapOf(BackupLayer.CORE to BackupLayerStats()))
        val withKeys = BackupResultSummary(layers = mapOf(BackupLayer.CORE to BackupLayerStats(), BackupLayer.SECRETS to BackupLayerStats()))

        assertThat(restoreResultMissingKeys(withoutKeys)).isTrue()
        assertThat(restoreResultMissingKeys(withKeys)).isFalse()
        // 旧版不分层的包无法判断，不提示。
        assertThat(restoreResultMissingKeys(BackupResultSummary())).isFalse()
    }
}
