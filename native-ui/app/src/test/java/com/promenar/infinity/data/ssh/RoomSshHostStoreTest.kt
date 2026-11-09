package com.promenar.infinity.data.ssh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.security.AndroidKeystoreSecretStore
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.domain.secret.SshSecretField
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomSshHostStoreTest {
    private lateinit var database: InfinityDatabase
    private lateinit var secrets: SecretStore
    private lateinit var store: SshHostStore
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        preferences = context.getSharedPreferences("ssh-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
        secrets = AndroidKeystoreSecretStore(preferences, key)
        store = RoomSshHostStore(database.sshHostDao(), secrets)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun `按稳定id替换单个秘密字段保留最新配置和其它凭据`() = runBlocking {
        val original = host("secret-field")
        store.save(original, SshCredential(privateKey = "fixture-private-key".toByteArray(),
            password = "fixture-old-password".toByteArray(), passphrase = "fixture-passphrase".toByteArray()))
        val latest = original.copy(alias = "secret-renamed", port = 8022, keepTerminal = true)
        store.save(latest)
        val password = "fixture-new-password".toByteArray()
        store.replaceSecret(original.id, SshSecretField.PASSWORD, password)
        assertThat(password.all { it == 0.toByte() }).isTrue()
        assertThat(store.find("secret-field")).isNull()
        assertThat(store.find("secret-renamed")).isEqualTo(latest)
        store.credential(latest)!!.use {
            assertThat(it.password).isEqualTo("fixture-new-password".toByteArray())
            assertThat(it.privateKey).isEqualTo("fixture-private-key".toByteArray())
            assertThat(it.passphrase).isEqualTo("fixture-passphrase".toByteArray())
        }
    }

    @Test
    fun `稳定id重命名保留凭据且别名冲突不能覆盖已有主机`() = runBlocking {
        val first = host("first")
        val second = host("second")
        store.save(first, SshCredential(password = "fake-password".toByteArray()))
        store.save(second)

        val renamed = first.copy(alias = "renamed")
        store.save(renamed)
        assertThat(store.find("first")).isNull()
        assertThat(store.find("renamed")?.id).isEqualTo(first.id)
        store.credential(renamed)!!.use { assertThat(it.password).isEqualTo("fake-password".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(renamed.copy(alias = "second")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(host("second")) } }
        assertThat(store.find("second")?.id).isEqualTo(second.id)
        assertThat(store.observeHosts().first().map { it.alias }).containsExactly("renamed", "second").inOrder()

        store.delete("renamed")
        assertThat(store.find("renamed")).isNull()
        assertThat(secrets.contains(SecretCatalog.sshCredential(first.id))).isFalse()
        assertThat(store.credential(renamed)).isNull()
    }

    @Test
    fun `信任只能显式CAS录入且过期替换不能覆盖新的指纹`() = runBlocking {
        val configured = host("trusted")
        store.save(configured.copy(trustedKeyType = "ssh-ed25519", trustedFingerprint = "forged"))
        assertThat(store.find("trusted")?.trustedFingerprint).isNull()
        val initial = store.find("trusted")!!
        val first = SshHostKey("ssh-ed25519", "SHA256:first")
        val next = SshHostKey("ssh-ed25519", "SHA256:next")
        assertThat(store.trust("trusted", null, first, initial)).isTrue()
        assertThat(store.trust("trusted", null, next, initial)).isFalse()
        val firstTrusted = store.find("trusted")!!
        assertThat(store.trust("trusted", "SHA256:first", next, firstTrusted)).isTrue()
        assertThat(store.trust("trusted", "SHA256:first", first, firstTrusted)).isFalse()
        assertThat(store.find("trusted")?.trustedFingerprint).isEqualTo("SHA256:next")
        assertThat(store.trust("missing", null, first, configured.copy(alias = "missing"))).isFalse()
    }

    @Test
    fun `并发首次信任恰有一个候选被采用`() = runBlocking {
        store.save(host("race"))
        val snapshot = store.find("race")!!
        val attempts = listOf("SHA256:one", "SHA256:two").map { fingerprint ->
            async { store.trust("race", null, SshHostKey("ssh-ed25519", fingerprint), snapshot) }
        }
        assertThat(attempts.map { it.await() }.count { it }).isEqualTo(1)
    }

    @Test
    fun `首次探测后同id修改地址时过期指纹不得绑定新地址`() = runBlocking {
        store.save(host("endpoint-race"))
        val probedHost = store.find("endpoint-race")!!
        store.save(probedHost.copy(hostname = "replacement.example.test"))

        assertThat(store.trust("endpoint-race", null, SshHostKey("ssh-ed25519", "SHA256:old-endpoint"), probedHost)).isFalse()
        assertThat(store.find("endpoint-race")?.hostname).isEqualTo("replacement.example.test")
        assertThat(store.find("endpoint-race")?.trustedFingerprint).isNull()
    }

    @Test
    fun `首次探测后删除并重建同别名时过期指纹不得绑定新id`() = runBlocking {
        store.save(host("recreated"))
        val probedHost = store.find("recreated")!!
        store.delete("recreated")
        val replacement = host("recreated")
        store.save(replacement)

        assertThat(store.trust("recreated", null, SshHostKey("ssh-ed25519", "SHA256:deleted-host"), probedHost)).isFalse()
        assertThat(store.find("recreated")?.id).isEqualTo(replacement.id)
        assertThat(store.find("recreated")?.trustedFingerprint).isNull()
    }

    @Test
    fun `指纹值相同时旧密钥类型快照也不能覆盖新的信任`() = runBlocking {
        store.save(host("key-type-race"))
        val initial = store.find("key-type-race")!!
        store.trust(initial.alias, null, SshHostKey("ssh-ed25519", "SHA256:shared"), initial)
        val oldType = store.find(initial.alias)!!
        store.trust(initial.alias, oldType.trustedFingerprint, SshHostKey("rsa-sha2-256", "SHA256:shared"), oldType)

        assertThat(store.trust(initial.alias, oldType.trustedFingerprint,
            SshHostKey("ssh-ed25519", "SHA256:stale"), oldType)).isFalse()
        assertThat(store.find(initial.alias)?.trustedKeyType).isEqualTo("rsa-sha2-256")
    }

    @Test
    fun `拒绝与目标别名不符的探测快照`() = runBlocking {
        val configured = host("target")
        store.save(configured)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.trust("target", null, SshHostKey("ssh-ed25519", "SHA256:key"), configured.copy(alias = "different")) }
        }
        assertThat(store.find("target")?.trustedFingerprint).isNull()
    }

    @Test
    fun `地址端口用户名变更清信任而认证切换保留信任并清凭据`() = runBlocking {
        val transforms: List<(SshHost) -> SshHost> = listOf(
            { it.copy(hostname = "other.example.test") },
            { it.copy(port = 8022) },
            { it.copy(username = "other") },
            { it.copy(authType = SshAuthType.KEY) },
        )
        transforms.forEachIndexed { index, transform ->
            val original = host("host-$index")
            store.save(original, SshCredential(password = "old-password".toByteArray()))
            store.trust(original.alias, null, SshHostKey("ssh-ed25519", "SHA256:old"), original)
            val trusted = store.find(original.alias)!!
            val displayChange = trusted.copy(keepTerminal = true)
            store.save(displayChange)
            assertThat(store.find(original.alias)?.trustedFingerprint).isEqualTo("SHA256:old")
            store.credential(displayChange)!!.close()

            val changed = transform(displayChange)
            store.save(changed)
            // 主机指纹标识服务器本身，只随地址、端口、用户名失效；认证方式不影响。
            val endpointChanged = changed.authType == original.authType
            assertThat(store.find(original.alias)?.trustedFingerprint).isEqualTo(if (endpointChanged) null else "SHA256:old")
            assertThat(store.find(original.alias)?.trustedKeyType).isEqualTo(if (endpointChanged) null else "ssh-ed25519")
            if (changed.authType == original.authType) {
                store.credential(changed)!!.use { assertThat(it.password).isEqualTo("old-password".toByteArray()) }
            } else {
                assertThat(store.credential(changed)).isNull()
            }
            assertThat(store.credential(original)).isNull()
        }
    }

    @Test
    fun `凭据仅以加密envelope保存且身份不符不能读取`() = runBlocking {
        val configured = host("encrypted")
        val password = "synthetic-password-only"
        val credential = SshCredential(password = password.toByteArray())
        store.save(configured, credential)
        assertThat(credential.password).isEqualTo(password.toByteArray())
        credential.close()
        assertThat(preferences.all.values.single().toString()).doesNotContain(password)
        assertThat(store.credential(configured.copy(hostname = "spoofed.example.test"))).isNull()
        store.credential(configured)!!.use { assertThat(it.password).isEqualTo(password.toByteArray()) }
    }

    @Test
    fun `写入失败恢复已存凭据且异常不泄露底层内容`() = runBlocking {
        var failOnce = false
        val sensitiveError = "synthetic-value-in-underlying-exception"
        val failing = object : SecretStore by secrets {
            override fun put(id: SecretId, value: ByteArray) {
                secrets.put(id, value)
                if (failOnce) { failOnce = false; throw IllegalStateException(sensitiveError) }
            }
        }
        val guarded = RoomSshHostStore(database.sshHostDao(), failing)
        val original = host("failure")
        guarded.save(original, SshCredential(password = "original".toByteArray()))
        failOnce = true
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { guarded.save(original.copy(hostname = "changed.example.test"), SshCredential(password = "replacement".toByteArray())) }
        }
        assertThat(error.stackTraceToString()).doesNotContain(sensitiveError)
        assertThat(guarded.find("failure")?.hostname).isEqualTo(original.hostname)
        guarded.credential(original)!!.use { assertThat(it.password).isEqualTo("original".toByteArray()) }
    }

    @Test
    fun `拒绝非ASCII别名控制符非法端口及尚未支持的跳板`() {
        listOf("UPPER", "主机", "a.b", "-host", "a".repeat(49), "host\n").forEach { alias ->
            assertThrows(IllegalArgumentException::class.java) { host(alias) }
        }
        listOf(0, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { host("valid").copy(port = port) }
        }
        assertThrows(IllegalArgumentException::class.java) { host("valid").copy(hostname = "bad\naddress") }
        assertThrows(IllegalArgumentException::class.java) { host("valid").copy(username = "bad\u0000user") }
        assertThrows(IllegalArgumentException::class.java) { host("valid").copy(jumpAlias = "jump") }
        assertThrows(IllegalArgumentException::class.java) { host("valid").copy(localForwardPort = 0) }
        assertThrows(IllegalArgumentException::class.java) { host("valid").copy(localForwardPort = 8080) }
        assertThrows(IllegalArgumentException::class.java) { host("valid").copy(forwardPort = 80) }
    }

    private fun host(alias: String) = SshHost(alias = alias, hostname = "example.test", username = "user", authType = SshAuthType.PASSWORD)
}
