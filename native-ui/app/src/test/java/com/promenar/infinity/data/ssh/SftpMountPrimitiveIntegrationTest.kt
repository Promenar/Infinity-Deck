package com.promenar.infinity.data.ssh

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.*
import org.junit.Test

class SftpMountPrimitiveIntegrationTest {
    @Test fun 真实JSch按精确偏移上传下载并排他创建发布() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sftp-mount-test").toFile().canonicalFile
        val remote = File(dir, "remote").apply { mkdir() }
        val hosts = MemorySshHosts()
        val temporary = "/.infinity-part-offset-test.tmp"
        val target = "/文件'?.bin"
        val published = "/verified?.bin"
        val checksumPaths = setOf(temporary, target, published)
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(dir, "server.key").toPath()).apply { algorithm = "EC" }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ -> password == hosts.password }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, command ->
                val path = checksumPaths.firstOrNull { remoteChecksumCommand(it) == command }
                val local = if (path == null) null else remoteChecksumCommand(File(remote, path.removePrefix("/")).path)
                LocalChecksumFixture(local ?: command.takeIf { it == remoteChecksumCommand(null) })
            }
            fileSystemFactory = VirtualFileSystemFactory(remote.toPath())
            start()
        }
        try {
            hosts.host = SshHost(alias = "nas", hostname = "127.0.0.1", port = server.port, username = "test", authType = SshAuthType.PASSWORD)
            SshRuntime(hosts).use { runtime ->
                val hostKey = runtime.probe("nas")
                hosts.host = hosts.host.copy(trustedKeyType = hostKey.type, trustedFingerprint = hostKey.fingerprint)
                val channel = runtime.bindSftpMountTransport("mount-test", "nas")
                val data = ByteArray(SFTP_MOUNT_CHUNK_BYTES * 2 + 109) { (it * 17 + it / 241).toByte() }
                val source = File(dir, "source").apply { writeBytes(data) }
                channel.createTemporary(temporary)
                assertEquals(384, channel.stat(temporary)!!.permissions and 511)
                assertEquals("TEMPORARY_CONFLICT", (runCatching { channel.createTemporary(temporary) }.exceptionOrNull() as SshFailure).code)
                var pause = false
                val first = channel.sftpResumableUploadChunked(temporary, source, data.size.toLong(), 0,
                    { if (pause) SftpChunkDirective.PAUSE else SftpChunkDirective.CONTINUE }) { pause = true }
                assertEquals(SFTP_MOUNT_CHUNK_BYTES.toLong(), first.confirmedBytes)
                assertArrayEquals(data.copyOf(SFTP_MOUNT_CHUNK_BYTES), File(remote, temporary.removePrefix("/")).readBytes())
                assertTrue(first.paused)
                channel.sftpResumableUploadChunked(temporary, source, data.size.toLong(), first.confirmedBytes, { SftpChunkDirective.CONTINUE }) {}
                val uploaded = File(remote, temporary.removePrefix("/")).readBytes()
                assertArrayEquals(data, uploaded)
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), MessageDigest.getInstance("SHA-256").digest(uploaded))

                val output = File(dir, "out")
                pause = false
                val second = channel.sftpResumableDownloadChunked(temporary, output, data.size.toLong(), 0,
                    { if (pause) SftpChunkDirective.PAUSE else SftpChunkDirective.CONTINUE }) { pause = true }
                assertTrue(second.paused)
                assertEquals(SFTP_MOUNT_CHUNK_BYTES.toLong(), second.confirmedBytes)
                channel.sftpResumableDownloadChunked(temporary, output, data.size.toLong(), second.confirmedBytes, { SftpChunkDirective.CONTINUE }) {}
                assertArrayEquals(data, output.readBytes())
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), MessageDigest.getInstance("SHA-256").digest(output.readBytes()))

                val checksum = channel.sha256(temporary)
                val linked = channel.publish(temporary, published, checksum.sha256)
                assertFalse(linked.alreadyExistedSameHash)
                assertTrue(Files.isSameFile(File(remote, temporary.removePrefix("/")).toPath(), File(remote, published.removePrefix("/")).toPath()))
                assertTrue(channel.publish(temporary, published, checksum.sha256).alreadyExistedSameHash)
                val occupied = File(remote, target.removePrefix("/")).apply { writeText("已有内容") }
                assertEquals("TARGET_CONFLICT", (runCatching { channel.publish(temporary, target, checksum.sha256) }.exceptionOrNull() as SshFailure).code)
                assertEquals("已有内容", occupied.readText())
                assertTrue(occupied.delete())
                channel.renameNoReplace(temporary, target)
                assertArrayEquals(data, channel.read(target, data.size.toLong()) { it.readBytes() })
                channel.createTemporary(temporary)
                assertEquals("TARGET_CONFLICT", (runCatching { channel.renameNoReplace(temporary, target) }.exceptionOrNull() as SshFailure).code)
                assertArrayEquals(data, File(remote, target.removePrefix("/")).readBytes())
            }
        } finally {
            server.stop(true)
            dir.deleteRecursively()
        }
    }
}

/** 回环服务只执行测试声明的固定校验命令，映射到同一虚拟文件根。 */
private class LocalChecksumFixture(private val command: String?) : Command {
    private lateinit var stdout: OutputStream
    private lateinit var stderr: OutputStream
    private lateinit var exit: ExitCallback
    private var process: Process? = null

    override fun setInputStream(input: InputStream) = Unit
    override fun setOutputStream(output: OutputStream) { stdout = output }
    override fun setErrorStream(error: OutputStream) { stderr = error }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }
    override fun start(channel: ChannelSession, env: Environment) {
        if (command == null) {
            exit.onExit(126)
            return
        }
        val child = ProcessBuilder("/bin/sh", "-c", command).start().also { process = it }
        Thread {
            child.inputStream.use { it.copyTo(stdout) }
            child.errorStream.use { it.copyTo(stderr) }
            stdout.flush()
            stderr.flush()
            exit.onExit(child.waitFor())
        }.apply { isDaemon = true; start() }
    }
    override fun destroy(channel: ChannelSession) { process?.destroy() }
}
