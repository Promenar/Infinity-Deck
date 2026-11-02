package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class DrainingServerSocketTest {
    /** accept 退出后停在门闩上，模拟 Linux 上被唤醒前仍持有监听套接字的线程。 */
    private class GatedSocket(timeoutMillis: Long = 5_000) : DrainingServerSocket(timeoutMillis) {
        val exited = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun onAcceptExit() {
            exited.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `close等到在途accept退出后才返回且端口立即可重新绑定`() {
        val socket = ForwardServerSocketFactory.bind(GatedSocket(), 0, 0, LOOPBACK)
        val port = socket.localPort
        val acceptor = acceptInBackground(socket)
        awaitInFlight(socket)

        val closed = CountDownLatch(1)
        val closer = thread(isDaemon = true) { socket.close(); closed.countDown() }
        assertThat(socket.exited.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(closed.await(200, TimeUnit.MILLISECONDS)).isFalse()

        socket.release.countDown()
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
        closer.join(1_000)
        acceptor.join(1_000)
        assertThat(socket.inFlight).isEqualTo(0)
        assertCannotConnect(port)
        ServerSocket(port, 1, LOOPBACK).use { assertThat(it.localPort).isEqualTo(port) }
    }

    @Test fun `accept始终不退出时close在上限后放弃等待`() {
        val socket = ForwardServerSocketFactory.bind(GatedSocket(timeoutMillis = 100), 0, 0, LOOPBACK)
        val acceptor = acceptInBackground(socket)
        awaitInFlight(socket)
        try {
            val started = System.nanoTime()
            socket.close()
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isAtLeast(100)
            assertThat(socket.isClosed).isTrue()
        } finally {
            socket.release.countDown()
            acceptor.join(1_000)
        }
    }

    @Test fun `无在途accept时close立即返回`() {
        val socket = ForwardServerSocketFactory.createServerSocket(0, 0, LOOPBACK)
        val port = socket.localPort
        socket.close()
        assertThat(socket.isClosed).isTrue()
        assertCannotConnect(port)
    }

    @Test fun `端口被占用时抛出BindException且不留下打开的套接字`() {
        ServerSocket(0, 1, LOOPBACK).use { occupied ->
            val socket = DrainingServerSocket()
            assertThrows(BindException::class.java) {
                ForwardServerSocketFactory.bind(socket, occupied.localPort, 0, LOOPBACK)
            }
            assertThat(socket.isClosed).isTrue()
        }
    }

    private fun acceptInBackground(socket: ServerSocket) = thread(isDaemon = true) {
        try { while (true) socket.accept().close() } catch (_: IOException) {
            // 监听关闭后 accept 抛出，线程结束。
        }
    }

    private fun awaitInFlight(socket: DrainingServerSocket) {
        // 只要计数已登记，close 就必须等它退出；无需确认线程已进入系统调用。
        while (socket.inFlight == 0) Thread.onSpinWait()
    }

    private fun assertCannotConnect(port: Int) {
        val connected = Socket().use { probe ->
            try { probe.connect(InetSocketAddress(LOOPBACK, port), 500); true } catch (_: IOException) { false }
        }
        assertThat(connected).isFalse()
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
