package com.promenar.infinity.data.git.remote

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.promenar.infinity.data.linux.LinuxGhConfig
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 监听端点可替换，HTTP 转发与 nonce 校验不依赖 Unix socket。 */
interface GitHubApiEndpoint : AutoCloseable {
    fun start()
}

internal interface GitHubProxyListener : Closeable {
    fun accept(): GitHubProxyConnection
}

internal class GitHubProxyConnection(
    private val socket: Closeable,
    val serve: () -> Unit,
) : Closeable by socket

/** 宿主私有目录中的 FILESYSTEM socket，由命令租约按需启停。 */
class GitHubApiProxy internal constructor(
    private val maxConnections: Int,
    private val listen: () -> GitHubProxyListener,
) : GitHubApiEndpoint {
    constructor(directory: File, forwarder: GitHubApiForwarder, maxConnections: Int = 16) :
        this(maxConnections, { AndroidGitHubProxyListener.open(directory, forwarder) })

    private var endpoint: IsolatedSocketServer<GitHubProxyConnection>? = null
    private var closed = false

    @Synchronized
    override fun start() {
        check(!closed) { "GitHub 代理已关闭" }
        if (endpoint != null) return
        val listener = listen()
        try {
            endpoint = IsolatedSocketServer("infinity-github-api", maxConnections, listener::accept, listener::close,
                handle = { it.serve() }).also { it.start() }
        } catch (_: Exception) {
            runCatching { listener.close() }
            throw IOException("GitHub 代理无法启动")
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        endpoint?.close()
        endpoint = null
    }
}

private class AndroidGitHubProxyListener(
    private val path: File,
    private val bound: LocalSocket,
    private val listener: LocalServerSocket,
    private val forwarder: GitHubApiForwarder,
) : GitHubProxyListener {
    override fun accept(): GitHubProxyConnection {
        val client = listener.accept()
        val connection = GitHttpConnection(Closeable { client.shutdownAndClose() })
        return GitHubProxyConnection(connection) {
            forwarder.serve(client.inputStream, client.outputStream, connection) { millis -> client.soTimeout = millis }
        }
    }

    override fun close() {
        // LocalServerSocket(fd) 不拥有 fd。先 shutdown 唤醒内核中的 accept，再关闭绑定套接字。
        bound.shutdownAndClose()
        runCatching { listener.close() }
        runCatching { Files.deleteIfExists(path.toPath()) }
    }

    companion object {
        fun open(directory: File, forwarder: GitHubApiForwarder): GitHubProxyListener {
            val path = LinuxGhConfig.socket(directory)
            LinuxGhConfig.requireSocketPath(path.absolutePath)
            LinuxGhConfig.prepare(directory)
            Files.deleteIfExists(path.toPath())
            val socket = LocalSocket()
            try {
                socket.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                return AndroidGitHubProxyListener(path, socket, LocalServerSocket(socket.fileDescriptor), forwarder)
            } catch (_: Exception) {
                socket.shutdownAndClose()
                Files.deleteIfExists(path.toPath())
                throw IOException("GitHub 代理无法启动")
            }
        }
    }
}

private fun LocalSocket.shutdownAndClose() {
    runCatching { shutdownInput() }
    runCatching { shutdownOutput() }
    runCatching { close() }
}
