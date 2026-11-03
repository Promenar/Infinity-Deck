package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.sshTools
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** 仅回环内嵌服务与临时合成认证材料，不读取用户主机或私钥。 */
class SshRuntimeIntegrationTest {
    private val root = Files.createTempDirectory("infinity-ssh-test")
    private val commands = AtomicInteger()
    private val startedCommands = AtomicInteger()
    private val authentications = AtomicInteger()
    private val createdSessions = AtomicInteger()
    private lateinit var server: SshServer
    private lateinit var runtime: SshRuntime
    private val hostStore = MemorySshHosts()

    @Before fun setup() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("server.key")).apply { algorithm = "EC" }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ ->
                (password == hostStore.password).also { if (it) authentications.incrementAndGet() }
            }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, _, _ -> true }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> startedCommands.incrementAndGet(); TestCommand(command, false, commands) }
            shellFactory = org.apache.sshd.server.shell.ShellFactory { TestCommand("", true, commands) }
            addSessionListener(object : SessionListener {
                override fun sessionCreated(session: Session) { createdSessions.incrementAndGet() }
            })
            start()
        }
        hostStore.host = SshHost(alias = "termux", hostname = "127.0.0.1", port = server.port, username = "synthetic-user", authType = SshAuthType.PASSWORD)
        runtime = SshRuntime(hostStore)
    }
    @After fun cleanup() { if (::runtime.isInitialized) runtime.close(); if (::server.isInitialized) server.stop(true); root.toFile().deleteRecursively() }

    @Test fun `未信任不认证经显式确认后密码执行返回退出码与stderr并复用连接`() = runBlocking {
        val rejected = runCatching { runtime.exec("chat", "termux", "hello") }.exceptionOrNull()
        assertThat((rejected as SshFailure).code).isEqualTo("HOST_KEY_UNTRUSTED")
        trust()
        val result = runtime.exec("chat", "termux", "hello")
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(result.stdout).contains("stdout-ok")
        assertThat(result.stderr).contains("stderr-ok")
        assertThat(result.stdout).doesNotContain("synthetic-password")
        runtime.exec("chat", "termux", "hello")
        assertThat(server.activeSessions.size).isEqualTo(1)
        runtime.closeOwner("chat")
        awaitServerSessionsClosed()
    }

    @Test fun `生成Ed25519公钥认证执行与错误脱敏`() = runBlocking {
        SshKeyGenerator.generate().use { key ->
            hostStore.host = hostStore.host.copy(authType = SshAuthType.KEY)
            hostStore.key = key.privateKey.copyOf()
            trust()
            assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        }
    }

    @Test fun `信任变更在通知延迟时也禁止旧连接承载新操作并按新固定值握手`() = runBlocking {
        withoutHostNotifications()
        trust()
        val trusted = hostStore.host
        runtime.exec("chat", "termux", "hello")
        assertThat(authentications.get()).isEqualTo(1)
        hostStore.host = trusted.copy(trustedFingerprint = "SHA256:" + "A".repeat(43))
        val operations: List<suspend () -> Any?> = listOf(
            { runtime.exec("chat", "termux", "hello") },
            { runtime.list("chat", "termux", "/") },
            { runtime.read("chat", "termux", "/note.txt") },
            { runtime.write("chat", "termux", "/note.txt", "禁止写入") },
            { runtime.openSession("chat", "termux") },
        )
        operations.forEach { operation ->
            val failure = runCatching { operation() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SshFailure::class.java)
            assertThat((failure as SshFailure).code).isEqualTo("HOST_KEY_CHANGED")
        }
        assertThat(Files.exists(root.resolve("note.txt"))).isFalse()
        assertThat(authentications.get()).isEqualTo(1)
        hostStore.host = trusted
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        assertThat(authentications.get()).isEqualTo(2)
    }

    @Test fun `连接身份变化在通知延迟时仍须重新认证`() = runBlocking {
        withoutHostNotifications()
        trust()
        runtime.exec("chat", "termux", "hello")
        assertThat(authentications.get()).isEqualTo(1)
        hostStore.host = hostStore.host.copy(username = "replacement-user")
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        assertThat(authentications.get()).isEqualTo(2)
        hostStore.host = hostStore.host.copy(id = "replacement-host")
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        assertThat(authentications.get()).isEqualTo(3)
    }

    @Test fun `信任变更后的既有PTY即使尚未收到通知也不能发送新输入`() = runBlocking {
        withoutHostNotifications()
        trust()
        val opened = runtime.openSession("chat", "termux")
        hostStore.host = hostStore.host.copy(trustedFingerprint = "SHA256:" + "A".repeat(43))
        val failure = runCatching { runtime.sendSession("chat", opened.sessionId, "禁止执行\n") }.exceptionOrNull()
        assertThat((failure as SshFailure).code).isEqualTo("HOST_CONFIGURATION_CHANGED")
        assertThat(runtime.sessionHost("chat", opened.sessionId)).isNull()
    }

    @Test fun `信任变化通知回收既有PTY和端口转发`() = runBlocking {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        hostStore.host = hostStore.host.copy(localForwardPort = localPort, forwardPort = server.port)
        trust()
        val opened = runtime.openSession("ui", "termux")
        runtime.startForward("termux")
        assertThat(runtime.hasPersistentResources).isTrue()
        hostStore.host = hostStore.host.copy(trustedFingerprint = "SHA256:" + "A".repeat(43))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (runtime.hasPersistentResources && System.nanoTime() < deadline) delay(10)
        assertThat(runtime.hasPersistentResources).isFalse()
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isNull()
        assertThat(runtime.forwardStates.value).isEmpty()
        assertThat(runCatching { Socket("127.0.0.1", localPort).close() }.isSuccess).isFalse()
    }

    @Test fun `输出采集有界超时关闭命令且用户取消及时关闭通道`() = runBlocking {
        trust()
        val result = runtime.exec("chat", "termux", "large", maxOutputChars = 100)
        assertThat(result.truncated).isTrue()
        assertThat(result.stdout.length + result.stderr.length).isAtMost(100)
        val timeout = runtime.exec("chat", "termux", "sleep", timeoutSeconds = 1)
        assertThat(timeout.timedOut).isTrue()
        val pending = async { runtime.exec("chat", "termux", "sleep") }
        delay(150)
        pending.cancelAndJoin()
        repeat(20) { if (commands.get() != 0) delay(25) }
        assertThat(commands.get()).isEqualTo(0)
        runtime.closeOwner("chat")
    }

    @Test fun `SFTP文本分段读写哈希冲突拒绝且二进制拒绝`() = runBlocking {
        trust()
        val hash = runtime.write("chat", "termux", "/note.txt", "中文 hello")
        val page = runtime.read("chat", "termux", "/note.txt")
        assertThat(page.content).isEqualTo("中文 hello")
        assertThat(page.eof).isTrue()
        assertThat(runtime.list("chat", "termux", "/").entries.map { it.name }).contains("note.txt")
        val conflict = runCatching { runtime.write("chat", "termux", "/note.txt", "bad", "0".repeat(64)) }.exceptionOrNull()
        assertThat((conflict as SshFailure).code).isEqualTo("SHA256_CONFLICT")
        assertThat(runtime.read("chat", "termux", "/note.txt").content).isEqualTo("中文 hello")
        runtime.write("chat", "termux", "/note.txt", "replaced", hash)
        assertThat(runtime.read("chat", "termux", "/note.txt").content).isEqualTo("replaced")
        Files.write(root.resolve("binary.bin"), byteArrayOf(1, 0, 2, -1))
        assertThat((runCatching { runtime.read("chat", "termux", "/binary.bin") }.exceptionOrNull() as SshFailure).code).isEqualTo("BINARY_FILE")
    }

    @Test fun `SFTP工具条件覆盖后可以读取且冲突不会改写文件`() = runBlocking {
        trust()
        val tools = sshTools(runtime).associateBy { it.name }
        val context = object : SkillExecutionContext {
            override val sessionId = "chat"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
        suspend fun call(name: String, arguments: String) = tools.getValue(name).execute(Json.parseToJsonElement(arguments).jsonObject, context)
        val initial = call("sftp_write", """{"host":"termux","path":"/tool.txt","content":"设备初始😀"}""")
        assertWithMessage(initial.content).that(initial.status).isEqualTo("success")
        val hash = Json.parseToJsonElement(initial.content).jsonObject.getValue("sha256").jsonPrimitive.content
        val written = call("sftp_write", """{"host":"termux","path":"/tool.txt","content":"条件写入成功","expected_sha256":"$hash"}""")
        assertWithMessage(written.content).that(written.status).isEqualTo("success")
        val conflict = call("sftp_write", """{"host":"termux","path":"/tool.txt","content":"不得覆盖","expected_sha256":"$hash"}""")
        assertThat(conflict.status).isEqualTo("error")
        assertThat(conflict.content).contains("SHA256_CONFLICT")
        val read = call("sftp_read", """{"host":"termux","path":"/tool.txt"}""")
        assertWithMessage(read.content).that(read.status).isEqualTo("success")
        assertThat(Json.parseToJsonElement(read.content).jsonObject.getValue("content").jsonPrimitive.content).isEqualTo("条件写入成功")
        assertThat(Files.readAllBytes(root.resolve("tool.txt")).toString(Charsets.UTF_8)).isEqualTo("条件写入成功")
    }

    @Test fun `ssh_exec按统一命令结果格式返回退出码与超时`() = runBlocking {
        trust()
        val exec = sshTools(runtime).single { it.name == "ssh_exec" }
        val context = object : SkillExecutionContext {
            override val sessionId = "chat"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
        suspend fun call(arguments: String) = exec.execute(Json.parseToJsonElement(arguments).jsonObject, context)
        fun field(content: String, key: String) = Json.parseToJsonElement(content).jsonObject.getValue(key)

        val zero = call("""{"host":"termux","command":"zero"}""")
        assertWithMessage(zero.content).that(zero.status).isEqualTo("success")
        assertThat(field(zero.content, "exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(field(zero.content, "truncation").jsonPrimitive.content).isEqualTo("none")

        val failed = call("""{"host":"termux","command":"hello"}""")
        assertThat(failed.status).isEqualTo("error")
        assertThat(field(failed.content, "exit_code").jsonPrimitive.int).isEqualTo(7)
        assertThat(field(failed.content, "stderr").jsonPrimitive.content).isEqualTo("stderr-ok")
        assertThat(failed.content).doesNotContain("synthetic-password")

        val noisy = call("""{"host":"termux","command":"noisy-fail","max_output_chars":1000}""")
        assertThat(field(noisy.content, "stderr").jsonPrimitive.content).isEqualTo("fatal: boom")
        assertThat(field(noisy.content, "stdout").jsonPrimitive.content.length + "fatal: boom".length).isAtMost(1000)
        assertThat(field(noisy.content, "stdout_truncated").jsonPrimitive.content).isEqualTo("true")
        assertThat(field(noisy.content, "truncation").jsonPrimitive.content).isEqualTo("tail_removed")
        assertThat(field(noisy.content, "stdout_original_length")).isEqualTo(JsonNull)

        val timeout = call("""{"host":"termux","command":"sleep","timeout_seconds":1}""")
        assertThat(timeout.status).isEqualTo("error")
        assertThat(field(timeout.content, "error_code").jsonPrimitive.content).isEqualTo("SSH_TIMEOUT")
        assertThat(field(timeout.content, "timed_out").jsonPrimitive.content).isEqualTo("true")
        assertThat(field(timeout.content, "exit_code")).isEqualTo(JsonNull)
        runtime.closeOwner("chat")
    }

    @Test fun `输出预算在stdout与stderr之间分配且不让一方挤掉另一方`() {
        assertThat(splitOutputBudget(10, 5, 100)).isEqualTo(10 to 5)
        assertThat(splitOutputBudget(100_000, 11, 1000)).isEqualTo(989 to 11)
        assertThat(splitOutputBudget(3, 100_000, 1000)).isEqualTo(3 to 997)
        assertThat(splitOutputBudget(100_000, 100_000, 1000)).isEqualTo(500 to 500)
        assertThat(splitOutputBudget(100_000, 100_000, 1)).isEqualTo(1 to 0)
    }

    @Test fun `PTY往返调整窗口按owner隔离并在生成结束关闭`() = runBlocking {
        trust()
        val session = runtime.openSession("chat", "termux")
        assertThat(runtime.sessionHost("other", session.sessionId)).isNull()
        assertThat((runCatching { runtime.sendSession("other", session.sessionId, "whoami\n") }.exceptionOrNull() as SshFailure).code).isEqualTo("SESSION_NOT_FOUND")
        runtime.resizeSession("chat", session.sessionId, 120, 32)
        runtime.sendSession("chat", session.sessionId, "hello\n")
        val output = buildString {
            repeat(20) {
                append(runtime.readSession("chat", session.sessionId, 50).output)
                if (contains("echo:hello")) return@buildString
            }
        }
        assertThat(output).contains("echo:hello")
        runtime.closeOwner("chat")
        assertThat(runtime.sessionHost("chat", session.sessionId)).isNull()
    }

    @Test fun `目录名和分段文件读取均隐藏连接认证材料`() = runBlocking {
        trust()
        Files.write(root.resolve("synthetic-password"), "prefix synthetic-password suffix".toByteArray())
        assertThat(runtime.list("chat", "termux", "/").entries.joinToString { it.name }).doesNotContain("synthetic-password")
        val first = runtime.read("chat", "termux", "/synthetic-password", 7, 10)
        val second = runtime.read("chat", "termux", "/synthetic-password", first.nextOffset, 10)
        assertThat(first.content + second.content).doesNotContain("synthetic-password")
    }

    @Test fun `同身份凭据更新后已有连接仍按原认证材料脱敏`() = runBlocking {
        trust()
        runtime.exec("chat", "termux", "hello")
        hostStore.password = "new-synthetic-password"
        assertThat(runtime.exec("chat", "termux", "hello").stdout).doesNotContain("synthetic-password")
    }

    @Test fun `UTF8字节采集边界截断仍隐藏多字节口令前缀`() = runBlocking {
        hostStore.password = "合成口令-只用于测试"
        trust()
        val result = runtime.exec("chat", "termux", "unicode-secret", maxOutputChars = 1)
        assertThat(result.stdout).isEqualTo("█")
        assertThat(result.truncated).isTrue()
    }

    @Test fun `较早前台服务的销毁不释放新注册实例的资源`() = runBlocking {
        trust()
        val opened = runtime.openSession("ui", "termux")
        val old = runtime.registerForegroundService()
        val current = runtime.registerForegroundService()
        runtime.closePersistent(old)
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isEqualTo("termux")
        runtime.closePersistent(current)
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isNull()
    }

    @Test fun `更新配置后回收旧连接并且新连接只按最新配置创建`() = runBlocking {
        trust()
        runtime.exec("chat", "termux", "hello")
        hostStore.host = hostStore.host.copy(keepTerminal = true)
        awaitServerSessionsClosed()
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
    }

    @Test fun `前台服务启动失败可重试服务实例关闭不受其它资源释放影响`() = runBlocking {
        trust()
        runtime.close()
        var starts = 0
        runtime = SshRuntime(hostStore, onPersistentActivity = { active ->
            if (active) { starts++; if (starts == 1) throw IllegalStateException("synthetic unavailable") }
        })
        assertThat(runCatching { runtime.openSession("ui", "termux") }.exceptionOrNull()).isInstanceOf(SshFailure::class.java)
        val opened = runtime.openSession("ui", "termux")
        assertThat(starts).isEqualTo(2)
        val token = runtime.registerForegroundService()
        runtime.exec("agent:test", "termux", "hello")
        runtime.closeOwner("agent:test")
        runtime.closePersistent(token)
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isNull()
        assertThat(runtime.forwardStates.value).isEmpty()
    }

    @Test fun `覆盖保留私有权限符号链接拒绝且取消上传清理临时文件`() = runBlocking {
        trust()
        val path = root.resolve("private.txt")
        Files.write(path, "old".toByteArray())
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        runtime.write("chat", "termux", "/private.txt", "new")
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        Files.createSymbolicLink(root.resolve("link.txt"), path)
        assertThat((runCatching { runtime.write("chat", "termux", "/link.txt", "bad") }.exceptionOrNull() as SshFailure).code).isEqualTo("UNSUPPORTED_WRITE_TARGET")
        val pending = async { runtime.write("chat", "termux", "/cancel.txt", "x".repeat(1_048_576)) }
        delay(10)
        pending.cancelAndJoin()
        delay(100)
        assertThat(Files.list(root).use { paths -> paths.anyMatch { ".infinity-" in it.fileName.toString() } }).isFalse()
    }

    @Test fun `审批等待租约独立于PTY持续输出且闲置会话释放`() = runBlocking {
        trust()
        runtime.close()
        runtime = SshRuntime(hostStore, idleTimeoutMs = 250)
        val opened = runtime.openSession("chat", "termux")
        runtime.pauseOwner("chat")
        repeat(6) { runCatching { runtime.sendSession("chat", opened.sessionId, "ping\n") }; delay(60) }
        assertThat(runtime.sessionHost("chat", opened.sessionId)).isNull()
    }

    @Test fun `实时输出经凭据遮蔽逐段推送，界面停止时断开通道并标记停止`() = runBlocking {
        trust()
        val store = com.promenar.infinity.data.exec.ToolLiveOutputStore()
        val sink = store.open("step")
        val started = System.nanoTime()
        val run = async(Dispatchers.IO) { runtime.exec("chat", "termux", "stream", timeoutSeconds = 60, live = sink) }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (store.snapshot("step")?.text?.contains("tail") != true && System.nanoTime() < deadline) delay(20)
        val live = store.snapshot("step")!!
        assertThat(live.text).startsWith("part-1 ")
        assertThat(live.text).endsWith("半 tail\n")
        assertThat(live.text).doesNotContain("synthetic-password")
        assertThat(live.text).contains("█")
        assertThat(live.stoppable).isTrue()

        store.requestStop("step")
        val result = withTimeout(10_000) { run.await() }
        assertThat(result.stoppedByUser).isTrue()
        assertThat(result.timedOut).isFalse()
        assertThat(result.stdout).doesNotContain("synthetic-password")
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(20_000)
    }

    @Test fun `启动前已在界面停止的命令不在远端启动`() = runBlocking {
        trust()
        val store = com.promenar.infinity.data.exec.ToolLiveOutputStore()
        val sink = store.open("step")
        store.requestStop("step")
        val before = startedCommands.get()
        val result = withTimeout(10_000) { runtime.exec("chat", "termux", "stream", timeoutSeconds = 60, live = sink) }
        assertThat(result.stoppedByUser).isTrue()
        assertThat(result.timedOut).isFalse()
        assertThat(result.stdout).isEmpty()
        assertThat(startedCommands.get()).isEqualTo(before)
    }

    @Test fun `用户默认不空闲回收而Agent仍按原超时回收`() = runBlocking {
        trust()
        runtime.close()
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        runtime = SshRuntime(hostStore, idleTimeoutMs = 100, monotonicMillis = clock::get)
        val user = runtime.openSession("ui", "termux")
        val agent = runtime.openSession("agent:chat", "termux")
        clock.set(1_000)
        withTimeout(5_000) { while (runtime.sessionHost("agent:chat", agent.sessionId) != null) delay(10) }
        assertThat(runtime.sessionHost("ui", user.sessionId)).isEqualTo("termux")
    }

    @Test fun `用户空闲策略按最后输入输出计时且可实时关闭`() = runBlocking {
        trust()
        runtime.close()
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val timeout = java.util.concurrent.atomic.AtomicReference<Long?>(1_800_000L)
        runtime = SshRuntime(hostStore, idleTimeoutMs = 100, uiIdleTimeoutMs = timeout::get, monotonicMillis = clock::get)
        val user = runtime.openSession("ui", "termux")
        clock.set(1_700_000)
        runtime.sendSession("ui", user.sessionId, "ping\n")
        withTimeout(5_000) { while (!runtime.readSession("ui", user.sessionId).output.contains("echo:ping")) delay(10) }
        clock.set(1_900_000)
        delay(100)
        assertThat(runtime.sessionHost("ui", user.sessionId)).isEqualTo("termux")
        timeout.set(null)
        clock.set(4_000_000)
        delay(100)
        assertThat(runtime.sessionHost("ui", user.sessionId)).isEqualTo("termux")
        timeout.set(1_800_000L)
        withTimeout(5_000) { while (runtime.sessionHost("ui", user.sessionId) != null) delay(10) }
    }

    @Test fun `Agent镜像只读不清空而用户终端不开槽关闭时撤销镜像`() = runBlocking {
        trust()
        runtime.close()
        val live = com.promenar.infinity.data.exec.ToolLiveOutputStore()
        runtime = SshRuntime(hostStore, liveOutput = live)
        val user = runtime.openSession("ui", "termux")
        assertThat(live.snapshot("ssh_session:${user.sessionId}")).isNull()
        val agent = runtime.openSession("agent:chat", "termux")
        val key = "ssh_session:${agent.sessionId}"
        assertThat(runtime.activity.value.agentSessions.single().liveOutputKey).isEqualTo(key)
        runtime.sendSession("agent:chat", agent.sessionId, "synthetic-password hello\n")
        withTimeout(5_000) { while (live.snapshot(key)?.text?.contains("hello") != true) delay(10) }
        val mirror = live.snapshot(key)!!
        assertThat(mirror.stoppable).isFalse()
        assertThat(mirror.text).doesNotContain("synthetic-password")
        assertThat(runtime.readSession("agent:chat", agent.sessionId).output).contains("hello")
        assertThat(runtime.readSession("agent:chat", agent.sessionId).output).isEmpty()
        assertThat(live.snapshot(key)?.text).isEqualTo(mirror.text)
        live.requestStop(key)
        assertThat(runtime.sessionHost("agent:chat", agent.sessionId)).isEqualTo("termux")
        runtime.closeSession("agent:chat", agent.sessionId)
        assertThat(live.snapshot(key)).isNull()
        assertThat(runtime.activity.value.agentSessions).isEmpty()
    }

    @Test fun `活动清单和按连接断开覆盖终端转发而全部断开保留Agent`() = runBlocking {
        trust()
        hostStore.host = hostStore.host.copy(localForwardPort = ServerSocket(0).use { it.localPort }, forwardPort = server.port)
        val user = runtime.openSession("ui", "termux")
        runtime.openSession("agent:chat", "termux")
        runtime.startForward("termux")
        assertThat(runtime.activity.value.connections.map { it.kind }).containsExactly(SshConnectionKind.TERMINAL, SshConnectionKind.AGENT, SshConnectionKind.FORWARD)
        runtime.disconnect("ui", "termux")
        assertThat(runtime.sessionHost("ui", user.sessionId)).isNull()
        runtime.disconnectAll()
        assertThat(runtime.forwardStates.value).isEmpty()
        assertThat(runtime.activity.value.connections.single().owner).isEqualTo("agent:chat")
        assertThat(runtime.hasPersistentResources).isFalse()
    }

    @Test fun `SSH前台同步返回且后台停止关闭通道并拒绝输入`() = sshJobs { jobs ->
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val context = sshContext()
        val quick = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"zero","on_exit":"none"}""").jsonObject, context)
        assertThat(Json.parseToJsonElement(quick.content).jsonObject["job_id"]).isNull()
        assertThat(quick.status).isEqualTo("success")
        val result = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"stream","background":true,"on_exit":"none"}""").jsonObject, context)
        val id = Json.parseToJsonElement(result.content).jsonObject.getValue("job_id").jsonPrimitive.content
        assertThat(jobs.status("chat", id).record.running).isTrue()
        // 与本地后台任务同一列表，靠运行环境与主机 ID 区分。
        val listed = jobs.list("chat").single { it.id == id }.job()
        assertThat(listed.runner).isEqualTo(com.promenar.infinity.domain.jobs.BackgroundJobRunner.SSH)
        assertThat(listed.remoteHost).isEqualTo("termux")
        runtime.closeOwner("agent:chat")
        withTimeout(5_000) { while (jobs.status("chat", id).tail.text.contains("tail").not()) delay(10) }
        assertThat(jobs.status("chat", id).tail.text).doesNotContain("synthetic-password")
        val input = runCatching { jobs.input("chat", id, "answer") }.exceptionOrNull()
        assertThat((input as com.promenar.infinity.data.jobs.JobAccessException).code).isEqualTo("JOB_INPUT_UNSUPPORTED")
        val stopped = jobs.stop("chat", id)
        assertThat(stopped.record.state).isEqualTo("STOPPED")
        assertThat(stopped.record.guidance).contains("远端进程可能仍在运行")
        withTimeout(5_000) { while (commands.get() != 0) delay(10) }
        assertThat(runtime.activity.value.connections.none { it.kind == SshConnectionKind.JOB }).isTrue()
    }

    @Test fun `SSH让出转交连接在轮次关闭后继续且按连接断开记停止`() = sshJobs { jobs ->
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val context = sshContext()
        val invalid = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"zero","timeout_seconds":1,"yield_after_seconds":2}""").jsonObject, context)
        assertThat(invalid.status).isEqualTo("error")
        val result = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"sleep","timeout_seconds":2,"yield_after_seconds":1,"on_exit":"none"}""").jsonObject, context)
        val id = Json.parseToJsonElement(result.content).jsonObject.getValue("job_id").jsonPrimitive.content
        assertThat(Json.parseToJsonElement(result.content).jsonObject.getValue("yielded_after_seconds").jsonPrimitive.int).isEqualTo(1)
        runtime.closeOwner("agent:chat")
        delay(1_200)
        assertThat(jobs.status("chat", id).record.running).isTrue()
        val connection = runtime.activity.value.connections.single { it.jobId == id }
        assertThat(connection.sessionId).isEqualTo("chat")
        runtime.disconnect(connection.owner, connection.alias)
        withTimeout(5_000) { while (jobs.status("chat", id).record.running) delay(10) }
        assertThat(jobs.status("chat", id).record.state).isEqualTo("STOPPED")
        val status = com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools(jobs).single { it.name == "job_status" }
            .execute(Json.parseToJsonElement("""{"job_id":"$id"}""").jsonObject, context)
        assertThat(Json.parseToJsonElement(status.content).jsonObject.getValue("guidance").jsonPrimitive.content).contains("远端进程可能仍在运行")
        assertThat(Json.parseToJsonElement(status.content).jsonObject.getValue("exit_code")).isEqualTo(JsonNull)
    }

    @Test fun `SSH零自动阈值时插队接管运行命令且关闭轮次不结束任务`() = sshJobs { jobs ->
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val queue = MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val result = async(com.promenar.infinity.data.exec.SteeringContext(queue)) {
            exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"sleep","yield_after_seconds":0,"on_exit":"none"}""").jsonObject, sshContext())
        }
        withTimeout(5_000) { while (startedCommands.get() < 1) delay(10) }
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("q", "插队", createdAt = 0, steering = true)))
        val response = withTimeout(5_000) { result.await() }
        val id = Json.parseToJsonElement(response.content).jsonObject.getValue("job_id").jsonPrimitive.content
        runtime.closeOwner("agent:chat")
        assertThat(jobs.status("chat", id).record.running).isTrue()
        assertThat(commands.get()).isEqualTo(1)
        jobs.stop("chat", id)
    }

    @Test fun `SSH文件捕获被插队后由任务所有者完成且日志不包含捕获值`() = sshJobs { jobs ->
        runtime.close()
        val vault = com.promenar.infinity.data.secret.SessionSecretVault()
        runtime = SshRuntime(hostStore, vault = vault, redactor = com.promenar.infinity.data.secret.SecretOutputRedactor(vault))
        Files.writeString(root.resolve("capture.txt"), "fixture-captured-value")
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val queue = MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val result = async(com.promenar.infinity.data.exec.SteeringContext(queue)) {
            exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"delayed-zero","on_exit":"none","capture_secret":{"name":"TOKEN","label":"测试槽位","from":"file","path":"/capture.txt"}}""").jsonObject, sshContext())
        }
        withTimeout(5_000) { while (commands.get() < 1) delay(10) }
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("q", "插队", createdAt = 0, steering = true)))
        val response = withTimeout(5_000) { result.await() }
        val id = Json.parseToJsonElement(response.content).jsonObject.getValue("job_id").jsonPrimitive.content
        runtime.closeOwner("agent:chat")
        val ended = jobs.waitFor("chat", id, 5)
        assertThat(ended.record.state).isEqualTo("EXITED")
        assertThat(vault.revealBytes("chat", "TOKEN")!!.toString(Charsets.UTF_8)).isEqualTo("fixture-captured-value")
        assertThat(ended.record.commandResult.toString()).doesNotContain("fixture-captured-value")
        assertThat(ended.tail.text).doesNotContain("fixture-captured-value")
    }

    @Test fun `SSH连接锁等待期间插队配对中断且原命令继续`() = sshJobs { jobs ->
        val first = async(Dispatchers.IO) { runtime.exec("agent:chat", "termux", "sleep") }
        try {
            withTimeout(5_000) { while (startedCommands.get() < 1) delay(10) }
            val queue = MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
            val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
            val waiting = async(com.promenar.infinity.data.exec.SteeringContext(queue)) {
                exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"zero","yield_after_seconds":0,"on_exit":"none"}""").jsonObject, sshContext())
            }
            withTimeout(5_000) { while (jobs.list("chat").isEmpty()) delay(10) }
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("q", "插队", createdAt = 0, steering = true)))
            val response = withTimeout(5_000) { waiting.await() }
            assertThat(response.content).contains("EXEC_INTERRUPTED")
            assertThat(startedCommands.get()).isEqualTo(1)
            assertThat(first.isCompleted).isFalse()
        } finally { first.cancelAndJoin() }
    }

    @Test fun `让出完成保留同会话的AgentPTY`() = sshJobs { jobs ->
        val pty = runtime.openSession("agent:chat", "termux")
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val response = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"delayed-zero","yield_after_seconds":1,"on_exit":"none"}""").jsonObject, sshContext())
        val id = Json.parseToJsonElement(response.content).jsonObject.getValue("job_id").jsonPrimitive.content
        val ended = jobs.waitFor("chat", id, 5)
        assertThat(ended.record.state).isEqualTo("EXITED")
        runtime.sendSession("agent:chat", pty.sessionId, "alive\n")
        withTimeout(5_000) { while (!runtime.readSession("agent:chat", pty.sessionId).output.contains("echo:alive")) delay(10) }
        assertThat(runtime.activity.value.connections.none { it.kind == SshConnectionKind.JOB }).isTrue()
    }

    @Test fun `SSH后台时限使用max_runtime而非timeout且全部断开停止任务`() = sshJobs { jobs ->
        runtime.close()
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        runtime = SshRuntime(hostStore, monotonicMillis = clock::get)
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val response = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"stream","background":true,"timeout_seconds":600,"max_runtime_minutes":1,"on_exit":"none"}""").jsonObject, sshContext())
        val id = Json.parseToJsonElement(response.content).jsonObject.getValue("job_id").jsonPrimitive.content
        withTimeout(5_000) { while (!jobs.status("chat", id).tail.text.contains("tail")) delay(10) }
        clock.set(61_000)
        assertThat(jobs.waitFor("chat", id, 5).record.state).isEqualTo("TIMED_OUT")
        val next = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"sleep","background":true,"on_exit":"none"}""").jsonObject, sshContext())
        val nextId = Json.parseToJsonElement(next.content).jsonObject.getValue("job_id").jsonPrimitive.content
        runtime.disconnectAll()
        assertThat(jobs.waitFor("chat", nextId, 5).record.state).isEqualTo("STOPPED")
    }

    @Test fun `全部断开SSH后台任务保留同会话AgentPTY`() = sshJobs { jobs ->
        val pty = runtime.openSession("agent:chat", "termux")
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val response = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"sleep","yield_after_seconds":1,"on_exit":"none"}""").jsonObject, sshContext())
        val id = Json.parseToJsonElement(response.content).jsonObject.getValue("job_id").jsonPrimitive.content
        runtime.disconnectAll()
        assertThat(jobs.waitFor("chat", id, 5).record.state).isEqualTo("STOPPED")
        runtime.sendSession("agent:chat", pty.sessionId, "preserved\n")
        withTimeout(5_000) { while (!runtime.readSession("agent:chat", pty.sessionId).output.contains("echo:preserved")) delay(10) }
        assertThat(runtime.activity.value.agentSessions.single().id).isEqualTo(pty.sessionId)
    }

    @Test fun `等待Agent连接锁的任务可以停止且不启动第二条远端命令`() = sshJobs { jobs ->
        val first = async(Dispatchers.IO) { runtime.exec("agent:chat", "termux", "sleep") }
        try {
            withTimeout(5_000) { while (startedCommands.get() < 1) delay(10) }
            val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
            val queued = async {
                exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"zero","yield_after_seconds":1,"on_exit":"none"}""").jsonObject, sshContext())
            }
            val job = withTimeout(5_000) {
                while (jobs.list("chat").isEmpty()) delay(10)
                jobs.list("chat").single()
            }
            val stopped = withTimeout(5_000) { jobs.stop("chat", job.id) }
            assertThat(stopped.record.state).isEqualTo("STOPPED")
            val response = queued.await()
            assertThat(response.status).isEqualTo("error")
            assertThat(Json.parseToJsonElement(response.content).jsonObject.getValue("error_code").jsonPrimitive.content).isEqualTo("SSH_STOPPED")
            assertThat(startedCommands.get()).isEqualTo(1)
        } finally { first.cancelAndJoin() }
    }

    @Test fun `后台和让出各通知一次而前台同步结果不通知`() {
        val notifications = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        sshJobs(com.promenar.infinity.data.jobs.BackgroundJobCompletion(notify = { notifications.send(it.id) })) { jobs ->
            val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
            val quick = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"zero"}""").jsonObject, sshContext())
            assertThat(quick.status).isEqualTo("success")
            for (extra in listOf("\"background\":true", "\"yield_after_seconds\":1")) {
                val response = exec.execute(Json.parseToJsonElement("""{"host":"termux","command":"delayed-zero",$extra}""").jsonObject, sshContext())
                val id = Json.parseToJsonElement(response.content).jsonObject.getValue("job_id").jsonPrimitive.content
                assertThat(jobs.waitFor("chat", id, 5).record.state).isEqualTo("EXITED")
                assertThat(withTimeout(5_000) { notifications.receive() }).isEqualTo(id)
            }
            assertThat(notifications.tryReceive().isFailure).isTrue()
        }
        notifications.close()
    }

    @Test fun `后台命令的审批快照变化在创建任务之前拒绝`() = sshJobs { jobs ->
        val args = Json.parseToJsonElement("""{"host":"termux","command":"zero","background":true}""").jsonObject
        val exec = sshTools(runtime, jobs).single { it.name == "ssh_exec" }
        val target = requireNotNull(com.promenar.infinity.data.generation.resolveSessionToolTarget(
            com.promenar.infinity.data.model.Session(id = "chat", agentId = "agent"),
            com.promenar.infinity.data.remote.protocol.ProtocolTool(
                function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction("ssh_exec", "测试", exec.parametersSchema),
                risk = com.promenar.infinity.domain.tool.ToolRisk.PROCESS,
            ), args.toString(), null, sshHostStore = hostStore,
        ))
        val context = object : SkillExecutionContext by sshContext() {
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        hostStore.host = hostStore.host.copy(username = "changed-user")
        val result = exec.execute(args, context)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_AUTHORIZATION_CHANGED")
        assertThat(jobs.list("chat")).isEmpty()
        assertThat(startedCommands.get()).isEqualTo(0)
    }

    private fun sshContext() = object : SkillExecutionContext {
        override val sessionId = "chat"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    private fun sshJobs(
        completion: com.promenar.infinity.data.jobs.BackgroundJobCompletion = com.promenar.infinity.data.jobs.BackgroundJobCompletion(),
        block: suspend kotlinx.coroutines.CoroutineScope.(com.promenar.infinity.data.jobs.BackgroundJobManager) -> Unit,
    ) = runBlocking {
        trust()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val directory = root.resolve("jobs-test").toFile()
        val jobs = com.promenar.infinity.data.jobs.BackgroundJobManager(
            scope, com.promenar.infinity.data.jobs.BackgroundJobStore(java.io.File(directory, "store")), java.io.File(directory, "runs"),
            // SSH 任务不经本机命令执行器，这里只为满足构造参数。
            com.promenar.infinity.data.exec.BuiltinCommandRunner(listOf("/bin/sh"), "/bin/sh", { _, _ -> }, { java.io.File(directory, "scratch") }),
            prepareWorkspace = { error("SSH 不准备本机工作区") }, prepareCommand = { _, _, _, _, _ -> error("SSH 不运行本机命令") },
            completion = completion,
        )
        try { block(jobs) } finally { runtime.disconnectAll(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
    }

    private suspend fun trust() {
        val key = runtime.probe("termux")
        hostStore.host = hostStore.host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
        // 探测在客户端断开后立即返回，服务端会话由后台 IO 线程异步移除；等它移除，后续会话断言只反映被测操作。
        awaitServerSessionsClosed()
    }

    /** 等待服务端会话全部移除；期限只作失败兜底，条件满足即返回。 */
    private suspend fun awaitServerSessionsClosed() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (server.activeSessions.isNotEmpty() && System.nanoTime() < deadline) delay(10)
        assertWithMessage("服务端会话未在期限内关闭").that(server.activeSessions).isEmpty()
    }

    private fun withoutHostNotifications() {
        runtime.close()
        runtime = SshRuntime(object : SshHostStore by hostStore {
            override fun observeHosts() = kotlinx.coroutines.flow.emptyFlow<List<SshHost>>()
        })
    }

    @Test fun `旧格式SSH批准目标在执行入口被拒绝`() = runBlocking {
        trust()
        val sessionsBefore = createdSessions.get()
        val result = executeApproved("""["process","termux","[PTY]"]""")
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_AUTHORIZATION_CHANGED")
        assertThat(createdSessions.get()).isEqualTo(sessionsBefore)
        assertThat(server.activeSessions).isEmpty()
    }

    @Test fun `身份保持不变的SSH批准目标可以打开PTY`() = runBlocking {
        trust()
        val result = executeApproved(requireNotNull(approvalTarget()))
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("session_id")
    }

    @Test fun `审批后同名主机身份变化不能执行到新用户名`() = runBlocking {
        trust()
        val target = requireNotNull(approvalTarget())
        hostStore.host = hostStore.host.copy(username = "different-user")
        val sessionsBefore = createdSessions.get()
        val result = executeApproved(target)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_AUTHORIZATION_CHANGED")
        assertThat(createdSessions.get()).isEqualTo(sessionsBefore)
        assertThat(server.activeSessions).isEmpty()
    }

    @Test fun `身份复核与连接间配置变化拒绝远端PTY`() = runBlocking {
        trust()
        val target = requireNotNull(approvalTarget())
        val original = hostStore.host
        runtime.close()
        val reads = AtomicInteger()
        runtime = SshRuntime(object : SshHostStore by hostStore {
            override fun observeHosts() = kotlinx.coroutines.flow.emptyFlow<List<SshHost>>()
            override suspend fun find(alias: String): SshHost = if (reads.incrementAndGet() >= 3) {
                original.copy(username = "changed-during-connect")
            } else original
        })
        val result = executeApproved(target)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_CONFIGURATION_CHANGED")
    }

    private suspend fun approvalTarget(): String? = com.promenar.infinity.data.generation.resolveSessionToolTarget(
        com.promenar.infinity.data.model.Session(id = "chat", agentId = "agent"),
        com.promenar.infinity.data.remote.protocol.ProtocolTool(
            function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction("ssh_session_open", "测试", "{}"),
            risk = com.promenar.infinity.domain.tool.ToolRisk.PROCESS,
        ),
        """{"host":"termux"}""", null, sshHostStore = hostStore,
    )

    private suspend fun executeApproved(target: String): com.promenar.infinity.data.model.ToolResult {
        val context = object : com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext {
            override val sessionId = "chat"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        return com.promenar.infinity.ui.chat.manager.skills.sshTools(runtime).single { it.name == "ssh_session_open" }.execute(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"host":"termux"}""") as kotlinx.serialization.json.JsonObject,
            context,
        )
    }
}

