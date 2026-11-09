package com.promenar.infinity.data.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@JvmInline
value class SecretId(val value: String)

interface SecretStore {
    fun put(id: SecretId, value: ByteArray)
    fun get(id: SecretId): ByteArray?
    fun contains(id: SecretId): Boolean
    fun remove(id: SecretId)

    /** 返回标识，不读取或解密值。不能枚举时必须显式失败。 */
    fun ids(): Set<SecretId> = throw UnsupportedOperationException("密钥存储不支持枚举")
    val changes: Flow<Long> get() = flowOf(0L)
    fun observeChanges(listener: (SecretId) -> Unit): AutoCloseable = AutoCloseable { }
}
