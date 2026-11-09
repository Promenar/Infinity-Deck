package com.promenar.infinity.ui.keychain

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostKey
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.domain.keychain.DeployCredentialSource
import com.promenar.infinity.domain.keychain.DeployOptions
import com.promenar.infinity.domain.keychain.DeployProgress
import com.promenar.infinity.domain.keychain.DeployStage
import com.promenar.infinity.domain.keychain.DeployTarget
import com.promenar.infinity.domain.keychain.ImportSshKeyResult
import com.promenar.infinity.domain.keychain.KeychainAction
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainDeleteResult
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainMetadata
import com.promenar.infinity.domain.keychain.KeychainOrigin
import com.promenar.infinity.domain.keychain.KeychainRepository
import com.promenar.infinity.domain.keychain.KeychainUsage
import com.promenar.infinity.domain.keychain.SshKeyDeployment
import com.promenar.infinity.domain.keychain.SshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeychainSshViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = SshFakeRepository()
    private val hosts = SshFakeHostStore()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(deployment: SshKeyDeployment = SshFakeDeployment(emptyFlow())) = KeychainSshViewModel(repository, hosts) { deployment }

    @Test fun `生成密钥把口令转成字节交给仓库并清零输入`() = runTest(dispatcher) {
        val phrase = "口令 pass".toCharArray()
        val result = model().generate(" web 集群 ", SshKeyType.RSA_4096, phrase)

        assertThat(result).isInstanceOf(KeychainSshResult.Ok::class.java)
        assertThat(phrase.all { it == '\u0000' }).isTrue()
        assertThat(repository.generated).containsExactly(Triple("web 集群", SshKeyType.RSA_4096, "口令 pass"))
        assertThat(repository.passedPhrases.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
    }

    @Test fun `空口令生成时不传口令`() = runTest(dispatcher) {
        model().generate("k", SshKeyType.ED25519, CharArray(0))
        assertThat(repository.generated.single().third).isNull()
    }

    @Test fun `导入后清零私钥字节并返回仓库结果`() = runTest(dispatcher) {
        repository.importResult = ImportSshKeyResult.PassphraseRequired
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()
        val result = model().import("工作", key, null, KeychainOrigin.CLIPBOARD_IMPORT)

        assertThat(result).isEqualTo(KeychainSshResult.Ok(ImportSshKeyResult.PassphraseRequired))
        assertThat(key.all { it == 0.toByte() }).isTrue()
        assertThat(repository.importOrigins).containsExactly(KeychainOrigin.CLIPBOARD_IMPORT)
    }

    @Test fun `导出交给写入回调后清零私钥字节`() = runTest(dispatcher) {
        var written: String? = null
        val result = model().export("kc-1", "newpass88".toCharArray()) { written = String(it, Charsets.US_ASCII) }

        assertThat(result).isEqualTo(KeychainSshResult.Ok(Unit))
        assertThat(written).isEqualTo("PRIVATE")
        assertThat(repository.exported.single().all { it == 0.toByte() }).isTrue()
        assertThat(repository.exportPhrase).isEqualTo("newpass88")
    }

    @Test fun `操作失败返回 SSH 错误码或写入失败`() = runTest(dispatcher) {
        repository.failure = SshFailure("EXPORT_PASSPHRASE_REQUIRED")
        assertThat(model().export("kc-1", null) {}).isEqualTo(KeychainSshResult.Failed("EXPORT_PASSPHRASE_REQUIRED"))
        repository.failure = IllegalStateException("x")
        assertThat(model().changePassphrase("kc-1", "a".toCharArray())).isEqualTo(KeychainSshResult.Failed("KEYCHAIN_WRITE_FAILED"))
    }

    @Test fun `重命名保留其余元数据`() = runTest(dispatcher) {
        val item = KeychainItem("kc-1", KeychainCategory.SSH_KEY, "旧名", note = "备注", agentEnabled = false)
        model().rename(item, " 新名 ")
        assertThat(repository.updates.single()).isEqualTo("kc-1" to KeychainMetadata("新名", null, null, "备注", false))
    }

    @Test fun `部署按进度更新各主机状态并转交清零一次性密码`() = runTest(dispatcher) {
        val progress = Channel<DeployProgress>(Channel.UNLIMITED)
        val deployment = SshFakeDeployment(progress.receiveAsFlow())
        val model = model(deployment)
        val db = SshHost(id = "h-db", alias = "db", hostname = "db.test", username = "root", authType = SshAuthType.PASSWORD)
        val web = SshHost(id = "h-web", alias = "web", hostname = "web.test", username = "root", authType = SshAuthType.PASSWORD)
        val typed = "一次性".toCharArray()

        model.startDeploy("kc-1", linkedMapOf(db to SshDeployLogin.StoredPassword, web to SshDeployLogin.OneTimePassword(typed)), DeployOptions(deleteStoredPassword = false))

        assertThat(typed.all { it == '\u0000' }).isTrue()
        assertThat(deployment.receivedPasswords).containsExactly("一次性")
        assertThat(deployment.targets.map { it.credentialSource::class }).containsExactly(
            DeployCredentialSource.StoredPassword::class, DeployCredentialSource.OneTimePassword::class,
        ).inOrder()
        assertThat(model.deploy.value!!.hosts.map { it.stage }).containsExactly(null, null)

        progress.send(DeployProgress("h-db", DeployStage.COMPLETED))
        progress.send(DeployProgress("h-web", DeployStage.FAILED, "KEY_LOGIN_FAILED"))
        progress.close()

        val run = model.deploy.value!!
        assertThat(run.finished).isTrue()
        assertThat(run.succeeded.map { it.alias }).containsExactly("db")
        assertThat(run.hosts.last().errorCode).isEqualTo("KEY_LOGIN_FAILED")
        assertThat(run.options.deleteStoredPassword).isFalse()

        model.dismissDeploy()
        assertThat(model.deploy.value).isNull()
    }

    @Test fun `部署流抛错时未结束的主机标记失败`() = runTest(dispatcher) {
        val model = model(SshFakeDeployment(flow { emit(DeployProgress("h-1", DeployStage.CONNECTING)); throw SshFailure("TIMEOUT") }))
        val host = SshHost(id = "h-1", alias = "a", hostname = "a.test", username = "u")
        model.startDeploy("kc-1", mapOf(host to SshDeployLogin.CurrentKey), DeployOptions())
        val state = model.deploy.value!!.hosts.single()
        assertThat(state.stage).isEqualTo(DeployStage.FAILED)
        assertThat(state.errorCode).isEqualTo("TIMEOUT")
    }

    @Test fun `进行中的部署忽略新请求并清零其一次性密码`() = runTest(dispatcher) {
        val progress = Channel<DeployProgress>()
        val model = model(SshFakeDeployment(progress.receiveAsFlow()))
        val host = SshHost(id = "h-1", alias = "a", hostname = "a.test", username = "u")
        model.startDeploy("kc-1", mapOf(host to SshDeployLogin.CurrentKey), DeployOptions())
        val second = "pw".toCharArray()
        model.startDeploy("kc-2", mapOf(host to SshDeployLogin.OneTimePassword(second)), DeployOptions())

        assertThat(second.all { it == '\u0000' }).isTrue()
        assertThat(model.deploy.value!!.entryRef).isEqualTo("kc-1")
        model.dismissDeploy()
        assertThat(model.deploy.value).isNotNull()
        progress.close()
    }

    @Test fun `部署候选排除已用这把密钥的主机并区分登录方式`() {
        val hostsList = listOf(
            SshHost(id = "using", alias = "using", hostname = "h", username = "u", keyEntryId = "kc-1"),
            SshHost(id = "other-key", alias = "other", hostname = "h", username = "u", keyEntryId = "kc-2"),
            SshHost(id = "saved", alias = "saved", hostname = "h", username = "u", authType = SshAuthType.PASSWORD),
            SshHost(id = "prompt", alias = "prompt", hostname = "h", username = "u", authType = SshAuthType.PASSWORD),
        )
        val items = listOf(
            KeychainItem("kc-2", KeychainCategory.SSH_KEY, "旧密钥"),
            KeychainItem("ssh-password:saved", KeychainCategory.SSH_PASSWORD, "saved"),
        )
        val candidates = sshDeployCandidates("kc-1", hostsList, items)

        assertThat(candidates.map { it.host.alias to it.kind }).containsExactly(
            "other" to SshDeployLoginKind.CURRENT_KEY,
            "saved" to SshDeployLoginKind.STORED_PASSWORD,
            "prompt" to SshDeployLoginKind.INPUT_PASSWORD,
        ).inOrder()
        assertThat(candidates.first().currentKeyName).isEqualTo("旧密钥")
        assertThat(SshDeployForm(selected = setOf("prompt")).canRun(candidates)).isFalse()
        assertThat(SshDeployForm(selected = setOf("prompt"), passwords = mapOf("prompt" to "x")).canRun(candidates)).isTrue()
        assertThat(SshDeployForm().canRun(candidates)).isFalse()
    }

    @Test fun `导出表单无口令时必须设置至少 8 位且两次一致`() {
        val withPhrase = KeychainItem("kc", KeychainCategory.SSH_KEY, "k", hasPassphrase = true)
        val without = withPhrase.copy(hasPassphrase = false)
        assertThat(SshKeyExportForm().canExport(withPhrase)).isTrue()
        assertThat(SshKeyExportForm().canExport(without)).isFalse()
        assertThat(SshKeyExportForm(passphrase = "1234567", repeat = "1234567").canExport(without)).isFalse()
        assertThat(SshKeyExportForm(passphrase = "12345678", repeat = "12345679").canExport(without)).isFalse()
        assertThat(SshKeyExportForm(passphrase = "12345678", repeat = "12345678").canExport(without)).isTrue()
        assertThat(SshKeyExportForm(mode = SshExportMode.NEW).canExport(withPhrase)).isFalse()
    }

    @Test fun `导出文件名与类型标签`() {
        val item = KeychainItem("kc", KeychainCategory.SSH_KEY, "NAS 部署/密钥", keyType = "ssh-ed25519")
        assertThat(exportFileName(item)).isEqualTo("NAS-部署-密钥_ed25519")
        assertThat(exportFileName(item.copy(keyType = "ecdsa-sha2-nistp256"))).isEqualTo("NAS-部署-密钥_nistp256")
        assertThat(keychainKeyTypeLabel("ssh-rsa")).isEqualTo("RSA")
        assertThat(keychainKeyTypeLabel("ED25519")).isEqualTo("ED25519")
    }

    @Test fun `使用密钥的主机只算密钥认证且引用相同`() {
        val list = listOf(
            SshHost(id = "1", alias = "a", hostname = "h", username = "u", keyEntryId = "kc"),
            SshHost(id = "2", alias = "b", hostname = "h", username = "u", authType = SshAuthType.PASSWORD, keyEntryId = "kc"),
            SshHost(id = "3", alias = "c", hostname = "h", username = "u", keyEntryId = "other"),
        )
        assertThat(hostsUsingKey(list, "kc").map { it.alias }).containsExactly("a")
    }
}

