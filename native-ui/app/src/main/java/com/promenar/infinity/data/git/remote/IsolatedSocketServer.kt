package com.promenar.infinity.data.git.remote

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 监听只负责接入与登记。处理槽满时最多额外保留一条连接来返回忙碌状态。 */
internal class IsolatedSocketServer<C : Closeable>(
    private val threadPrefix: String,
    private val maxConnections: Int,
    private val accept: () -> C,
    private val stopAccepting: () -> Unit,
    private val handle: suspend (C) -> Unit,
    private val reject: ((C) -> Unit)? = null,
    private val closeClient: (C) -> Unit = { it.close() },
) : Closeable {
    init { require(maxConnections > 0) }

    private val lock = Any()
    private val clients = mutableSetOf<C>()
    private var rejectedClient: C? = null
    @Volatile private var closed = false
    private val nextWorker = AtomicInteger()
    private val dispatcher = Executors.newFixedThreadPool(maxConnections + if (reject == null) 0 else 1) {
        Thread(it, "$threadPrefix-connection-${nextWorker.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val listener = Thread(::acceptLoop, "$threadPrefix-accept").apply { isDaemon = true }

    fun start() = listener.start()

    private fun acceptLoop() {
        while (!closed) {
            val client = try { accept() } catch (_: IOException) { return }
            val dispatched = synchronized(lock) {
                if (closed) false else dispatchLocked(client)
            }
            if (!dispatched) runCatching { closeClient(client) }
        }
    }

    private fun dispatchLocked(client: C): Boolean {
        val busy = clients.size >= maxConnections
        if (busy) {
            if (reject == null || rejectedClient != null) return false
            rejectedClient = client
        } else {
            clients += client
        }
        scope.launch {
            try {
                client.use {
                    if (!closed) {
                        if (busy) reject?.invoke(it) else handle(it)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 协议处理失败时关闭本连接，避免把请求或系统异常原文写入日志。
            } finally {
                synchronized(lock) {
                    if (busy) rejectedClient = null else clients -= client
                }
            }
        }
        return true
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            (clients.toList() + listOfNotNull(rejectedClient)).also {
                clients.clear()
                rejectedClient = null
            }
        }
        scope.cancel()
        runCatching(stopAccepting)
        active.forEach { runCatching { closeClient(it) } }
        dispatcher.close()
    }
}
