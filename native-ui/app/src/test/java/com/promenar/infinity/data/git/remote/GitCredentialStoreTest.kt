package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import org.junit.Assert.assertThrows
import org.junit.Test

internal class MemorySecrets : SecretStore {
    val values = linkedMapOf<String, ByteArray>()
    private val listeners = mutableListOf<(SecretId) -> Unit>()
    override fun put(id: SecretId, value: ByteArray) { values[id.value] = value.copyOf(); listeners.toList().forEach { it(id) } }
    override fun get(id: SecretId): ByteArray? = values[id.value]?.copyOf()
    override fun contains(id: SecretId): Boolean = id.value in values
    override fun remove(id: SecretId) { values.remove(id.value); listeners.toList().forEach { it(id) } }
    override fun ids(): Set<SecretId> = values.keys.mapTo(linkedSetOf(), ::SecretId)
    override fun observeChanges(listener: (SecretId) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
}

class GitCredentialStoreTest {
    private val token = "nxt_SyntheticToken0123456789abcdef"

    @Test fun `主机键规范化`() {
        assertThat(GitCredentialHost.normalize("GitHub.com")).isEqualTo("github.com")
        assertThat(GitCredentialHost.normalize(" https://github.com/ ")).isEqualTo("github.com")
        assertThat(GitCredentialHost.normalize("github.com:443")).isEqualTo("github.com")
        assertThat(GitCredentialHost.normalize("git.example.com:8443")).isEqualTo("git.example.com:8443")
        listOf("", "github.com/owner", "user@github.com", "http://github.com", "bad host", "a..b", "-x.com", "x.com:0", "x.com:70000", "x.com:abc")
            .forEach { assertThat(GitCredentialHost.normalize(it)).isNull() }
        assertThat(GitCredentialHost.of("GitHub.com", -1)).isEqualTo("github.com")
        assertThat(GitCredentialHost.of("git.example.com", 8443)).isEqualTo("git.example.com:8443")
    }

    @Test fun `GitHub登录凭据记来源，401标记持久化，重新保存后清除`() {
        val secrets = MemorySecrets()
        var now = 1_000L
        val store = SecretGitCredentialStore(secrets) { now }
        store.saveGitHubLogin("octocat", token.toByteArray())
        assertThat(store.credential("github.com")).isEqualTo(GitCredential("github.com", "octocat", GitCredential.SOURCE_GITHUB_LOGIN))
        assertThat(store.credential("github.com")!!.fromGitHubLogin).isTrue()

        store.markRejected("github.com")
        now = 2_000L
        store.markRejected("github.com")
        store.markRejected("missing.example")
        // 只记第一次被拒绝的时间，未保存的主机不受影响；重新加载后标记仍在。
        assertThat(SecretGitCredentialStore(secrets).credential("github.com")!!.rejectedAtMillis).isEqualTo(1_000L)
        assertThat(store.hosts()).containsExactly("github.com")

        store.saveGitHubLogin("octocat", "gho_new".toByteArray())
        assertThat(store.credential("github.com")!!.rejectedAtMillis).isNull()
        store.markRejected("github.com")
        store.save("github.com", "octocat", "ghp_manual".toByteArray())
        assertThat(store.credential("github.com")).isEqualTo(GitCredential("github.com", "octocat"))
    }

    @Test fun `旧版主机列表没有来源字段时按手动令牌读取`() {
        val secrets = MemorySecrets()
        secrets.put(SecretGitCredentialStore.INDEX_ID, """[{"host":"github.com","username":"octocat"}]""".toByteArray())
        assertThat(SecretGitCredentialStore(secrets).credential("github.com"))
            .isEqualTo(GitCredential("github.com", "octocat", GitCredential.SOURCE_TOKEN, rejectedAtMillis = null))
    }

