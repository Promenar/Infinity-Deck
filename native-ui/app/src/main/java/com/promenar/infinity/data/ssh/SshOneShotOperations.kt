package com.promenar.infinity.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.promenar.infinity.data.security.SshCredential
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 独立于终端、转发与连接池；入口只接受安装公钥或验证登录两种固定操作。 */
internal class SshOneShotOperations(
    private val isClosed: () -> Boolean,
    private val clientFactory: () -> com.jcraft.jsch.JSch = SshKeyGenerator::configuredClient,
) : AutoCloseable {
    private val active = ConcurrentHashMap.newKeySet<Session>()

    suspend fun probe(host: SshHost): SshHostKey = bounded {
        checkOpen()
        val verifier = PinnedSshHostKeys(host, probe = true)
        val client = clientFactory().apply { setHostKeyRepository(verifier) }
        val session = session(client, host)
        active.add(session)
        try {
            blocking({ session.disconnect() }) { cancelled ->
                checkOpen(); if (cancelled()) throw CancellationException()
                try { session.connect(15_000) }
                catch (failure: Exception) { if (verifier.observed == null) throw classifySshFailure(failure, fallbackCode = "CONNECTION_FAILED") }
                verifier.observed ?: throw SshFailure("CONNECTION_FAILED")
            }
        } finally { session.disconnect(); active.remove(session) }
    }

    suspend fun install(host: SshHost, credential: SshCredential, publicKey: String, connected: suspend () -> Unit) =
        execute(host, credential, authorizedKeyInstallCommand(publicKey), "KEY_INSTALL_FAILED", connected)

    suspend fun verify(host: SshHost, credential: SshCredential) = execute(host, credential, "true", "KEY_LOGIN_FAILED") {}

    private suspend fun execute(host: SshHost, credential: SshCredential, command: String, failureCode: String, connected: suspend () -> Unit) = bounded {
        checkOpen()
        val client = clientFactory()
        val verifier = PinnedSshHostKeys(host)
        client.setHostKeyRepository(verifier)
        val session = session(client, host)
        val channel = AtomicReference<ChannelExec?>()
        val owned = SshCredential(credential.privateKey.copyOf(), credential.password.copyOf(), credential.passphrase.copyOf())
        active.add(session)
        try {
            blocking({ session.disconnect() }) { cancelled ->
                try {
                    checkOpen(); if (cancelled()) throw CancellationException()
                    when (host.authType) {
                        SshAuthType.PASSWORD -> {
                            if (owned.password.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
                            session.setConfig("PreferredAuthentications", "password"); session.setPassword(owned.password)
                        }
                        SshAuthType.KEY -> {
                            if (owned.privateKey.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
                            SshPolicy.validatePrivateKey(owned.privateKey)
                            session.setConfig("PreferredAuthentications", "publickey")
                            client.addIdentity("infinity-once", owned.privateKey, null, owned.passphrase)
                        }
                    }
                    try { session.connect(15_000) }
                    catch (failure: Exception) { throw classifySshFailure(failure, verifier.rejection, "AUTH_OR_CONNECTION_FAILED") }
                } finally { owned.close(); session.setPassword(ByteArray(0)); client.removeAllIdentity() }
            }
            connected()
            blocking({ channel.get()?.disconnect(); session.disconnect() }) { cancelled ->
                checkOpen(); if (cancelled()) throw CancellationException()
                val exec = (session.openChannel("exec") as ChannelExec).also(channel::set)
                try {
                    exec.setCommand(command)
                    exec.setInputStream(null)
                    val discard = object : OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                    }
                    exec.setOutputStream(discard); exec.setErrStream(discard)
                    exec.connect(15_000)
                    while (!exec.isClosed) {
                        if (cancelled()) throw CancellationException()
                        checkOpen()
                        if (!session.isConnected) throw SshFailure("CONNECTION_FAILED")
                        Thread.sleep(20)
                    }
                    if (exec.exitStatus != 0) throw SshFailure(failureCode)
                } finally { exec.disconnect() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                channel.get()?.disconnect(); session.disconnect(); owned.close(); client.removeAllIdentity(); active.remove(session)
            }
        }
    }

    private fun session(client: com.jcraft.jsch.JSch, host: SshHost): Session = client.getSession(host.username, host.hostname, host.port).apply {
        setConfig("StrictHostKeyChecking", "yes")
        setConfig("PreferredAuthentications", "none")
        setDaemonThread(true)
        setTimeout(30_000)
        com.promenar.infinity.data.tailnet.InstalledTailnetRouting.routing.jschProxy(host.hostname)?.let { setProxy(it) }
    }

    private suspend fun <T> bounded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { withTimeout(45_000) { block() } }
        catch (_: TimeoutCancellationException) { throw SshFailure("TIMEOUT") }
    }

    private suspend fun <T> blocking(onCancel: () -> Unit, block: (() -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val worker = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = block { cancelled.get() }
                if (continuation.isActive) continuation.resume(result)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (failure is CancellationException) failure else classifySshFailure(failure))
            } finally {
                // 取消钩子可能先于 Session.connect 生效；工作线程结束后再次回收迟到连接。
                if (cancelled.get() || isClosed()) runCatching(onCancel)
            }
        }
        continuation.invokeOnCancellation { cancelled.set(true); runCatching(onCancel); worker.cancel() }
    }

    private fun checkOpen() { if (isClosed()) throw SshFailure("RUNTIME_CLOSED") }
    override fun close() { active.forEach { it.disconnect() }; active.clear() }
}
