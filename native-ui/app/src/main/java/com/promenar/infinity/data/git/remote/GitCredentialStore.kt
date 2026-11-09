package com.promenar.infinity.data.git.remote

import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.SecretMutationRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Git 凭据的主机键：小写主机名，非 443 端口时为 `主机:端口`。
 * 用户可输入 `https://github.com/`、`GitHub.com` 等写法，统一规范化为同一个键。
 */
object GitCredentialHost {
    private val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** 规范化后的主机键；格式无效时返回 null。 */
    fun normalize(input: String): String? {
        var value = input.trim().lowercase()
        if (value.startsWith("https://")) value = value.removePrefix("https://")
        value = value.trimEnd('/')
        if (value.isEmpty() || value.length > 260 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        if ('/' in value || '@' in value || '?' in value || '#' in value) return null
        val host = value.substringBefore(':')
        val port = if (':' in value) value.substringAfter(':').toIntOrNull() ?: return null else 443
        if (port !in 1..65535) return null
        if (host.length > 253 || host.split('.').any { !label.matches(it) }) return null
        return if (port == 443) host else "$host:$port"
    }

    /** 由 URL 的主机与端口得到主机键（端口为 -1 或 443 时省略）。 */
    fun of(host: String, port: Int): String? = normalize(if (port == -1 || port == 443) host else "$host:$port")
}

/**
 * 保存的 Git HTTPS 凭据元数据；令牌不在此对象中。
 * [source] 区分手动令牌与 GitHub 登录，[rejectedAtMillis] 是远端最近一次以 401 拒绝该凭据的时间，重新保存后清除。
 */
@Serializable
data class GitCredential(
    val host: String,
    val username: String,
    val source: String = SOURCE_TOKEN,
    val rejectedAtMillis: Long? = null,
) {
    val fromGitHubLogin: Boolean get() = source == SOURCE_GITHUB_LOGIN

    companion object {
        const val SOURCE_TOKEN = "token"
        const val SOURCE_GITHUB_LOGIN = "github_device"
    }
}

/** Git 凭据输入校验失败；消息只描述字段，不含输入内容。 */
class GitCredentialInputException(val field: Field) : IllegalArgumentException("Git 凭据字段无效：$field") {
    enum class Field { HOST, USERNAME, TOKEN }
}

interface GitCredentialStore {
    /** 已保存的主机与用户名（不含令牌），按主机排序。 */
    val credentials: StateFlow<List<GitCredential>>

    /** 保存或覆盖主机的凭据；[token] 由调用方在返回后清零。 */
    fun save(host: String, username: String, token: ByteArray)
    fun delete(host: String)

    /** 保存 GitHub 设备码登录得到的 github.com 凭据，并标记来源，失效时可引导重新登录。 */
    fun saveGitHubLogin(username: String, token: ByteArray) = save(GITHUB_HOST, username, token)

    /** 远端以 401 拒绝该主机的凭据时调用；重新保存凭据前一直保留标记。 */
    fun markRejected(host: String) {}

    fun credential(host: String): GitCredential? = credentials.value.firstOrNull { it.host == host }

    /** 只供凭据注入代理读取；调用方用完立即清零返回的数组。 */
    fun token(host: String): ByteArray?
    fun username(host: String): String? = credentials.value.firstOrNull { it.host == host }?.username
    fun hosts(): Set<String> = credentials.value.mapTo(linkedSetOf()) { it.host }

