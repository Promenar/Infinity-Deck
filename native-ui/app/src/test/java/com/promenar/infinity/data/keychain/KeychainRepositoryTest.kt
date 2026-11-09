package com.promenar.infinity.data.keychain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.jcraft.jsch.KeyPair
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.RoomSshHostStore
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshKeyGenerator
import com.promenar.infinity.domain.keychain.*
import com.promenar.infinity.domain.secret.SshSecretField
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class KeychainRepositoryTest {
    private lateinit var db: InfinityDatabase
    private lateinit var secrets: MemorySecrets
    private lateinit var hosts: RoomSshHostStore
    private lateinit var repository: DefaultKeychainRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        secrets = MemorySecrets()
        hosts = RoomSshHostStore(db.sshHostDao(), secrets, db.keychainDao())
        repository = DefaultKeychainRepository(db.keychainDao(), db.sshHostDao(), hosts, secrets, FakeGit(), flowOf(emptyList()))
    }
    @After fun close() { db.close(); secrets.clear() }

    @Test fun `增删改只在加密存储读取值目录和字符串不含值`() = runBlocking {
        val value = "fixture-value-a".toByteArray()
        val created = repository.createCustom(KeychainMetadata("示例"), value)
        assertThat(repository.entries().first().toString()).doesNotContain("fixture-value-a")
        assertThat(created.toString()).doesNotContain("fixture-value-a")
        assertThat(repository.reveal(created.ref)).isEqualTo(value)
        val updated = repository.update(created.ref, KeychainMetadata("改名"), "fixture-value-b".toByteArray())
        assertThat(updated.name).isEqualTo("改名")
        assertThat(repository.reveal(created.ref)).isEqualTo("fixture-value-b".toByteArray())
        assertThat(repository.delete(created.ref)).isEqualTo(KeychainDeleteResult.Deleted)
        assertThat(repository.reveal(created.ref)).isNull()
    }

    @Test fun `既有密钥按Git主机SSH密码提供商与服务汇总`() = runBlocking {
        val git = FakeGit()
        git.save("github.com", "fixture-user", "fixture-git".toByteArray())
        val providerId = SecretCatalog.providerApiKey("provider")
        secrets.put(providerId, "fixture-provider".toByteArray())
        secrets.put(SecretCatalog.tavilyApiKey, "fixture-tavily".toByteArray())
        val host = SshHost(alias = "password-host", hostname = "example.test", username = "alice", authType = SshAuthType.PASSWORD)
        SshCredential(password = "fixture-password".toByteArray()).use { hosts.save(host, it) }
        val repository = DefaultKeychainRepository(db.keychainDao(), db.sshHostDao(), hosts, secrets, git,
            flowOf(listOf(KeychainProvider("provider", "供应商", true, false))))
        val items = repository.entries().first()
        assertThat(items.map { it.category }).containsAtLeast(KeychainCategory.GIT, KeychainCategory.SSH_PASSWORD,
            KeychainCategory.PROVIDER, KeychainCategory.SERVICE)
        assertThat(items.toString()).doesNotContain("fixture-password")
        assertThat(repository.reveal("ssh-password:${host.id}")).isEqualTo("fixture-password".toByteArray())
        assertThat(repository.reveal("git:github.com")).isEqualTo("fixture-git".toByteArray())
        assertThat(repository.reveal("secret:${providerId.value}")).isEqualTo("fixture-provider".toByteArray())
    }

    @Test fun `网站域名严格匹配且保留显式端口`() = runBlocking {
        repository.createWebLogin(KeychainMetadata("甲", "https://例子.测试.:8443/", "alice"), "a".toByteArray())
        repository.createWebLogin(KeychainMetadata("乙", "example.com"), "b".toByteArray())
        repository.createWebLogin(KeychainMetadata("丙", "EXAMPLE.COM:443"), "c".toByteArray())
        assertThat(repository.matchWebLogin("XN--FSQU00A.XN--0ZWM56D.", 8443).map { it.name }).containsExactly("甲")
        assertThat(repository.matchWebLogin("example.com", null).map { it.name }).containsExactly("乙")
        assertThat(repository.matchWebLogin("example.com", 443).map { it.name }).containsExactly("丙")
        assertThat(repository.matchWebLogin("sub.example.com", null)).isEmpty()
    }

    @Test fun `被主机引用的私钥不能删除使用记录只保留最近两百条`() = runBlocking {
        val host = SshHost(alias = "server", hostname = "example.test", username = "alice", authType = SshAuthType.KEY)
        hosts.save(host)
        keyFixture("fixture-phrase".toByteArray()).use { fixture ->
            val key = repository.createSshKey("主密钥", fixture.bytes, fixture.phrase, KeychainOrigin.MANUAL)
            repository.assignKey(host.id, key.ref)
            assertThat(repository.delete(key.ref)).isEqualTo(KeychainDeleteResult.InUse(listOf(KeychainHostReference(host.id, host.alias))))
            repeat(205) { repository.recordUsage(key.ref, KeychainAction.SIGN, "会话", it.toLong()) }
            val history = repository.usage(key.ref).first()
            assertThat(history).hasSize(200)
            assertThat(history.first().at).isEqualTo(204)
            assertThat(history.last().at).isEqualTo(5)
            hosts.credential(hosts.find("server")!!)!!.use { credential ->
                assertThat(credential.privateKey).isEqualTo(fixture.bytes)
                assertThat(credential.passphrase).isEqualTo(fixture.phrase)
            }
            val passphrase = fixture.phrase.copyOf()
            hosts.replaceSecret(host.id, SshSecretField.KEY_PASSPHRASE, passphrase)
            assertThat(passphrase.all { it == 0.toByte() }).isTrue()
            val wrong = "incorrect".toByteArray()
            assertThrows(Exception::class.java) { runBlocking { hosts.replaceSecret(host.id, SshSecretField.KEY_PASSPHRASE, wrong) } }
            assertThat(wrong.all { it == 0.toByte() }).isTrue()
            hosts.credential(hosts.find("server")!!)!!.use { assertThat(it.passphrase).isEqualTo(fixture.phrase) }
            keyFixture("replacement-phrase".toByteArray()).use { replacement ->
                val updated = repository.update(key.ref, KeychainMetadata("替换后的密钥"), replacement.bytes, replacement.phrase)
                assertThat(updated.fingerprintSha256).isNotEqualTo(key.fingerprintSha256)
                hosts.credential(hosts.find("server")!!)!!.use {
                    assertThat(it.privateKey).isEqualTo(replacement.bytes)
                    assertThat(it.passphrase).isEqualTo(replacement.phrase)
                }
            }
        }
    }

    @Test fun `两台主机相同带口令密钥合并且失败主机不阻断`() = runBlocking {
        val first = SshHost(alias = "one", hostname = "one.test", username = "alice")
        val second = SshHost(alias = "two", hostname = "two.test", username = "alice")
        val broken = SshHost(alias = "bad", hostname = "bad.test", username = "alice")
        keyFixture("fixture-phrase".toByteArray()).use { fixture ->
            listOf(first, second).forEach { host ->
                SshCredential(fixture.bytes.copyOf(), "login-password".toByteArray(), fixture.phrase.copyOf()).use { hosts.save(host, it) }
            }
            SshCredential("bad-key".toByteArray()).use { hosts.save(broken, it) }
            val failures = mutableListOf<String>()
            SshKeyMigration(hosts, db.sshHostDao(), db.keychainDao(), repository, secrets) { _, alias, _ -> failures += alias }.migrate()
            val migratedFirst = hosts.find(first.alias)!!
            val migratedSecond = hosts.find(second.alias)!!
            assertThat(migratedFirst.keyEntryId).isNotNull()
            assertThat(migratedSecond.keyEntryId).isEqualTo(migratedFirst.keyEntryId)
            assertThat(failures).containsExactly("bad")
            assertThat(hosts.find(broken.alias)!!.keyEntryId).isNull()
            assertThat(repository.entries().first().count { it.category == KeychainCategory.SSH_KEY }).isEqualTo(1)
            hosts.credential(migratedFirst)!!.use {
                assertThat(it.privateKey).isEqualTo(fixture.bytes)
                assertThat(it.passphrase).isEqualTo(fixture.phrase)
                assertThat(it.password).isEqualTo("login-password".toByteArray())
            }
            SshKeyMigration(hosts, db.sshHostDao(), db.keychainDao(), repository, secrets) { _, _, _ -> }.migrate()
            assertThat(repository.entries().first().count { it.category == KeychainCategory.SSH_KEY }).isEqualTo(1)
        }
    }

    @Test fun `旧主机保存入口新增私钥后立即进入钥匙串`() = runBlocking {
        lateinit var migration: SshKeyMigration
        val wiredHosts = RoomSshHostStore(db.sshHostDao(), secrets, db.keychainDao()) { migration.migrateOne(it) }
        val wiredRepository = DefaultKeychainRepository(db.keychainDao(), db.sshHostDao(), wiredHosts, secrets, FakeGit(), flowOf(emptyList()))
        migration = SshKeyMigration(wiredHosts, db.sshHostDao(), db.keychainDao(), wiredRepository, secrets) { _, _, _ -> }
        val host = SshHost(alias = "new-host", hostname = "new.test", username = "alice")
        keyFixture("fixture-phrase".toByteArray()).use { fixture ->
            SshCredential(fixture.bytes.copyOf(), passphrase = fixture.phrase.copyOf()).use { wiredHosts.save(host, it) }
            val saved = wiredHosts.find(host.alias)!!
            assertThat(saved.keyEntryId).isNotNull()
            assertThat(wiredRepository.entries().first().count { it.category == KeychainCategory.SSH_KEY }).isEqualTo(1)
            wiredHosts.credential(saved)!!.use { assertThat(it.privateKey).isEqualTo(fixture.bytes) }
        }
    }

    private fun keyFixture(phrase: ByteArray): Fixture {
        val key = KeyPair.genKeyPair(SshKeyGenerator.configuredClient(), KeyPair.RSA, 2048)
        return try {
            val out = ByteArrayOutputStream()
            key.writeOpenSSHv1PrivateKey(out, phrase)
            Fixture(out.toByteArray(), phrase)
        } finally { key.dispose() }
    }
    private class Fixture(val bytes: ByteArray, val phrase: ByteArray) : AutoCloseable {
        override fun close() { bytes.fill(0); phrase.fill(0) }
    }
    private class MemorySecrets : SecretStore {
        private val values = linkedMapOf<SecretId, ByteArray>()
        private val revision = MutableStateFlow(0L)
        override val changes = revision
        override fun put(id: SecretId, value: ByteArray) { values.put(id, value.copyOf())?.fill(0); revision.value++ }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId) = id in values
        override fun remove(id: SecretId) { values.remove(id)?.fill(0); revision.value++ }
        override fun ids(): Set<SecretId> = values.keys.toSet()
        fun clear() { values.values.forEach { it.fill(0) }; values.clear() }
    }
    private class FakeGit : GitCredentialStore {
        override val credentials = MutableStateFlow<List<GitCredential>>(emptyList())
        private val tokens = mutableMapOf<String, ByteArray>()
        override fun save(host: String, username: String, token: ByteArray) {
            tokens[host] = token.copyOf()
            credentials.value = credentials.value.filterNot { it.host == host } + GitCredential(host, username)
        }
        override fun delete(host: String) { tokens.remove(host)?.fill(0); credentials.value = credentials.value.filterNot { it.host == host } }
        override fun token(host: String): ByteArray? = tokens[host]?.copyOf()
    }
}
