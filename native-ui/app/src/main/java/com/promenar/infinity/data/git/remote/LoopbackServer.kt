package com.promenar.infinity.data.git.remote

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** 只绑定 127.0.0.1 的 IPv4 回环地址，不用 `getLoopbackAddress()`（可能解析为 ::1）。 */
internal val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/** 回环 TCP 服务使用独立的监听与连接线程，由所有者负责关闭。 */
internal abstract class LoopbackServer(private val maxConnections: Int) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    private var endpoint: IsolatedSocketServer<Socket>? = null
    private var closed = false

    /** 已监听的端口；未启动时为 null。 */
    val port: Int? get() = server?.localPort
    val boundAddress: InetAddress? get() = server?.inetAddress

    @Synchronized
    fun start(): Int {
        check(!closed) { "回环服务已关闭" }
        server?.let { return it.localPort }
        val socket = ServerSocket(0, BACKLOG, LOOPBACK)
        try {
            endpoint = IsolatedSocketServer("infinity-loopback", maxConnections, socket::accept, socket::close,
                handle = ::handle, reject = ::rejectBusy, closeClient = ::closeClient).also { it.start() }
            server = socket
            return socket.localPort
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    protected abstract suspend fun handle(socket: Socket)
    protected open fun rejectBusy(socket: Socket) = Unit
    protected open fun closeClient(socket: Socket) = socket.close()

    @Synchronized
    override fun close() {
        closed = true
        endpoint?.close()
        endpoint = null
        server = null
    }

    private companion object {
        const val BACKLOG = 16
    }
}

/** 逐字节读取一行（以 LF 结尾，去掉 CR）；超过 [limit] 字节或流提前结束返回 null。 */
internal fun InputStream.readAsciiLine(limit: Int): String? {
    val buffer = java.io.ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0) return null
        if (value == '\n'.code) break
        if (buffer.size() >= limit) return null
        buffer.write(value)
    }
    val bytes = buffer.toByteArray()
    val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
    return String(bytes, 0, end, Charsets.UTF_8)
}
