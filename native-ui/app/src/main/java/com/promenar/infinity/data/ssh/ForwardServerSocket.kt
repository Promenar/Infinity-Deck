package com.promenar.infinity.data.ssh

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本地端口转发的监听套接字：close() 返回前等待在途 accept() 全部退出。
 *
 * Linux（含 Android）上阻塞中的 accept 系统调用持有监听套接字引用，JDK/ART 的 close() 只替换 fd 并唤醒线程就返回，
 * 线程被调度回来之前内核仍会为新连接完成握手；等 accept 退出后端口才确定不可连接、可立即重新绑定。
 */
internal open class DrainingServerSocket(private val drainTimeoutMillis: Long = DRAIN_TIMEOUT_MILLIS) : ServerSocket() {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var acceptsInFlight = 0

    internal val inFlight: Int get() = lock.withLock { acceptsInFlight }

    override fun accept(): Socket {
        lock.withLock { acceptsInFlight++ }
        try {
            return super.accept()
        } finally {
            onAcceptExit()
            lock.withLock { if (--acceptsInFlight == 0) drained.signalAll() }
        }
    }

    /** 测试钩子：accept 已退出系统调用、尚未登记完成。 */
    protected open fun onAcceptExit() {}

    override fun close() {
        super.close()
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis)
            // 超时只放弃等待，不抛出：停止转发不能因监听线程异常而卡死。
            while (acceptsInFlight > 0 && remaining > 0) remaining = drained.awaitNanos(remaining)
        }
    }

    private companion object {
        const val DRAIN_TIMEOUT_MILLIS = 2_000L
    }
}

internal object ForwardServerSocketFactory : com.jcraft.jsch.ServerSocketFactory {
    override fun createServerSocket(port: Int, backlog: Int, bindAddr: InetAddress?): ServerSocket =
        bind(DrainingServerSocket(), port, backlog, bindAddr)

    // 先构造未绑定实例再绑定：带地址的构造函数绑定失败时会在子类字段初始化前回调 close()。
    internal fun <T : ServerSocket> bind(socket: T, port: Int, backlog: Int, bindAddr: InetAddress?): T {
        try {
            socket.bind(InetSocketAddress(bindAddr, port), backlog)
        } catch (failure: IOException) {
            socket.close()
            throw failure
        }
        return socket
    }
}