private class SshFakeDeployment(private val progress: Flow<DeployProgress>) : SshKeyDeployment {
    val targets = mutableListOf<DeployTarget>()
    val receivedPasswords = mutableListOf<String>()
    override fun deployPublicKey(entryId: String, targets: List<DeployTarget>, options: DeployOptions): Flow<DeployProgress> {
        this.targets += targets
        targets.forEach { target ->
            (target.credentialSource as? DeployCredentialSource.OneTimePassword)?.let { receivedPasswords += String(it.bytes, Charsets.UTF_8) }
        }
        return progress
    }
}

private class SshFakeHostStore : SshHostStore {
    val hosts = MutableStateFlow<List<SshHost>>(emptyList())
    override fun observeHosts(): Flow<List<SshHost>> = hosts
    override suspend fun find(alias: String): SshHost? = hosts.value.firstOrNull { it.alias == alias }
    override suspend fun save(host: SshHost, credential: SshCredential?) = Unit
    override suspend fun delete(alias: String) = Unit
    override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost): Boolean = true
    override fun credential(host: SshHost): SshCredential? = null
}

private class SshFakeRepository : KeychainRepository {
    var failure: Exception? = null
    var importResult: ImportSshKeyResult = ImportSshKeyResult.UnsupportedKeyType
    val generated = mutableListOf<Triple<String, SshKeyType, String?>>()
    val passedPhrases = mutableListOf<ByteArray>()
    val importOrigins = mutableListOf<KeychainOrigin>()
    val exported = mutableListOf<ByteArray>()
    var exportPhrase: String? = null
    val updates = mutableListOf<Pair<String, KeychainMetadata>>()

