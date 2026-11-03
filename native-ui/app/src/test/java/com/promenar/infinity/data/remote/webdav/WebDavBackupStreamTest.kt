package com.promenar.infinity.data.remote.webdav

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class WebDavBackupStreamTest {
    private val config = WebDavConfig("https://dav.example.test/backups/", "test-user", "test-password")
    private val name = "infinity_backup_1234567890123.iwb"
    private val etag = "\"backup-stream\""

    @Test fun `超过十六MiB的文件上传采用WriteChannelContent并原子提交`() = runBlocking {
        val path = Files.createTempFile("webdav-stream", ".iwb")
        try {
            java.io.RandomAccessFile(path.toFile(), "rw").use { it.setLength(17L * 1024 * 1024) }
            var copied = 0L
            val methods = mutableListOf<String>()
            val client = KtorWebDavBackupClient(MockEngine { request ->
                methods += request.method.value
                if (request.method.value == "PUT") {
                    val body = request.body as OutgoingContent.WriteChannelContent
                    assertEquals(Files.size(path), body.contentLength)
                    coroutineScope {
                        val channel = ByteChannel(autoFlush = true)
                        val reader = async(Dispatchers.Default) {
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val count = channel.readAvailable(buffer); if (count < 0) break; copied += count }
                        }
                        body.writeTo(channel); channel.close(); reader.await()
                    }
                }
                respond("", HttpStatusCode.Created)
            })
            client.uploadAtomic(config, name, path)
            assertEquals(listOf("PUT", "MOVE"), methods)
            assertEquals(Files.size(path), copied)
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun `大文件下载校验ETag大小并写入文件`() = runBlocking {
        val path = Files.createTempFile("webdav-stream-download", ".iwb")
        val bytes = ByteArray(17 * 1024 * 1024) { 9 }
        try {
            val client = KtorWebDavBackupClient(MockEngine { request ->
                assertEquals(etag, request.headers[HttpHeaders.IfMatch])
                respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf(HttpHeaders.ETag to listOf(etag), HttpHeaders.ContentLength to listOf(bytes.size.toString())))
            })
            client.downloadTo(config, RemoteBackup(name, bytes.size.toLong(), 0, etag), path)
            assertEquals(bytes.size.toLong(), Files.size(path))
            Files.newInputStream(path).use { assertEquals(9, it.read()) }
        } finally { Files.deleteIfExists(path); bytes.fill(0) }
    }
    @Test fun `下载截断不留下可提交文件`() = runBlocking {
        val path = Files.createTempFile("webdav-stream-truncated", ".iwb")
        try {
            val client = KtorWebDavBackupClient(MockEngine {
                respond(ByteReadChannel(byteArrayOf(1)), HttpStatusCode.OK, headersOf(HttpHeaders.ETag, etag))
            })
            var rejected = false
            try { client.downloadTo(config, RemoteBackup(name, 2, 0, etag), path) } catch (_: WebDavException) { rejected = true }
            assertTrue(rejected)
            assertFalse(Files.exists(path))
        } finally { Files.deleteIfExists(path) }
    }
}
