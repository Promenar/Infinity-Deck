package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class GitHubApiProxyTest {
    @Test fun `关闭代理会取消匿名下载正在等待的上游请求`() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val stopListener = CountDownLatch(1)
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
            check(chain.call().isCanceled())
            finished.countDown()
            throw IOException()
        }.build()
        val forwarder = GitHubApiForwarder(io.mockk.mockk(), GitNonceRegistry(), client)
        val proxy = GitHubApiProxy(maxConnections = 1, listen = {
            object : GitHubProxyListener {
                private var first = true
                override fun accept(): GitHubProxyConnection {
                    if (!first) {
                        stopListener.await(3, TimeUnit.SECONDS)
                        throw IOException()
                    }
                    first = false
                    val lifetime = GitHttpConnection(Closeable {})
                    return GitHubProxyConnection(lifetime) {
                        forwarder.serve("GET /archive HTTP/1.1\r\nHost: codeload.github.com\r\n\r\n".byteInputStream(),
                            java.io.ByteArrayOutputStream(), lifetime)
                    }
                }
                override fun close() { stopListener.countDown() }
            }
        })
        try {
            proxy.start()
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            proxy.close()
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue()
        } finally {
            proxy.close()
        }
    }

    @Test fun `关闭监听时已接受但尚未登记的连接会被关闭`() {
        val entered = ArrayBlockingQueue<Thread>(1)
        val letAcceptReturn = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val handled = CountDownLatch(1)
        val proxy = GitHubApiProxy(maxConnections = 1, listen = {
            object : GitHubProxyListener {
                override fun accept(): GitHubProxyConnection {
                    entered.add(Thread.currentThread())
                    check(letAcceptReturn.await(2, TimeUnit.SECONDS))
                    return GitHubProxyConnection(Closeable { clientClosed.countDown() }) { handled.countDown() }
                }
                override fun close() { letAcceptReturn.countDown() }
            }
        })
        try {
            proxy.start()
            val acceptThread = checkNotNull(entered.poll(2, TimeUnit.SECONDS))
            assertThat(acceptThread.name).isEqualTo("infinity-github-api-accept")
            assertThat(acceptThread.isDaemon).isTrue()
            proxy.close()
            assertThat(clientClosed.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(handled.count).isEqualTo(1)
            acceptThread.join(2_000)
            assertThat(acceptThread.isAlive).isFalse()
        } finally {
            proxy.close()
        }
    }

    @Test fun `连接在专用守护线程处理且关闭唤醒阻塞中的处理`() {
        val accepted = ArrayBlockingQueue<Thread>(1)
        val listening = CountDownLatch(1)
        val closeListener = CountDownLatch(1)
        val closeClient = CountDownLatch(1)
        val handlingDone = CountDownLatch(1)
        val proxy = GitHubApiProxy(maxConnections = 1, listen = {
            object : GitHubProxyListener {
                private var first = true
                override fun accept(): GitHubProxyConnection {
                    if (!first) {
                        listening.countDown()
                        check(closeListener.await(2, TimeUnit.SECONDS))
                        throw IOException()
                    }
                    first = false
                    return GitHubProxyConnection(Closeable { closeClient.countDown() }) {
                        accepted.add(Thread.currentThread())
                        try { check(closeClient.await(2, TimeUnit.SECONDS)) } finally { handlingDone.countDown() }
                    }
                }
                override fun close() { closeListener.countDown() }
            }
        })
        try {
            proxy.start()
            val worker = checkNotNull(accepted.poll(2, TimeUnit.SECONDS))
            assertThat(worker.name.substringBefore(" @")).isEqualTo("infinity-github-api-connection-1")
            assertThat(worker.isDaemon).isTrue()
            assertThat(listening.await(2, TimeUnit.SECONDS)).isTrue()
            proxy.close()
            assertThat(handlingDone.await(2, TimeUnit.SECONDS)).isTrue()
        } finally {
            proxy.close()
        }
    }
}
