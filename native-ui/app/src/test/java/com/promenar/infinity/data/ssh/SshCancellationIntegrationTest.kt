package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Test

/** 通过真实回环连接及服务端门闩验证取消时机，不访问用户主机或认证材料。 */
class SshCancellationIntegrationTest {
    @Test(timeout = 30_000)
    fun `认证后通道打开被门闩阻塞时取消立即释放连接且可重新认证`() = runBlocking {
        val opening = BlockingPoint()
        val armed = AtomicBoolean(true)
        val authenticatedAtGate = AtomicBoolean(false)
        val listener = object : ChannelListener {
            override fun channelInitialized(channel: Channel) {
                if (channel is ChannelSession && armed.compareAndSet(true, false)) {
                    authenticatedAtGate.set(channel.session.isAuthenticated)
                    opening.blockServer()
                }
            }
        }
        try {
            Harness(channelListener = listener).use { fixture ->
                fixture.start()
                fixture.trust()
                supervisorScope {
                    val pending = async { fixture.runtime.openSession("opening-owner", ALIAS) }
                    try {
                        opening.awaitReached()
                        assertThat(authenticatedAtGate.get()).isTrue()
                        assertThat(fixture.authentications.get()).isEqualTo(1)
                        assertThat(pending.isActive).isTrue()

                        // 关闭客户端不依赖服务端释放打开响应，也不等待空闲超时。
                        withTimeout(3_000) { pending.cancelAndJoin() }
                        opening.release()
                        awaitCondition { fixture.server.activeSessions.isEmpty() }
                        assertThat(fixture.runtime.hasPersistentResources).isFalse()

                        fixture.runtime.list("opening-owner", ALIAS, "/")
                        assertThat(fixture.authentications.get()).isEqualTo(2)
                        fixture.runtime.closeOwner("opening-owner")
                        awaitCondition { fixture.server.activeSessions.isEmpty() }
                    } finally {
                        finishPending(pending, opening)
                    }
                }
            }
        } finally {
            opening.release()
        }
    }

    @Test(timeout = 30_000)
    fun `SFTP第二个数据块写入时取消会删除己方临时文件且保留原文件`() = runBlocking {
        val uploading = BlockingPoint()
        val chunks = AtomicInteger()
        val temporaryName = AtomicReference<String?>()
        val listener = object : SftpEventListener {
            override fun writing(
                session: ServerSession,
                remoteHandle: String,
                localHandle: FileHandle,
                offset: Long,
                data: ByteArray,
                dataOffset: Int,
                dataLen: Int,
            ) {
                val name = localHandle.file.fileName.toString()
                if (name.startsWith("document.txt.infinity-") && name.endsWith(".tmp") && dataLen > 0 &&
                    chunks.incrementAndGet() == 2
                ) {
                    temporaryName.set(name)
                    uploading.blockServer()
                }
            }
        }
        try {
            Harness(sftpListener = listener).use { fixture ->
                fixture.start()
                fixture.trust()
                val original = fixture.files.resolve("document.txt")
                val unrelated = fixture.files.resolve("unrelated.infinity-preserve.tmp")
                Files.write(original, "原文件必须保持".toByteArray(Charsets.UTF_8))
                Files.write(unrelated, "其它上传所有者的文件".toByteArray(Charsets.UTF_8))
                supervisorScope {
                    val pending = async {
                        fixture.runtime.write("upload-owner", ALIAS, "/document.txt", "x".repeat(1_000_000))
                    }
                    try {
                        uploading.awaitReached()
                        val temporary = fixture.files.resolve(requireNotNull(temporaryName.get()))
                        assertThat(chunks.get()).isEqualTo(2)
                        assertThat(Files.exists(temporary)).isTrue()
                        assertThat(Files.size(temporary)).isGreaterThan(0L)
                        assertThat(String(Files.readAllBytes(original), Charsets.UTF_8)).isEqualTo("原文件必须保持")

                        // 门闩保持关闭，清理必须使用独立通道，不能等待原上传自然完成。
                        withTimeout(7_000) { pending.cancelAndJoin() }
                        assertThat(pending.isCancelled).isTrue()
                        assertThat(Files.exists(temporary)).isFalse()
                        assertThat(String(Files.readAllBytes(original), Charsets.UTF_8)).isEqualTo("原文件必须保持")
                        assertThat(String(Files.readAllBytes(unrelated), Charsets.UTF_8)).isEqualTo("其它上传所有者的文件")
                        assertThat(fixture.runtime.cleanupWarnings.value).isEmpty()
                    } finally {
                        finishPending(pending, uploading)
                    }
                }
                fixture.runtime.closeOwner("upload-owner")
                awaitCondition { fixture.server.activeSessions.isEmpty() }
            }
        } finally {
            uploading.release()
        }
    }

