package com.promenar.infinity.data.remote

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 在环回接口捕获真实 HTTP 请求，按原字节检查上传内容。 */
internal class LoopbackUploadServer(vararg responses: Pair<Int, String>) : AutoCloseable {
    data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 8_000 }
    val url = "http://127.0.0.1:${server.localPort}"
    private val capture = FutureTask {
        responses.map { (status, response) ->
            server.accept().use { socket ->
                socket.soTimeout = 8_000
                val input = socket.getInputStream().buffered()
                val line = input.httpLine()
                val headers = generateSequence { input.httpLine().takeIf(String::isNotEmpty) }
                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                val length = headers["content-length"]?.toInt() ?: 0
                val bytes = input.readNBytes(length)
                check(bytes.size == length) { "请求体提前结束。" }
                val body = response.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body)
                    flush()
                }
                Request(line, headers, bytes)
            }
        }
    }

    init { thread(isDaemon = true, name = "loopback-upload", block = capture::run) }

    fun requests(): List<Request> = capture.get(10, TimeUnit.SECONDS)
    override fun close() = server.close()

    private fun InputStream.httpLine(): String = buildString {
        while (true) {
            val byte = read()
            check(byte >= 0) { "HTTP 请求头提前结束。" }
            if (byte == '\n'.code) break
            if (byte != '\r'.code) append(byte.toChar())
        }
    }
}