    private fun fail() { failure?.let { throw it } }
    private fun key(name: String) = KeychainItem("kc-new", KeychainCategory.SSH_KEY, name)

    override suspend fun generateSshKey(name: String, type: SshKeyType, passphrase: ByteArray?): KeychainItem {
        fail()
        generated += Triple(name, type, passphrase?.toString(Charsets.UTF_8))
        passphrase?.let { passedPhrases += it }
        return key(name)
    }
    override suspend fun importSshKey(name: String, privateKey: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin): ImportSshKeyResult {
        fail()
        importOrigins += origin
        return importResult
    }
    override suspend fun exportOpenSsh(entryId: String, exportPassphrase: ByteArray?): ByteArray {
        fail()
        exportPhrase = exportPassphrase?.toString(Charsets.UTF_8)
        return "PRIVATE".toByteArray().also { exported += it }
    }
    override suspend fun changePassphrase(entryId: String, newPassphrase: ByteArray?): KeychainItem { fail(); return key("k") }
    override suspend fun update(ref: String, metadata: KeychainMetadata, value: ByteArray?, passphrase: ByteArray?): KeychainItem {
        fail()
        updates += ref to metadata
        return key(metadata.name)
    }

    override fun entries(): Flow<List<KeychainItem>> = MutableStateFlow(emptyList())
    override suspend fun reveal(ref: String): ByteArray? = null
    override suspend fun recordUsage(entryRef: String, action: KeychainAction, context: String?, at: Long) = Unit
    override fun usage(entryRef: String): Flow<List<KeychainUsage>> = MutableStateFlow(emptyList())
    override suspend fun createCustom(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?) = error("unused")
    override suspend fun createWebLogin(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?) = error("unused")
    override suspend fun createSshKey(name: String, privateKeyPem: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin, sessionId: String?) =
        error("unused")
    override suspend fun delete(ref: String): KeychainDeleteResult = KeychainDeleteResult.NotFound
    override suspend fun matchWebLogin(host: String, port: Int?): List<KeychainItem> = emptyList()
    override suspend fun assignKey(hostId: String, entryId: String) = Unit
    override suspend fun saveGit(host: String, username: String, token: ByteArray) = Unit
    override suspend fun saveGitHubLogin(username: String, token: ByteArray) = Unit
    override suspend fun deleteGit(host: String) = Unit
}