internal class MemorySshHosts : SshHostStore {
    private val hosts = MutableStateFlow<List<SshHost>>(emptyList())
    var host: SshHost
        get() = hosts.value.single()
        set(value) { hosts.value = listOf(value) }
    var key: ByteArray? = null
    var password: String = "synthetic-password"
    override fun observeHosts() = hosts
    override suspend fun find(alias: String) = hosts.value.firstOrNull { it.alias == alias }
    override suspend fun save(host: SshHost, credential: SshCredential?) { this.host = host }
    override suspend fun delete(alias: String) = Unit
    override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost) = false
    override fun credential(host: SshHost) = key?.let { SshCredential(privateKey = it.copyOf()) }
        ?: SshCredential(password = password.toByteArray())
}

private class TestCommand(private val command: String, private val shell: Boolean, private val active: AtomicInteger) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private lateinit var callback: ExitCallback
    private var worker: Thread? = null
    override fun setInputStream(value: InputStream) { input = value }
    override fun setOutputStream(value: OutputStream) { output = value }
    override fun setErrorStream(value: OutputStream) { error = value }
    override fun setExitCallback(value: ExitCallback) { callback = value }
    override fun start(channel: ChannelSession, env: Environment) {
        worker = Thread {
            active.incrementAndGet()
            try {
                when {
                    shell -> input.bufferedReader().useLines { lines -> lines.forEach { output.write("echo:$it\n".toByteArray()); output.flush() } }
                    command == "sleep" -> Thread.sleep(30_000)
                    // 口令跨两次写入，验证实时输出也能遮蔽跨分片的凭据。
                    command == "stream" -> {
                        output.write("part-1 synth".toByteArray()); output.flush()
                        Thread.sleep(100)
                        output.write("etic-password 半".toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 1) }); output.flush()
                        Thread.sleep(100)
                        output.write(byteArrayOf("半".toByteArray(Charsets.UTF_8).last()) + " tail\n".toByteArray()); output.flush()
                        Thread.sleep(30_000)
                    }
                    command == "delayed-zero" -> { Thread.sleep(1_500); output.write("done".toByteArray()) }
                    command == "large" -> output.write("x".repeat(100_000).toByteArray())
                    command == "unicode-secret" -> output.write("合成口令-只用于测试".toByteArray())
                    command == "zero" -> output.write("zero-ok".toByteArray())
                    command == "noisy-fail" -> { output.write("x".repeat(100_000).toByteArray()); error.write("fatal: boom".toByteArray()) }
                    else -> { output.write("stdout-ok synthetic-password".toByteArray()); error.write("stderr-ok".toByteArray()) }
                }
                output.flush(); error.flush(); callback.onExit(if (command == "zero" || command == "delayed-zero") 0 else 7)
            } catch (_: Exception) { } finally { active.decrementAndGet() }
        }.apply { isDaemon = true; start() }
    }
    override fun destroy(channel: ChannelSession) { worker?.interrupt(); runCatching { input.close() } }
}