    companion object {
        const val GITHUB_HOST = "github.com"
    }
}

/**
 * 首次使用时才创建底层存储：[SecretGitCredentialStore] 构造时要解密 Keystore 中的主机列表，
 * 而代理与执行后端可能在主线程装配；实际调用都发生在 IO 线程。
 */
class LazyGitCredentialStore(provider: () -> GitCredentialStore) : GitCredentialStore {
    private val store by lazy(provider)
    override val credentials: StateFlow<List<GitCredential>> get() = store.credentials
    override fun save(host: String, username: String, token: ByteArray) = store.save(host, username, token)
    override fun delete(host: String) = store.delete(host)
    override fun saveGitHubLogin(username: String, token: ByteArray) = store.saveGitHubLogin(username, token)
    override fun markRejected(host: String) = store.markRejected(host)
    override fun token(host: String): ByteArray? = store.token(host)
    override fun username(host: String): String? = store.username(host)
    override fun hosts(): Set<String> = store.hosts()
}

/**
 * 主机列表与令牌都存于 [SecretStore]（Android Keystore 加密）：
 * 用户选择密钥备份时，索引与令牌随应用备份一并保存。
 */
class SecretGitCredentialStore(
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : GitCredentialStore {
    private val state = MutableStateFlow(load())
    override val credentials: StateFlow<List<GitCredential>> = state.asStateFlow()
    private val secretChanges = secrets.observeChanges { id ->
        if (id == INDEX_ID) synchronized(this) { state.value = load() }
    }

    override fun save(host: String, username: String, token: ByteArray) = store(host, username, token, GitCredential.SOURCE_TOKEN)

    override fun saveGitHubLogin(username: String, token: ByteArray) =
        store(GitCredentialStore.GITHUB_HOST, username, token, GitCredential.SOURCE_GITHUB_LOGIN)

    @Synchronized
    override fun markRejected(host: String) {
        val current = state.value.firstOrNull { it.host == host } ?: return
        if (current.rejectedAtMillis != null) return
        persist(state.value.map { if (it.host == host) it.copy(rejectedAtMillis = clock()) else it })
    }

    @Synchronized
    private fun store(host: String, username: String, token: ByteArray, source: String) = SecretMutationRevision.blocking {
        val key = GitCredentialHost.normalize(host) ?: throw GitCredentialInputException(GitCredentialInputException.Field.HOST)
        val user = username.trim()
        if (user.isEmpty() || user.length > 256 || ':' in user || user.any { it.isISOControl() || it.isWhitespace() }) {
            throw GitCredentialInputException(GitCredentialInputException.Field.USERNAME)
        }
        if (!validToken(token)) throw GitCredentialInputException(GitCredentialInputException.Field.TOKEN)
        secrets.put(tokenId(key), token)
        val next = (state.value.filterNot { it.host == key } + GitCredential(key, user, source)).sortedBy { it.host }
        persist(next)
    }

    @Synchronized
    override fun delete(host: String) = SecretMutationRevision.blocking {
        val key = GitCredentialHost.normalize(host) ?: return@blocking
        val next = state.value.filterNot { it.host == key }
        persist(next)
        secrets.remove(tokenId(key))
    }

    override fun token(host: String): ByteArray? {
        if (state.value.none { it.host == host }) return null
        return secrets.get(tokenId(host))
    }

    private fun persist(list: List<GitCredential>) {
        val encoded = json.encodeToString(ListSerializer(GitCredential.serializer()), list).toByteArray(Charsets.UTF_8)
        try { secrets.put(INDEX_ID, encoded) } finally { encoded.fill(0) }
        state.value = list
    }

    private fun load(): List<GitCredential> {
        val bytes = runCatching { secrets.get(INDEX_ID) }.getOrNull() ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(GitCredential.serializer()), bytes.toString(Charsets.UTF_8))
                .filter { GitCredentialHost.normalize(it.host) == it.host }
                .sortedBy { it.host }
        } catch (_: Exception) {
            emptyList()
        } finally { bytes.fill(0) }
    }

    companion object {
        /** 主机列表（JSON，不含令牌）。 */
        val INDEX_ID = SecretId("git_credential_index_v1")

        /** 令牌按主机键的 SHA-256 命名，SharedPreferences 的键里不出现主机名。 */
        fun tokenId(host: String): SecretId = SecretId("git_credential_token_v1:" + sha256(host))

        /** 令牌只允许可见 ASCII（含 GitHub/GitLab 等常见令牌字符），拒绝空白与控制字符，避免请求头注入。 */
        fun validToken(token: ByteArray): Boolean =
            token.isNotEmpty() && token.size <= 4096 && token.all { it in 0x21..0x7e }

        private val json = Json { ignoreUnknownKeys = true }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
