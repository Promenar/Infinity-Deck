package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TailnetClientTest {
    private val options = TailnetStartOptions(
        stateDir = "/data/tailnet",
        hostname = "infinity-test",
        authKey = "tskey-auth-SECRETVALUE",
        proxyUser = "U".repeat(24),
        proxyPassword = "P".repeat(24),
    )

    @Test
    fun `请求按 id 配对应答并转发状态事件`(): Unit = runBlocking {
        val child = FakeTailnetChild(port = 40100)
        val states = CopyOnWriteArrayList<TailnetStatusSummary>()
        val client = TailnetClient(child, onState = { states += it }, onExit = {})
        assertThat(client.start(options).proxyPort).isEqualTo(40100)
        assertThat(client.status().backendState).isEqualTo("NeedsLogin")
        child.goOnline()
        assertThat(client.status().running).isTrue()
        assertThat(client.peers().map { it.name }).containsExactly("nas", "laptop").inOrder()
        val failure = runCatching { client.call("bogus") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(TailnetCommandException::class.java)
        assertThat((failure as TailnetCommandException).code).isEqualTo(TailnetErrors.UNSUPPORTED)
        assertThat(states.map { it.backendState }).contains("NeedsLogin")
        assertThat(states.first().authUrl).isEqualTo("https://login.example.test/a/first")
        client.close()
    }

    @Test
    fun `auth key 只经 stdin 的 start 请求传给子进程`(): Unit = runBlocking {
        val child = FakeTailnetChild()
        val client = TailnetClient(child, onState = {}, onExit = {})
        client.start(options)
        assertThat(child.received.single { "\"op\":\"start\"" in it }).contains("tskey-auth-SECRETVALUE")
        assertThat(child.received.filterNot { "\"op\":\"start\"" in it }.joinToString()).doesNotContain("SECRETVALUE")
        client.close()
    }

    @Test
    fun `关闭 stdin 后子进程退出，未退出时强制结束`() {
        val child = FakeTailnetChild()
        val exited = CountDownLatch(1)
        val client = TailnetClient(child, onState = {}, onExit = { exited.countDown() })
        client.close(graceMillis = 2_000)
        assertThat(child.stdinClosed).isTrue()
        assertThat(child.destroyed).isFalse()
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(client.isExited).isTrue()

        // 不响应 stdin 关闭的子进程：等待期满后强制结束。
        val stubborn = object : TailnetProcessHandle by FakeTailnetChild() {
            var killed = false
            override fun waitFor(timeoutMillis: Long): Boolean = false
            override fun destroyForcibly() {
                killed = true
            }
        }
        TailnetClient(stubborn, onState = {}, onExit = {}).close(graceMillis = 10)
        assertThat(stubborn.killed).isTrue()
    }

    @Test
    fun `子进程退出时未完成请求失败并回调退出`(): Unit = runBlocking {
        val child = FakeTailnetChild(handler = { _, request -> request["op"].toString().contains("status") })
        val exited = CountDownLatch(1)
        val client = TailnetClient(child, onState = {}, onExit = { exited.countDown() })
        val pending = async(Dispatchers.IO) { runCatching { client.status() }.exceptionOrNull() }
        Thread.sleep(200)
        child.crash()
        assertThat(pending.await()).isInstanceOf(IOException::class.java)
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue()
        assertThrows(IOException::class.java) { runBlocking { client.status() } }
    }

    @Test
    fun `应答超时报告 IOException`(): Unit = runBlocking {
        val child = FakeTailnetChild(handler = { _, _ -> true })
        val client = TailnetClient(child, onState = {}, onExit = {})
        val failure = runCatching { client.call(TailnetOps.STATUS, timeoutMillis = 100) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        client.close(graceMillis = 1_000)
    }
}