    @Test fun `保存列出删除且令牌只在按主机摘要命名的密钥中`() {
        val secrets = MemorySecrets()
        val store = SecretGitCredentialStore(secrets)
        store.save("https://GitHub.com/", "octocat", token.toByteArray())
        store.save("git.example.com:8443", "ci", "glpat-abc".toByteArray())

        assertThat(store.credentials.value).containsExactly(
            GitCredential("git.example.com:8443", "ci"), GitCredential("github.com", "octocat"),
        ).inOrder()
        assertThat(store.token("github.com")!!.toString(Charsets.UTF_8)).isEqualTo(token)
        assertThat(store.username("github.com")).isEqualTo("octocat")
        assertThat(store.hosts()).containsExactly("git.example.com:8443", "github.com")
        // 主机列表不含令牌；令牌键名不含主机名。
        val index = secrets.values.getValue(SecretGitCredentialStore.INDEX_ID.value).toString(Charsets.UTF_8)
        assertThat(index).doesNotContain(token)
        assertThat(secrets.values.keys.filter { it.startsWith("git_credential_token_v1:") }.none { "github" in it }).isTrue()

        // 重新加载得到相同列表。
        assertThat(SecretGitCredentialStore(secrets).credentials.value).isEqualTo(store.credentials.value)

        store.save("github.com", "other", "nxt_new".toByteArray())
        assertThat(store.username("github.com")).isEqualTo("other")
        assertThat(store.token("github.com")!!.toString(Charsets.UTF_8)).isEqualTo("nxt_new")

        store.delete("GITHUB.COM")
        assertThat(store.hosts()).containsExactly("git.example.com:8443")
        assertThat(store.token("github.com")).isNull()
        assertThat(secrets.contains(SecretGitCredentialStore.tokenId("github.com"))).isFalse()
    }

    @Test fun `输入校验且异常消息不含令牌`() {
        val store = SecretGitCredentialStore(MemorySecrets())
        val cases = listOf(
            Triple("github.com/x", "u", token) to GitCredentialInputException.Field.HOST,
            Triple("github.com", "", token) to GitCredentialInputException.Field.USERNAME,
            Triple("github.com", "a:b", token) to GitCredentialInputException.Field.USERNAME,
            Triple("github.com", "a b", token) to GitCredentialInputException.Field.USERNAME,
            Triple("github.com", "u", "") to GitCredentialInputException.Field.TOKEN,
            Triple("github.com", "u", "$token\r\nX-Injected: 1") to GitCredentialInputException.Field.TOKEN,
            Triple("github.com", "u", "with space") to GitCredentialInputException.Field.TOKEN,
            Triple("github.com", "u", "令牌") to GitCredentialInputException.Field.TOKEN,
        )
        cases.forEach { (input, field) ->
            val failure = assertThrows(GitCredentialInputException::class.java) {
                store.save(input.first, input.second, input.third.toByteArray())
            }
            assertThat(failure.field).isEqualTo(field)
            assertThat(failure.message).doesNotContain(token)
        }
        assertThat(store.credentials.value).isEmpty()
    }

    @Test fun `Git索引与令牌可随密钥层备份`() {
        assertThat(SecretCatalog.isBackupEligible(SecretGitCredentialStore.INDEX_ID, emptyList())).isTrue()
        assertThat(SecretCatalog.isBackupEligible(SecretGitCredentialStore.tokenId("github.com"), emptyList())).isTrue()
    }

    @Test fun `外部恢复索引后已初始化的Git仓库刷新主机列表`() {
        val secrets = MemorySecrets()
        val running = SecretGitCredentialStore(secrets)
        val restored = SecretGitCredentialStore(secrets)
        restored.saveGitHubLogin("octocat", token.toByteArray())
        assertThat(running.credential("github.com")?.fromGitHubLogin).isTrue()
        assertThat(running.token("github.com")).isEqualTo(token.toByteArray())
        restored.delete("github.com")
        assertThat(running.credentials.value).isEmpty()
    }

    @Test fun `延迟存储在装配时不创建底层存储，首次调用才创建且只创建一次`() {
        var created = 0
        val secrets = MemorySecrets()
        val store = LazyGitCredentialStore { created++; SecretGitCredentialStore(secrets) }
        assertThat(created).isEqualTo(0)
        assertThat(store.hosts()).isEmpty()
        store.save("github.com", "octocat", token.toByteArray())
        assertThat(store.credentials.value).containsExactly(GitCredential("github.com", "octocat"))
        assertThat(store.token("github.com")!!.toString(Charsets.UTF_8)).isEqualTo(token)
        assertThat(created).isEqualTo(1)
    }

    @Test fun `损坏的主机列表视为空`() {
        val secrets = MemorySecrets()
        secrets.put(SecretGitCredentialStore.INDEX_ID, "not json".toByteArray())
        assertThat(SecretGitCredentialStore(secrets).credentials.value).isEmpty()
    }
}