    @Test(timeout = 30_000)
    fun `本地转发只接受回环TCP且停止后状态监听及已有连接全部关闭`() = runBlocking {
        EchoServer().use { echo ->
            val localPort = availableLoopbackPort()
            Harness(localForwardPort = localPort, remoteForwardPort = echo.port).use { fixture ->
                fixture.start()
                fixture.trust()
                val bound = fixture.runtime.startForward(ALIAS)
                assertThat(bound).isEqualTo(localPort)
                assertThat(fixture.runtime.forwardStates.value).containsExactly(ALIAS, localPort)
                assertThat(fixture.runtime.hasPersistentResources).isTrue()

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(LOOPBACK, bound), 2_000)
                    socket.soTimeout = 2_000
                    assertEcho(socket, "forwarded-before-stop")
                    ownNonLoopbackAddresses().forEach { assertCannotConnect(it, bound) }

                    fixture.runtime.stopForward(ALIAS)

                    assertThat(fixture.runtime.forwardStates.value).isEmpty()
                    assertThat(fixture.runtime.hasPersistentResources).isFalse()
                    assertClosedSocket(socket)
                    assertCannotConnect(LOOPBACK, bound)
                    awaitCondition { fixture.server.activeSessions.isEmpty() && echo.activeConnections == 0 }
                }
                assertThat(fixture.persistentEvents).containsExactly(true, false).inOrder()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `停止转发后立即在同一端口重启不会遇到端口占用`() = runBlocking {
        EchoServer().use { echo ->
            val localPort = availableLoopbackPort()
            Harness(localForwardPort = localPort, remoteForwardPort = echo.port).use { fixture ->
                fixture.start()
                fixture.trust()
                repeat(10) { round ->
                    assertThat(fixture.runtime.startForward(ALIAS)).isEqualTo(localPort)
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(LOOPBACK, localPort), 2_000)
                        socket.soTimeout = 2_000
                        assertEcho(socket, "round-$round")
                        fixture.runtime.stopForward(ALIAS)
                        assertClosedSocket(socket)
                    }
                    // stopForward 返回即代表监听已释放，不需要等待内核回收。
                    assertCannotConnect(LOOPBACK, localPort)
                }
                assertThat(fixture.runtime.forwardStates.value).isEmpty()
            }
        }
    }

    private class BlockingPoint {
        private val reached = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun blockServer() {
            reached.countDown()
            try {
                if (!released.await(12, TimeUnit.SECONDS)) throw IOException("服务端测试门闩等待超时")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("服务端测试门闩已被关闭", interrupted)
            }
        }

        suspend fun awaitReached() = withContext(Dispatchers.IO) {
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        }

        fun release() { released.countDown() }
    }

    private class Harness(
        private val channelListener: ChannelListener? = null,
        private val sftpListener: SftpEventListener? = null,
        private val localForwardPort: Int? = null,
        private val remoteForwardPort: Int? = null,
    ) : AutoCloseable {
        private val root = Files.createTempDirectory("infinity-ssh-cancel")
        val files: Path = Files.createDirectory(root.resolve("files"))
        val authentications = AtomicInteger()
        val persistentEvents: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        val server = SshServer.setUpDefaultServer()
        private val hosts = MemorySshHosts()
        lateinit var runtime: SshRuntime
            private set

        fun start() {
            val subsystem = SftpSubsystemFactory.Builder().apply {
                sftpListener?.let { addSftpEventListener(it) }
            }.build()
            server.apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host.key")).apply { algorithm = "EC" }
                passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ ->
                    (password == "synthetic-password").also { if (it) authentications.incrementAndGet() }
                }
                subsystemFactories = listOf(subsystem)
                fileSystemFactory = VirtualFileSystemFactory(files)
                forwardingFilter = AcceptAllForwardingFilter.INSTANCE
                channelListener?.let { addChannelListener(it) }
                start()
            }
            hosts.host = SshHost(
                alias = ALIAS, hostname = "127.0.0.1", port = server.port,
                username = "synthetic-user", authType = SshAuthType.PASSWORD,
                localForwardPort = localForwardPort, forwardPort = remoteForwardPort,
            )
            runtime = SshRuntime(hosts, onPersistentActivity = { active -> persistentEvents.add(active) })
        }

        suspend fun trust() {
            val key = runtime.probe(ALIAS)
            hosts.host = hosts.host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
            awaitCondition { server.activeSessions.isEmpty() }
        }

        override fun close() {
            try {
                if (::runtime.isInitialized) runtime.close()
            } finally {
                try { server.stop(true) } finally { root.toFile().deleteRecursively() }
            }
        }
    }

    private class EchoServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, LOOPBACK).apply { soTimeout = 500 }
        private val running = AtomicBoolean(true)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = CopyOnWriteArrayList<Thread>()
        val port: Int get() = listener.localPort
        val activeConnections: Int get() = sockets.size
        private val acceptor = Thread({
            while (running.get()) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    break
                }
                sockets.add(socket)
                val worker = Thread({
                    try {
                        socket.use {
                            val input = it.getInputStream()
                            val output = it.getOutputStream()
                            val buffer = ByteArray(1024)
                            while (running.get()) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                output.flush()
                            }
                        }
                    } catch (_: IOException) {
                        // 端口转发停止后，对端关闭属于测试预期。
                    } finally { sockets.remove(socket) }
                }, "infinity-test-echo-client").apply { isDaemon = true }
                workers.add(worker)
                worker.start()
            }
        }, "infinity-test-echo-accept").apply { isDaemon = true; start() }

        override fun close() {
            running.set(false)
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
            workers.forEach { it.join(1_000) }
        }
    }

    private companion object {
        const val ALIAS = "cancel-test"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        suspend fun awaitCondition(condition: () -> Boolean) {
            withTimeout(3_000) { while (!condition()) delay(10) }
        }

        suspend fun finishPending(pending: Job, point: BlockingPoint) {
            point.release()
            withContext(NonCancellable) {
                pending.cancel()
                withTimeoutOrNull(3_000) { pending.join() }
            }
        }

        fun availableLoopbackPort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

        // 点对点代理地址可能回送到回环；入站暴露检查使用非点对点接口。
        fun ownNonLoopbackAddresses(): List<InetAddress> = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }

        fun assertEcho(socket: Socket, text: String) {
            val expected = text.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(expected)
            socket.getOutputStream().flush()
            assertThat(socket.getInputStream().readNBytes(expected.size)).isEqualTo(expected)
        }

        fun assertCannotConnect(address: InetAddress, port: Int) {
            val connected = Socket().use { socket ->
                try { socket.connect(InetSocketAddress(address, port), 500); true } catch (_: IOException) { false }
            }
            assertThat(connected).isFalse()
        }

        fun assertClosedSocket(socket: Socket) {
            val closed = try {
                socket.getInputStream().read() == -1
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: IOException) {
                true
            }
            assertThat(closed).isTrue()
        }
    }
}
