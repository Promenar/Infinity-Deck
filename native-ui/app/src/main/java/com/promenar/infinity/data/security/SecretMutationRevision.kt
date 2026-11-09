package com.promenar.infinity.data.security

/** 密钥及其 Room 元数据跨存储写入时，令备份检测进行中的修改并重试。 */
internal object SecretMutationRevision {
    private val lock = Any()
    private var sequence = 0L
    private var active = 0

    fun current(): Long = synchronized(lock) { (sequence shl 1) or if (active > 0) 1L else 0L }

    private fun begin() = synchronized(lock) { active++; sequence++ }
    private fun end() = synchronized(lock) { active--; sequence++ }

    fun start(): AutoCloseable {
        begin()
        return AutoCloseable { end() }
    }

    fun <T> blocking(block: () -> T): T {
        begin()
        return try { block() } finally { end() }
    }

    suspend fun <T> suspending(block: suspend () -> T): T {
        begin()
        return try { block() } finally { end() }
    }
}
