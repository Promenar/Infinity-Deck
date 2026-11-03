package com.promenar.infinity.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class WebDavBackupClientTest {
    private val config = WebDavConfig(
        baseUrl = "https://dav.example.test/backups/",
        username = "private-user",
        password = "private-password",
    )

    @Test fun `HTTP地址可向配置的WebDAV服务器请求`() = runTest {
        var requested: String? = null
        val client = client(MockEngine { request ->
            requested = request.url.toString()
            respond(multistatus(response("/backups/", collection = true)), HttpStatusCode.MultiStatus)
        })
        assertThat(client.test(config.copy(baseUrl = "http://dav.example.test/backups")).isSuccess).isTrue()
        assertThat(requested).startsWith("http://dav.example.test/backups/")
    }

    @Test
    fun `download preserves arbitrary bytes including zero bytes`() = runTest {
        val expected = byteArrayOf(0, 1, 2, 0, -1, 10, 13, 0)
        val engine = MockEngine {
            respond(ByteReadChannel(expected), HttpStatusCode.OK, headersOf(HttpHeaders.ETag, TEST_ETAG))
        }

        val actual = client(engine).download(config, remote(1000))

        assertThat(actual.asList()).containsExactlyElementsIn(expected.asList()).inOrder()
    }

    @Test
    fun `download rejects declared oversized response before reading body`() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(byteArrayOf(1)),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf((16L * 1024 * 1024 + 1).toString()),
                    HttpHeaders.ETag to listOf(TEST_ETAG),
                ),
            )
        }

        val error = runCatching { client(engine).download(config, remote(1000)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(error!!.message).contains("过大")
    }

    @Test
    fun `download rejects chunked response when actual bytes cross limit`() = runTest {
        val oversized = ByteArray(16 * 1024 * 1024 + 1) { 7 }
        val engine = MockEngine {
            respond(ByteReadChannel(oversized), HttpStatusCode.OK, headersOf(HttpHeaders.ETag, TEST_ETAG))
        }

        val error = runCatching { client(engine).download(config, remote(1000)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(error!!.message).contains("停止读取")
    }

    @Test
    fun `upload uses temporary PUT then MOVE to same-origin absolute destination`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("", if (request.method.value == "PUT") HttpStatusCode.Created else HttpStatusCode.Created)
        }
        val name = backupName(1000)

        client(engine).uploadAtomic(config, name, byteArrayOf(0, 5, 0))

        assertThat(requests.map { it.method.value }).containsExactly("PUT", "MOVE").inOrder()
        assertThat(requests[0].url.encodedPath).isEqualTo("/backups/$name.tmp-00000000-0000-0000-0000-000000000007")
        assertThat(requests[1].headers["Destination"]).isEqualTo("https://dav.example.test/backups/$name")
        assertThat(requests[1].headers["Overwrite"]).isEqualTo("F")
    }

    @Test
    fun `failed PUT never sends MOVE and attempts temporary cleanup`() = runTest {
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            respondError(HttpStatusCode.InsufficientStorage)
        }

        val error = runCatching { client(engine).uploadAtomic(config, backupName(1000), byteArrayOf(1)) }.exceptionOrNull()

        assertThat(methods).containsExactly("PUT", "DELETE").inOrder()
        assertThat(error).isInstanceOf(WebDavException::class.java)
    }

    @Test
    fun `failed MOVE attempts temporary DELETE and reports destination conflict safely`() = runTest {
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            when (request.method.value) {
                "PUT" -> respond("", HttpStatusCode.Created)
                "MOVE" -> respondError(HttpStatusCode.PreconditionFailed)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        val error = runCatching { client(engine).uploadAtomic(config, backupName(1000), byteArrayOf(1)) }.exceptionOrNull()

        assertThat(methods).containsExactly("PUT", "MOVE", "DELETE").inOrder()
        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(error!!.message).contains("已存在")
    }

    @Test
    fun `concurrent uploads use distinct temporary names and never allow overwrite`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val counter = AtomicInteger()
        val engine = MockEngine { request ->
            synchronized(requests) { requests += request }
            respond("", HttpStatusCode.Created)
        }
        val client = KtorWebDavBackupClient(
            engine = engine,
            uuidFactory = { UUID(0, counter.incrementAndGet().toLong()) },
        )

        listOf(backupName(1000), backupName(1001)).map { name ->
            async { client.uploadAtomic(config, name, byteArrayOf(1)) }
        }.awaitAll()

        val putPaths = requests.filter { it.method.value == "PUT" }.map { it.url.encodedPath }
        assertThat(putPaths.distinct()).hasSize(2)
        assertThat(requests.filter { it.method.value == "MOVE" }.map { it.headers["Overwrite"] })
            .containsExactly("F", "F")
    }

    @Test
    fun `list parses multistatus and ignores collections temp foreign and escaped hrefs`() = runTest {
        val xml = multistatus(
            response("/backups/", collection = true),
            response("/backups/${backupName(2000)}", modified = "Wed, 02 Jul 2025 10:00:00 GMT", size = 20),
            response("/backups/infinity_backup_1000.iwb.tmp-deadbeef", size = 2),
            response("/backups/foreign.zip", size = 3),
            response("/other/${backupName(3000)}", size = 4),
            response("/backups/infinity_backup_000000000200%31.iwb", modified = "Wed, 02 Jul 2025 11:00:00 GMT", size = 21),
            response("/backups/${backupName(4000)}", size = 40, propStatus = "HTTP/1.1 404 Not Found"),
        )
        val engine = MockEngine { respond(xml, HttpStatusCode.MultiStatus) }

        val files = client(engine).list(config)

        assertThat(files.map { it.fileName })
            .containsExactly(backupName(2001), backupName(2000))
            .inOrder()
        assertThat(files.map { it.sizeBytes }).containsExactly(21L, 20L).inOrder()
    }

    @Test
    fun `list rejects doctype and external entities`() = runTest {
        val xml = """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY xxe SYSTEM="file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>"""
        val engine = MockEngine { respond(xml, HttpStatusCode.MultiStatus) }

        val error = runCatching { client(engine).list(config) }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(error!!.message).contains("响应格式")
    }

    @Test
    fun `prune keeps newest five and aggregates delete failures`() = runTest {
        val methodsAndPaths = mutableListOf<String>()
        val xml = multistatus(*(1L..7L).map { response("/backups/${backupName(it)}", size = it) }.toTypedArray())
        val engine = MockEngine { request ->
            methodsAndPaths += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.method.value == "PROPFIND" -> respond(xml, HttpStatusCode.MultiStatus)
                request.url.encodedPath.endsWith(backupName(1)) -> respondError(HttpStatusCode.Locked)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        val error = runCatching { client(engine).prune(config, keep = 5) }.exceptionOrNull()

        assertThat(methodsAndPaths.filter { it.startsWith("DELETE") })
            .containsExactly("DELETE /backups/${backupName(2)}", "DELETE /backups/${backupName(1)}")
            .inOrder()
        assertThat(error).isInstanceOf(WebDavPruneException::class.java)
        assertThat(error!!.message).doesNotContain(config.username)
        assertThat(error.message).doesNotContain(config.password)
    }

    @Test
    fun `prune warning after committed upload does not delete final backup`() = runTest {
        val requests = mutableListOf<String>()
        val xml = multistatus(*(1L..6L).map { response("/backups/${backupName(it)}", size = it) }.toTypedArray())
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when (request.method.value) {
                "PROPFIND" -> respond(xml, HttpStatusCode.MultiStatus)
                "DELETE" -> respondError(HttpStatusCode.Locked)
                else -> respond("", HttpStatusCode.Created)
            }
        }
        val client = client(engine)
        val uploaded = backupName(7000)

        client.uploadAtomic(config, uploaded, byteArrayOf(1))
        val warning = runCatching { client.prune(config, 5) }.exceptionOrNull()

        assertThat(warning).isInstanceOf(WebDavPruneException::class.java)
        assertThat(requests).contains("MOVE /backups/$uploaded.tmp-00000000-0000-0000-0000-000000000007")
        assertThat(requests).doesNotContain("DELETE /backups/$uploaded")
    }

    @Test
    fun `invalid filename and header injection are rejected locally`() = runTest {
        var requests = 0
        val client = client(MockEngine { requests++; respond("", HttpStatusCode.OK) })

        val names = listOf("../${backupName(1)}", "/${backupName(1)}", "${backupName(1)}\r\nX-Evil: yes")
        names.forEach { name ->
            assertThat(runCatching { client.download(config, RemoteBackup(name, 0, 0, TEST_ETAG)) }.exceptionOrNull())
                .isInstanceOf(WebDavException::class.java)
        }
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun `credentials and sensitive query never appear in errors or toString`() = runTest {
        val sensitive = config.copy(baseUrl = "https://dav.example.test/backups/?token=private-query")
        val client = client(MockEngine { respondError(HttpStatusCode.Unauthorized) })

        val errors = listOf(
            client.test(config).exceptionOrNull(),
            runCatching { client.download(sensitive, remote(1)) }.exceptionOrNull(),
        )

        errors.filterNotNull().forEach { error ->
            val rendered = error.toString()
            assertThat(rendered).doesNotContain(config.username)
            assertThat(rendered).doesNotContain(config.password)
            assertThat(rendered).doesNotContain("private-query")
        }
        assertThat(sensitive.toString()).doesNotContain(config.username)
        assertThat(sensitive.toString()).doesNotContain(config.password)
        assertThat(sensitive.toString()).doesNotContain("private-query")
    }

    @Test
    fun `basic credentials exist only in authorization header`() = runTest {
        var observed: HttpRequestData? = null
        val engine = MockEngine { request ->
            observed = request
            respond(byteArrayOf(), HttpStatusCode.OK, headersOf(HttpHeaders.ETag, TEST_ETAG))
        }

        client(engine).download(config, remote(1))

        assertThat(observed!!.headers[HttpHeaders.Authorization]).startsWith("Basic ")
        assertThat(observed!!.url.toString()).doesNotContain(config.username)
        assertThat(observed!!.url.toString()).doesNotContain(config.password)
    }

    @Test
    fun `401 403 and 5xx produce status-only sanitized errors`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            val error = runCatching {
                client(MockEngine { respondError(status) }).download(config, remote(1))
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(WebDavException::class.java)
            assertThat(error!!.message).contains(status.value.toString())
            assertThat(error.message).doesNotContain(config.username)
            assertThat(error.message).doesNotContain(config.password)
        }
    }

    @Test
    fun `test uses depth-zero PROPFIND and validates status`() = runTest {
        var observed: HttpRequestData? = null
        val success = client(MockEngine { request ->
            observed = request
            respond(multistatus(response("/backups/", collection = true)), HttpStatusCode.MultiStatus)
        }).test(config)

        assertThat(success.isSuccess).isTrue()
        assertThat(observed!!.method.value).isEqualTo("PROPFIND")
        assertThat(observed!!.headers["Depth"]).isEqualTo("0")

        val forbidden = client(MockEngine { respondError(HttpStatusCode.Forbidden) }).test(config)
        assertThat(forbidden.isFailure).isTrue()
        assertThat(forbidden.exceptionOrNull()!!.message).contains("403")
    }

    @Test
    fun `cancellation is propagated unchanged`() = runTest {
        val cancellation = CancellationException("cancel-now")
        val engine = MockEngine { throw cancellation }

        val error = runCatching { client(engine).download(config, remote(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(error!!.message).isEqualTo("cancel-now")
    }

    private fun client(engine: MockEngine): KtorWebDavBackupClient = KtorWebDavBackupClient(
        engine = engine,
        uuidFactory = { UUID.fromString("00000000-0000-0000-0000-000000000007") },
    )

    private fun backupName(timestamp: Long) = "infinity_backup_${timestamp.toString().padStart(13, '0')}.iwb"

    private fun remote(timestamp: Long) = RemoteBackup(backupName(timestamp), 0, 0, TEST_ETAG)

    private fun multistatus(vararg responses: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>"""

    private fun response(
        href: String,
        collection: Boolean = false,
        modified: String = "Wed, 02 Jul 2025 09:00:00 GMT",
        size: Long = 0,
        propStatus: String = "HTTP/1.1 200 OK",
    ): String = """
        <d:response>
          <d:href>$href</d:href>
          <d:propstat><d:prop>
            <d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>
            <d:getcontentlength>$size</d:getcontentlength>
            <d:getlastmodified>$modified</d:getlastmodified>
          </d:prop><d:status>$propStatus</d:status></d:propstat>
        </d:response>
    """.trimIndent()

    private companion object {
        const val TEST_ETAG = "\"test-etag\""
    }
}
