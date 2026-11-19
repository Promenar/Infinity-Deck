package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class LoopbackServerTest {
    @Test fun `监听空闲时无处理线程且关闭会唤醒接收和已接入的读取`() {
        val started = ArrayBlockingQueue<Pair<String, Boolean>>(1)
        val stopped = CountDownLatch(1)
        val server = object : LoopbackServer(1) {
            override suspend fun handle(socket: Socket) {
                val thread = Thread.currentThread()
                started.add(thread.name.substringBefore(" @") to thread.isDaemon)
                try { socket.getInputStream().read() } finally { stopped.countDown() }
            }
        }
        val port = server.start()
        try {
            val accept = Thread.getAllStackTraces().keys.single { it.name == "infinity-loopback-accept" }
            assertThat(accept.isDaemon).isTrue()
            assertThat(started.isEmpty()).isTrue()
            Socket(LOOPBACK, port).use {
                assertThat(started.poll(2, TimeUnit.SECONDS)).isEqualTo("infinity-loopback-connection-1" to true)
                server.close()
                assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue()
            }
            accept.join(2_000)
            assertThat(accept.isAlive).isFalse()
        } finally {
            server.close()
        }
    }
}
