package com.promenar.infinity.data.ssh

import com.jcraft.jsch.K2FakeJsch
import com.promenar.infinity.data.security.SshCredential
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SshOneShotOperationsTest {
    private val pin = SshPolicy.key(K2FakeJsch.HOST_KEY)
    private val host = SshHost(alias = "server", hostname = "server.test", username = "u", authType = SshAuthType.PASSWORD,
        trustedKeyType = pin.type, trustedFingerprint = pin.fingerprint)

    @Test fun `固定安装与验证各建独立连接并断开及清除认证材料`() = runBlocking {
        val clients = mutableListOf<K2FakeJsch>()
        val runtime = SshOneShotOperations({ false }) { K2FakeJsch().also(clients::add) }
        val password = "fixture password".toByteArray()
        SshCredential(password = password).use { credential ->
            var connected = false
            runtime.install(host, credential, "ssh-ed25519 AAAA infinity") { connected = true }
            assertTrue(connected)
            assertEquals("fixture password", password.toString(Charsets.UTF_8))
            runtime.verify(host, credential)
        }
        assertEquals(2, clients.size)
        assertTrue(clients.all { it.sessions.single().disconnected && it.sessions.single().passwordCleared() })
        assertTrue(clients.first().sessions.single().exec.text.contains("authorized_keys"))
        assertEquals("true", clients.last().sessions.single().exec.text)
    }

    @Test fun `连接与命令失败均关闭资源`() = runBlocking {
        for (connectFailure in listOf(true, false)) {
            val client = K2FakeJsch().also { it.failConnect = connectFailure; it.exitCode = 1 }
            val runtime = SshOneShotOperations({ false }) { client }
            val result = runCatching { SshCredential(password = "fixture".toByteArray()).use { runtime.verify(host, it) } }
            assertTrue(result.exceptionOrNull() is SshFailure)
            if (!connectFailure) assertEquals("KEY_LOGIN_FAILED", (result.exceptionOrNull() as SshFailure).code)
            assertFalse(client.sessions.single().connected); assertTrue(client.sessions.single().passwordCleared())
        }
    }

    @Test fun `取消执行断开一次性连接且探测不认证`() = runBlocking {
        val client = K2FakeJsch().also { it.keepChannelOpen = true }
        val runtime = SshOneShotOperations({ false }) { client }
        val task = launch(Dispatchers.Default) { SshCredential(password = "fixture".toByteArray()).use { runtime.verify(host, it) } }
        withTimeout(5_000) { while (client.sessions.firstOrNull()?.exec == null) delay(10) }
        task.cancelAndJoin()
        assertTrue(client.sessions.single().disconnected)
        val probeClient = K2FakeJsch()
        val probe = SshOneShotOperations({ false }) { probeClient }
        assertEquals(pin, probe.probe(host.copy(trustedFingerprint = null, trustedKeyType = null)))
        assertTrue(probeClient.sessions.single().passwordCleared()); assertFalse(probeClient.sessions.single().connected)
    }

    @Test fun `认证设置后连接建立前取消也不会遗留迟到连接`() = runBlocking {
        val client = K2FakeJsch().also { it.pauseAfterPassword = true }
        val runtime = SshOneShotOperations({ false }) { client }
        val task = launch(Dispatchers.Default) { SshCredential(password = "fixture".toByteArray()).use { runtime.verify(host, it) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { client.passwordSet.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            task.cancelAndJoin()
            client.releasePassword.countDown()
            assertTrue(withContext(Dispatchers.IO) { client.connectFinished.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            withTimeout(2_000) { while (client.sessions.single().connected) delay(10) }
            assertFalse(client.sessions.single().connected)
        } finally { client.releasePassword.countDown(); task.cancelAndJoin(); runtime.close() }
    }
}
