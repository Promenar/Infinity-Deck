package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.*
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.keychain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KeychainSshDeploymentTest {
    private val pin = SshHostKey("ssh-ed25519", "SHA256:fixturehost")

    private suspend fun host(f: K2MemoryFixture, alias: String, key: KeychainItem? = null, trusted: Boolean = true): SshHost {
        val host = SshHost(alias = alias, hostname = "$alias.test", username = "u", authType = if (key == null) SshAuthType.PASSWORD else SshAuthType.KEY)
        if (key == null) SshCredential(password = "stored fixture password".toByteArray()).use { f.hosts.save(host, it) }
        else f.hosts.saveWithKey(host, key.ref, "retained fixture password".toByteArray())
        val saved = f.hosts.find(alias)!!
        if (trusted) assertTrue(f.hosts.trust(alias, null, pin, saved))
        return f.hosts.find(alias)!!
    }

    @Test fun `三种登录凭据只在验证成功后切换且一次性密码不落盘`() = runBlocking {
        for (source in listOf("password", "key", "one-time")) {
            val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标")
            val original = host(f, "server", if (source == "key") f.repository.generateSshKey("当前") else null)
            val once = "one time fixture".toByteArray()
            val input = when (source) { "password" -> DeployCredentialSource.StoredPassword; "key" -> DeployCredentialSource.CurrentKey; else -> DeployCredentialSource.OneTimePassword(once) }
            val runtime = FakeRuntime(pin).also { it.checkVerify = { assertEquals(original, f.hosts.find(original.alias)) } }
            val progress = DefaultSshKeyDeployer(f.hosts, runtime) { _, _ -> true }
                .deployPublicKey(item.ref, listOf(DeployTarget(original.id, input))).toList()
            assertEquals(listOf(DeployStage.CONNECTING, DeployStage.WRITING_AUTHORIZED_KEYS, DeployStage.VERIFYING_KEY, DeployStage.COMPLETED), progress.map { it.stage })
            val saved = f.hosts.find(original.alias)!!
            assertEquals(SshAuthType.KEY, saved.authType); assertEquals(item.ref, saved.keyEntryId); assertEquals(pin.fingerprint, saved.trustedFingerprint)
            f.hosts.credential(saved)!!.use { assertTrue(it.password.isEmpty()) }
            assertEquals(if (source == "key") SshAuthType.KEY else SshAuthType.PASSWORD, runtime.installAuth)
            if (source == "one-time") { assertTrue(once.all { it == 0.toByte() }); assertEquals("one time fixture", runtime.installPassword) }
            assertEquals(KeychainAction.DEPLOY, f.repository.usage(item.ref).first().single().action)
            assertTrue(runtime.receivedCredentials.all { it.privateKey.all { b -> b == 0.toByte() } && it.password.all { b -> b == 0.toByte() } })
        }
    }

    @Test fun `连接写入验证与指纹拒绝均保持主机和凭据原样`() = runBlocking {
        for (failure in listOf("connect", "write", "verify", "reject")) {
            val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标")
            val original = host(f, "server", trusted = failure != "reject")
            val before = f.secrets.get(SecretCatalog.sshCredential(original.id))
            val runtime = FakeRuntime(pin).also { it.failure = failure }
            val progress = DefaultSshKeyDeployer(f.hosts, runtime) { _, _ -> failure != "reject" }
                .deployPublicKey(item.ref, listOf(DeployTarget(original.id, DeployCredentialSource.StoredPassword))).toList()
            assertEquals(DeployStage.FAILED, progress.last().stage)
            assertEquals(when (failure) { "connect" -> "CONNECTION_REFUSED"; "write" -> "KEY_INSTALL_FAILED"; "verify" -> "KEY_LOGIN_FAILED"; else -> "HOST_KEY_UNTRUSTED" }, progress.last().errorCode)
            assertEquals(original, f.hosts.find(original.alias)); assertArrayEquals(before, f.secrets.get(SecretCatalog.sshCredential(original.id)))
            assertTrue(f.repository.usage(item.ref).first().isEmpty())
            if (failure == "reject") assertFalse(runtime.installed)
        }
    }

    @Test fun `选项四种组合独立控制密钥引用和密码删除`() = runBlocking {
        for (switch in listOf(false, true)) for (delete in listOf(false, true)) {
            val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标"); val original = host(f, "server")
            val result = DefaultSshKeyDeployer(f.hosts, FakeRuntime(pin)) { _, _ -> true }.deployPublicKey(item.ref,
                listOf(DeployTarget(original.id, DeployCredentialSource.StoredPassword)), DeployOptions(switch, delete)).toList()
            assertEquals(DeployStage.COMPLETED, result.last().stage)
            val saved = f.hosts.find(original.alias)!!
            assertEquals(if (switch) SshAuthType.KEY else original.authType, saved.authType)
            assertEquals(if (switch) item.ref else original.keyEntryId, saved.keyEntryId)
            assertEquals(pin.fingerprint, saved.trustedFingerprint)
            f.hosts.credential(saved)!!.use { assertEquals(delete, it.password.isEmpty()) }
        }
    }

    @Test fun `多主机隔离并清零不存在主机的一次性密码`() = runBlocking {
        val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标")
        val first = host(f, "bad"); val second = host(f, "good")
        val missingPassword = "missing fixture".toByteArray()
        val runtime = FakeRuntime(pin).also { it.failHost = first.id }
        val result = DefaultSshKeyDeployer(f.hosts, runtime) { _, _ -> true }.deployPublicKey(item.ref, listOf(
            DeployTarget(first.id, DeployCredentialSource.StoredPassword), DeployTarget("missing", DeployCredentialSource.OneTimePassword(missingPassword)),
            DeployTarget(second.id, DeployCredentialSource.StoredPassword))).toList()
        assertEquals(listOf(DeployStage.FAILED, DeployStage.FAILED, DeployStage.COMPLETED), result.filter { it.stage in setOf(DeployStage.FAILED, DeployStage.COMPLETED) }.map { it.stage })
        assertEquals(first, f.hosts.find(first.alias)); assertEquals(item.ref, f.hosts.find(second.alias)!!.keyEntryId)
        assertTrue(missingPassword.all { it == 0.toByte() })
    }

    @Test fun `主机目录或凭据写入失败回滚并不记录成功`() = runBlocking {
        for (failure in listOf("metadata", "credential", "usage")) {
            val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标"); val original = host(f, "server")
            val before = f.secrets.get(SecretCatalog.sshCredential(original.id))
            val runtime = FakeRuntime(pin).also { it.checkVerify = {
                when (failure) {
                    "metadata" -> f.hostDao.failUpdate = true
                    "credential" -> f.secrets.failNextWrite = SecretCatalog.sshCredential(original.id)
                    "usage" -> f.keys.failUsage = true
                }
            } }
            val result = DefaultSshKeyDeployer(f.hosts, runtime) { _, _ -> true }.deployPublicKey(item.ref,
                listOf(DeployTarget(original.id, DeployCredentialSource.StoredPassword))).toList()
            assertEquals(DeployStage.FAILED, result.last().stage)
            assertEquals(original, f.hosts.find(original.alias)); assertArrayEquals(before, f.secrets.get(SecretCatalog.sshCredential(original.id)))
            assertTrue(f.repository.usage(item.ref).first().isEmpty())
        }
    }

    @Test fun `部署期间配置或凭据变化不被覆盖`() = runBlocking {
        for (change in listOf("host", "password", "key")) {
            val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标"); val original = host(f, "server")
            val runtime = FakeRuntime(pin).also { it.checkVerify = {
                when (change) {
                    "host" -> f.hosts.save(original.copy(keepTerminal = true))
                    "password" -> f.hosts.replaceSecret(original.id, com.promenar.infinity.domain.secret.SshSecretField.PASSWORD, "replacement fixture".toByteArray())
                    "key" -> f.repository.delete(item.ref)
                }
            } }
            val result = DefaultSshKeyDeployer(f.hosts, runtime) { _, _ -> true }.deployPublicKey(item.ref,
                listOf(DeployTarget(original.id, DeployCredentialSource.StoredPassword))).toList()
            assertEquals(DeployStage.FAILED, result.last().stage)
            assertNull(f.hosts.find(original.alias)!!.keyEntryId)
            if (change == "host") assertTrue(f.hosts.find(original.alias)!!.keepTerminal)
            if (change == "password") f.hosts.credential(original)!!.use { assertEquals("replacement fixture", it.password.toString(Charsets.UTF_8)) }
        }
    }

    @Test fun `取消清零所有一次性密码且流不能重复消费`() = runBlocking {
        val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标"); val original = host(f, "server")
        val first = "first fixture".toByteArray(); val next = "next fixture".toByteArray()
        val runtime = FakeRuntime(pin).also { it.checkVerify = { throw CancellationException("模拟取消") } }
        val flow = DefaultSshKeyDeployer(f.hosts, runtime) { _, _ -> true }.deployPublicKey(item.ref,
            listOf(DeployTarget(original.id, DeployCredentialSource.OneTimePassword(first)), DeployTarget("next", DeployCredentialSource.OneTimePassword(next))))
        assertTrue(runCatching { flow.toList() }.exceptionOrNull() is CancellationException)
        assertTrue(first.all { it == 0.toByte() }); assertTrue(next.all { it == 0.toByte() })
        assertTrue(runCatching { flow.toList() }.isFailure)
        assertEquals(original, f.hosts.find(original.alias))
    }

    @Test fun `上游尚未启动就取消也会清零一次性密码`() = runBlocking {
        val f = K2MemoryFixture()
        val password = "cancelled fixture".toByteArray()
        val flow = DefaultSshKeyDeployer(f.hosts, FakeRuntime(pin)) { _, _ -> true }.deployPublicKey("unused",
            listOf(DeployTarget("unused", DeployCredentialSource.OneTimePassword(password))))
        val cancelled = Job().also { it.cancel() }
        CoroutineScope(coroutineContext + cancelled).launch(start = CoroutineStart.UNDISPATCHED) { flow.toList() }.join()
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun `共享的一次性密码数组支持多目标且从未写入安全存储`() = runBlocking {
        val f = K2MemoryFixture(); val item = f.repository.generateSshKey("目标")
        val first = host(f, "first"); val second = host(f, "second")
        val password = "shared one time fixture".toByteArray()
        f.secrets.onPut = { _, value -> assertFalse(value.toString(Charsets.UTF_8).contains("shared one time fixture")) }
        val result = DefaultSshKeyDeployer(f.hosts, FakeRuntime(pin)) { _, _ -> true }.deployPublicKey(item.ref,
            listOf(first, second).map { DeployTarget(it.id, DeployCredentialSource.OneTimePassword(password)) }, DeployOptions(false, false)).toList()
        assertEquals(2, result.count { it.stage == DeployStage.COMPLETED })
        assertTrue(password.all { it == 0.toByte() })
        for (original in listOf(first, second)) f.hosts.credential(original)!!.use {
            assertEquals("stored fixture password", it.password.toString(Charsets.UTF_8))
        }
    }

    @Test fun `选择已存密码不依赖主机当前私钥值可用`() = runBlocking {
        val f = K2MemoryFixture(); val target = f.repository.generateSshKey("目标"); val current = f.repository.generateSshKey("当前")
        val original = host(f, "server", current)
        f.secrets.remove(SecretCatalog.keychain(current.ref))
        val result = DefaultSshKeyDeployer(f.hosts, FakeRuntime(pin)) { _, _ -> true }.deployPublicKey(target.ref,
            listOf(DeployTarget(original.id, DeployCredentialSource.StoredPassword))).toList()
        assertEquals(DeployStage.COMPLETED, result.last().stage)
    }

    private class FakeRuntime(val pin: SshHostKey) : SshKeyDeploymentRuntime {
        var failure: String? = null
        var failHost: String? = null
        var installed = false
        var installAuth: SshAuthType? = null
        var installPassword: String? = null
        val receivedCredentials = mutableListOf<SshCredential>()
        var checkVerify: suspend () -> Unit = {}
        override suspend fun probe(host: SshHost) = pin
        override suspend fun install(host: SshHost, credential: SshCredential, publicKey: String, connected: suspend () -> Unit) {
            receivedCredentials += credential
            if (failure == "connect" || host.id == failHost) throw SshFailure("CONNECTION_REFUSED")
            connected(); installed = true; installAuth = host.authType; installPassword = credential.password.toString(Charsets.UTF_8)
            if (failure == "write") throw SshFailure("KEY_INSTALL_FAILED")
        }
        override suspend fun verify(host: SshHost, credential: SshCredential) {
            receivedCredentials += credential
            assertEquals(SshAuthType.KEY, host.authType); assertTrue(credential.privateKey.isNotEmpty())
            checkVerify()
            if (failure == "verify") throw SshFailure("KEY_LOGIN_FAILED")
        }
    }
}
