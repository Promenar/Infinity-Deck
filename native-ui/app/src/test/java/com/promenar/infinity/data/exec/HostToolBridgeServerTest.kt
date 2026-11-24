package com.promenar.infinity.data.exec

import com.promenar.infinity.data.linux.LinuxResourceBudget
import com.promenar.infinity.data.linux.LinuxToolBridgeConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HostToolBridgeServerTest {
    @get:Rule val temp = TemporaryFolder(File("/tmp"))

    @Test(timeout = 15_000)
    fun duplexHeaderAndFramesPreserveBytesAndExitCode() = fixture("""
        printf 'warn' >&2
        /bin/cat
        exit 42
    """.trimIndent()) { server, _, _ ->
        server.openJobLease(mounts(), emptyMap(), budget).use { lease ->
            connect(lease.socket).use { client ->
                client.header(lease)
                assertEquals("""{"ok":true}""", client.line())
                assertEquals(Frame(2, "warn".toByteArray().toList()), client.frame())
                val binary = byteArrayOf(0, 0xff.toByte(), 3, 10, 13)
                client.send(6, byteArrayOf(0, 80, 0, 24))
                client.send(0, binary)
                client.send(4)
                assertEquals(Frame(1, binary.toList()), client.frame())
                assertEquals(42, client.frame().exit())
            }
        }
    }

    @Test(timeout = 15_000)
    fun rejectsWrongUidBadHeadersUnknownToolsAndRevokedLeases() = fixture("exit 0", uid = 50) { server, launches, _ ->
        server.openJobLease(mounts(), emptyMap(), budget).use { lease ->
            connect(lease.socket).use { client ->
                client.header(lease, version = 2)
                assertEquals("TOOL_HEADER", client.code())
            }
            connect(lease.socket).use { client ->
                client.header(lease, tool = "sh")
                assertEquals("TOOL_UNAVAILABLE", client.code())
            }
            connect(lease.socket).use { client ->
                client.header(lease, cwd = "/etc")
                assertEquals("TOOL_PATH", client.code())
            }
            lease.close()
            connect(lease.socket).use { client ->
                client.header(lease)
                assertEquals("TOOL_LEASE", client.code())
            }
        }
        assertTrue(launches.isEmpty())
    }.also {
        fixture("exit 0", uid = 50, peerUid = 51) { server, launches, _ ->
            server.openJobLease(mounts(), emptyMap(), budget).use { lease ->
                connect(lease.socket).use { assertEquals(-1, it.input.read()) }
            }
            assertTrue(launches.isEmpty())
        }
    }

    @Test(timeout = 15_000)
    fun forwardsSignalsAndRevocationReapsAllChildren() = fixture("""
        trap 'printf interrupted; exit 17' INT
        trap '' TERM
        printf ready
        while :; do /bin/sleep 0.03; done
    """.trimIndent()) { server, launches, signals ->
        server.openJobLease(mounts(), emptyMap(), budget).use { lease ->
            connect(lease.socket).use { client ->
                client.start(lease)
                repeat(3) { client.send(0, ByteArray(65_536)) }
                client.send(5, ByteBuffer.allocate(4).putInt(2).array())
                assertEquals("interrupted", client.frame().text())
                assertEquals(17, client.frame().exit())
            }
            connect(lease.socket).use { first ->
                connect(lease.socket).use { second ->
                    first.start(lease)
                    second.start(lease)
                    val firstClose = java.util.concurrent.CompletableFuture.runAsync(lease::close)
                    val secondClose = java.util.concurrent.CompletableFuture.runAsync(lease::close)
                    firstClose.get(2, TimeUnit.SECONDS)
                    secondClose.get(2, TimeUnit.SECONDS)
                    for (process in launches.drop(1)) assertTrue(process.waitFor(2, TimeUnit.SECONDS))
                    assertEquals(2, signals.count { it == 15 })
                    assertEquals(3, signals.count { it == 9 })
                }
            }
        }
    }

    private fun mounts() = HostToolPathTranslator(mapOf("/workspace" to temp.root))

    private fun fixture(
        script: String,
        uid: Int = 50,
        peerUid: Int = 50,
        body: (HostToolBridgeServer, List<Process>, List<Int>) -> Unit,
    ) {
        assumeTrue(File("/usr/bin/perl").canExecute())
        val cache = temp.newFolder()
        val libs = temp.newFolder()
        File(libs, "libnxtoolshim.so").createNewFile()
        File(libs, "libnxrg.so").apply { writeText("#!/bin/sh\n$script\n"); setExecutable(true) }
        val tools = PackagedHostTools(libs)
        val launches = CopyOnWriteArrayList<Process>()
        val signals = CopyOnWriteArrayList<Int>()
        val runner = BuiltinCommandRunner(
            listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"), "/bin/sh",
            { group, signal ->
                signals += signal
                ProcessBuilder("/bin/kill", "-$signal", "-$group").redirectErrorStream(true).start().waitFor()
            },
            { File(cache, "scratch") },
        )
        val server = HostToolBridgeServer(cache, tools, GitHostBroker(tools, GitHostConfig(cache, tools)),
            { command -> runner.startHostTool(command) { group ->
                ProcessBuilder("/bin/kill", "-0", "-$group").redirectErrorStream(true).start().waitFor() == 0
            }.also { launches += it.process } },
            currentUid = { uid }, bind = { socket -> JvmListener(socket, peerUid) })
        server.use { body(it, launches, signals) }
        assertFalse(LinuxToolBridgeConfig.socket(cache).exists())
    }

    private data class Frame(val type: Int, val bytes: List<Byte>) {
        fun exit(): Int {
            assertEquals(3, type)
            return ByteBuffer.wrap(bytes.toByteArray()).int
        }
        fun text() = bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private class Client(private val channel: SocketChannel) : AutoCloseable {
        val input = DataInputStream(ChannelInput(channel))
        private val output = DataOutputStream(ChannelOutput(channel))

        fun header(lease: HostToolBridgeServer.JobLease, version: Int = 1, tool: String = "rg", cwd: String = "/workspace") {
            val request = buildJsonObject {
                put("v", version)
                put("nonce", lease.environment.getValue(LinuxToolBridgeConfig.ENV_NONCE))
                put("tool", tool)
                put("cwd", cwd)
                putJsonArray("argv") { add("pattern") }
                putJsonObject("env") { put("LD_PRELOAD", "/missing") }
                putJsonObject("tty") {
                    for (stream in listOf("in", "out", "err")) put(stream, false)
                    put("cols", 0)
                    put("rows", 0)
                }
            }
            output.write("$request\n".toByteArray())
        }
        fun start(lease: HostToolBridgeServer.JobLease) {
            header(lease)
            assertEquals("""{"ok":true}""", line())
            assertEquals("ready", frame().text())
        }
        fun send(type: Int, data: ByteArray = byteArrayOf()) {
            output.writeByte(type)
            output.writeInt(data.size)
            output.write(data)
        }
        fun line() = buildString {
            while (true) {
                val byte = input.readUnsignedByte()
                if (byte == 10) break
                append(byte.toChar())
            }
        }
        fun code() = Json.parseToJsonElement(line()).jsonObject.getValue("code").jsonPrimitive.content
        fun frame(): Frame {
            val type = input.readUnsignedByte()
            val length = input.readInt()
            return Frame(type, ByteArray(length).also(input::readFully).toList())
        }
        override fun close() = channel.close()
    }

    private class JvmListener(private val socket: File, private val uid: Int) : HostToolListener {
        private val listener = openJvmToolSocket(socket)
        override fun accept(): HostToolConnection {
            val channel = listener.accept()
            return object : HostToolConnection {
                override val peerUid = uid
                override val input = ChannelInput(channel)
                override val output = ChannelOutput(channel)
                override fun headerTimeout(millis: Int) = Unit
                override fun close() = channel.close()
            }
        }

        override fun close() {
            listener.close()
            Files.deleteIfExists(socket.toPath())
        }
    }

    private class ChannelInput(private val channel: SocketChannel) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int) = channel.read(ByteBuffer.wrap(bytes, offset, length))
    }

    private class ChannelOutput(private val channel: SocketChannel) : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private companion object {
        val budget = LinuxResourceBudget(512L * 1024 * 1024, listOf(0, 1), 2)
        fun connect(socket: File) = Client(SocketChannel.open(UnixDomainSocketAddress.of(socket.path)))
    }
}

/** Android 编译桩只有 open()，在 JVM 上调用 Java 16 的 Unix socket 重载。 */
internal fun openJvmToolSocket(socket: File): ServerSocketChannel =
    (ServerSocketChannel::class.java.getMethod("open", java.net.ProtocolFamily::class.java)
        .invoke(null, StandardProtocolFamily.UNIX) as ServerSocketChannel)
        .bind(UnixDomainSocketAddress.of(socket.path))
