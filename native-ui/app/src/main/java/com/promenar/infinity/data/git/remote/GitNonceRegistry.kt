package com.promenar.infinity.data.git.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 每条 shell_exec / python_exec 命令一个一次性 nonce：命令开始前签发，命令结束（含超时、取消、异常）时吊销。
 * 吊销同时关闭该 nonce 名下仍在进行的 git、GitHub API 代理与 SSH 桥连接。
 * nonce 只授权当前命令经受限代理访问远端，本身不含也不能换出令牌。
 */
class GitNonceRegistry(private val random: SecureRandom = SecureRandom()) {
    private val active = ConcurrentHashMap<String, Scope>()

    /** 一条命令的 nonce 作用域；[close] 即吊销。 */
    inner class Scope internal constructor(val nonce: String, private val digest: String) : AutoCloseable {
        private val resources = mutableSetOf<AutoCloseable>()
        private val initialHttpsCredentialHosts = AtomicReference<Set<String>?>(null)
        @Volatile var revoked = false
            private set

        /** 通用 HTTPS 路由在本次租约内沿用首次请求的主机授权状态。令牌仍在每次请求时读取。 */
        internal fun httpsCredentialHosts(readHosts: () -> Set<String>): Set<String> =
            initialHttpsCredentialHosts.get() ?: readHosts().toSet().let { hosts ->
                initialHttpsCredentialHosts.updateAndGet { it ?: hosts }!!
            }

        /** 登记随命令结束一并关闭的连接；已吊销时立即关闭并返回 false。 */
        fun register(resource: AutoCloseable): Boolean {
            synchronized(resources) {
                if (!revoked) {
                    resources += resource
                    return true
                }
            }
            runCatching { resource.close() }
            return false
        }

        fun unregister(resource: AutoCloseable) {
            synchronized(resources) { resources -= resource }
        }

        override fun close() {
            val pending = synchronized(resources) {
                if (revoked) return
                revoked = true
                resources.toList().also { resources.clear() }
            }
            active.remove(digest, this)
            pending.forEach { runCatching { it.close() } }
        }
    }

    fun issue(): Scope {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        bytes.fill(0)
        val digest = digest(nonce)
        return Scope(nonce, digest).also { active[digest] = it }
    }

    /** 按 nonce 取有效作用域；未知、格式错误或已吊销时返回 null。以摘要查表，不按前缀比较明文。 */
    fun resolve(nonce: String?): Scope? {
        if (nonce == null || nonce.length != NONCE_LENGTH || nonce.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
        val scope = active[digest(nonce)] ?: return null
        if (scope.revoked || !MessageDigest.isEqual(scope.nonce.toByteArray(), nonce.toByteArray())) return null
        return scope
    }

    val activeCount: Int get() = active.size

    companion object {
        const val NONCE_BYTES = 32
        /** 32 字节的 base64url（无填充）长度。 */
        const val NONCE_LENGTH = 43

        private fun digest(nonce: String): String = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
